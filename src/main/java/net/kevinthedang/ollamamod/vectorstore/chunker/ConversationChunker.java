package net.kevinthedang.ollamamod.vectorstore.chunker;

import net.kevinthedang.ollamamod.vectorstore.VectorStoreSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConversationChunker extends Chunker {
    private static final Pattern EXCHANGE_PATTERN = Pattern.compile(
        "(Player|Villager):\\s*[^\\n]+",
        Pattern.MULTILINE
    );

    // Chunker for conversation logs grouped by Player/Villager exchanges.
    public ConversationChunker() {
        super(VectorStoreSettings.chunkSize, 0);
    }

    // Chunker for conversation logs with explicit size settings.
    public ConversationChunker(int maxChunkSize) {
        super(maxChunkSize, 0);
    }

    // Split conversations into exchange-based chunks.
    @Override
    public List<String> chunk(String content) {
        if (content.length() <= maxChunkSize) {
            return List.of(content);
        }

        List<String> exchanges = parseExchanges(content);
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunkBuilder = new StringBuilder();

        for (String exchange : exchanges) {
            if (currentChunkBuilder.length() + exchange.length() > maxChunkSize
                && currentChunkBuilder.length() > 0) {
                chunks.add(currentChunkBuilder.toString().trim());
                currentChunkBuilder = new StringBuilder();
            }
            currentChunkBuilder.append(exchange).append("\n");
        }

        if (currentChunkBuilder.length() > 0) {
            chunks.add(currentChunkBuilder.toString().trim());
        }

        return chunks;
    }

    // Parse Player/Villager exchanges from the raw transcript.
    private List<String> parseExchanges(String content) {
        List<String> exchanges = new ArrayList<>();
        Matcher matcher = EXCHANGE_PATTERN.matcher(content);

        StringBuilder currentExchangeBuilder = new StringBuilder();
        String lastSpeaker = null;

        while (matcher.find()) {
            String line = matcher.group();
            String speaker = line.startsWith("Player") ? "Player" : "Villager";

            if ("Player".equals(lastSpeaker) && "Villager".equals(speaker)) {
                currentExchangeBuilder.append(line).append("\n");
                exchanges.add(currentExchangeBuilder.toString().trim());
                currentExchangeBuilder = new StringBuilder();
                lastSpeaker = null;
            } else {
                if (currentExchangeBuilder.length() > 0 && "Player".equals(speaker)) {
                    exchanges.add(currentExchangeBuilder.toString().trim());
                    currentExchangeBuilder = new StringBuilder();
                }
                currentExchangeBuilder.append(line).append("\n");
                lastSpeaker = speaker;
            }
        }

        if (currentExchangeBuilder.length() > 0) {
            exchanges.add(currentExchangeBuilder.toString().trim());
        }

        return exchanges;
    }
}
