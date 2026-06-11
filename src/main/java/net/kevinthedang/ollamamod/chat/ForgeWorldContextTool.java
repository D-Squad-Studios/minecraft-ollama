package net.kevinthedang.ollamamod.chat;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;


public class ForgeWorldContextTool implements WorldContextTool {
	private static final int ENTITY_RADIUS = 24;
	private static final int MAX_FACTS = 15;

	// Tiered block scanning radii
	private static final int TIER1_RADIUS = 2;      // 5x5x5 = 125 blocks — report ALL non-air
	private static final int TIER2_RADIUS = 16;     // 33x33x33 = ~36K blocks — notable only
	private static final int TIER3_RADIUS = 48;     // 97x97x33(Y-clamped) = ~300K blocks — rare only
	private static final int TIER3_Y_BAND = 16;     // Y range clamp for Tier 3

	// Tier 2: ores, hazards, and functional blocks (proxy for player-placed)
	private static final Set<String> TIER2_NOTABLE = Set.of(
			// Ores
			"minecraft:coal_ore", "minecraft:deepslate_coal_ore",
			"minecraft:iron_ore", "minecraft:deepslate_iron_ore",
			"minecraft:copper_ore", "minecraft:deepslate_copper_ore",
			"minecraft:gold_ore", "minecraft:deepslate_gold_ore",
			"minecraft:redstone_ore", "minecraft:deepslate_redstone_ore",
			"minecraft:lapis_ore", "minecraft:deepslate_lapis_ore",
			"minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
			"minecraft:emerald_ore", "minecraft:deepslate_emerald_ore",
			"minecraft:nether_gold_ore", "minecraft:nether_quartz_ore",
			"minecraft:ancient_debris",
			// Hazards
			"minecraft:lava", "minecraft:water",
			"minecraft:magma_block", "minecraft:fire", "minecraft:soul_fire",
			// Functional blocks
			"minecraft:crafting_table", "minecraft:furnace", "minecraft:blast_furnace",
			"minecraft:smoker", "minecraft:anvil", "minecraft:chipped_anvil",
			"minecraft:damaged_anvil", "minecraft:enchanting_table",
			"minecraft:brewing_stand", "minecraft:chest", "minecraft:trapped_chest",
			"minecraft:barrel", "minecraft:beacon",
			"minecraft:torch", "minecraft:wall_torch", "minecraft:soul_torch",
			"minecraft:lantern", "minecraft:soul_lantern",
			"minecraft:campfire", "minecraft:soul_campfire",
			"minecraft:spawner",
			// Valuable solid blocks
			"minecraft:diamond_block", "minecraft:emerald_block",
			"minecraft:gold_block", "minecraft:iron_block",
			"minecraft:netherite_block"
	);

	// Tier 3: rare/valuable blocks only
	private static final Set<String> TIER3_RARE = Set.of(
			"minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
			"minecraft:emerald_ore", "minecraft:deepslate_emerald_ore",
			"minecraft:ancient_debris",
			"minecraft:spawner",
			"minecraft:beacon",
			"minecraft:end_portal_frame", "minecraft:end_portal",
			"minecraft:nether_portal",
			"minecraft:budding_amethyst",
			"minecraft:crying_obsidian",
			"minecraft:gilded_blackstone",
			"minecraft:reinforced_deepslate",
			"minecraft:diamond_block", "minecraft:emerald_block",
			"minecraft:netherite_block"
	);

	@Override
	public WorldFactBundle collect(VillagerBrain.Context ctx, List<ChatMessage> history, String playerMessage) {
		List<WorldFact> facts = new ArrayList<>();
		long nowTtl = 5_000;
		long slowTtl = 30_000;

		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null || mc.level == null) {
			// Guarantee >=3
			facts.add(fact("Player coordinates: Unknown (no client world)", "forge.coords", 0.2, nowTtl));
			facts.add(fact("Dimension: Unknown (no client world)", "forge.dimension", 0.2, nowTtl));
			facts.add(fact("Time: Unknown (no client world)", "forge.time", 0.2, nowTtl));
			return new WorldFactBundle(trim(facts));
		}

		Level level = mc.level;
		var player = mc.player;

		// Position facts — lead with human-readable location, keep raw coords available
		BlockPos p = player.blockPosition();
		String biome = prettyBiomeName(tryGetBiomeName(level, p));
		String dimension = prettyDimension(level.dimension());

