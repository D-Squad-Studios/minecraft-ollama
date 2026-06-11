package net.kevinthedang.ollamamod.vectorstore.store;

import net.kevinthedang.ollamamod.vectorstore.model.MetadataFilter;
import net.kevinthedang.ollamamod.vectorstore.model.VectorDocument;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface VectorStore {
    // Store a single document.
    void store(VectorDocument document);
    // Store multiple documents.
    void storeAll(List<VectorDocument> documents);

    // Query the store with an embedding and optional metadata filter.
    List<VectorDocument> query(float[] queryEmbedding, MetadataFilter filter,
                               int topK, double minScore);

    // Retrieve a document by its id.
    Optional<VectorDocument> getById(String documentId);
    // Delete a document by id.
    boolean delete(String documentId);
    // Delete documents matching a filter and return count removed.
    int deleteByFilter(MetadataFilter filter);
    // Count documents matching a filter.
    int count(MetadataFilter filter);

    // Persist the store to disk.
    void persist(Path path);
    // Load the store from disk.
    void load(Path path);
    // Load the store from a stream.
    void loadFromStream(InputStream stream);
    // Clear all documents from the store.
    void clear();
}
