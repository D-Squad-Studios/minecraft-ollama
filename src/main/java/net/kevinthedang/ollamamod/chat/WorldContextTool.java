package net.kevinthedang.ollamamod.chat;

import java.util.List;

/**
 * Produces structured facts about the current villager/world conversation context.
 *
 * For now intentionally limits itself to fields already present in VillagerBrain.Context.
 */
public interface WorldContextTool {
    WorldFactBundle collect(VillagerBrain.Context ctx, List<ChatMessage> history, String playerMessage);
}
