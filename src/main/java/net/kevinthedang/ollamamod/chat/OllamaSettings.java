package net.kevinthedang.ollamamod.chat;

// Temporary Ollama settings

public class OllamaSettings {

    public static String baseUrl = "http://localhost:11434";

    public static final String DEFAULT_CHAT_MODEL = "granite4:latest";
    public static final String DEFAULT_TOOL_MODEL = "minimax-m2.5:cloud";

    public static String chatModel = DEFAULT_CHAT_MODEL;            // for low-effort conversations
    public static String toolModel = DEFAULT_TOOL_MODEL;            // higher-effort conversations
}
