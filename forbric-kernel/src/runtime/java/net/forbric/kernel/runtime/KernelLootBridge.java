/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.runtime;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.storage.loot.LootTable;

import net.forbric.kernel.boot.LootTableEventDispatch;
import net.neoforged.neoforge.event.EventHooks;

/**
 * The two calls {@code LootTableEventBridgeInjector} routes out of {@code ReloadableServerRegistries}, typed.
 *
 * <p>Same names and descriptors as the callees they replace, so the injector swaps only the owner. NeoForge's
 * {@link EventHooks#loadLootTable} runs first and untouched — {@code LootTable.EMPTY} and a cancelled event both
 * answer {@code null}, which drops the table exactly as before — and only a survivor is offered to Fabric.
 *
 * <p>MinecraftForge's {@code LootTableLoadEvent} is named nowhere in the merged base, so it is posted HERE, in
 * the seam it belongs at. The chain is NeoForge, then MinecraftForge, then Fabric — the same order every other
 * bridge uses, with the surviving hook's listeners first — and each link may drop the table or replace it, which
 * is what those mods are for: a loot mod that adds to or replaces a table on load had no effect at all before.
 *
 * <p>It is registered as {@link net.forbric.api.GameEventBridge#LOOT_TABLE_LOAD} rather than simply called, so
 * {@code -Dforbric.unifiedEvents=off} leaves the old behaviour and the dead-event audit keeps naming a waiting
 * mod when the link is not there.
 *
 * <p><b>PORT(1.21.1):</b> 1.21.1's {@code ReloadableServerRegistries} has no {@code scheduleRegistryLoad} lambdas
 * and no {@code TagLoader.loadTagsForRegistry} to hang the two anchors on — loot tables are parsed by
 * {@code scheduleElementParse} and tags are bound by {@code ReloadableServerResources.updateRegistryTags} over a
 * {@code TagManager}. Both methods are kept, with the descriptors the injector names, so the seam still compiles
 * and a re-derived anchor can be pointed at it; {@link #loadTagsForRegistry} now performs 1.21.1's tag bind
 * itself. Re-anchoring (and the loot-table half, whose {@code EventHooks.loadLootTable} call 1.21.1's
 * {@code ReloadableServerRegistries} never makes) is a W5 item.
 */
public final class KernelLootBridge {
	/**
	 * Whether MinecraftForge's link is in the chain.
	 *
	 * <p>Flipped by {@link #install()} rather than read from the carrier's presence: the chain runs on every
	 * loaded table, on a worker thread, long after boot, and a per-table {@code Class.forName} in a
	 * {@code catch} is both slower and quieter than one decision made once at the point the rest of the bridges
	 * are installed.
	 */
	private static volatile boolean forgeLinked;

	private KernelLootBridge() {
	}

	/** Puts MinecraftForge's {@code LootTableLoadEvent} into the chain. Called once, with the other bridges. */
	public static void install() {
		forgeLinked = true;
	}

	/** {@code ReloadableServerRegistries}'s per-table call, for every loaded table. */
	public static LootTable loadLootTable(HolderLookup.Provider provider, ResourceLocation id, LootTable table) {
		LootTable neo = EventHooks.loadLootTable(provider, id, table);
		if (neo == null) return null;
		LootTable forge = forgeLinked ? net.minecraftforge.event.ForgeEventFactory.onLoadLootTable(id, neo) : neo;
		// null means a MinecraftForge mod cancelled the load, which drops the table — the same answer NeoForge's
		// own hook gives for a cancelled event, so the caller needs no new case.
		if (forge == null) return null;
		Object fabric = LootTableEventDispatch.afterLoad(provider, ResourceKey.create(Registries.LOOT_TABLE, id), id, forge);
		return fabric instanceof LootTable result ? result : forge;
	}

	/** {@code ReloadableServerRegistries}'s per-registry call, once per reloadable registry. */
	public static void loadTagsForRegistry(ResourceManager resources, WritableRegistry<?> registry) {
		loadTags(resources, registry);
		if (Registries.LOOT_TABLE.equals(registry.key())) {
			LootTableEventDispatch.allLoaded(resources, registry);
		}
	}

	/**
	 * 1.21.1's own tag bind, which is what {@code TagLoader.loadTagsForRegistry} did on 26.2.
	 *
	 * <p>{@code ReloadableServerResources.updateRegistryTags} does exactly this per {@code TagManager.LoadResult}:
	 * one {@link TagLoader} over {@code Registries.tagsDirPath(registry)}, then {@code bindTags} with the
	 * identifiers re-keyed as {@link TagKey}s.
	 */
	private static <T> void loadTags(ResourceManager resources, WritableRegistry<T> registry) {
		ResourceKey<? extends Registry<T>> key = registry.key();
		TagLoader<Holder<T>> loader = new TagLoader<>(registry::getHolder, Registries.tagsDirPath(key));
		Map<ResourceLocation, Collection<Holder<T>>> loaded = loader.loadAndBuild(resources);
		Map<TagKey<T>, List<Holder<T>>> tags = new HashMap<>();
		loaded.forEach((id, holders) -> tags.put(TagKey.create(key, id), List.copyOf(holders)));
		registry.bindTags(tags);
	}
}
