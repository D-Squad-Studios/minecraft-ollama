package net.kevinthedang.ollamamod.vectorstore.chunker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TextChunkerTest {
    // Short text should return a single chunk.
    @Test
    public void shortTextReturnsSingleChunk() {
        TextChunker chunker = new TextChunker(100, 10);
        List<String> chunks = chunker.chunk("Short text.");
        assertTrue(chunks.size() == 1, "Expected a single chunk");
        assertFalse(chunks.get(0).isEmpty(), "Chunk should not be empty");
    }

    // Long text should split into multiple sentence-aligned chunks.
    @Test
    public void longTextSplitsIntoMultipleChunks() {
        TextChunker chunker = new TextChunker(40, 5);
        String content = "Sentence one. Sentence two. Sentence three. Sentence four.";
        List<String> chunks = chunker.chunk(content);
        assertTrue(chunks.size() > 1, "Expected multiple chunks");
        for (String chunk : chunks) {
            assertFalse(chunk.isEmpty(), "Chunk should not be empty");
        }
    }
}
