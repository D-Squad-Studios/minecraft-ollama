package net.kevinthedang.ollamamod.vectorstore.chunker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import net.kevinthedang.ollamamod.vectorstore.VectorStoreSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class JsonChunker extends Chunker {
    private final Gson gson = new Gson();

    // Chunker for JSON content using object boundaries.
    public JsonChunker() {
        super(VectorStoreSettings.chunkSize, 0);
    }

    // Chunker for JSON content with explicit size settings.
    public JsonChunker(int maxChunkSize) {
        super(maxChunkSize, 0);
    }

    // Split JSON into object-based chunks without splitting objects.
    @Override
    public List<String> chunk(String content) {
        try {
            JsonElement root = JsonParser.parseString(content);
            List<JsonElement> elements = extractChunkElements(root);
            if (elements.isEmpty()) {
                return List.of(content);
            }
            return groupJsonElements(elements);
        } catch (JsonSyntaxException exception) {
            throw new IllegalArgumentException("Invalid JSON content", exception);
        }
    }

    // Recursively extract JSON elements, prioritizing nested arrays/objects over large parents.
    private List<JsonElement> extractChunkElements(JsonElement element) {
        if (element.isJsonArray()) {
            return extractFromArray(element.getAsJsonArray());
        }

        if (element.isJsonObject()) {
            if (!shouldDrillDown(element)) {
                return List.of(element);
            }

            JsonObject object = element.getAsJsonObject();
            List<JsonElement> arrayChildren = new ArrayList<>();
            List<JsonElement> objectChildren = new ArrayList<>();

            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonArray()) {
                    arrayChildren.add(value);
                } else if (value.isJsonObject()) {
                    objectChildren.add(value);
                }
            }

            if (!arrayChildren.isEmpty()) {
                List<JsonElement> results = new ArrayList<>();
                // Prefer arrays containing objects over arrays of primitives.
                List<JsonElement> objectArrays = new ArrayList<>();
                for (JsonElement child : arrayChildren) {
                    if (arrayContainsObjects(child.getAsJsonArray())) {
                        objectArrays.add(child);
                    }
                }
                List<JsonElement> arraysToProcess = objectArrays.isEmpty() ? arrayChildren : objectArrays;
                for (JsonElement child : arraysToProcess) {
                    results.addAll(extractChunkElements(child));
                }
                return results;
            }

            if (!objectChildren.isEmpty()) {
                List<JsonElement> results = new ArrayList<>();
                for (JsonElement child : objectChildren) {
                    results.addAll(extractChunkElements(child));
                }
                return results;
            }

            return List.of(element);
        }

        return List.of(element);
    }

    // Extract elements from an array, preferring objects without drilling into them.
    private List<JsonElement> extractFromArray(JsonArray array) {
        List<JsonElement> results = new ArrayList<>();
        boolean hasObject = false;
        for (JsonElement child : array) {
            if (child.isJsonObject()) {
                hasObject = true;
                break;
            }
        }
        for (JsonElement child : array) {
            if (hasObject && shouldDrillDown(child)) {
                results.addAll(extractChunkElements(child));
            } else {
                results.add(child);
            }
        }
        return results;
    }

    // Check if the array contains object elements.
    private boolean arrayContainsObjects(JsonArray array) {
        for (JsonElement child : array) {
            if (child.isJsonObject()) {
                return true;
            }
        }
        return false;
    }

    // Decide whether to drill into a JSON element based on size and structure.
    private boolean shouldDrillDown(JsonElement element) {
        if (!element.isJsonArray() && !element.isJsonObject()) {
            return false;
        }
        String serialized = gson.toJson(element);
        if (serialized.length() <= maxChunkSize) {
            return false;
        }
        if (element.isJsonArray()) {
            return arrayContainsObjects(element.getAsJsonArray());
        }
        JsonObject object = element.getAsJsonObject();
        return objectContainsComplexChildren(object);
    }

    // Check if an object has nested structures worth drilling into.
    private boolean objectContainsComplexChildren(JsonObject object) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonArray()) {
                if (arrayContainsObjects(value.getAsJsonArray())) {
                    return true;
                }
            } else if (value.isJsonObject()) {
                return true;
            }
        }
        return false;
    }

    // Group JSON elements into chunks that stay under the max chunk size.
    private List<String> groupJsonElements(List<JsonElement> elements) {
        if (elements.isEmpty()) return List.of();

        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunkBuilder = new StringBuilder();
        for (JsonElement element : elements) {
            String elementText = labeledText(element);
            int separatorLen = currentChunkBuilder.length() > 0 ? 1 : 0;
            if (currentChunkBuilder.length() + separatorLen + elementText.length() > maxChunkSize
                && currentChunkBuilder.length() > 0) {
                chunks.add(currentChunkBuilder.toString());
                currentChunkBuilder = new StringBuilder();
            }
            if (currentChunkBuilder.length() > 0) {
                currentChunkBuilder.append("\n");
            }
            currentChunkBuilder.append(elementText);
        }
        if (currentChunkBuilder.length() > 0) {
            chunks.add(currentChunkBuilder.toString());
        }
        return chunks;
    }

    // Wrap a JSON element with a human-readable label prefix for better embeddings.
    private String labeledText(JsonElement element) {
        String label = extractLabel(element);
        if (label == null) {
            return gson.toJson(element);
        }
        // Render recipes as human-readable text instead of raw JSON
        if (element.isJsonObject() && element.getAsJsonObject().has("resultingItem")
                && element.getAsJsonObject().has("type")) {
            return formatRecipeText(element.getAsJsonObject());
        }
        return label + ": " + gson.toJson(element);
    }

    // Renders a recipe JSON object as human-readable natural language.
    // Shaped recipes become a 3x3 grid; shapeless recipes become an ingredient list.
    String formatRecipeText(JsonObject recipe) {
        JsonObject resultingItem = recipe.getAsJsonObject("resultingItem");
        String item = resultingItem.get("item").getAsString().replace("_", " ");
        int count = resultingItem.has("itemCount") ? resultingItem.get("itemCount").getAsInt() : 1;
        String type = recipe.get("type").getAsString();

        if ("shaped".equals(type) && recipe.has("pattern")) {
            StringBuilder sb = new StringBuilder();
            sb.append("shaped recipe for ").append(item).append(" (makes ").append(count).append("). Crafting grid:\n");
            JsonArray pattern = recipe.getAsJsonArray("pattern");
            for (JsonElement rowElement : pattern) {
                JsonArray row = rowElement.getAsJsonArray();
                List<String> cells = new ArrayList<>();
                for (JsonElement cell : row) {
                    if (cell.isJsonNull() || (cell.isJsonPrimitive() && cell.getAsString().isEmpty())) {
                        cells.add("[Empty]");
                    } else {
                        String name = cell.getAsString().replace("_", " ");
                        cells.add("[" + capitalize(name) + "]");
                    }
                }
                sb.append(String.join(" ", cells)).append("\n");
            }
            return sb.toString().trim();
        }

        if ("shapeless".equals(type) && recipe.has("ingredients")) {
            JsonArray ingredients = recipe.getAsJsonArray("ingredients");
            List<String> names = new ArrayList<>();
            for (JsonElement ing : ingredients) {
                if (ing.isJsonPrimitive()) {
                    names.add(ing.getAsString().replace("_", " "));
                } else if (ing.isJsonObject() && ing.getAsJsonObject().has("item")) {
                    names.add(ing.getAsJsonObject().get("item").getAsString().replace("_", " "));
                }
            }
            return "shapeless recipe for " + item + " (makes " + count + "). Ingredients: " + String.join(", ", names);
        }

        // Fallback for unknown recipe types
        return type + " recipe for " + item + ": " + gson.toJson(recipe);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // Extract a human-readable label from a JSON object for embedding quality.
    // Returns null if no recognizable pattern is found.
    String extractLabel(JsonElement element) {
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject obj = element.getAsJsonObject();

        // Recipe pattern: resultingItem.item + type
        if (obj.has("resultingItem") && obj.has("type")) {
            JsonElement resultingItem = obj.get("resultingItem");
            if (resultingItem.isJsonObject()) {
                JsonObject ri = resultingItem.getAsJsonObject();
                if (ri.has("item")) {
                    String item = ri.get("item").getAsString().replace("_", " ");
                    String type = obj.get("type").getAsString();
                    return type + " recipe for " + item;
                }
            }
        }

        // Item/entity pattern: prefer displayName over name
        if (obj.has("displayName")) {
            return obj.get("displayName").getAsString();
        }
        if (obj.has("name")) {
            return obj.get("name").getAsString();
        }

        return null;
    }
}