		facts.add(fact("Player is in a " + biome + " area in the " + dimension,
				"forge.location", 1.0, slowTtl));

		facts.add(fact("Player exact coordinates: x=" + p.getX() + ", y=" + p.getY() + ", z=" + p.getZ(),
				"forge.coords", 0.6, nowTtl));

		facts.add(fact(prettyTime(level),
				"forge.time", 1.0, nowTtl));

		facts.add(fact(prettyWeather(level),
				"forge.weather", 0.95, nowTtl));

		// Prefer the villager (persona anchor). If not present, fall back to player.
		Villager anchorVillager = findNearestVillager(level, player.blockPosition(), 12);
		BlockPos anchorPos = (anchorVillager != null) ? anchorVillager.blockPosition() : player.blockPosition();

		// Tiered block scanning — always runs, no keyword gate
		BlockScanResult tier1 = scanTier1(level, anchorPos);
		BlockScanResult tier2 = scanTier2(level, anchorPos);
		BlockScanResult tier3 = scanTier3(level, anchorPos);

		// Indoor/outdoor detection
		String setting = detectSetting(level, anchorPos);

		// Convert tier-1 blocks to BlockInfo records for SceneDescriptionBuilder
		List<SceneDescriptionBuilder.BlockInfo> immediateBlocks = toBlockInfoList(tier1, anchorPos);

		// Surroundings fact
		facts.add(fact(SceneDescriptionBuilder.buildSurroundings(setting, immediateBlocks),
				"forge.scene.surroundings", 0.95, 3_000));

		// Notable/rare blocks fact
		List<SceneDescriptionBuilder.BlockInfo> notableBlocks = new ArrayList<>();
		notableBlocks.addAll(toBlockInfoList(tier2, anchorPos));
		notableBlocks.addAll(toBlockInfoList(tier3, anchorPos));
		String notableDesc = SceneDescriptionBuilder.buildNotableBlocks(notableBlocks);
		if (!notableDesc.isEmpty()) {
			facts.add(fact("Nearby blocks of interest:" + notableDesc,
					"forge.scene.notable", 0.9, 5_000));
		}

		// Always collect lightweight entity info for scene description
		List<SceneDescriptionBuilder.EntityInfo> entityInfos = collectEntityInfos(level, anchorPos, ENTITY_RADIUS);
		String entityDesc = SceneDescriptionBuilder.buildEntities(entityInfos);
		if (!entityDesc.isEmpty()) {
			facts.add(fact("People and creatures nearby:" + entityDesc,
					"forge.scene.entities", 0.9, 3_000));
		}

		if (anchorVillager != null) {
			facts.add(fact("Anchor villager position: x=" + anchorPos.getX() + ", y=" + anchorPos.getY() + ", z=" + anchorPos.getZ(),
					"forge.villager.anchor", 0.95, nowTtl));
		}

		// Agentic router — structures still keyword-gated
		String msg = playerMessage == null ? "" : playerMessage.toLowerCase(Locale.ROOT);

		// Detailed mob breakdown still keyword-gated
		if (wantsMobs(msg)) {
			addCapped(facts, queryNearbyMobs(level, player, ENTITY_RADIUS));
		}

		if (wantsStructures(msg)) {
			addCapped(facts, queryNearbyStructuresBestEffort(mc, level, p));
		}


		// Enforce cap + ensure >=3
		if (facts.size() < 3) {
			facts.add(fact("World context: (insufficient facts; tool fallback)", "forge.fallback", 0.2, nowTtl));
		}

