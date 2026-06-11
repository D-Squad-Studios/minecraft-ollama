package net.kevinthedang.ollamamod.vectorstore.chunker;

import java.util.List;

public abstract class Chunker {
    protected final int maxChunkSize;
    protected final int overlapSize;

    // Base chunker with size and overlap configuration.
    public Chunker(int maxChunkSize, int overlapSize) {
        this.maxChunkSize = maxChunkSize;
        this.overlapSize = overlapSize;
    }

    // Split raw content into chunk strings.
    public abstract List<String> chunk(String content);

}
