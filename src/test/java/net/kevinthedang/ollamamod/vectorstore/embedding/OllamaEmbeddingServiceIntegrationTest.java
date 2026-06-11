package net.kevinthedang.ollamamod.vectorstore.embedding;

import net.kevinthedang.ollamamod.vectorstore.VectorStoreSettings;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("ollama")
public class OllamaEmbeddingServiceIntegrationTest {

    // Ollama should be reachable and return an embedding vector of expected size.
    @Test
    public void embedReturnsExpectedDimension() {
        OllamaEmbeddingService service = new OllamaEmbeddingService();
        Assumptions.assumeTrue(
            service.isHealthy(),
            "Ollama is not running at " + VectorStoreSettings.ollamaBaseUrl
                + " (run: ollama pull nomic-embed-text)"
        );
        float[] embedding = service.embed("hello world").join();
        assertEquals(VectorStoreSettings.embeddingDimension, embedding.length);
    }

    // Batch embedding should return vectors for each input.
    @Test
    public void embedBatchReturnsVectors() {
        OllamaEmbeddingService service = new OllamaEmbeddingService();
        Assumptions.assumeTrue(
            service.isHealthy(),
            "Ollama is not running at " + VectorStoreSettings.ollamaBaseUrl
                + " (run: ollama pull nomic-embed-text)"
        );
        List<float[]> embeddings = service.embedBatch(List.of("a", "b", "c")).join();
        assertEquals(3, embeddings.size());
        assertEquals(VectorStoreSettings.embeddingDimension, embeddings.get(0).length);
    }
}
