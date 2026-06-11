package net.kevinthedang.ollamamod.vectorstore;

import net.kevinthedang.ollamamod.vectorstore.chunker.ConversationChunker;
import net.kevinthedang.ollamamod.vectorstore.chunker.JsonChunker;
import net.kevinthedang.ollamamod.vectorstore.chunker.TextChunker;
import net.kevinthedang.ollamamod.vectorstore.embedding.EmbeddingService;
import net.kevinthedang.ollamamod.vectorstore.model.VectorDocument;
import net.kevinthedang.ollamamod.vectorstore.store.LangChain4jVectorStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VectorStoreServiceFakeEmbeddingTest {

    // Store and query memories using deterministic fake embeddings.
    @Test
    public void storeAndQueryMemoryUsingFakeEmbeddings() {
        VectorStoreService service = new VectorStoreService(
            new FakeEmbeddingService(VectorStoreSettings.embeddingDimension),
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

    private static class FakeEmbeddingService implements EmbeddingService {
        private final int dimension;

        private FakeEmbeddingService(int dimension) {
            this.dimension = dimension;
        }

        @Override
        public CompletableFuture<float[]> embed(String text) {
            return CompletableFuture.completedFuture(makeVector(1.0f));
        }

        @Override
        public CompletableFuture<List<float[]>> embedBatch(List<String> texts) {
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                vectors.add(makeVector(1.0f));
            }
            return CompletableFuture.completedFuture(vectors);
        }

        @Override
        public int getDimension() {
            return dimension;
        }

        @Override
        public boolean isHealthy() {
            return true;
        }

        private float[] makeVector(float value) {
            float[] vector = new float[dimension];
            for (int i = 0; i < dimension; i++) {
                vector[i] = value;
            }
            return vector;
        }
    }
}
