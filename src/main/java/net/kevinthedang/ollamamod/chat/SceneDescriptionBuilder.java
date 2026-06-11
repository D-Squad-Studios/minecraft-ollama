package net.kevinthedang.ollamamod.chat;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Composes natural-language scene descriptions from raw block/entity scan data.
 * Takes plain Java types only — no Minecraft imports — so it's fully unit-testable.
 */
class SceneDescriptionBuilder {

	// A block found during scanning with its relative position to the anchor
	record BlockInfo(String blockId, int count, int dx, int dy, int dz) {}

	// An entity found nearby with its relative position
	record EntityInfo(String type, String profession, int dx, int dy, int dz) {}

	private static final int MAX_NOTABLE_ENTRIES = 6;

	// Blocks considered generic ground/filler — omit from notable descriptions
	private static final Set<String> GROUND_BLOCKS = Set.of(
			"minecraft:dirt", "minecraft:grass_block", "minecraft:coarse_dirt",
			"minecraft:podzol", "minecraft:mycelium", "minecraft:rooted_dirt",
			"minecraft:mud", "minecraft:sand", "minecraft:red_sand",
			"minecraft:gravel", "minecraft:clay", "minecraft:stone",
			"minecraft:cobblestone", "minecraft:deepslate",
			"minecraft:cobbled_deepslate", "minecraft:netherrack",
			"minecraft:end_stone", "minecraft:soul_sand", "minecraft:soul_soil",
			"minecraft:bedrock", "minecraft:snow_block"
	);

	// Blocks that are "filler" underground — skip when count is very high
	private static final Set<String> UNDERGROUND_FILLER = Set.of(
			"minecraft:bedrock", "minecraft:stone", "minecraft:deepslate",
			"minecraft:cobbled_deepslate", "minecraft:tuff", "minecraft:granite",
			"minecraft:diorite", "minecraft:andesite"
	);

	// Hostile mob types
	private static final Set<String> HOSTILE_TYPES = Set.of(
			"minecraft:zombie", "minecraft:skeleton", "minecraft:creeper",
			"minecraft:spider", "minecraft:enderman", "minecraft:witch",
			"minecraft:phantom", "minecraft:drowned", "minecraft:husk",
			"minecraft:stray", "minecraft:blaze", "minecraft:ghast",
			"minecraft:wither_skeleton", "minecraft:piglin_brute",
			"minecraft:vindicator", "minecraft:evoker", "minecraft:ravager",
			"minecraft:pillager", "minecraft:guardian", "minecraft:elder_guardian",
			"minecraft:warden", "minecraft:breeze"
	);

	// Passive mobs that should be grouped when multiple
	private static final Set<String> PASSIVE_TYPES = Set.of(
			"minecraft:cow", "minecraft:sheep", "minecraft:pig",
			"minecraft:chicken", "minecraft:horse", "minecraft:donkey",
			"minecraft:rabbit", "minecraft:cat", "minecraft:wolf",
			"minecraft:fox", "minecraft:parrot", "minecraft:bee",
			"minecraft:goat", "minecraft:frog", "minecraft:turtle",
			"minecraft:squid", "minecraft:glow_squid", "minecraft:dolphin",
			"minecraft:bat", "minecraft:cod", "minecraft:salmon",
			"minecraft:tropical_fish", "minecraft:pufferfish",
			"minecraft:armadillo", "minecraft:camel", "minecraft:sniffer"
	);

	/**
	 * Produces a natural direction phrase from dx/dy/dz offsets.
	 * Examples: "right next to you", "about 4 blocks to the north",
	 * "about 10 blocks to the southeast and below", "just below your feet"
	 */
	static String naturalDirection(int dx, int dy, int dz) {
		int dist = (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));

		if (dist <= 1) return "right next to you";

		// Check if mostly vertical
		int horizDist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
		int absDy = Math.abs(dy);
		if (absDy > 2 && horizDist <= 2) {
			return dy > 0 ? "just above you" : "just below your feet";
		}

		// Horizontal direction (Minecraft: -Z = North, +Z = South, +X = East, -X = West)
		String ns = "";
		String ew = "";
		if (dz < -2) ns = "north";
		else if (dz > 2) ns = "south";
		if (dx > 2) ew = "east";
		else if (dx < -2) ew = "west";

