package net.kevinthedang.ollamamod.chat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface VillagerBrain {

    record Context(
            UUID conversationId,
            String villagerName,
            String villagerProfession,
            String worldName
    ) { }

    /**
     * Ask the brain for a reply. Must be non-blocking.
     *
     * @param context        basic villager/world info
     * @param history        recent chat history
     * @param playerMessage  latest player message
     * @return future with the villager's full reply text
     */
    CompletableFuture<String> getReply(
            Context context,
            List<ChatMessage> history,
            String playerMessage
    );

    default void getReplyStreaming(
            Context context,
            List<ChatMessage> history,
            String playerMessage,
            StreamCallbacks callbacks
    ) {
        getReply(context, history, playerMessage)
                .whenComplete((reply, throwable) -> {
                    if (throwable != null) {
                        callbacks.onError(throwable);
                    } else {
                        callbacks.onCompleted(reply);
                    }
                });
    }

    interface StreamCallbacks {
        void onDelta(String delta);
        void onCompleted(String fullReply);
        void onError(Throwable t);
    }

    default boolean isHealthy() {
        return true;
    }
}
