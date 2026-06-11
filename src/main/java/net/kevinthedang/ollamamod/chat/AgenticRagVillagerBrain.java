package net.kevinthedang.ollamamod.chat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kevinthedang.ollamamod.OllamaMod;
import net.kevinthedang.ollamamod.vectorstore.VectorStoreSettings;
import net.kevinthedang.ollamamod.vectorstore.model.VectorDocument;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class AgenticRagVillagerBrain implements VillagerBrain {

	private static final int MAX_TOOL_ITERATIONS = 3;
	private static final List<String> INNER_ARG_KEYS = List.of("content", "value", "query", "text");

	private final HttpClient client;
	private final URI chatUri;
	private final Gson gson = new Gson();

	private final RouterPolicy router;
	private final WorldContextTool worldContextTool;
	private final PromptComposer promptComposer;

	// Per-turn pre-fetch state for short-circuiting redundant tool calls
	private volatile String prefetchedQuery;
	private volatile List<VectorDocument> prefetchedDocs;
	private volatile List<VectorDocument> prefetchedMemories;

	public AgenticRagVillagerBrain() {
		this(new RuleBasedRouterPolicy(), new ForgeWorldContextTool(), new PromptComposerV1());
	}

	public AgenticRagVillagerBrain(RouterPolicy router, WorldContextTool worldContextTool, PromptComposer promptComposer) {
		this.client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(3))
				.build();
		this.chatUri = URI.create(OllamaSettings.baseUrl + "/api/chat");

		this.router = router;
		this.worldContextTool = worldContextTool;
		this.promptComposer = promptComposer;
	}

	@Override
	public CompletableFuture<String> getReply(Context context, List<ChatMessage> history, String playerMessage) {
		OllamaMod.VECTOR_STORE.clearEmbeddingCache();
		this.prefetchedQuery = null;
		this.prefetchedDocs = null;
		this.prefetchedMemories = null;

		WorldFactBundle worldFacts = worldContextTool.collect(context, history, playerMessage);
		RoutePlan plan = router.plan(context, history, playerMessage);
		String retrievalQuery = plan.effectiveQuery(playerMessage);
		System.out.println("[AgenticRAG] facts=" + worldFacts.facts().size());
		worldFacts.facts().forEach(f -> System.out.println("[AgenticRAG]   fact: " + f.factText()));
		System.out.println("[AgenticRAG] Route: useRetriever=" + plan.useRetriever() + " useMemory=" + plan.useMemory());
		if (!retrievalQuery.equals(playerMessage)) {
			System.out.println("[AgenticRAG] Augmented retrieval query: " + retrievalQuery);
		}

		List<Map<String, Object>> messages = toObjectMaps(
				promptComposer.buildMessages(context, history, playerMessage, worldFacts));

		CompletableFuture<List<VectorDocument>> memFut = plan.useMemory()
				? OllamaMod.VECTOR_STORE.queryMemories(retrievalQuery, context.conversationId().toString(), VectorStoreSettings.defaultTopK)
						.exceptionally(e -> List.of())
				: CompletableFuture.completedFuture(List.of());

		if (!plan.useRetriever()) {
			// Fast path: FACTS + history + memories, no tools, fast model
			return memFut.thenCompose(memories -> {
				this.prefetchedMemories = memories;
				injectPrefetchedContext(messages, List.of(), memories);
				System.out.println("[AgenticRAG] Fast path (chatModel, no tools, memories=" + memories.size() + ")");
				return sendNonStreaming(messages, false, OllamaSettings.chatModel)
						.thenApply(responseBody -> {
							JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
							return extractContent(root.getAsJsonObject("message"));
						})
						.exceptionallyCompose(e -> {
							OllamaMod.LOGGER.warn("[AgenticRAG] chatModel unavailable, retrying with toolModel: {}", e.getMessage());
							return sendNonStreaming(messages, false, OllamaSettings.toolModel).thenApply(responseBody -> {
								JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
								return extractContent(root.getAsJsonObject("message"));
							});
						});
			});
		}

		// Retriever path: pre-fetch docs + memories
		CompletableFuture<List<VectorDocument>> docsFut = OllamaMod.VECTOR_STORE
				.queryDocuments(retrievalQuery, VectorStoreSettings.defaultTopK)
				.exceptionally(e -> List.of());

		return CompletableFuture.allOf(docsFut, memFut).thenCompose(v -> {
			this.prefetchedQuery = retrievalQuery;
			this.prefetchedDocs = docsFut.join();
			this.prefetchedMemories = memFut.join();
			injectPrefetchedContext(messages, prefetchedDocs, prefetchedMemories);

			if (!prefetchedDocs.isEmpty()) {
				// Docs found — respond directly, no tools needed
				System.out.println("[AgenticRAG] Retriever path: direct reply (docs=" + prefetchedDocs.size()
						+ ", memories=" + prefetchedMemories.size() + ")");
				return sendNonStreaming(messages, false, OllamaSettings.toolModel)
						.thenApply(responseBody -> {
							JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
							return extractContent(root.getAsJsonObject("message"));
						})
						.exceptionallyCompose(e -> {
							OllamaMod.LOGGER.warn("[AgenticRAG] toolModel unavailable, retrying with chatModel: {}", e.getMessage());
							return sendNonStreaming(messages, false, OllamaSettings.chatModel).thenApply(responseBody -> {
								JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
								return extractContent(root.getAsJsonObject("message"));
							});
						});
			}

			// No docs found — fall back to tool loop for accuracy
			System.out.println("[AgenticRAG] Retriever path: tool fallback (no docs found, memories="
					+ prefetchedMemories.size() + ")");
			return toolLoop(messages, context, 0);
		});
	}

	// Recursive tool-calling loop: sends messages to Ollama, executes any tool calls, and repeats.
	private CompletableFuture<String> toolLoop(List<Map<String, Object>> messages, Context context, int iteration) {
		return sendNonStreaming(messages, true, OllamaSettings.toolModel).thenCompose(responseBody -> {
			JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
			JsonObject message = root.getAsJsonObject("message");

			if (hasToolCalls(message) && iteration < MAX_TOOL_ITERATIONS) {
				messages.add(assistantMessageFromJson(message));

				JsonArray toolCalls = message.getAsJsonArray("tool_calls");
				return executeToolCalls(toolCalls, context).thenCompose(toolResults -> {
					for (Map<String, Object> toolResult : toolResults) {
						messages.add(toolResult);
					}
					System.out.println("[AgenticRAG] Tool iteration " + (iteration + 1)
							+ ", tool calls: " + toolCalls.size());
					return toolLoop(messages, context, iteration + 1);
				});
			}

			String content = extractContent(message);
			System.out.println("[AgenticRAG] Final reply after " + iteration + " tool iterations");
			return CompletableFuture.completedFuture(content);
		});
	}

	@Override
	public void getReplyStreaming(Context context, List<ChatMessage> history, String playerMessage, StreamCallbacks callbacks) {
		OllamaMod.VECTOR_STORE.clearEmbeddingCache();
		this.prefetchedQuery = null;
		this.prefetchedDocs = null;
		this.prefetchedMemories = null;

		WorldFactBundle worldFacts = worldContextTool.collect(context, history, playerMessage);
		RoutePlan plan = router.plan(context, history, playerMessage);
		String retrievalQuery = plan.effectiveQuery(playerMessage);
		System.out.println("[AgenticRAG] facts=" + worldFacts.facts().size());
		worldFacts.facts().forEach(f -> System.out.println("[AgenticRAG]   fact: " + f.factText()));
		System.out.println("[AgenticRAG] Route: useRetriever=" + plan.useRetriever() + " useMemory=" + plan.useMemory());
		if (!retrievalQuery.equals(playerMessage)) {
			System.out.println("[AgenticRAG] Augmented retrieval query: " + retrievalQuery);
		}

		List<Map<String, Object>> messages = toObjectMaps(
				promptComposer.buildMessages(context, history, playerMessage, worldFacts));

		CompletableFuture<List<VectorDocument>> memFut = plan.useMemory()
				? OllamaMod.VECTOR_STORE.queryMemories(retrievalQuery, context.conversationId().toString(), VectorStoreSettings.defaultTopK)
						.exceptionally(e -> List.of())
				: CompletableFuture.completedFuture(List.of());

		if (!plan.useRetriever()) {
			// Fast path: FACTS + history + memories, no tools, stream directly with fast model
			memFut.thenAccept(memories -> {
				this.prefetchedMemories = memories;
				injectPrefetchedContext(messages, List.of(), memories);
				System.out.println("[AgenticRAG] Fast path (chatModel, no tools, streaming, memories=" + memories.size() + ")");
				streamFinalReply(messages, callbacks, OllamaSettings.chatModel, OllamaSettings.toolModel);
			}).exceptionally(e -> {
				callbacks.onError(e);
				return null;
			});
			return;
		}

		// Retriever path: pre-fetch docs + memories
		CompletableFuture<List<VectorDocument>> docsFut = OllamaMod.VECTOR_STORE
				.queryDocuments(retrievalQuery, VectorStoreSettings.defaultTopK)
				.exceptionally(e -> List.of());

		CompletableFuture.allOf(docsFut, memFut).thenCompose(v -> {
			List<VectorDocument> docs = docsFut.join();
			List<VectorDocument> memories = memFut.join();

			this.prefetchedQuery = retrievalQuery;
			this.prefetchedDocs = docs;
			this.prefetchedMemories = memories;
			injectPrefetchedContext(messages, docs, memories);

			if (!docs.isEmpty()) {
				// Docs found — stream directly, no tools needed
				System.out.println("[AgenticRAG] Retriever path: streaming (docs=" + docs.size()
						+ ", memories=" + memories.size() + ")");
				streamFinalReply(messages, callbacks, OllamaSettings.toolModel, OllamaSettings.chatModel);
				return CompletableFuture.completedFuture((Void) null);
			}

			// No docs found — fall back to tool loop for accuracy
			System.out.println("[AgenticRAG] Retriever path: tool fallback (no docs found, memories="
					+ memories.size() + ")");

			return sendNonStreaming(messages, true, OllamaSettings.toolModel).thenCompose(responseBody -> {
				JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
				JsonObject message = root.getAsJsonObject("message");

				if (!hasToolCalls(message)) {
					String content = extractContent(message);
					System.out.println("[AgenticRAG] Tool fallback: direct reply (no tool calls)");
					callbacks.onDelta(content);
					callbacks.onCompleted(content);
					return CompletableFuture.completedFuture((Void) null);
				}

				messages.add(assistantMessageFromJson(message));
				JsonArray toolCalls = message.getAsJsonArray("tool_calls");
				System.out.println("[AgenticRAG] Tool iteration 1, tool calls: " + toolCalls.size());

				return executeToolCalls(toolCalls, context).thenCompose(toolResults -> {
					for (Map<String, Object> toolResult : toolResults) {
						messages.add(toolResult);
					}
					return toolLoopNonStreaming(messages, context, 1);
				}).thenAccept(resolvedMessages -> {
					streamFinalReply(resolvedMessages, callbacks, OllamaSettings.toolModel);
				});
			});
		}).exceptionally(e -> {
			callbacks.onError(e);
			return null;
		});
	}

	// Runs the remaining tool loop iterations non-streaming, returning messages ready for a final call.
	private CompletableFuture<List<Map<String, Object>>> toolLoopNonStreaming(
			List<Map<String, Object>> messages, Context context, int iteration) {

		return sendNonStreaming(messages, true, OllamaSettings.toolModel).thenCompose(responseBody -> {
			JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
			JsonObject message = root.getAsJsonObject("message");

			if (hasToolCalls(message) && iteration < MAX_TOOL_ITERATIONS) {
				messages.add(assistantMessageFromJson(message));

				JsonArray toolCalls = message.getAsJsonArray("tool_calls");
				return executeToolCalls(toolCalls, context).thenCompose(toolResults -> {
					for (Map<String, Object> toolResult : toolResults) {
						messages.add(toolResult);
					}
					System.out.println("[AgenticRAG] Tool iteration " + (iteration + 1)
							+ ", tool calls: " + toolCalls.size());
					return toolLoopNonStreaming(messages, context, iteration + 1);
				});
			}

			System.out.println("[AgenticRAG] Tool loop done after " + iteration + " iterations, streaming final reply");
			return CompletableFuture.completedFuture(messages);
		});
	}

	// Injects pre-fetched vector store results as a system message so the LLM can answer in 1 call.
	private void injectPrefetchedContext(List<Map<String, Object>> messages, List<VectorDocument> docs, List<VectorDocument> memories) {
		String block = formatPrefetchedBlock(docs, memories);
		if (!block.isEmpty()) {
			Map<String, Object> contextMsg = new HashMap<>();
			contextMsg.put("role", "system");
			contextMsg.put("content", block);
			// Insert before the last message (the user's question)
			int insertPos = Math.max(0, messages.size() - 1);
			messages.add(insertPos, contextMsg);
		}
	}

	// Formats pre-fetched results into a context block for the LLM.
	private static String formatPrefetchedBlock(List<VectorDocument> docs, List<VectorDocument> memories) {
		StringBuilder sb = new StringBuilder();
		if (!memories.isEmpty()) {
			sb.append("CONVERSATION MEMORY (from past chats with this player):\n");
			for (int i = 0; i < Math.min(3, memories.size()); i++) {
				String text = memories.get(i).content();
				if (text == null) continue;
				sb.append("- ").append(abbreviate(text.replace('\n', ' '), 300)).append('\n');
			}
			sb.append('\n');
		}
		if (!docs.isEmpty()) {
			sb.append("KNOWLEDGE BASE (Minecraft facts, recipes, items):\n");
			for (int i = 0; i < Math.min(5, docs.size()); i++) {
				String text = docs.get(i).content();
				if (text == null) continue;
				sb.append("- ").append(abbreviate(text.replace('\n', ' '), 500)).append('\n');
			}
			sb.append('\n');
		}
		if (sb.length() > 0) {
			sb.append("IMPORTANT: Answer the player's question in plain natural language.\n");
			sb.append("WORLD INFO always overrides MEMORY for current world state such as weather, time, and location. MEMORY may be outdated.\n");
			sb.append("Do NOT call tools if the answer is already in WORLD INFO or MEMORY.\n");
			sb.append("Only use search_knowledge or recall_memory if neither WORLD INFO nor MEMORY answers the question.\n");
		}
		return sb.toString().trim();
	}

	// Sends a final streaming call without tools to generate the synthesized response.
	// If the primary model fails and fallbackModel is non-null, retries with the fallback.
	private void streamFinalReply(List<Map<String, Object>> messages, StreamCallbacks callbacks, String model) {
		streamFinalReply(messages, callbacks, model, null);
	}

	private void streamFinalReply(List<Map<String, Object>> messages, StreamCallbacks callbacks, String model, String fallbackModel) {
		Map<String, Object> requestBody = buildOllamaRequestBody(messages, true, false, model);
		String json = gson.toJson(requestBody);

		HttpRequest request = HttpRequest.newBuilder()
				.uri(chatUri)
				.timeout(Duration.ofSeconds(60))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
				.build();

		Thread t = new Thread(() -> {
			try {
				HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
				if (response.statusCode() != 200) {
					throw new RuntimeException("Ollama HTTP " + response.statusCode());
				}

				StringBuilder fullReply = new StringBuilder();
				try (BufferedReader reader = new BufferedReader(
						new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {

					String line;
					while ((line = reader.readLine()) != null) {
						if (line.isBlank()) continue;

						JsonObject chunk = JsonParser.parseString(line).getAsJsonObject();
						if (chunk.has("error")) {
							throw new RuntimeException("Ollama error: " + chunk.get("error").getAsString());
						}

						if (chunk.has("message")) {
							JsonObject msgObj = chunk.getAsJsonObject("message");
							if (msgObj != null && msgObj.has("content")) {
								String delta = msgObj.get("content").getAsString();
								if (!delta.isEmpty()) {
									fullReply.append(delta);
									callbacks.onDelta(delta);
								}
							}
						}

						if (chunk.has("done") && chunk.get("done").getAsBoolean()) {
							break;
						}
					}
				}

				callbacks.onCompleted(fullReply.toString());
			} catch (Exception e) {
				if (fallbackModel != null) {
					OllamaMod.LOGGER.warn("[AgenticRAG] {} unavailable, falling back to {}: {}", model, fallbackModel, e.getMessage());
					callbacks.onDelta("[System: " + model + " unavailable, switching to " + fallbackModel + "]\n");
					streamFinalReply(messages, callbacks, fallbackModel, null);
				} else {
					callbacks.onError(e);
				}
			}
		}, "AgenticRAG-Ollama-Streaming-Thread");

		t.setDaemon(true);
		t.start();
	}

	// Sends a non-streaming request to Ollama.
	private CompletableFuture<String> sendNonStreaming(List<Map<String, Object>> messages, boolean includeTools, String model) {
		Map<String, Object> requestBody = buildOllamaRequestBody(messages, false, includeTools, model);
		String json = gson.toJson(requestBody);

		HttpRequest request = HttpRequest.newBuilder()
				.uri(chatUri)
				.timeout(Duration.ofSeconds(60))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
				.build();

		long start = System.currentTimeMillis();
		System.out.println("[AgenticRAG] Sending non-streaming request to " + model
				+ " (tools=" + includeTools + ", messages=" + messages.size() + ")");

		return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenApply(response -> {
					long elapsed = System.currentTimeMillis() - start;
					System.out.println("[AgenticRAG] Response from " + model + " in " + elapsed + "ms (HTTP " + response.statusCode() + ")");
					if (response.statusCode() != 200) {
						throw new RuntimeException("Ollama HTTP " + response.statusCode() + ": " + response.body());
					}
					return response.body();
				});
	}

	// Builds the JSON request body for the Ollama /api/chat endpoint.
	private Map<String, Object> buildOllamaRequestBody(
			List<Map<String, Object>> messages, boolean stream, boolean includeTools, String model) {
		Map<String, Object> body = new HashMap<>();
		body.put("model", model);
		body.put("stream", stream);
		body.put("messages", messages);
		if (includeTools) {
			body.put("tools", OllamaToolDefinition.allTools());
		}
		// No num_predict cap — let the model use its default token limit.
		// Some models use reasoning tokens internally, so a low cap truncates output.
		return body;
	}

	// Checks whether the assistant response contains tool_calls.
	private boolean hasToolCalls(JsonObject message) {
		if (message == null || !message.has("tool_calls")) return false;
		JsonArray calls = message.getAsJsonArray("tool_calls");
		return calls != null && !calls.isEmpty();
	}

	// Extracts the text content from an assistant message, falling back if missing.
	private String extractContent(JsonObject message) {
		if (message == null || !message.has("content")) {
			return "[No reply from Ollama]";
		}
		return message.get("content").getAsString();
	}

	// Converts the assistant message JSON into a Map for the conversation history.
	private Map<String, Object> assistantMessageFromJson(JsonObject message) {
		Map<String, Object> msg = new HashMap<>();
		msg.put("role", "assistant");
		msg.put("content", message.has("content") ? message.get("content").getAsString() : "");
		if (message.has("tool_calls")) {
			// Keep as raw JsonArray to preserve integer types (Gson's List deserialize turns int to double,
			// which Ollama's Go backend rejects for fields like "index")
			msg.put("tool_calls", message.getAsJsonArray("tool_calls"));
		}
		return msg;
	}

	// Check if a tool query is similar enough to the pre-fetched query to reuse cached results.
	private boolean isQuerySimilarToPrefetched(String toolQuery) {
		if (prefetchedQuery == null || toolQuery == null) return false;
		String a = prefetchedQuery.trim().toLowerCase();
		String b = toolQuery.trim().toLowerCase();
		return a.equals(b) || a.contains(b) || b.contains(a);
	}

	// Dispatches tool calls to the appropriate backend (vector store queries).
	private CompletableFuture<List<Map<String, Object>>> executeToolCalls(JsonArray toolCalls, Context context) {
		List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();

		for (JsonElement element : toolCalls) {
			JsonObject call = element.getAsJsonObject();
			JsonObject function = call.getAsJsonObject("function");
			String name = function.get("name").getAsString();
			JsonObject arguments = function.getAsJsonObject("arguments");
			String query = extractStringArg(arguments, "query");

			System.out.println("[AgenticRAG] Tool call: " + name + " query=\"" + abbreviate(query, 80) + "\"");

			CompletableFuture<String> resultFut;
			switch (name) {
				case "search_knowledge":
					if (isQuerySimilarToPrefetched(query) && prefetchedDocs != null) {
						System.out.println("[AgenticRAG] Reusing pre-fetched docs for search_knowledge");
						resultFut = CompletableFuture.completedFuture(formatDocResults(prefetchedDocs));
					} else {
						resultFut = OllamaMod.VECTOR_STORE
								.queryDocuments(query, VectorStoreSettings.defaultTopK)
								.thenApply(AgenticRagVillagerBrain::formatDocResults)
								.exceptionally(e -> "(knowledge search failed: " + e.getMessage() + ")");
					}
					break;
				case "recall_memory":
					if (isQuerySimilarToPrefetched(query) && prefetchedMemories != null) {
						System.out.println("[AgenticRAG] Reusing pre-fetched memories for recall_memory");
						resultFut = CompletableFuture.completedFuture(formatDocResults(prefetchedMemories));
					} else {
						resultFut = OllamaMod.VECTOR_STORE
								.queryMemories(query, context.conversationId().toString(), VectorStoreSettings.defaultTopK)
								.thenApply(AgenticRagVillagerBrain::formatDocResults)
								.exceptionally(e -> "(memory recall failed: " + e.getMessage() + ")");
					}
					break;
				default:
					resultFut = CompletableFuture.completedFuture("(unknown tool: " + name + ")");
					break;
			}

			futures.add(resultFut.thenApply(content -> {
				System.out.println("[AgenticRAG] Tool result for " + name + ":\n" + content);
				Map<String, Object> toolMsg = new HashMap<>();
				toolMsg.put("role", "tool");
				toolMsg.put("content", content);
				return toolMsg;
			}));
		}

		return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
				.thenApply(v -> futures.stream()
						.map(CompletableFuture::join)
						.toList());
	}

	// Formats vector store results into a readable string for the LLM.
	private static String formatDocResults(List<VectorDocument> docs) {
		if (docs.isEmpty()) return "(no results found)";

		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < Math.min(5, docs.size()); i++) {
			String text = docs.get(i).content();
			if (text == null) continue;
			sb.append("- ").append(abbreviate(text.replace('\n', ' '), 500)).append('\n');
		}
		return sb.toString().trim();
	}

	// Widens Map<String, String> to Map<String, Object> for Ollama messages that may contain tool_calls.
	private static List<Map<String, Object>> toObjectMaps(List<Map<String, String>> stringMaps) {
		List<Map<String, Object>> result = new ArrayList<>(stringMaps.size());
		for (Map<String, String> m : stringMaps) {
			result.add(new HashMap<>(m));
		}
		return result;
	}

	// Safely extracts a string argument from a tool call's arguments object.
	// Some models wrap the value in an object echoing the schema (e.g., {"content":"...", "description":"..."}).
	// This method unwraps that to extract the actual string.
	private static String extractStringArg(JsonObject arguments, String key) {
		if (arguments == null || !arguments.has(key)) return "";
		JsonElement value = arguments.get(key);
		if (value.isJsonPrimitive()) {
			return value.getAsString();
		}
		if (value.isJsonObject()) {
			JsonObject obj = value.getAsJsonObject();
			for (String innerKey : INNER_ARG_KEYS) {
				if (obj.has(innerKey) && obj.get(innerKey).isJsonPrimitive()) {
					return obj.get(innerKey).getAsString();
				}
			}
		}
		return value.toString();
	}

	private static String abbreviate(String s, int max) {
		if (s == null) return "";
		if (s.length() <= max) return s;
		return s.substring(0, Math.max(0, max - 3)) + "...";
	}

	@Override
	public boolean isHealthy() {
		try {
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(OllamaSettings.baseUrl + "/api/tags"))
					.timeout(Duration.ofSeconds(2))
					.GET()
					.build();
			client.send(request, HttpResponse.BodyHandlers.discarding());
			return true;
		} catch (Exception e) {
			return false;
		}
	}
}
