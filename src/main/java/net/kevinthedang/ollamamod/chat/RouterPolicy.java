package net.kevinthedang.ollamamod.chat;

import java.util.List;

/**
 * Decides which context tools to use for a given player message.
 */
public interface RouterPolicy {
    RoutePlan plan(VillagerBrain.Context ctx, List<ChatMessage> history, String playerMessage);
}
