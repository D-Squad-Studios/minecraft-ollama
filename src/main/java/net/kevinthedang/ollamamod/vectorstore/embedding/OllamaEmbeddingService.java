package net.kevinthedang.ollamamod.vectorstore.embedding;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kevinthedang.ollamamod.vectorstore.VectorStoreSettings;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class OllamaEmbeddingService implements EmbeddingService {
    private final HttpClient httpClient;
    private final URI embedEndpoint;
    private final Gson gson = new Gson();

    // Uses VectorStoreSettings defaults for base URL and model.
    public OllamaEmbeddingService() {
        this(VectorStoreSettings.ollamaBaseUrl, VectorStoreSettings.embeddingModel);
    }

    // Creates an embedding service targeting a specific Ollama base URL and model.
    public OllamaEmbeddingService(String baseUrl, String model) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        this.embedEndpoint = URI.create(baseUrl + "/api/embed");
        this.model = model;
    }

    private final String model;

    // Embed a single text input using Ollama's /api/embed endpoint.
    @Override
    public CompletableFuture<float[]> embed(String text) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("input", text);
        String json = gson.toJson(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(embedEndpoint)
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() != 200) {
                    throw new RuntimeException("Ollama HTTP " + response.statusCode() + ": " + response.body());
                }
                return parseFirstEmbedding(response.body());
            });
    }

    // Embed a batch of text inputs using Ollama's /api/embed endpoint.
    @Override
    public CompletableFuture<List<float[]>> embedBatch(List<String> texts) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("input", texts);
        String json = gson.toJson(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(embedEndpoint)
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() != 200) {
                    throw new RuntimeException("Ollama HTTP " + response.statusCode() + ": " + response.body());
                }
                return parseEmbeddings(response.body());
            });
    }

    // Return the configured embedding dimension.
    @Override
    public int getDimension() {
        return VectorStoreSettings.embeddingDimension;
    }

    // Check Ollama availability by hitting /api/tags.
    @Override
    public boolean isHealthy() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(VectorStoreSettings.ollamaBaseUrl + "/api/tags"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // Extract the first embedding vector from the response JSON.
    private static float[] parseFirstEmbedding(String jsonResponse) {
        List<float[]> embeddings = parseEmbeddings(jsonResponse);
        if (embeddings.isEmpty()) {
            throw new IllegalArgumentException("Ollama response contained no embeddings");
        }
        return embeddings.get(0);
    }

    // Parse all embedding vectors from the response JSON.
    private static List<float[]> parseEmbeddings(String jsonResponse) {
        JsonObject root = JsonParser.parseString(jsonResponse).getAsJsonObject();
        JsonArray embeddings = root.getAsJsonArray("embeddings");
        if (embeddings == null) {
            throw new IllegalArgumentException("No embeddings field in response");
        }

        List<float[]> vectors = new ArrayList<>(embeddings.size());
        for (JsonElement element : embeddings) {
            JsonArray arr = element.getAsJsonArray();
            float[] vector = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                vector[i] = arr.get(i).getAsFloat();
            }
            vectors.add(vector);
        }
        return vectors;
    }
}
