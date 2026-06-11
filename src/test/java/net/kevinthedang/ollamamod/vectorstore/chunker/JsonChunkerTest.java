package net.kevinthedang.ollamamod.vectorstore.chunker;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JsonChunkerTest {
    // Invalid JSON should throw an error.
    @Test
    public void invalidJsonThrows() {
        JsonChunker chunker = new JsonChunker(50);
        assertThrows(IllegalArgumentException.class, () -> chunker.chunk("{ invalid"));
    }

    // Nested arrays should be chunked by their sub-elements.
    @Test
    public void nestedArraysPrioritizeSubEntries() {
        int chunkSize = 60;
        JsonChunker chunker = new JsonChunker(chunkSize);
        String content = "{\"recipes\":[{\"name\":\"a\",\"inputs\":[1,2]}," +
            "{\"name\":\"b\",\"inputs\":[3,4]}],\"meta\":{\"version\":1}}";
        List<String> chunks = chunker.chunk(content);
        assertTrue(!chunks.isEmpty(), "Expected chunks derived from nested array entries");
        boolean foundRecipeEntry = false;
        for (String chunk : chunks) {
            assertTrue(!chunk.contains("\"meta\""), "Chunk should not include top-level meta object");
            assertTrue(chunk.length() <= chunkSize,
                "Chunk should respect max chunk size");
            if (chunk.contains("name")) {
                foundRecipeEntry = true;
            }
        }
        assertTrue(foundRecipeEntry, "Expected at least one recipe entry chunk");
    }

    // Small JSON objects should stay whole when under the chunk size.
    @Test
    public void smallJsonStaysWhole() {
        JsonChunker chunker = new JsonChunker(200);
        String content = "{\"name\":\"small\",\"inputs\":[1,2],\"meta\":{\"version\":1}}";
        List<String> chunks = chunker.chunk(content);
        assertTrue(chunks.size() == 1, "Expected a single chunk for small JSON");
        assertTrue(chunks.get(0).contains("\"name\""), "Chunk should include the object");
    }

    // Large JSON objects should drill into nested object arrays rather than primitives.
    @Test
    public void largeJsonDrillsIntoObjectArrays() {
        JsonChunker chunker = new JsonChunker(60);
        String content = "{\"recipes\":[{\"name\":\"a\",\"inputs\":[1,2,3,4,5]},"
            + "{\"name\":\"b\",\"inputs\":[6,7,8,9,10]}],\"meta\":{\"version\":1}}";
        List<String> chunks = chunker.chunk(content);
        boolean foundRecipeEntry = false;
        for (String chunk : chunks) {
            if (chunk.contains("\"name\"")) {
                foundRecipeEntry = true;
            }
            assertTrue(!chunk.contains("\"meta\""), "Chunk should not include top-level meta object");
        }
        assertTrue(foundRecipeEntry, "Expected recipe entries to be chunked");
    }

    // Recipe objects should get a label with type and item name.
    @Test
    public void extractLabelForRecipe() {
        JsonChunker chunker = new JsonChunker(512);
        String json = "{\"resultingItem\":{\"item\":\"diamond_pickaxe\",\"itemCount\":1},"
            + "\"type\":\"shaped\",\"pattern\":[[\"diamond\",\"diamond\",\"diamond\"]]}";
        String label = chunker.extractLabel(JsonParser.parseString(json));
        assertEquals("shaped recipe for diamond pickaxe", label);
    }

    // Objects with displayName should use it as the label.
    @Test
    public void extractLabelForDisplayName() {
        JsonChunker chunker = new JsonChunker(512);
        String json = "{\"name\":\"stone\",\"displayName\":\"Stone\",\"stackSize\":64}";
        String label = chunker.extractLabel(JsonParser.parseString(json));
        assertEquals("Stone", label);
    }

    // Objects with only a name field should use it as the label.
    @Test
    public void extractLabelForNameOnly() {
        JsonChunker chunker = new JsonChunker(512);
        String json = "{\"name\":\"badlands\",\"category\":\"mesa\"}";
        String label = chunker.extractLabel(JsonParser.parseString(json));
        assertEquals("badlands", label);
    }

    // Objects with no recognizable fields should return null.
    @Test
    public void extractLabelReturnsNullForUnknown() {
        JsonChunker chunker = new JsonChunker(512);
        String json = "{\"foo\":\"bar\",\"count\":5}";
        assertNull(chunker.extractLabel(JsonParser.parseString(json)));
    }

    // Primitives should return null label.
    @Test
    public void extractLabelReturnsNullForPrimitive() {
        JsonChunker chunker = new JsonChunker(512);
        assertNull(chunker.extractLabel(JsonParser.parseString("42")));
    }

    // Chunks should include labels as prefixes.
    @Test
    public void chunksContainLabels() {
        JsonChunker chunker = new JsonChunker(512);
        String content = "[{\"resultingItem\":{\"item\":\"diamond_pickaxe\",\"itemCount\":1},"
            + "\"type\":\"shaped\",\"pattern\":[[\"diamond\",\"diamond\",\"diamond\"],"
            + "[null,\"stick\",null],[null,\"stick\",null]]}]";
        List<String> chunks = chunker.chunk(content);
        assertTrue(chunks.size() >= 1, "Expected at least one chunk");
        assertTrue(chunks.get(0).startsWith("shaped recipe for diamond pickaxe"),
            "Chunk should start with recipe label");
    }

    // Shaped recipes should render as a human-readable grid with [Item] cells.
    @Test
    public void shapedRecipeFormattedAsGrid() {
        JsonChunker chunker = new JsonChunker(1024);
        String content = "[{\"resultingItem\":{\"item\":\"diamond_pickaxe\",\"itemCount\":1},"
            + "\"type\":\"shaped\",\"pattern\":[[\"diamond\",\"diamond\",\"diamond\"],"
            + "[null,\"stick\",null],[null,\"stick\",null]]}]";
        List<String> chunks = chunker.chunk(content);
        String chunk = chunks.get(0);

        assertTrue(chunk.contains("[Diamond] [Diamond] [Diamond]"),
            "Top row should show three diamonds: " + chunk);
        assertTrue(chunk.contains("[Empty] [Stick] [Empty]"),
            "Middle/bottom rows should show centered stick: " + chunk);
        assertTrue(chunk.contains("Crafting grid:"),
            "Should contain grid header: " + chunk);
        assertFalse(chunk.contains("{"),
            "Should not contain raw JSON: " + chunk);
    }

    // Shapeless recipes should render as an ingredient list.
    @Test
    public void shapelessRecipeFormattedAsList() {
        JsonChunker chunker = new JsonChunker(1024);
        String content = "[{\"resultingItem\":{\"item\":\"granite\",\"itemCount\":1},"
            + "\"type\":\"shapeless\",\"ingredients\":[\"diorite\",\"quartz\"]}]";
        List<String> chunks = chunker.chunk(content);
        String chunk = chunks.get(0);

        assertTrue(chunk.contains("Ingredients:"),
            "Should contain Ingredients header: " + chunk);
        assertTrue(chunk.contains("diorite"),
            "Should list diorite: " + chunk);
        assertTrue(chunk.contains("quartz"),
            "Should list quartz: " + chunk);
        assertFalse(chunk.contains("{"),
            "Should not contain raw JSON: " + chunk);
    }
}
