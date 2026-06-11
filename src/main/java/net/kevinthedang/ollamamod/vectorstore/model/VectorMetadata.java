package net.kevinthedang.ollamamod.vectorstore.model;

public record VectorMetadata(
    String type,
    String villagerId,
    String playerId,
    long timestamp,
    int chunkIndex,
    int chunkTotal
) {
    public static VectorMetadata document() {
        return new VectorMetadata("document", null, null, System.currentTimeMillis(), 0, 1);
    }

    public static VectorMetadata memory(String villagerId, String playerId) {
        return new VectorMetadata("memory", villagerId, playerId, System.currentTimeMillis(), 0, 1);
    }

    public VectorMetadata withChunk(int index, int total) {
        return new VectorMetadata(type, villagerId, playerId, timestamp, index, total);
    }
}
