package net.kevinthedang.ollamamod.chat;

import java.util.List;
import java.util.Map;

public interface PromptComposer {
    List<Map<String, String>> buildMessages(
            VillagerBrain.Context ctx,
            List<ChatMessage> history,
            String playerMessage,
            WorldFactBundle worldFacts
    );
}
