package net.kevinthedang.ollamamod.vectorstore.chunker;

import net.kevinthedang.ollamamod.vectorstore.VectorStoreSettings;

import java.util.ArrayList;
import java.util.List;

public class TextChunker extends Chunker {
    // Chunker for plain text with sentence boundary awareness.
    public TextChunker() {
        super(VectorStoreSettings.chunkSize, VectorStoreSettings.chunkOverlap);
    }

    // Chunker for plain text with explicit size and overlap settings.
    public TextChunker(int maxChunkSize, int overlapSize) {
        super(maxChunkSize, overlapSize);
    }

    // Split content into sentence-aligned chunks with overlap.
    @Override
    public List<String> chunk(String content) {
        if (content.length() <= maxChunkSize) {
            return List.of(content);
        }

        List<String> chunks = new ArrayList<>();
        int startIndex = 0;

        while (startIndex < content.length()) {
            int endIndex = Math.min(startIndex + maxChunkSize, content.length());

            if (endIndex < content.length()) {
                int sentenceEndIndex = findSentenceBoundary(content, startIndex, endIndex);
                if (sentenceEndIndex > startIndex) {
                    endIndex = sentenceEndIndex;
                }
            }

            if (endIndex <= startIndex) {
                endIndex = Math.min(startIndex + maxChunkSize, content.length());
                if (endIndex <= startIndex) {
                    break;
                }
            }

            String chunk = content.substring(startIndex, endIndex).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            int nextStart = endIndex - overlapSize;
            if (nextStart <= startIndex) {
                startIndex = endIndex;
            } else {
                startIndex = nextStart;
            }
        }

        return chunks;
    }

    // Find a sentence boundary to avoid cutting mid-sentence.
    private int findSentenceBoundary(String text, int startIndex, int endIndex) {
        int minimumIndex = startIndex + maxChunkSize / 2;
        for (int index = endIndex; index > minimumIndex; index--) {
            char character = text.charAt(index - 1);
            if ((character == '.' || character == '!' || character == '?') &&
                (index == text.length() || Character.isWhitespace(text.charAt(index)))) {
                return index;
            }
        }
        return endIndex;
    }
}
