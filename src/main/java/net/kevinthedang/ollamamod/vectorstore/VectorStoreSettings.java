package net.kevinthedang.ollamamod.vectorstore;

public final class VectorStoreSettings {
    public static final String ollamaBaseUrl = "http://localhost:11434";

    public static final String embeddingModel = "nomic-embed-text";
    public static final int embeddingDimension = 768;

    public static final int chunkSize = 512;
    public static final int chunkOverlap = 64;

    public static final int defaultTopK = 3;
    public static final double defaultMinScore = 0.65;

    public static final String dataDirectory = "ollamamod/vectorstore";
    public static final String storeFile = "vectors.store";
    public static final String seedStorePath = "/ollamamod/seed/documents.store";

    private VectorStoreSettings() {}
}
