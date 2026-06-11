package net.kevinthedang.ollamamod.vectorstore.model;

public record MetadataFilter(
    String type,
    String villagerId,
    String playerId,
    Long timestampAfter,
    Long timestampBefore
) {
    public static MetadataFilter documents() {
        return new MetadataFilter("document", null, null, null, null);
    }

    public static MetadataFilter memories() {
        return new MetadataFilter("memory", null, null, null, null);
    }

    public static MetadataFilter memoriesForVillager(String villagerId) {
        return new MetadataFilter("memory", villagerId, null, null, null);
    }

    public static MetadataFilter memoriesForVillagerAndPlayer(String villagerId, String playerId) {
        return new MetadataFilter("memory", villagerId, playerId, null, null);
    }

    public static MetadataFilter all() {
        return new MetadataFilter(null, null, null, null, null);
    }

    public MetadataFilter after(long timestamp) {
        return new MetadataFilter(type, villagerId, playerId, timestamp, timestampBefore);
    }

    public MetadataFilter before(long timestamp) {
        return new MetadataFilter(type, villagerId, playerId, timestampAfter, timestamp);
    }
}
