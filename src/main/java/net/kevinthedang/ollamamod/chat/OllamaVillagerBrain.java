package net.kevinthedang.ollamamod.chat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class OllamaVillagerBrain implements VillagerBrain {

    private final HttpClient client;
    private final URI chatUri;
    private final Gson gson = new Gson();

    public OllamaVillagerBrain() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        this.chatUri = URI.create(OllamaSettings.baseUrl + "/api/chat");
    }

    @Override
    public CompletableFuture<String> getReply(Context context, List<ChatMessage> history, String playerMessage) {
        List<Map<String, String>> messages = buildMessages(context, history, playerMessage);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", OllamaSettings.chatModel);
        requestBody.put("stream", false); 
        requestBody.put("messages", messages);

        String json = gson.toJson(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(chatUri)
                .timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("Ollama HTTP " + response.statusCode());
                    }
                    return parseReply(response.body());
                });
    }

    @Override
    public void getReplyStreaming(Context context,
                                List<ChatMessage> history,
                                String playerMessage,
                                StreamCallbacks callbacks) {

        List<Map<String, String>> messages = buildMessages(context, history, playerMessage);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", OllamaSettings.chatModel);
        requestBody.put("stream", true);
        requestBody.put("messages", messages);

        String json = gson.toJson(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(chatUri)
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        // Run the streaming call on a background thread so we don’t block the client
        Thread t = new Thread(() -> {
            try {
                HttpResponse<InputStream> response =
                        client.send(request, HttpResponse.BodyHandlers.ofInputStream());

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

                        // For /api/chat streaming, each chunk includes:
                        // { "message": { "role": "...", "content": "..." }, "done": false, ... }
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
                callbacks.onError(e);
            }
        }, "Ollama-Streaming-Thread");

        t.setDaemon(true);
        t.start();
    }

    // For non-streaming response parsing
    private String parseReply(String responseBody) {
        /*
         * {
         *   "message": { "role": "assistant", "content": "..." },
         *   "done": true,
         *   ...
         * }
         */
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonObject message = root.getAsJsonObject("message");
        if (message == null || !message.has("content")) {
            return "[No reply from Ollama]";
        }
        return message.get("content").getAsString();
    }

    private List<Map<String, String>> buildMessages(Context context, List<ChatMessage> history, String playerMessage) {
        List<Map<String, String>> messages = new ArrayList<>();

        // system prompt
        messages.add(Map.of(
                "role", "system",
                "content",
                "You are Minecraft character talking to a player. " +
                "Stay in-character, keep replies short, and reference trading or the village when it fits. " +
                "If users ask about how to make anything, DO NOT help them cheat or break game rules. " +
                "Politely refuse to answer anything outside of their knowledge. " +
                "Use simple language with short sentences appropriate for their. " +
                "If users ask about how to create anything, just say that you don't know. "
        ));

        for (ChatMessage msg : history) {
            String role = switch (msg.role()) {
                case PLAYER -> "user";
                case VILLAGER -> "assistant";
                case SYSTEM -> "system";
            };
            messages.add(Map.of(
                    "role", role,
                    "content", msg.content()
            ));
        }

        messages.add(Map.of(
                "role", "user",
                "content", playerMessage
        ));

        return messages;
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
