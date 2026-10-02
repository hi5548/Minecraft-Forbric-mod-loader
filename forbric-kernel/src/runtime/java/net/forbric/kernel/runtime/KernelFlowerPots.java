/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.Map;
import java.util.function.Supplier;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.google.common.collect.Tables;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FlowerPotBlock;

/**
 * Which full pot a plant makes, for every family's way of declaring one (FlowerPotRepairInjector).
 *
 * <p>The merged {@code FlowerPotBlock.useItemOn} is MinecraftForge's body: it looks the plant up in the empty pot's
 * {@code fullPots} map, which only MinecraftForge's constructor and {@code addPlant} ever filled, and the merge kept
 * NeoForge's bodies of both. NeoForge declares a pot by its constructor (empty pot + plant) and keeps them in
 * the pot table NeoForge's registry bake callback fills — and that callback never runs on the merged base, where
 * the block registry is MinecraftForge's wrapper. So every lookup answered air.
 *
 * <p>The lookup now asks, in order: the empty pot's explicit {@code addPlant} entries (MinecraftForge's API), then
 * NeoForge's table, which {@link #rebuildTable} fills from every registered pot the way NeoForge's bake does. A
 * vanilla or Fabric pot is in that table too: NeoForge's (Block, Properties) constructor names the vanilla empty pot.
 *
 * <h2>PORT(1.21.1): the table is the kernel's own</h2>
 *
 * <p>On 26.2 NeoForge kept the table itself ({@code GameData.getFlowerPotBlockTable()}) and filled it from a
 * block-registry bake callback. NeoForge 21.1 has no such table — {@code GameData} on the staged
 * {@code neoforge-runtime.jar} (21.1.252) exposes no flower-pot accessor at all, and
 * {@code NeoForgeRegistryCallbacks$BlockCallbacks} carries only {@code onAdd}/{@code onClear}/{@code onBake} —
 * so there is nothing to fill and nothing to invalidate. The kernel owns the table instead: same three
 * operations, same key (empty pot, content), same fill and lookup rules, one less indirection.
 *
 * <p>The shape it plays against did survive: on 1.21.1 {@code FlowerPotBlock} keeps Forge's per-pot
 * {@code fullPots} map ({@code addPlant}/{@code getFullPotsView}) and NeoForge's constructors, and its
 * {@code getPotted()} reads NeoForge's {@code flowerDelegate} supplier, so {@link #rebuildTable}'s scan is the
 * same scan. Verified with javap on the merged base.
 */
public final class KernelFlowerPots {
	/**
	 * (empty pot, content) → full pot, filled by {@link #rebuildTable}. Synchronized because the fill happens in
	 * the registration window and the lookups run later, and from a client render path as well.
	 */
	private static final Table<Block, Block, Block> TABLE = Tables.synchronizedTable(HashBasedTable.create());
	private static volatile boolean warned;

	private KernelFlowerPots() {
	}

	/** The full pot {@code content} makes in {@code self}'s empty pot, or air. Never throws. */
	public static Block fullPotFor(Map<?, ?> explicit, FlowerPotBlock self, Block content) {
		try {
			if (explicit != null && !explicit.isEmpty()) {
				ResourceLocation key = BuiltInRegistries.BLOCK.getKey(content);
				// An unregistered block answers the default key (air); that is not a request for the air slot.
				boolean aliased = content != Blocks.AIR && key.equals(BuiltInRegistries.BLOCK.getDefaultKey());
				if (!aliased && explicit.get(key) instanceof Supplier<?> supplier && supplier.get() instanceof Block full) {
					return full;
				}
			}
			Block full = TABLE.get(self.getEmptyPot(), content);
			return full != null ? full : Blocks.AIR;
		} catch (Throwable failure) {
			if (!warned) {
				warned = true;
				ForbricLog.warn("[Forbric/FlowerPot] could not look up the full pot for %s (%s); the pot stays empty",
						content, Reflect.unwrap(failure));
			}
			return Blocks.AIR;
		}
	}

	/**
	 * Fills the pot table from every registered pot, as NeoForge's bake callback would: each pot that is not its
	 * own empty pot is the full pot of (its empty pot, its plant). Returns how many pots were entered, or -1 on
	 * failure.
	 */
	public static int rebuildTable() {
		// The same switches as the rest of the flower pot repair (NativeCoremodParity, FlowerPotRepairInjector).
		if ("off".equalsIgnoreCase(System.getProperty("forbric.coremodParity", "on"))
				|| "off".equalsIgnoreCase(System.getProperty("forbric.flowerPotRepair", "on"))) return -1;
		try {
			Table<Block, Block, Block> table = TABLE;
			table.clear();
			int entered = 0, failed = 0;
			for (Block block : BuiltInRegistries.BLOCK) {
				if (!(block instanceof FlowerPotBlock pot)) continue;
				try {
					FlowerPotBlock empty = pot.getEmptyPot();
					if (empty == pot) continue;
					table.put(empty, pot.getPotted(), pot);
					entered++;
				} catch (Throwable unresolved) {
					failed++;   // one mod's unbound supplier must not cost every other pot its entry
				}
			}
			ForbricLog.info("[Forbric/FlowerPot] filled the flower pot table with %d pot(s)%s — NeoForge's bake callback "
					+ "never runs on the merged block registry, and 21.1 has no table of its own to fill, so every "
					+ "plant looked up air", entered,
					failed == 0 ? "" : " (" + failed + " pot(s) could not name their plant yet)");
			return entered;
		} catch (Throwable failure) {
			ForbricLog.warn("[Forbric/FlowerPot] could not fill the flower pot table", Reflect.unwrap(failure));
			return -1;
		}
	}

}
