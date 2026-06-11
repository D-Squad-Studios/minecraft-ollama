package net.kevinthedang.ollamamod.chat;

/**
 * In later milestones, this can expand to memory/retrieval depth, tool timeouts, etc.
 */
public record RoutePlan(
        boolean useWorld,
        boolean useMemory,
        boolean useRetriever,
        int depth,
        String augmentedQuery
) {
    // Backward-compatible constructor without augmentedQuery.
    public RoutePlan(boolean useWorld, boolean useMemory, boolean useRetriever, int depth) {
        this(useWorld, useMemory, useRetriever, depth, null);
    }

    // Returns augmentedQuery if present, otherwise falls back to playerMessage.
    public String effectiveQuery(String playerMessage) {
        return augmentedQuery != null && !augmentedQuery.isBlank()
                ? augmentedQuery
                : playerMessage;
    }

    public static RoutePlan phase1Default() {
        return new RoutePlan(true, false, false, 0, null);
    }
}
