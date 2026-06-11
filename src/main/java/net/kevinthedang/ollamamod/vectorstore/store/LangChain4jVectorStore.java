package net.kevinthedang.ollamamod.vectorstore.store;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.filter.logical.And;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import net.kevinthedang.ollamamod.vectorstore.model.MetadataFilter;
import net.kevinthedang.ollamamod.vectorstore.model.VectorDocument;
import net.kevinthedang.ollamamod.vectorstore.model.VectorMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class LangChain4jVectorStore implements VectorStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(LangChain4jVectorStore.class);
    private final InMemoryEmbeddingStore<TextSegment> embeddingStore;
    private final Map<String, VectorDocument> documentIndex;
    private final Map<String, String> embeddingIdIndex;

    // Initialize an empty in-memory store and index.
    public LangChain4jVectorStore() {
        this.embeddingStore = new InMemoryEmbeddingStore<>();
        this.documentIndex = new HashMap<>();
        this.embeddingIdIndex = new HashMap<>();
    }

    // Store a single document in the embedding store and index.
    @Override
    public void store(VectorDocument document) {
        TextSegment segment = toSegment(document);
        String embeddingId = embeddingStore.add(Embedding.from(document.embedding()), segment);
        documentIndex.put(document.id(), document);
        embeddingIdIndex.put(document.id(), embeddingId);
    }

    // Store multiple documents in the embedding store and index.
    @Override
    public void storeAll(List<VectorDocument> documents) {
        for (VectorDocument document : documents) {
            store(document);
        }
    }

    // Query the embedding store with an optional metadata filter.
    @Override
    public List<VectorDocument> query(float[] queryEmbedding, MetadataFilter filter,
                                      int topK, double minScore) {
        Filter metadataFilter = toLangChainFilter(filter);
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
            .queryEmbedding(Embedding.from(queryEmbedding))
            .maxResults(topK)
            .minScore(minScore)
            .filter(metadataFilter)
            .build();
        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(request);
        List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();

        List<VectorDocument> results = new ArrayList<>(matches.size());
        for (EmbeddingMatch<TextSegment> match : matches) {
            TextSegment segment = match.embedded();
            String id = segment.metadata().getString("id");
            VectorDocument document = documentIndex.get(id);
            if (document != null) {
                results.add(document);
            }
        }
        return results;
    }

    // Retrieve a stored document by id.
    @Override
    public Optional<VectorDocument> getById(String documentId) {
        return Optional.ofNullable(documentIndex.get(documentId));
    }

    // Delete a document by id from the store and indexes.
    @Override
    public boolean delete(String documentId) {
        VectorDocument document = documentIndex.remove(documentId);
        if (document == null) {
            return false;
        }
        String embeddingId = embeddingIdIndex.remove(documentId);
        if (embeddingId != null) {
            embeddingStore.removeAll(List.of(embeddingId));
        } else {
            LOGGER.warn("Embedding ID missing for document {}. Embedding entry not removed.", documentId);
        }
        return true;
    }

    // Delete all documents matching the filter and rebuild if needed.
    @Override
    public int deleteByFilter(MetadataFilter filter) {
        if (filter == null) {
            int removed = documentIndex.size();
            documentIndex.clear();
            embeddingStore.removeAll();
            embeddingIdIndex.clear();
            return removed;
        }

        int removed = 0;
        List<String> toRemove = new ArrayList<>();
        List<String> embeddingIdsToRemove = new ArrayList<>();
        for (VectorDocument document : documentIndex.values()) {
            if (matchesFilter(document, filter)) {
                toRemove.add(document.id());
            }
        }
        for (String id : toRemove) {
            documentIndex.remove(id);
            String embeddingId = embeddingIdIndex.remove(id);
            if (embeddingId != null) {
                embeddingIdsToRemove.add(embeddingId);
            }
            removed++;
        }
        if (!embeddingIdsToRemove.isEmpty()) {
            embeddingStore.removeAll(embeddingIdsToRemove);
        } else if (removed > 0) {
            LOGGER.warn("Embedding IDs missing for {} documents. Embedding entries not removed.", removed);
        }
        return removed;
    }

    // Count documents matching the filter.
    @Override
    public int count(MetadataFilter filter) {
        if (filter == null) return documentIndex.size();
        int count = 0;
        for (VectorDocument document : documentIndex.values()) {
            if (matchesFilter(document, filter)) {
                count++;
            }
        }
        return count;
    }

    // Persist the store to disk as a binary format.
    @Override
    public void persist(Path path) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            try (DataOutputStream outputStream = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(path)))) {
                outputStream.writeInt(documentIndex.size());
                for (VectorDocument document : documentIndex.values()) {
                    document.writeTo(outputStream);
                }
            }
        } catch (IOException exception) {
            throw new RuntimeException("Failed to persist vector store", exception);
        }
    }

    // Load the store from a file, replacing any existing content.
    @Override
    public void load(Path path) {
        if (!Files.exists(path)) return;
        try (DataInputStream inputStream = new DataInputStream(
            new BufferedInputStream(Files.newInputStream(path)))) {
            int documentCount = inputStream.readInt();
            documentIndex.clear();
            embeddingStore.removeAll();
            for (int index = 0; index < documentCount; index++) {
                VectorDocument document = VectorDocument.readFrom(inputStream);
                store(document);
            }
        } catch (EOFException eof) {
            throw new RuntimeException("Vector store file appears truncated", eof);
        } catch (IOException exception) {
            throw new RuntimeException("Failed to load vector store", exception);
        }
    }

    // Load the store from a stream and append entries to the current store.
    @Override
    public void loadFromStream(InputStream stream) {
        try (DataInputStream inputStream = new DataInputStream(new BufferedInputStream(stream))) {
            int documentCount = inputStream.readInt();
            for (int index = 0; index < documentCount; index++) {
                VectorDocument document = VectorDocument.readFrom(inputStream);
                store(document);
            }
        } catch (EOFException eof) {
            throw new RuntimeException("Vector store stream appears truncated", eof);
        } catch (IOException exception) {
            throw new RuntimeException("Failed to load vector store", exception);
        }
    }

    // Clear the store and in-memory index.
    @Override
    public void clear() {
        documentIndex.clear();
        embeddingStore.removeAll();
        embeddingIdIndex.clear();
    }

    // Convert a VectorDocument into a LangChain4j TextSegment with metadata.
    private TextSegment toSegment(VectorDocument document) {
        VectorMetadata metadata = document.metadata();
        Metadata langMetadata = new Metadata();
        langMetadata.put("id", document.id());
        langMetadata.put("type", metadata.type());
        if (metadata.villagerId() != null) {
            langMetadata.put("villagerId", metadata.villagerId());
        }
        if (metadata.playerId() != null) {
            langMetadata.put("playerId", metadata.playerId());
        }
        langMetadata.put("timestamp", metadata.timestamp());
        langMetadata.put("chunkIndex", metadata.chunkIndex());
        langMetadata.put("chunkTotal", metadata.chunkTotal());
        return new TextSegment(document.content(), langMetadata);
    }

    // Evaluate whether a document matches the metadata filter.
    private static boolean matchesFilter(VectorDocument document, MetadataFilter filter) {
        if (filter == null) return true;
        VectorMetadata metadata = document.metadata();

        if (filter.type() != null && !filter.type().equals(metadata.type())) return false;
        if (filter.villagerId() != null && !filter.villagerId().equals(metadata.villagerId())) return false;
        if (filter.playerId() != null && !filter.playerId().equals(metadata.playerId())) return false;
        if (filter.timestampAfter() != null && metadata.timestamp() <= filter.timestampAfter()) return false;
        if (filter.timestampBefore() != null && metadata.timestamp() >= filter.timestampBefore()) return false;

        return true;
    }

    // Convert MetadataFilter to a LangChain4j Filter for server-side filtering.
    private static Filter toLangChainFilter(MetadataFilter filter) {
        if (filter == null) return null;

        List<Filter> filters = new ArrayList<>();
        if (filter.type() != null) {
            filters.add(MetadataFilterBuilder.metadataKey("type").isEqualTo(filter.type()));
        }
        if (filter.villagerId() != null) {
            filters.add(MetadataFilterBuilder.metadataKey("villagerId").isEqualTo(filter.villagerId()));
        }
        if (filter.playerId() != null) {
            filters.add(MetadataFilterBuilder.metadataKey("playerId").isEqualTo(filter.playerId()));
        }
        if (filter.timestampAfter() != null) {
            filters.add(MetadataFilterBuilder.metadataKey("timestamp").isGreaterThan(filter.timestampAfter()));
        }
        if (filter.timestampBefore() != null) {
            filters.add(MetadataFilterBuilder.metadataKey("timestamp").isLessThan(filter.timestampBefore()));
        }

        if (filters.isEmpty()) return null;
        Filter combined = filters.get(0);
        for (int index = 1; index < filters.size(); index++) {
            combined = new And(combined, filters.get(index));
        }
        return combined;
    }

    // Rebuild the embedding store from the current index.
    private void rebuildEmbeddingStore() {
        embeddingStore.removeAll();
        embeddingIdIndex.clear();
        for (VectorDocument document : documentIndex.values()) {
            String embeddingId = embeddingStore.add(Embedding.from(document.embedding()), toSegment(document));
            embeddingIdIndex.put(document.id(), embeddingId);
        }
    }

}
