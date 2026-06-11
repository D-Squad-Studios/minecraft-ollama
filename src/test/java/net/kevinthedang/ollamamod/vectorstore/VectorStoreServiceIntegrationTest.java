package net.kevinthedang.ollamamod.vectorstore;

import net.kevinthedang.ollamamod.vectorstore.chunker.ConversationChunker;
import net.kevinthedang.ollamamod.vectorstore.chunker.JsonChunker;
import net.kevinthedang.ollamamod.vectorstore.chunker.TextChunker;
import net.kevinthedang.ollamamod.vectorstore.embedding.OllamaEmbeddingService;
import net.kevinthedang.ollamamod.vectorstore.model.VectorDocument;
import net.kevinthedang.ollamamod.vectorstore.store.LangChain4jVectorStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("ollama")
public class VectorStoreServiceIntegrationTest {
    // Store and query memories using real Ollama embeddings.
    @Test
    public void storeAndQueryMemoryUsingOllama() {
        OllamaEmbeddingService embeddingService = new OllamaEmbeddingService();
        Assumptions.assumeTrue(
            embeddingService.isHealthy(),
            "Ollama is not running at " + VectorStoreSettings.ollamaBaseUrl
                + " (run: ollama pull nomic-embed-text)"
        );

        VectorStoreService service = new VectorStoreService(
            embeddingService,
            new LangChain4jVectorStore(),
            new TextChunker(),
            new JsonChunker(),
            new ConversationChunker()
        );

        String memory = "Player: Hello\nVillager: Hi there";
        service.storeMemory(memory, "villager-1", "player-1").join();

        List<VectorDocument> results = service.queryMemories(memory, "villager-1", 3).join();
        assertFalse(results.isEmpty(), "Expected memory query results");
        assertTrue(results.get(0).metadata().type().equals("memory"));
        assertTrue(results.get(0).metadata().villagerId().equals("villager-1"));
    }
}
