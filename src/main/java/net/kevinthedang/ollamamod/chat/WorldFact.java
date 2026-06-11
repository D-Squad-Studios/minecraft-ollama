package net.kevinthedang.ollamamod.chat;

/**
 * A single world/context fact intended to be injected into the prompt.
 *
 * factText should be a clean, quotable string (so we can validate "quoted word-for-word").
 */
public record WorldFact(
        String factText,
        String evidence,
        double confidence,
        long ttlMillis
) { 
        public static WorldFact of(String factText, String evidence, double confidence) {
        return new WorldFact(factText, evidence, confidence, 0);
        }
}