		String horiz;
		if (!ns.isEmpty() && !ew.isEmpty()) {
			horiz = ns + ew;
		} else if (!ns.isEmpty()) {
			horiz = ns;
		} else if (!ew.isEmpty()) {
			horiz = ew;
		} else {
			horiz = "nearby";
		}

		// Vertical suffix
		String vert = "";
		if (dy > 2) vert = " and above";
		else if (dy < -2) vert = " and below";

		return "about " + dist + " blocks to the " + horiz + vert;
	}

	/**
	 * Builds a 1-2 sentence description of immediate surroundings.
	 *
	 * @param setting "outdoors", "indoors", or "underground in darkness"
	 * @param immediateBlocks tier-1 block scan results
	 */
	static String buildSurroundings(String setting, List<BlockInfo> immediateBlocks) {
		if (immediateBlocks == null || immediateBlocks.isEmpty()) {
			return "You are " + setting + ".";
		}

		// Categorize blocks
		List<String> groundNames = new ArrayList<>();
		List<String> treeTypes = new ArrayList<>();
		List<String> functionalNames = new ArrayList<>();

		for (BlockInfo b : immediateBlocks) {
			String id = b.blockId();
			String pretty = prettyBlockName(id);

			if (GROUND_BLOCKS.contains(id)) {
				if (!groundNames.contains(pretty) && groundNames.size() < 2) {
					groundNames.add(pretty);
				}
			} else if (id.contains("_log")) {
				// Infer tree type from log
				String wood = pretty.replace(" log", "");
				if (!treeTypes.contains(wood)) {
					treeTypes.add(wood);
				}
			} else if (id.contains("_leaves") || id.contains("_sapling")) {
				// Skip — implied by trees
			} else if (isFunctionalBlock(id)) {
				functionalNames.add(pretty);
			}
		}

		StringBuilder sb = new StringBuilder();
		sb.append("You are ").append(setting);

		// Ground description
		if (!groundNames.isEmpty()) {
			sb.append(" on ").append(joinNatural(groundNames));
		}

		// Trees
		if (!treeTypes.isEmpty()) {
			sb.append(", with ").append(joinNatural(treeTypes)).append(" trees nearby");
		}
		sb.append(".");

		// Functional blocks
		if (!functionalNames.isEmpty()) {
			sb.append(" ");
			if (functionalNames.size() == 1) {
				sb.append("A ").append(functionalNames.get(0)).append(" is within arm's reach.");
			} else {
				sb.append(capitalizeFirst(joinNatural(functionalNames))).append(" are within arm's reach.");
			}
		}

		return sb.toString();
	}

	/**
	 * Builds a bullet-point list of notable/rare blocks with natural directions.
	 * Filters out underground filler and sorts by distance.
	 */
	static String buildNotableBlocks(List<BlockInfo> notableBlocks) {
		if (notableBlocks == null || notableBlocks.isEmpty()) return "";

		// Filter out underground filler with high counts
		List<BlockInfo> filtered = notableBlocks.stream()
				.filter(b -> !(UNDERGROUND_FILLER.contains(b.blockId()) && b.count() > 10))
				.sorted(Comparator.comparingDouble(b -> Math.sqrt(b.dx() * b.dx() + b.dy() * b.dy() + b.dz() * b.dz())))
				.limit(MAX_NOTABLE_ENTRIES)
				.toList();

		if (filtered.isEmpty()) return "";

		StringBuilder sb = new StringBuilder();
		for (BlockInfo b : filtered) {
			sb.append("\n- ");
			String pretty = prettyBlockName(b.blockId());
			sb.append(capitalizeFirst(pretty));
			sb.append(" ").append(naturalDirection(b.dx(), b.dy(), b.dz()));
		}
		return sb.toString();
	}

	/**
	 * Builds a bullet-point list of nearby entities with natural directions.
	 * Groups passive mobs when there are multiple of the same type.
	 */
	static String buildEntities(List<EntityInfo> entities) {
		if (entities == null || entities.isEmpty()) return "";

		StringBuilder sb = new StringBuilder();

		// Separate by type
		List<EntityInfo> villagers = new ArrayList<>();
		List<EntityInfo> hostiles = new ArrayList<>();
		Map<String, List<EntityInfo>> passiveGroups = new LinkedHashMap<>();
		List<EntityInfo> other = new ArrayList<>();

		for (EntityInfo e : entities) {
			if ("minecraft:villager".equals(e.type())) {
				villagers.add(e);
			} else if (HOSTILE_TYPES.contains(e.type())) {
				hostiles.add(e);
			} else if (PASSIVE_TYPES.contains(e.type())) {
				passiveGroups.computeIfAbsent(e.type(), k -> new ArrayList<>()).add(e);
			} else {
				other.add(e);
			}
		}

		// Villagers — individual with profession
		for (EntityInfo v : villagers) {
			String prof = (v.profession() != null && !v.profession().isBlank())
					? v.profession() + " villager"
					: "villager";
			sb.append("\n- A ").append(prof).append(" ")
					.append(naturalDirection(v.dx(), v.dy(), v.dz()));
		}

		// Hostiles — individual
		for (EntityInfo h : hostiles) {
			sb.append("\n- A ").append(prettyEntityName(h.type())).append(" ")
					.append(naturalDirection(h.dx(), h.dy(), h.dz()));
		}

		// Passive — group if > 2, individual otherwise
		for (var entry : passiveGroups.entrySet()) {
			List<EntityInfo> group = entry.getValue();
			String name = prettyEntityName(entry.getKey());
			if (group.size() > 2) {
				// Use average position for grouped direction
				int avgDx = (int) group.stream().mapToInt(EntityInfo::dx).average().orElse(0);
				int avgDy = (int) group.stream().mapToInt(EntityInfo::dy).average().orElse(0);
				int avgDz = (int) group.stream().mapToInt(EntityInfo::dz).average().orElse(0);
				sb.append("\n- A few ").append(name).append(" ")
						.append(naturalDirection(avgDx, avgDy, avgDz));
			} else {
				for (EntityInfo p : group) {
					sb.append("\n- A ").append(name).append(" ")
							.append(naturalDirection(p.dx(), p.dy(), p.dz()));
				}
			}
		}

		return sb.toString();
	}

	// Checks if a block is functional (workstation, container, etc.) vs ore/hazard
	private static boolean isFunctionalBlock(String blockId) {
		return blockId.contains("crafting_table") || blockId.contains("furnace")
				|| blockId.contains("smoker") || blockId.contains("anvil")
				|| blockId.contains("enchanting_table") || blockId.contains("brewing_stand")
				|| blockId.contains("chest") || blockId.contains("barrel")
				|| blockId.contains("beacon") || blockId.contains("lectern")
				|| blockId.contains("composter") || blockId.contains("loom")
				|| blockId.contains("stonecutter") || blockId.contains("grindstone")
				|| blockId.contains("cartography_table") || blockId.contains("fletching_table")
				|| blockId.contains("smithing_table") || blockId.contains("campfire")
				|| blockId.contains("bed");
	}

	// Converts "minecraft:deepslate_diamond_ore" to "deepslate diamond ore"
	static String prettyBlockName(String fullId) {
		if (fullId == null) return "unknown";
		String name = fullId.startsWith("minecraft:") ? fullId.substring("minecraft:".length()) : fullId;
		return name.replace('_', ' ');
	}

	// Converts "minecraft:zombie" to "zombie"
	private static String prettyEntityName(String fullId) {
		if (fullId == null) return "unknown";
		String name = fullId.startsWith("minecraft:") ? fullId.substring("minecraft:".length()) : fullId;
		return name.replace('_', ' ');
	}

	// Joins a list naturally: ["a", "b", "c"] → "a, b, and c"
	private static String joinNatural(List<String> items) {
		if (items.isEmpty()) return "";
		if (items.size() == 1) return items.get(0);
		if (items.size() == 2) return items.get(0) + " and " + items.get(1);
		return String.join(", ", items.subList(0, items.size() - 1))
				+ ", and " + items.get(items.size() - 1);
	}

	private static String capitalizeFirst(String s) {
		if (s == null || s.isEmpty()) return s;
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
