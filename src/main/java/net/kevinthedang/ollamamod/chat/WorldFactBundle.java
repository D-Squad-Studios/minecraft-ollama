package net.kevinthedang.ollamamod.chat;

import java.util.Collections;
import java.util.List;

/**
 * Container for all world facts injected into the prompt for a single turn.
 */
public record WorldFactBundle(List<WorldFact> facts) {
    public static WorldFactBundle empty() {
        return new WorldFactBundle(Collections.emptyList());
    }
}
