package net.kevinthedang.ollamamod.vectorstore.chunker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConversationChunkerTest {
    // Conversations should keep Player/Villager exchanges together.
    @Test
    public void keepsPlayerVillagerExchangesTogether() {
        ConversationChunker chunker = new ConversationChunker(40);
        String content = "Player: Hello\nVillager: Hi there\nPlayer: Trade?\nVillager: Sure";
        List<String> chunks = chunker.chunk(content);
        for (String chunk : chunks) {
            assertTrue(chunk.contains("Player:"), "Chunk should contain Player line");
            assertTrue(chunk.contains("Villager:"), "Chunk should contain Villager line");
        }
    }
}
