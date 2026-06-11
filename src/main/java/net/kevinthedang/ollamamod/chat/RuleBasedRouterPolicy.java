package net.kevinthedang.ollamamod.chat;

import java.util.List;
import java.util.Locale;

public class RuleBasedRouterPolicy implements RouterPolicy {

    // Patterns that are always answerable from FACTS — force fast path,
    // even if history suggests a follow-up to a retrieval conversation
    private static final String[] FAST_PATH_KEYWORDS = {
            // Time / weather (answered by FACTS)
            "what time", "time is it", "weather", "rain", "sunny", "thundering", "storm", "snow",
            "is it day", "is it night",
            // Greetings / farewells
            "hello", "hey", "hi", "howdy", "good morning", "good evening",
            "goodbye", "bye", "see you", "farewell",
            // Simple convos
            "how are you", "what's up", "who are you", "what is your name",
            "what's your name", "thank", "thanks",
    };

    // Keywords that indicate the player is asking a knowledge question
    private static final String[] RETRIEVER_KEYWORDS = {
            // Question patterns
            "how do i", "how do you", "how to", "what is", "what does", "what are",
            "where do", "where can", "can i", "can you",
            // Crafting / recipes
            "craft", "recipe", "make", "build", "create", "ingredient", "material",
            "smelt", "brew", "enchant", "combine",
            // Items and tools (common nouns players ask about)
            "pickaxe", "sword", "axe", "shovel", "hoe", "bow", "crossbow", "shield",
            "armor", "helmet", "chestplate", "leggings", "boots",
            "furnace", "anvil", "enchanting", "brewing stand", "crafting table",
            // Game mechanics
            "need", "require", "use", "drop", "find",
            "mine", "trade", "villager", "mob", "biome", "dimension",
            "explain",
    };

    // Pronouns/references that indicate a vague follow-up query — augment regardless of retriever keywords
    private static final String[] REFERENCE_PRONOUNS = {
            " it", " this", " that", " those", " them", " its", " these"
    };

    // Number of recent history messages to scan for follow-up detection
    private static final int HISTORY_LOOKBACK = 4;

    // Messages with fewer than this many words are considered vague for augmentation purposes
    private static final int VAGUE_WORD_THRESHOLD = 6;

    @Override
    public RoutePlan plan(VillagerBrain.Context ctx, List<ChatMessage> history, String playerMessage) {
        String m = playerMessage == null ? "" : playerMessage.toLowerCase(Locale.ROOT);

        boolean wantsWorld = true;

        // Fast-path override: questions answerable from FACTS always skip retrieval
        boolean forceFastPath = containsAny(m, FAST_PATH_KEYWORDS);

        // Fast-path questions (weather/time/greetings) are answered by FACTS alone;
        // injecting memory risks stale world-state overriding live sensor readings.
        boolean wantsMemory = !forceFastPath;

        boolean wantsRetriever = !forceFastPath && containsAny(m, RETRIEVER_KEYWORDS);

        // Follow-up detection: if recent history has retriever intent or the villager
        // asked a question (e.g. "Do you need help finding diamonds?"), treat short
        // replies like "yes please" as continuations that need retrieval.
        if (!wantsRetriever && !forceFastPath && history != null && !history.isEmpty()) {
            if (recentHistoryHasRetrieverIntent(history) || lastVillagerAskedQuestion(history)) {
                wantsRetriever = true;
            }
        }

        // Build augmented query for vague follow-ups
        String augmentedQuery = null;
        if (wantsRetriever && isVagueQuery(m)) {
            augmentedQuery = buildAugmentedQuery(m, history);
        }

        // depth is reserved for future (0=none, 1=low, 2=high)
        int depth = wantsRetriever ? 1 : 0;

        // Enable memory/retrieval routing based on detected intent.
        return new RoutePlan(wantsWorld, wantsMemory, wantsRetriever, depth, augmentedQuery);
    }

    // Checks whether recent player messages in the conversation contained retriever keywords.
    private boolean recentHistoryHasRetrieverIntent(List<ChatMessage> history) {
        int start = Math.max(0, history.size() - HISTORY_LOOKBACK);
        for (int i = history.size() - 1; i >= start; i--) {
            ChatMessage msg = history.get(i);
            if (msg.role() == ChatRole.PLAYER) {
                String text = msg.content().toLowerCase(Locale.ROOT);
                if (containsAny(text, RETRIEVER_KEYWORDS)) {
                    return true;
                }
            }
        }
        return false;
    }

    // Checks whether the most recent villager message within HISTORY_LOOKBACK ends with '?'.
    // This catches short affirmative replies like "yes please" after a villager question.
    private boolean lastVillagerAskedQuestion(List<ChatMessage> history) {
        int start = Math.max(0, history.size() - HISTORY_LOOKBACK);
        for (int i = history.size() - 1; i >= start; i--) {
            ChatMessage msg = history.get(i);
            if (msg.role() == ChatRole.VILLAGER) {
                return msg.content().trim().endsWith("?");
            }
        }
        return false;
    }

    // Checks whether the current message is vague (pronoun-dependent, no retriever keywords, or very short).
    private boolean isVagueQuery(String message) {
        // Pronoun references are always vague — augment regardless of retriever keywords
        if (containsAny(message, REFERENCE_PRONOUNS)) return true;
        if (containsAny(message, RETRIEVER_KEYWORDS)) return false;
        String[] words = message.trim().split("\\s+");
        return words.length < VAGUE_WORD_THRESHOLD;
    }

    // Finds the most recent player message with retriever keywords and prepends it
    // to the current message to give the embedding model stronger topic signal.
    // Falls back to the last villager message only for very short replies (≤3 words),
    // so "yes please" after "Do you need help finding diamonds?" becomes a useful query
    // without polluting longer queries like "where is the nearest obsidian?".
    String buildAugmentedQuery(String currentMessage, List<ChatMessage> history) {
        if (history == null || history.isEmpty()) return null;

        int start = Math.max(0, history.size() - HISTORY_LOOKBACK);
        for (int i = history.size() - 1; i >= start; i--) {
            ChatMessage msg = history.get(i);
            if (msg.role() == ChatRole.PLAYER) {
                String text = msg.content().toLowerCase(Locale.ROOT);
                if (containsAny(text, RETRIEVER_KEYWORDS)) {
                    return msg.content() + " " + currentMessage;
                }
            }
        }

        // Fallback: use last villager message only for very short replies (e.g. "yes", "sure", "ok please")
        // to avoid prepending long villager responses to specific queries
        int wordCount = currentMessage.trim().split("\\s+").length;
        if (wordCount <= 3) {
            for (int i = history.size() - 1; i >= start; i--) {
                ChatMessage msg = history.get(i);
                if (msg.role() == ChatRole.VILLAGER) {
                    return msg.content() + " " + currentMessage;
                }
            }
        }
        return null;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}
