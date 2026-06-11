package net.kevinthedang.ollamamod.vectorstore;

import net.kevinthedang.ollamamod.vectorstore.chunker.ConversationChunker;
import net.kevinthedang.ollamamod.vectorstore.chunker.JsonChunker;
import net.kevinthedang.ollamamod.vectorstore.chunker.TextChunker;
import net.kevinthedang.ollamamod.vectorstore.embedding.CachingEmbeddingService;
import net.kevinthedang.ollamamod.vectorstore.embedding.EmbeddingService;
import net.kevinthedang.ollamamod.vectorstore.embedding.OllamaEmbeddingService;
import net.kevinthedang.ollamamod.vectorstore.model.MetadataFilter;
import net.kevinthedang.ollamamod.vectorstore.model.VectorDocument;
import net.kevinthedang.ollamamod.vectorstore.model.VectorMetadata;
import net.kevinthedang.ollamamod.vectorstore.store.LangChain4jVectorStore;
import net.kevinthedang.ollamamod.vectorstore.store.VectorStore;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class VectorStoreService {
    private final EmbeddingService embeddingService;
    private final VectorStore store;
    private final TextChunker textChunker;
    private final JsonChunker jsonChunker;
    private final ConversationChunker conversationChunker;

    // Create a service with default embedding provider, chunkers, and store implementation.
    public VectorStoreService() {
        this(new OllamaEmbeddingService(), new LangChain4jVectorStore(),
            new TextChunker(), new JsonChunker(), new ConversationChunker());
    }

    // Create a service with custom dependencies (useful for testing).
    public VectorStoreService(EmbeddingService embeddingService,
                              VectorStore store,
                              TextChunker textChunker,
                              JsonChunker jsonChunker,
                              ConversationChunker conversationChunker) {
        this.embeddingService = embeddingService;
        this.store = store;
        this.textChunker = textChunker;
        this.jsonChunker = jsonChunker;
        this.conversationChunker = conversationChunker;
    }

    // Store a document from a file path by chunking, embedding, and inserting into the store.
    public CompletableFuture<Void> storeDocument(Path path) {
        return CompletableFuture.supplyAsync(() -> readFile(path))
            .thenCompose(content -> {
                String lower = path.getFileName().toString().toLowerCase();
                List<String> chunks = lower.endsWith(".json")
                    ? jsonChunker.chunk(content)
                    : textChunker.chunk(content);
                VectorMetadata baseMetadata = VectorMetadata.document();
                return embedAndStoreChunks(chunks, baseMetadata);
            });
    }

    // Store a memory transcript by chunking, embedding, and inserting into the store.
    public CompletableFuture<Void> storeMemory(String content, String villagerId, String playerId) {
        List<String> chunks = conversationChunker.chunk(content);
        VectorMetadata baseMetadata = VectorMetadata.memory(villagerId, playerId);
        return embedAndStoreChunks(chunks, baseMetadata);
    }

    // Query document chunks using the provided query text.
    public CompletableFuture<List<VectorDocument>> queryDocuments(String query, int topK) {
        return embeddingService.embed(query)
            .thenApply(queryEmbedding -> store.query(
                queryEmbedding,
                MetadataFilter.documents(),
                topK,
                VectorStoreSettings.defaultMinScore
            ));
    }

    // Query memory chunks for a specific villager using the provided query text.
    public CompletableFuture<List<VectorDocument>> queryMemories(String query, String villagerId, int topK) {
        return embeddingService.embed(query)
            .thenApply(queryEmbedding -> store.query(
                queryEmbedding,
                MetadataFilter.memoriesForVillager(villagerId),
                topK,
                VectorStoreSettings.defaultMinScore
            ));
    }

    // Persist all stored embeddings and metadata to disk in the provided directory.
    public void persistAll(Path baseDirectory) {
        store.persist(resolveStorePath(baseDirectory));
    }

    // Load stored embeddings and metadata from disk in the provided directory.
    public void loadAll(Path baseDirectory) {
        store.load(resolveStorePath(baseDirectory));
    }

    // Load seed data from the resources folder if present.
    public void loadSeedData() {
        try (InputStream seedStream = getClass().getResourceAsStream(VectorStoreSettings.seedStorePath)) {
            if (seedStream != null) {
                store.loadFromStream(seedStream);
            }
        } catch (java.io.IOException exception) {
            throw new RuntimeException("Failed to load seed data", exception);
        }
    }

    // Clear the per-turn embedding cache if the service supports it.
    public void clearEmbeddingCache() {
        if (embeddingService instanceof CachingEmbeddingService cachingSvc) {
            cachingSvc.clearCache();
        }
    }

    // Delete all memories associated with a specific villager.
    public int deleteMemoriesForVillager(String villagerId) {
        return store.deleteByFilter(MetadataFilter.memoriesForVillager(villagerId));
    }

    // Check whether the embedding service is reachable.
    public boolean isHealthy() {
        return embeddingService.isHealthy();
    }

    // Count documents matching a metadata filter.
    public int count(MetadataFilter filter) {
        return store.count(filter);
    }

    // Read a UTF-8 file into a string.
    private String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new RuntimeException("Failed to read file: " + path, exception);
        }
    }

    // Embed chunks in a batch and store them with metadata.
    private CompletableFuture<Void> embedAndStoreChunks(List<String> chunks, VectorMetadata baseMetadata) {
        if (chunks.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        return embeddingService.embedBatch(chunks)
            .thenAccept(embeddings -> {
                List<VectorDocument> documents = new ArrayList<>(chunks.size());
                int totalChunks = chunks.size();
                for (int index = 0; index < totalChunks; index++) {
                    VectorMetadata metadata = baseMetadata.withChunk(index, totalChunks);
                    VectorDocument document = new VectorDocument(
                        java.util.UUID.randomUUID().toString(),
                        chunks.get(index),
                        embeddings.get(index),
                        metadata
                    );
                    documents.add(document);
                }
                store.storeAll(documents);
            });
    }

    // Resolve the on-disk path to the vector store file.
    private Path resolveStorePath(Path baseDirectory) {
        return baseDirectory.resolve(VectorStoreSettings.dataDirectory)
            .resolve(VectorStoreSettings.storeFile);
    }
}
