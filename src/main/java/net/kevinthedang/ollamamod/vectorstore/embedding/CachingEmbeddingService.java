package net.kevinthedang.ollamamod.vectorstore.embedding;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

// Decorator that caches embedding results per conversation turn to avoid redundant HTTP calls.
public class CachingEmbeddingService implements EmbeddingService {

	private final EmbeddingService delegate;
	private final ConcurrentHashMap<String, CompletableFuture<float[]>> cache = new ConcurrentHashMap<>();

	public CachingEmbeddingService(EmbeddingService delegate) {
		this.delegate = delegate;
	}

	@Override
	public CompletableFuture<float[]> embed(String text) {
		return cache.computeIfAbsent(text, delegate::embed);
	}

	@Override
	public CompletableFuture<List<float[]>> embedBatch(List<String> texts) {
		return delegate.embedBatch(texts);
	}

	@Override
	public int getDimension() {
		return delegate.getDimension();
	}

	@Override
	public boolean isHealthy() {
		return delegate.isHealthy();
	}

	// Clear the per-turn cache. Call at the start of each conversation turn.
	public void clearCache() {
		cache.clear();
	}
}
