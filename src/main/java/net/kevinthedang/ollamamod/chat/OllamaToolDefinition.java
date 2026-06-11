package net.kevinthedang.ollamamod.chat;

import java.util.List;
import java.util.Map;

// Defines tool schemas for Ollama's tool/function calling API.
public final class OllamaToolDefinition {

	// Returns the list of tool definitions in the format expected by Ollama's /api/chat `tools` field.
	public static List<Map<String, Object>> allTools() {
		return List.of(searchKnowledge(), recallMemory());
	}

	private static Map<String, Object> searchKnowledge() {
		return Map.of(
			"type", "function",
			"function", Map.of(
				"name", "search_knowledge",
				"description", "Search the Minecraft knowledge base for information about recipes, crafting, " +
					"items, mobs, biomes, mechanics, and other game facts. Use this when the player asks " +
					"about how to craft something, what an item does, or any Minecraft gameplay question.",
				"parameters", Map.of(
					"type", "object",
					"properties", Map.of(
						"query", Map.of(
							"type", "string",
							"description", "The search query to look up in the knowledge base"
						)
					),
					"required", List.of("query")
				)
			)
		);
	}

	private static Map<String, Object> recallMemory() {
		return Map.of(
			"type", "function",
			"function", Map.of(
				"name", "recall_memory",
				"description", "Recall past conversation memories with this player. Use this when the player " +
					"asks about previous conversations, what you talked about before, or references " +
					"something from an earlier chat.",
				"parameters", Map.of(
					"type", "object",
					"properties", Map.of(
						"query", Map.of(
							"type", "string",
							"description", "The search query to find relevant past conversations"
						)
					),
					"required", List.of("query")
				)
			)
		);
	}

	private OllamaToolDefinition() {}
}
