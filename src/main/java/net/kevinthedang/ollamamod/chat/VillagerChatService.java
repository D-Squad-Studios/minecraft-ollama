package net.kevinthedang.ollamamod.chat;

import net.kevinthedang.ollamamod.OllamaMod;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;


import java.util.List;
import java.util.UUID;

public class VillagerChatService {
    private final ChatHistoryManager historyManager;
    private final VillagerBrain brain;

    public interface UiCallbacks {
        void onThinkingStarted();

        default void onVillagerReplyDelta(String delta) {
            // Called when a chunk of the villager's reply arrives.
        }

        void onVillagerReplyFinished(String fullText);

        void onError(String errorMessage);
    }

    public VillagerChatService(ChatHistoryManager historyManager, VillagerBrain brain) {
        this.historyManager = historyManager;
        this.brain = brain;
    }

    public void sendPlayerMessage(
            UUID conversationId,
            VillagerBrain.Context context,
            String messageText,
            UiCallbacks ui,
            Boolean isStreaming,
            Vec3 villagerSoundPos
    ) {

        OllamaMod.LOGGER.info("Villager chat [{}|{}] Player: {}", context.worldName(), context.villagerName(), messageText);
        historyManager.append(conversationId, new ChatMessage(ChatRole.PLAYER, messageText));
        ui.onThinkingStarted();

        List<ChatMessage> historySnapshot = historyManager.getHistory(conversationId);

        if (isStreaming == false) {
            brain.getReply(context, historySnapshot, messageText)
                    .whenComplete((reply, throwable) -> {
                        // Ensure UI updates happen on the Minecraft client thread
                        Minecraft mc = Minecraft.getInstance();
                        mc.execute(() -> {
                            if (throwable != null) {
                                OllamaMod.LOGGER.error("Error getting reply from Ollama for conversation {}", context.conversationId(), throwable);
                                ui.onError(describeError(throwable));
                                return;
                            }
                            historyManager.append(conversationId,
                                    new ChatMessage(ChatRole.VILLAGER, reply));
                            // play once when the reply arrives
                            if (villagerSoundPos != null && reply != null && !reply.isEmpty()) {
                                playVillagerReplySound(villagerSoundPos);
                            }
                            persistMemory(messageText, reply, context);
                            ui.onVillagerReplyFinished(reply);
                        });
                    });
        } else {
            brain.getReplyStreaming(context, historySnapshot, messageText, new VillagerBrain.StreamCallbacks() {
                private boolean startedSound = false;

                @Override
                public void onDelta(String delta) {
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> {
                        // Play once when the villager begins "speaking" (first streamed chunk)
                        if (!startedSound && villagerSoundPos != null && delta != null && !delta.isEmpty()) {
                            startedSound = true;
                            playVillagerReplySound(villagerSoundPos);
                        }
                        ui.onVillagerReplyDelta(delta);
                    });
                }

                @Override
                public void onCompleted(String fullReply) {
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> {
                        historyManager.append(conversationId, new ChatMessage(ChatRole.VILLAGER, fullReply));
                        persistMemory(messageText, fullReply, context);
                        ui.onVillagerReplyFinished(fullReply);
                    });
                }

                @Override
                public void onError(Throwable t) {
                    OllamaMod.LOGGER.error("Error getting reply from Ollama for conversation {}",
                            context.conversationId(), t);

                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> ui.onError(describeError(t)));
                }
            });
        }
    }

    // Persists the player-villager exchange to the vector store for future memory recall.
    private static void persistMemory(String playerMessage, String villagerReply, VillagerBrain.Context context) {
        try {
            Minecraft mc = Minecraft.getInstance();
            String playerId = (mc.player != null) ? mc.player.getUUID().toString() : "local-player";
            String transcript = "Player: " + (playerMessage == null ? "" : playerMessage) + "\n" +
                    "Villager: " + (villagerReply == null ? "" : villagerReply);
            OllamaMod.VECTOR_STORE
                    .storeMemory(transcript, context.conversationId().toString(), playerId)
                    .exceptionally(t -> { OllamaMod.LOGGER.debug("storeMemory failed", t); return null; });
        } catch (Throwable t) {
            OllamaMod.LOGGER.debug("storeMemory threw", t);
        }
    }

    // Plays a short villager talking sound when a response appears.
    private void playVillagerReplySound(Vec3 pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        mc.level.playLocalSound(
                pos.x, pos.y, pos.z,
                SoundEvents.VILLAGER_YES,
                SoundSource.NEUTRAL,
                1.0f,
                1.0f,
                false
        );
    }


    public List<ChatMessage> getHistory(UUID conversationId) {
        return historyManager.getHistory(conversationId);
    }

    // Returns a human-readable error message based on the exception type.
    private static String describeError(Throwable t) {
        if (t == null) return "Unknown error.";
        Throwable cause = t.getCause() != null ? t.getCause() : t;
        String msg = cause.getMessage() != null ? cause.getMessage() : "";

        if (cause instanceof java.net.ConnectException || msg.contains("Connection refused")) {
            return "Ollama is not running at " + OllamaSettings.baseUrl + ". Start Ollama and try again.";
        }
        if (msg.contains("404")) {
            return "Model not found on Ollama. Run: ollama pull <model>";
        }
        if (msg.contains("500")) {
            return "Ollama internal error. Check Ollama logs.";
        }
        if (cause instanceof java.net.http.HttpTimeoutException || msg.contains("timed out") || msg.contains("timeout")) {
            return "Ollama request timed out. Ollama may be overloaded.";
        }
        return "Villager is confused. " + (msg.isEmpty() ? cause.getClass().getSimpleName() : msg);
    }
}
