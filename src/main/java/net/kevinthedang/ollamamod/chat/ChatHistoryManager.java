package net.kevinthedang.ollamamod.chat;

import java.util.*;

public class ChatHistoryManager {

    private static final int MAX_MESSAGES = 50; // TODO: Determine appropriate limit based on system testing

    private final Map<UUID, Deque<ChatMessage>> historyByConversation = new java.util.concurrent.ConcurrentHashMap<>();

    public List<ChatMessage> getHistory(UUID conversationId) {
        Deque<ChatMessage> deque =
                historyByConversation.getOrDefault(conversationId, new ArrayDeque<>());
        return List.copyOf(deque);
    }

    public void append(UUID conversationId, ChatMessage message) {
        Deque<ChatMessage> deque =
                historyByConversation.computeIfAbsent(conversationId, id -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        deque.addLast(message);
        while (deque.size() > MAX_MESSAGES) {
            deque.removeFirst();
        }
    }

    public void clear(UUID conversationId) {
        historyByConversation.remove(conversationId);
    }

    public void clearAll() {
        historyByConversation.clear();
    }

    public void persistAll(java.nio.file.Path worldPath) {
        java.nio.file.Path file = worldPath.resolve("ollamamod/chat_history.bin");
        try {
            java.nio.file.Files.createDirectories(file.getParent());
            try (var out = new java.io.DataOutputStream(
                    new java.io.BufferedOutputStream(
                            java.nio.file.Files.newOutputStream(file)))) {
                out.writeInt(historyByConversation.size());
                for (var entry : historyByConversation.entrySet()) {
                    UUID id = entry.getKey();
                    out.writeLong(id.getMostSignificantBits());
                    out.writeLong(id.getLeastSignificantBits());
                    Deque<ChatMessage> deque = entry.getValue();
                    out.writeInt(deque.size());
                    for (ChatMessage msg : deque) {
                        out.writeByte(msg.role().ordinal());
                        out.writeUTF(msg.content());
                    }
                }
            }
        } catch (Exception e) {
            // log but don't crash — history loss is non-fatal
        }
    }

    public void loadAll(java.nio.file.Path worldPath) {
        java.nio.file.Path file = worldPath.resolve("ollamamod/chat_history.bin");
        if (!java.nio.file.Files.exists(file)) return;
        try (var in = new java.io.DataInputStream(
                new java.io.BufferedInputStream(
                        java.nio.file.Files.newInputStream(file)))) {
            int convCount = in.readInt();
            for (int i = 0; i < convCount; i++) {
                UUID id = new UUID(in.readLong(), in.readLong());
                int msgCount = in.readInt();
                Deque<ChatMessage> deque = new java.util.concurrent.ConcurrentLinkedDeque<>();
                ChatRole[] roles = ChatRole.values();
                for (int j = 0; j < msgCount; j++) {
                    ChatRole role = roles[in.readByte()];
                    String content = in.readUTF();
                    deque.addLast(new ChatMessage(role, content));
                }
                historyByConversation.put(id, deque);
            }
        } catch (Exception e) {
            // corrupted file — start fresh
            historyByConversation.clear();
        }
    }
}
