package net.kevinthedang.ollamamod.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SceneDescriptionBuilderTest {

	// === naturalDirection() tests ===

	@Test
	void nearbyWhenAllAxesSmall() {
		String result = SceneDescriptionBuilder.naturalDirection(1, 0, 1);
		assertEquals("right next to you", result);
	}

	@Test
	void nearbyWhenZero() {
		String result = SceneDescriptionBuilder.naturalDirection(0, 0, 0);
		assertEquals("right next to you", result);
	}

	@Test
	void northWhenNegativeZ() {
		String result = SceneDescriptionBuilder.naturalDirection(0, 0, -10);
		assertEquals("about 10 blocks to the north", result);
	}

	@Test
	void southWhenPositiveZ() {
		String result = SceneDescriptionBuilder.naturalDirection(0, 0, 8);
		assertEquals("about 8 blocks to the south", result);
	}

	@Test
	void eastWhenPositiveX() {
		String result = SceneDescriptionBuilder.naturalDirection(5, 0, 0);
		assertEquals("about 5 blocks to the east", result);
	}

	@Test
	void southeastCombined() {
		String result = SceneDescriptionBuilder.naturalDirection(8, 0, 6);
		assertEquals("about 10 blocks to the southeast", result);
	}

	@Test
	void southeastAndBelowCombined() {
		String result = SceneDescriptionBuilder.naturalDirection(8, -5, 6);
		assertTrue(result.contains("southeast"), "should contain southeast: " + result);
		assertTrue(result.contains("below"), "should contain below: " + result);
	}

	@Test
	void justBelowWhenMostlyVertical() {
		String result = SceneDescriptionBuilder.naturalDirection(1, -8, 0);
		assertEquals("just below your feet", result);
	}

	@Test
	void justAboveWhenMostlyVertical() {
		String result = SceneDescriptionBuilder.naturalDirection(0, 6, 1);
		assertEquals("just above you", result);
	}

	@Test
	void northwestAndAbove() {
		String result = SceneDescriptionBuilder.naturalDirection(-10, 5, -10);
		assertTrue(result.contains("northwest"), "should contain northwest: " + result);
		assertTrue(result.contains("above"), "should contain above: " + result);
	}

	// === buildSurroundings() tests ===

	@Test
	void outdoorsWithTreesAndFunctionalBlocks() {
		List<SceneDescriptionBuilder.BlockInfo> blocks = List.of(
				new SceneDescriptionBuilder.BlockInfo("minecraft:dirt", 35, 0, -1, 0),
				new SceneDescriptionBuilder.BlockInfo("minecraft:oak_log", 33, 1, 0, 1),
				new SceneDescriptionBuilder.BlockInfo("minecraft:grass_block", 8, 0, 0, 1),
				new SceneDescriptionBuilder.BlockInfo("minecraft:brewing_stand", 1, 1, 0, 0)
		);
		String result = SceneDescriptionBuilder.buildSurroundings("outdoors", blocks);

		assertTrue(result.contains("outdoors"), "should mention outdoors: " + result);
		assertTrue(result.contains("dirt"), "should mention dirt: " + result);
		assertTrue(result.contains("oak"), "should mention oak trees: " + result);
		assertTrue(result.contains("brewing stand"), "should mention brewing stand: " + result);
		assertTrue(result.contains("arm's reach"), "should mention arm's reach: " + result);
	}

	@Test
	void undergroundInDarkness() {
		List<SceneDescriptionBuilder.BlockInfo> blocks = List.of(
				new SceneDescriptionBuilder.BlockInfo("minecraft:stone", 50, 0, 0, 0),
				new SceneDescriptionBuilder.BlockInfo("minecraft:deepslate", 30, 0, -1, 0)
		);
		String result = SceneDescriptionBuilder.buildSurroundings("underground in darkness", blocks);

		assertTrue(result.contains("underground"), "should mention underground: " + result);
		assertTrue(result.contains("stone"), "should mention stone: " + result);
	}

	@Test
	void indoorsWithMultipleFunctionalBlocks() {
		List<SceneDescriptionBuilder.BlockInfo> blocks = List.of(
				new SceneDescriptionBuilder.BlockInfo("minecraft:oak_planks", 40, 0, 0, 0),
				new SceneDescriptionBuilder.BlockInfo("minecraft:chest", 1, 1, 0, 0),
				new SceneDescriptionBuilder.BlockInfo("minecraft:crafting_table", 1, -1, 0, 0)
		);
		String result = SceneDescriptionBuilder.buildSurroundings("indoors", blocks);

		assertTrue(result.contains("indoors"), "should mention indoors: " + result);
		assertTrue(result.toLowerCase().contains("chest"), "should mention chest: " + result);
		assertTrue(result.toLowerCase().contains("crafting table"), "should mention crafting table: " + result);
		assertTrue(result.contains("are within arm's reach"), "multiple items should use 'are': " + result);
	}

	@Test
	void noFunctionalBlocksOmitsArmReach() {
		List<SceneDescriptionBuilder.BlockInfo> blocks = List.of(
				new SceneDescriptionBuilder.BlockInfo("minecraft:dirt", 50, 0, -1, 0),
				new SceneDescriptionBuilder.BlockInfo("minecraft:grass_block", 20, 0, 0, 1)
		);
		String result = SceneDescriptionBuilder.buildSurroundings("outdoors", blocks);

		assertFalse(result.contains("arm's reach"), "no functional blocks = no arm's reach: " + result);
	}

	@Test
	void leavesOmittedTreesInferred() {
		List<SceneDescriptionBuilder.BlockInfo> blocks = List.of(
				new SceneDescriptionBuilder.BlockInfo("minecraft:oak_log", 10, 1, 1, 0),
				new SceneDescriptionBuilder.BlockInfo("minecraft:oak_leaves", 40, 1, 2, 0)
		);
		String result = SceneDescriptionBuilder.buildSurroundings("outdoors", blocks);

		assertTrue(result.contains("oak"), "should infer oak trees: " + result);
		assertFalse(result.contains("leaves"), "should not mention leaves directly: " + result);
	}

	@Test
	void emptyBlocksGivesMinimalDescription() {
		String result = SceneDescriptionBuilder.buildSurroundings("outdoors", List.of());
		assertEquals("You are outdoors.", result);
	}

	// === buildNotableBlocks() tests ===

	@Test
	void diamondOreWithDirection() {
		List<SceneDescriptionBuilder.BlockInfo> blocks = List.of(
				new SceneDescriptionBuilder.BlockInfo("minecraft:diamond_ore", 2, 8, -3, 6)
		);
		String result = SceneDescriptionBuilder.buildNotableBlocks(blocks);

		assertTrue(result.contains("Diamond ore"), "should contain diamond ore: " + result);
		assertTrue(result.contains("southeast"), "should contain southeast: " + result);
		assertTrue(result.contains("below"), "should contain below: " + result);
	}

	@Test
	void bedrockFilteredWhenHighCount() {
		List<SceneDescriptionBuilder.BlockInfo> blocks = List.of(
				new SceneDescriptionBuilder.BlockInfo("minecraft:bedrock", 1089, 0, -4, 0)
		);
		String result = SceneDescriptionBuilder.buildNotableBlocks(blocks);

		assertEquals("", result, "should filter out bedrock with high count");
	}

	@Test
	void bedrockKeptWhenLowCount() {
		List<SceneDescriptionBuilder.BlockInfo> blocks = List.of(
				new SceneDescriptionBuilder.BlockInfo("minecraft:bedrock", 3, 0, -4, 0)
		);
		String result = SceneDescriptionBuilder.buildNotableBlocks(blocks);

		assertTrue(result.contains("Bedrock"), "should keep bedrock with low count: " + result);
	}

	@Test
	void sortedByDistanceAscending() {
		List<SceneDescriptionBuilder.BlockInfo> blocks = List.of(
				new SceneDescriptionBuilder.BlockInfo("minecraft:diamond_ore", 1, 20, 0, 0),
				new SceneDescriptionBuilder.BlockInfo("minecraft:chest", 1, 3, 0, 0)
		);
		String result = SceneDescriptionBuilder.buildNotableBlocks(blocks);

		int chestIdx = result.indexOf("Chest");
		int diamondIdx = result.indexOf("Diamond ore");
		assertTrue(chestIdx < diamondIdx, "closer block should come first: " + result);
	}

	@Test
	void limitedToSixEntries() {
		List<SceneDescriptionBuilder.BlockInfo> blocks = new java.util.ArrayList<>();
		for (int i = 1; i <= 10; i++) {
			blocks.add(new SceneDescriptionBuilder.BlockInfo("minecraft:diamond_ore", 1, i * 3, 0, 0));
		}
		String result = SceneDescriptionBuilder.buildNotableBlocks(blocks);

		long count = result.chars().filter(c -> c == '\n').count();
		assertEquals(6, count, "should have exactly 6 entries: " + result);
	}

	@Test
	void emptyWhenNoNotableBlocks() {
		assertEquals("", SceneDescriptionBuilder.buildNotableBlocks(List.of()));
		assertEquals("", SceneDescriptionBuilder.buildNotableBlocks(null));
	}

	// === buildEntities() tests ===

	@Test
	void villagerWithProfessionAndDirection() {
		List<SceneDescriptionBuilder.EntityInfo> entities = List.of(
				new SceneDescriptionBuilder.EntityInfo("minecraft:villager", "librarian", 8, 0, 6)
		);
		String result = SceneDescriptionBuilder.buildEntities(entities);

		assertTrue(result.contains("librarian villager"), "should mention profession: " + result);
		assertTrue(result.contains("southeast"), "should mention direction: " + result);
	}

	@Test
	void hostileWithDirection() {
		List<SceneDescriptionBuilder.EntityInfo> entities = List.of(
				new SceneDescriptionBuilder.EntityInfo("minecraft:zombie", null, -5, 0, 3)
		);
		String result = SceneDescriptionBuilder.buildEntities(entities);

		assertTrue(result.contains("zombie"), "should mention zombie: " + result);
		assertTrue(result.contains("southwest"), "should mention direction: " + result);
	}

	@Test
	void passiveMobsGroupedWhenMultiple() {
		List<SceneDescriptionBuilder.EntityInfo> entities = List.of(
				new SceneDescriptionBuilder.EntityInfo("minecraft:sheep", null, 5, 0, 3),
				new SceneDescriptionBuilder.EntityInfo("minecraft:sheep", null, 6, 0, 4),
				new SceneDescriptionBuilder.EntityInfo("minecraft:sheep", null, 7, 0, 2)
		);
		String result = SceneDescriptionBuilder.buildEntities(entities);

		assertTrue(result.contains("A few sheep"), "should group sheep: " + result);
		// Should be a single line, not 3
		long lineCount = result.chars().filter(c -> c == '\n').count();
		assertEquals(1, lineCount, "should be grouped into 1 line: " + result);
	}

	@Test
	void singlePassiveMobNotGrouped() {
		List<SceneDescriptionBuilder.EntityInfo> entities = List.of(
				new SceneDescriptionBuilder.EntityInfo("minecraft:sheep", null, 4, 0, -3)
		);
		String result = SceneDescriptionBuilder.buildEntities(entities);

		assertTrue(result.contains("A sheep"), "should use singular: " + result);
		assertFalse(result.contains("few"), "should not say 'few': " + result);
	}

	@Test
	void emptyWhenNoEntities() {
		assertEquals("", SceneDescriptionBuilder.buildEntities(List.of()));
		assertEquals("", SceneDescriptionBuilder.buildEntities(null));
	}

	@Test
	void mixedEntityTypes() {
		List<SceneDescriptionBuilder.EntityInfo> entities = List.of(
				new SceneDescriptionBuilder.EntityInfo("minecraft:villager", "farmer", 3, 0, -5),
				new SceneDescriptionBuilder.EntityInfo("minecraft:zombie", null, -10, 0, 0),
				new SceneDescriptionBuilder.EntityInfo("minecraft:cow", null, 8, 0, 4),
				new SceneDescriptionBuilder.EntityInfo("minecraft:cow", null, 9, 0, 3),
				new SceneDescriptionBuilder.EntityInfo("minecraft:cow", null, 7, 0, 5)
		);
		String result = SceneDescriptionBuilder.buildEntities(entities);

		assertTrue(result.contains("farmer villager"), "should list farmer: " + result);
		assertTrue(result.contains("zombie"), "should list zombie: " + result);
		assertTrue(result.contains("A few cow"), "should group cows: " + result);
	}

	// === prettyBlockName() test ===

	@Test
	void prettyBlockNameStripsPrefix() {
		assertEquals("deepslate diamond ore", SceneDescriptionBuilder.prettyBlockName("minecraft:deepslate_diamond_ore"));
		assertEquals("dirt", SceneDescriptionBuilder.prettyBlockName("minecraft:dirt"));
		assertEquals("unknown", SceneDescriptionBuilder.prettyBlockName(null));
	}
}