		return new WorldFactBundle(trim(facts));
	}

	// Raw scan result: block counts + nearest position per block type
	private record BlockScanResult(Map<String, Integer> counts, Map<String, BlockPos> nearest) {}

	// Tier 1: ALL non-air blocks within a small cube.
	private static BlockScanResult scanTier1(Level level, BlockPos anchor) {
		Map<String, Integer> counts = new HashMap<>();
		Map<String, BlockPos> nearest = new HashMap<>();
		BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

		int minX = anchor.getX() - TIER1_RADIUS;
		int maxX = anchor.getX() + TIER1_RADIUS;
		int minY = Math.max(level.getMinY(), anchor.getY() - TIER1_RADIUS);
		int maxY = Math.min(level.getMaxY() - 1, anchor.getY() + TIER1_RADIUS);
		int minZ = anchor.getZ() - TIER1_RADIUS;
		int maxZ = anchor.getZ() + TIER1_RADIUS;

		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					mpos.set(x, y, z);
					BlockState st = level.getBlockState(mpos);
					if (st.isAir()) continue;
					String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
					counts.merge(id, 1, Integer::sum);
					BlockPos prev = nearest.get(id);
					if (prev == null || mpos.distSqr(anchor) < prev.distSqr(anchor)) {
						nearest.put(id, new BlockPos(mpos));
					}
				}
			}
		}

		return new BlockScanResult(counts, nearest);
	}

	// Tier 2: Notable blocks (ores, hazards, functional) within a medium radius.
	private static BlockScanResult scanTier2(Level level, BlockPos anchor) {
		Map<String, Integer> counts = new HashMap<>();
		Map<String, BlockPos> nearest = new HashMap<>();
		Set<Long> loadedChunks = new HashSet<>();
		BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

		int minX = anchor.getX() - TIER2_RADIUS;
		int maxX = anchor.getX() + TIER2_RADIUS;
		int minY = Math.max(level.getMinY(), anchor.getY() - TIER2_RADIUS);
		int maxY = Math.min(level.getMaxY() - 1, anchor.getY() + TIER2_RADIUS);
		int minZ = anchor.getZ() - TIER2_RADIUS;
		int maxZ = anchor.getZ() + TIER2_RADIUS;

		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				if (!isChunkLoaded(level, x, z, loadedChunks)) continue;
				for (int y = minY; y <= maxY; y++) {
					mpos.set(x, y, z);
					BlockState st = level.getBlockState(mpos);
					if (st.isAir()) continue;
					String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
					if (isTier2Notable(id)) {
						counts.merge(id, 1, Integer::sum);
						BlockPos prev = nearest.get(id);
						if (prev == null || mpos.distSqr(anchor) < prev.distSqr(anchor)) {
							nearest.put(id, new BlockPos(mpos));
						}
					}
				}
			}
		}

		return new BlockScanResult(counts, nearest);
	}

	// Tier 3: Rare/valuable blocks within a large radius, Y-band clamped.
	private static BlockScanResult scanTier3(Level level, BlockPos anchor) {
		Map<String, Integer> counts = new HashMap<>();
		Map<String, BlockPos> nearest = new HashMap<>();
		Set<Long> loadedChunks = new HashSet<>();
		BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

		int minX = anchor.getX() - TIER3_RADIUS;
		int maxX = anchor.getX() + TIER3_RADIUS;
		int minY = Math.max(level.getMinY(), anchor.getY() - TIER3_Y_BAND);
		int maxY = Math.min(level.getMaxY() - 1, anchor.getY() + TIER3_Y_BAND);
		int minZ = anchor.getZ() - TIER3_RADIUS;
		int maxZ = anchor.getZ() + TIER3_RADIUS;

		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				if (!isChunkLoaded(level, x, z, loadedChunks)) continue;
				for (int y = minY; y <= maxY; y++) {
					mpos.set(x, y, z);
					BlockState st = level.getBlockState(mpos);
					if (st.isAir()) continue;
					String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
					if (TIER3_RARE.contains(id)) {
						counts.merge(id, 1, Integer::sum);
						BlockPos prev = nearest.get(id);
						if (prev == null || mpos.distSqr(anchor) < prev.distSqr(anchor)) {
							nearest.put(id, new BlockPos(mpos));
						}
					}
				}
			}
		}

		return new BlockScanResult(counts, nearest);
	}

	// Detects whether the anchor position is outdoors, indoors, or underground.
	private static String detectSetting(Level level, BlockPos anchor) {
		try {
			boolean sky = level.canSeeSky(anchor.above());
			if (sky) return "outdoors";
			int lightLevel = level.getMaxLocalRawBrightness(anchor);
			if (lightLevel < 4) return "underground in darkness";
			return "indoors";
		} catch (Throwable t) {
			return "outdoors";
		}
	}

	// Converts a BlockScanResult into a list of BlockInfo records for SceneDescriptionBuilder.
	private static List<SceneDescriptionBuilder.BlockInfo> toBlockInfoList(BlockScanResult scan, BlockPos anchor) {
		List<SceneDescriptionBuilder.BlockInfo> out = new ArrayList<>();
		for (var entry : scan.counts().entrySet()) {
			String blockId = entry.getKey();
			int count = entry.getValue();
			BlockPos nearestPos = scan.nearest().get(blockId);
			int dx = 0, dy = 0, dz = 0;
			if (nearestPos != null) {
				dx = nearestPos.getX() - anchor.getX();
				dy = nearestPos.getY() - anchor.getY();
				dz = nearestPos.getZ() - anchor.getZ();
			}
			out.add(new SceneDescriptionBuilder.BlockInfo(blockId, count, dx, dy, dz));
		}
		return out;
	}

	// Collects lightweight entity info for the scene description (always runs).
	private static List<SceneDescriptionBuilder.EntityInfo> collectEntityInfos(Level level, BlockPos anchor, int radius) {
		List<SceneDescriptionBuilder.EntityInfo> out = new ArrayList<>();
		try {
			AABB box = new AABB(anchor).inflate(radius);
			List<Entity> entities = level.getEntities(null, box);
			for (Entity e : entities) {
				if (e == null) continue;
				String typeId = safeId(e);
				String profession = null;
				if (e instanceof Villager v) {
					profession = villagerProfessionId(v);
				}
				int dx = e.blockPosition().getX() - anchor.getX();
				int dy = e.blockPosition().getY() - anchor.getY();
				int dz = e.blockPosition().getZ() - anchor.getZ();
				out.add(new SceneDescriptionBuilder.EntityInfo(typeId, profession, dx, dy, dz));
			}
		} catch (Throwable t) {
			// Graceful fallback
		}
		return out;
	}

	// Checks whether the chunk at the given x/z world coordinates is loaded, using a cache.
	private static boolean isChunkLoaded(Level level, int x, int z, Set<Long> cache) {
		long key = ChunkPos.asLong(x >> 4, z >> 4);
		if (cache.contains(key)) return true;
		if (level.hasChunkAt(new BlockPos(x, 0, z))) {
			cache.add(key);
			return true;
		}
		return false;
	}

	// Returns true if the block ID is notable for Tier 2 scanning.
	private static boolean isTier2Notable(String blockId) {
		if (TIER2_NOTABLE.contains(blockId)) return true;
		// Variant checks for color variants (16 colors each)
		return blockId.contains("bed") || blockId.contains("shulker_box");
	}

	// Computes a human-readable cardinal direction + distance from anchor to target.
	// Returns e.g. "~8 SE, below" or "~3 N" or "~1 nearby, above".
	private static String cardinalDirection(BlockPos anchor, BlockPos target) {
		int dx = target.getX() - anchor.getX();
		int dz = target.getZ() - anchor.getZ();
		int dy = target.getY() - anchor.getY();

		// Horizontal direction (Minecraft: -Z = North, +Z = South, +X = East, -X = West)
		String ns = "";
		String ew = "";
		if (dz < -2) ns = "N";
		else if (dz > 2) ns = "S";
		if (dx > 2) ew = "E";
		else if (dx < -2) ew = "W";

		String horiz = ns + ew;
		if (horiz.isEmpty()) horiz = "nearby";

		// Vertical
		String vert = "";
		if (dy > 2) vert = "above";
		else if (dy < -2) vert = "below";

		int dist = (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));

		StringBuilder sb = new StringBuilder();
		sb.append("~").append(dist).append(" ").append(horiz);
		if (!vert.isEmpty()) sb.append(", ").append(vert);
		return sb.toString();
	}

	// Formats top N block counts with direction info for blocks that have nearest positions tracked.
	private static String topNWithDirection(Map<String, Integer> counts,
											Map<String, BlockPos> nearest,
											BlockPos anchor, int n) {
		if (counts == null || counts.isEmpty()) return "(none)";
		return counts.entrySet().stream()
				.sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
				.limit(n)
				.map(e -> {
					String base = prettyBlockName(e.getKey()) + "=" + e.getValue();
					BlockPos np = nearest.get(e.getKey());
					if (np != null) {
						base += " (nearest: " + cardinalDirection(anchor, np) + ")";
					}
					return base;
				})
				.collect(Collectors.joining(", "));
	}

	// Formats top N block counts with human-friendly names for readability.
	private static String topNShort(Map<String, Integer> counts, int n) {
		if (counts == null || counts.isEmpty()) return "(none)";
		return counts.entrySet().stream()
				.sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
				.limit(n)
				.map(e -> prettyBlockName(e.getKey()) + "=" + e.getValue())
				.collect(Collectors.joining(", "));
	}

	// Converts a block ID like "minecraft:deepslate_diamond_ore" to "deepslate diamond ore".
	private static String prettyBlockName(String fullId) {
		if (fullId == null) return "unknown";
		String name = fullId.startsWith("minecraft:") ? fullId.substring("minecraft:".length()) : fullId;
		return name.replace('_', ' ');
	}

	// Query functions
	private static List<WorldFact> queryNearbyMobs(Level level, Entity player, int radius) {
		List<WorldFact> out = new ArrayList<>();
		BlockPos playerPos = player.blockPosition();
		AABB box = player.getBoundingBox().inflate(radius);
		List<Entity> entities = level.getEntities(player, box);

		int total = entities.size();
		int mobs = 0, hostiles = 0, villagers = 0;

		Map<String, Integer> mobTop = new HashMap<>();
		List<String> villagerDetails = new ArrayList<>();

		for (Entity e : entities) {
			if (e instanceof Mob) mobs++;
			if (e instanceof Monster) hostiles++;

			String typeId = safeId(e);
			mobTop.merge(typeId, 1, Integer::sum);

			if (e instanceof Villager v) {
				villagers++;
				String prof = villagerProfessionId(v);
				String vtype = villagerTypeId(v);
				String dir = cardinalDirection(playerPos, v.blockPosition());
				villagerDetails.add(prof + " (" + dir + ")");

				System.out.println("[ForgeWorldContextTool] villager="
					+ v.getUUID()
					+ " prof=" + prof
					+ " type=" + vtype
					+ " data=" + v.getVillagerData());
			}
		}

		out.add(fact("Nearby entities within " + radius + " blocks: total=" + total + ", mobs=" + mobs + ", hostiles=" + hostiles + ", villagers=" + villagers,
				"forge.entities.scan", 0.95, 3_000));

		out.add(fact("Top nearby entity types: " + topN(mobTop, 5),
				"forge.entities.types", 0.85, 3_000));

		if (!villagerDetails.isEmpty()) {
			out.add(fact("Nearby villagers: " + String.join(", ", villagerDetails),
					"forge.villager.details", 0.9, 10_000));
		}

		return out;
	}

	private static String villagerProfessionId(Villager v) {
		try {
			var holder = v.getVillagerData().profession(); // Holder<VillagerProfession>
			var profession = holder.value();
			var key = BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession);
			return key == null ? "unknown" : key.getPath(); // e.g. "farmer"
		} catch (Throwable t) {
			return "unknown";
		}
}

	private static String villagerTypeId(Villager v) {
		try {
			var holder = v.getVillagerData().type(); // Holder<VillagerType>
			var type = holder.value();
			var key = BuiltInRegistries.VILLAGER_TYPE.getKey(type);
			return key == null ? "unknown" : key.getPath(); // e.g. "plains"
		} catch (Throwable t) {
			return "unknown";
		}
	}

	private static List<WorldFact> queryNearbyStructuresBestEffort(Minecraft mc, Level clientLevel, BlockPos origin) {
		List<WorldFact> out = new ArrayList<>();

		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null) {
			out.add(fact("Nearest structures: unavailable (client has no integrated server; multiplayer limitation)",
					"forge.structures.unavailable", 0.4, 10_000));
			return out;
		}

		ServerLevel serverLevel = server.getLevel(clientLevel.dimension());
		if (serverLevel == null) serverLevel = server.overworld();

		List<String> tagFieldNames = List.of("VILLAGE", "MINESHAFT", "STRONGHOLD");
		List<String> found = new ArrayList<>();

		for (String tagField : tagFieldNames) {
			String res = tryFindNearestStructureByTag(serverLevel, origin, tagField, 128);
			if (res != null) found.add(res);
		}

		if (found.isEmpty()) {
			out.add(fact("Nearest structures: (no result / API mismatch)", "forge.structures.none", 0.5, 20_000));
		} else {
			out.add(fact("Nearest structures (approx): " + String.join(" | ", found),
					"forge.structures.nearest", 0.85, 20_000));
		}

		return out;
	}

	// Utlities functions
	// Converts Minecraft ticks to a human-readable time of day.
	// Minecraft day starts at tick 0 = 6:00 AM. Each tick = 3.6 real seconds.
	private static String prettyTime(Level level) {
		long dayTime = level.getDayTime();
		long tod = dayTime % 24000L;

		// Convert ticks to 24h clock: tick 0 = 6:00, tick 6000 = 12:00, tick 18000 = 0:00
		int totalMinutes = (int) ((tod * 60) / 1000);  // ticks to in-game minutes
		int hour24 = (6 + totalMinutes / 60) % 24;
		int minute = totalMinutes % 60;

		// 12-hour format
		String ampm = hour24 >= 12 ? "PM" : "AM";
		int hour12 = hour24 % 12;
		if (hour12 == 0) hour12 = 12;
		String clock = String.format(Locale.ROOT, "%d:%02d %s", hour12, minute, ampm);

		// Time-of-day description
		String period;
		if (tod < 1000) period = "Early morning";
		else if (tod < 6000) period = "Morning";
		else if (tod < 6500) period = "Midday";
		else if (tod < 11500) period = "Afternoon";
		else if (tod < 13000) period = "Evening";
		else if (tod < 14000) period = "Dusk";
		else if (tod < 22000) period = "Night";
		else period = "Dawn";

		return "Time of day: " + period + ", around " + clock;
	}

	private static String prettyWeather(Level level) {
		boolean rain = level.isRaining();
		boolean thunder = level.isThundering();
		float rainLevel = level.getRainLevel(1.0F);

		if (thunder) {
			return "Weather: A thunderstorm is raging with lightning";
		} else if (rain) {
			if (rainLevel > 0.7f) return "Weather: Heavy rain";
			else if (rainLevel > 0.3f) return "Weather: Steady rain";
			else return "Weather: Light rain";
		} else {
			return "Weather: Clear skies";
		}
	}

	private static String tryGetBiomeName(Level level, BlockPos pos) {
		try {
			var holder = level.getBiome(pos);
			var keyOpt = holder.unwrapKey();
			if (keyOpt.isPresent()) return keyOpt.get().location().toString();
			return "Unknown (no biome key)";
		} catch (Throwable t) {
			return "Unknown (biome API mismatch)";
		}
	}

	private static String prettyDimension(ResourceKey<Level> key) {
		if (key == null) return "Unknown";
		ResourceLocation loc = key.location();
		if (loc == null) return "Unknown";
		return switch (loc.toString()) {
			case "minecraft:overworld" -> "Overworld";
			case "minecraft:the_nether" -> "Nether";
			case "minecraft:the_end" -> "End";
			default -> loc.getPath();
		};
	}

	// Converts a biome resource ID like "minecraft:plains" to "Plains"
	private static String prettyBiomeName(String rawBiome) {
		if (rawBiome == null || rawBiome.isBlank()) return "unknown";
		String name = rawBiome.contains(":") ? rawBiome.substring(rawBiome.indexOf(':') + 1) : rawBiome;
		return Arrays.stream(name.split("_"))
				.filter(w -> !w.isEmpty())
				.map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1))
				.collect(Collectors.joining(" "));
	}

   // Routing route
	private static boolean wantsStructures(String msg) {
		return containsAny(msg, "structure", "village", "stronghold", "mansion", "temple", "mineshaft", "fortress", "nearby structure");
	}

	private static boolean wantsMobs(String msg) {
		return containsAny(msg, "mob", "mobs", "enemy", "enemies", "hostile", "zombie", "skeleton", "creeper",
				"villager", "nearby", "anyone", "someone", "around", "people", "folk", "person", "who");
	}

	private static boolean containsAny(String haystack, String... needles) {
		if (haystack == null) return false;
		for (String n : needles) {
			if (haystack.contains(n)) return true;
		}
		return false;
	}

	// Helpers
	private static WorldFact fact(String text, String evidence, double conf, long ttlMillis) {
		return new WorldFact(text, evidence, conf, ttlMillis);
	}

	private static List<WorldFact> trim(List<WorldFact> facts) {
		if (facts.size() <= MAX_FACTS) return facts;
		return facts.subList(0, MAX_FACTS);
	}

	private static void addCapped(List<WorldFact> dest, List<WorldFact> toAdd) {
		if (toAdd == null || toAdd.isEmpty()) {
			return;
		}

		int remaining = MAX_FACTS - dest.size();
		if (remaining <= 0) {
			return;
		}
		dest.addAll(toAdd.subList(0, Math.min(toAdd.size(), remaining)));
	}

	private static String blockId(Level level, BlockPos pos) {
		try {
			BlockState st = level.getBlockState(pos);
			if (st.isAir()) return "minecraft:air";
			return BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
		} catch (Throwable t) {
			return "unknown:block";
		}
	}

	private static Villager findNearestVillager(Level level, BlockPos center, int radius) {
		try {
			AABB box = new AABB(center).inflate(radius);
			List<Villager> list = level.getEntitiesOfClass(Villager.class, box);
			if (list.isEmpty()) return null;
			Villager best = null;
			double bestDist = Double.MAX_VALUE;
			for (Villager v : list) {
				double d = v.blockPosition().distSqr(center);
				if (d < bestDist) { bestDist = d; best = v; }
			}
			return best;
		} catch (Throwable t) {
			return null;
		}
	}

	private static String safeId(Entity e) {
		try {
			ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
			return id == null ? "unknown" : id.toString();
		} catch (Throwable t) {
			return "unknown";
		}
	}

	private static String topN(Map<String, Integer> counts, int n) {
		if (counts == null || counts.isEmpty()) return "(none)";
		return counts.entrySet().stream()
				.sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
				.limit(n)
				.map(e -> e.getKey() + "=" + e.getValue())
				.collect(Collectors.joining(", "));
	}

	private static String parseVillagerDataField(String villagerDataToString, String key, String fallback) {
		if (villagerDataToString == null) return fallback;
		String lower = villagerDataToString.toLowerCase(Locale.ROOT);
		int idx = lower.indexOf(key.toLowerCase(Locale.ROOT) + "=");
		if (idx < 0) return fallback;

		int start = idx + (key.length() + 1);
		int end = lower.indexOf(",", start);
		if (end < 0) end = lower.indexOf("]", start);
		if (end < 0) end = villagerDataToString.length();

		String raw = villagerDataToString.substring(start, end).trim();
		// often minecraft:farmer
		if (raw.contains(":")) raw = raw.substring(raw.indexOf(":") + 1);
		if (raw.isEmpty()) return fallback;
		return raw;
	}

	private static String round2(float v) {
		return String.format(Locale.ROOT, "%.2f", v);
	}

	private static String tryFindNearestStructureByTag(ServerLevel level, BlockPos origin, String structureTagsField, int radius) {
		try {
			// 1. Load net.minecraft.tags.StructureTags
			Class<?> tagsCls = Class.forName("net.minecraft.tags.StructureTags");

			// 2. Get static field like StructureTags.VILLAGE
			Field f = tagsCls.getField(structureTagsField);
			Object tagKey = f.get(null);
			if (tagKey == null) return null;

			// 3. Find a method named findNearestMapStructure with suitable params
			Method target = null;
			for (Method m : level.getClass().getMethods()) {
				if (!m.getName().equals("findNearestMapStructure")) continue;
				Class<?>[] p = m.getParameterTypes();
				// expecting (TagKey, BlockPos, int, boolean) or similar
				if (p.length == 4 && p[1].getName().equals(BlockPos.class.getName()) && p[2] == int.class && p[3] == boolean.class) {
					target = m;
					break;
				}
			}
			if (target == null) return null;

			Object result = target.invoke(level, tagKey, origin, radius, false);
			if (result == null) return null;

			// result is often a Pair<BlockPos, Holder<Structure>>
			// try to extract first() / getFirst()
			BlockPos foundPos = null;
			try {
				Method first = result.getClass().getMethod("getFirst");
				Object fp = first.invoke(result);
				if (fp instanceof BlockPos bp) foundPos = bp;
			} catch (NoSuchMethodException ignored) {
				try {
					Method first = result.getClass().getMethod("first");
					Object fp = first.invoke(result);
					if (fp instanceof BlockPos bp) foundPos = bp;
				} catch (NoSuchMethodException ignored2) {
					// Some versions return just BlockPos directly
					if (result instanceof BlockPos bp) foundPos = bp;
				}
			}

			if (foundPos == null) return null;

			double dist = Math.sqrt(foundPos.distSqr(origin));
			return structureTagsField.toLowerCase(Locale.ROOT) + " @ x=" + foundPos.getX() + ",y=" + foundPos.getY() + ",z=" + foundPos.getZ() + " (≈" + (int) dist + "m)";

		} catch (Throwable t) {
			return null;
		}
	}
}
