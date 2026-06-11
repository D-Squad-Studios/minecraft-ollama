package net.kevinthedang.ollamamod.vectorstore.model;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record VectorDocument(
    String id,
    String content,
    float[] embedding,
    VectorMetadata metadata
) {
    // Serialize this document in the shared vector store binary format.
    public void writeTo(DataOutputStream outputStream) throws IOException {
        outputStream.writeUTF(id);
        outputStream.writeUTF(content);
        outputStream.writeInt(embedding.length);
        for (float value : embedding) {
            outputStream.writeFloat(value);
        }
        outputStream.writeUTF(metadata.type());
        outputStream.writeBoolean(metadata.villagerId() != null);
        if (metadata.villagerId() != null) outputStream.writeUTF(metadata.villagerId());
        outputStream.writeBoolean(metadata.playerId() != null);
        if (metadata.playerId() != null) outputStream.writeUTF(metadata.playerId());
        outputStream.writeLong(metadata.timestamp());
        outputStream.writeInt(metadata.chunkIndex());
        outputStream.writeInt(metadata.chunkTotal());
    }

    // Deserialize a document from the shared vector store binary format.
    public static VectorDocument readFrom(DataInputStream inputStream) throws IOException {
        String id = inputStream.readUTF();
        String content = inputStream.readUTF();
        int embeddingLength = inputStream.readInt();
        float[] embedding = new float[embeddingLength];
        for (int index = 0; index < embeddingLength; index++) {
            embedding[index] = inputStream.readFloat();
        }
        String type = inputStream.readUTF();
        String villagerId = null;
        if (inputStream.readBoolean()) {
            villagerId = inputStream.readUTF();
        }
        String playerId = null;
        if (inputStream.readBoolean()) {
            playerId = inputStream.readUTF();
        }
        long timestamp = inputStream.readLong();
        int chunkIndex = inputStream.readInt();
        int chunkTotal = inputStream.readInt();
        VectorMetadata metadata = new VectorMetadata(
            type,
            villagerId,
            playerId,
            timestamp,
            chunkIndex,
            chunkTotal
        );
        return new VectorDocument(id, content, embedding, metadata);
    }
}
