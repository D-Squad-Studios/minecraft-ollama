package net.kevinthedang.ollamamod.vectorstore.embedding;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface EmbeddingService {
    // Embed a single text input into a vector.
    CompletableFuture<float[]> embed(String text);
    // Embed a batch of text inputs into vectors.
    CompletableFuture<List<float[]>> embedBatch(List<String> texts);
    // Return the expected embedding dimension.
    int getDimension();
    // Health check for the embedding provider.
    boolean isHealthy();
}
