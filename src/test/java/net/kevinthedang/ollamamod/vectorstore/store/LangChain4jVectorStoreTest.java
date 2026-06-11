package net.kevinthedang.ollamamod.vectorstore.store;

import net.kevinthedang.ollamamod.vectorstore.model.MetadataFilter;
import net.kevinthedang.ollamamod.vectorstore.model.VectorDocument;
import net.kevinthedang.ollamamod.vectorstore.model.VectorMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LangChain4jVectorStoreTest {

    // Store and query with a metadata filter should only return matching documents.
    @Test
    public void queryFiltersByMetadata() {
        LangChain4jVectorStore store = new LangChain4jVectorStore();
        float[] embedding = new float[] { 1.0f, 0.0f, 0.0f };

        VectorDocument docA = new VectorDocument(
            "a",
            "doc A",
            embedding,
            VectorMetadata.document()
        );
        VectorDocument docB = new VectorDocument(
            "b",
            "doc B",
            embedding,
            VectorMetadata.memory("villager", "player")
        );

        store.storeAll(List.of(docA, docB));
        List<VectorDocument> results = store.query(embedding, MetadataFilter.documents(), 2, 0.0);
        assertEquals(1, results.size(), "Expected only document entries");
        assertEquals("a", results.get(0).id());
    }

    // Persist and load should round-trip stored documents.
    @Test
    public void persistAndLoadRoundTrip(@TempDir Path tempDir) {
        LangChain4jVectorStore store = new LangChain4jVectorStore();
        float[] embedding = new float[] { 0.1f, 0.2f };
        VectorDocument doc = new VectorDocument(
            "doc",
            "content",
            embedding,
            VectorMetadata.document()
        );
        store.store(doc);

        Path storePath = tempDir.resolve("vector.store");
        store.persist(storePath);

        LangChain4jVectorStore reloaded = new LangChain4jVectorStore();
        reloaded.load(storePath);

        assertEquals(1, reloaded.count(MetadataFilter.all()));
        assertTrue(reloaded.getById("doc").isPresent(), "Document should be loaded");
    }
}
