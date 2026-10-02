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

import java.util.Optional;

import com.mojang.serialization.DynamicOps;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.storage.loot.LootDataType;
import net.minecraft.world.level.storage.loot.LootTable;

import net.forbric.kernel.boot.LootTableEventDispatch;
import net.forbric.kernel.util.ForbricLog;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.EventHooks;

/**
 * The two calls {@code LootTableEventBridgeInjector} routes out of {@code ReloadableServerRegistries}, typed.
 *
 * <p><b>PORT(1.21.1): the chain inverted, and so did the family the kernel has to restore.</b> On 26.2 the
 * surviving native hook was NeoForge's {@code EventHooks.loadLootTable} and MinecraftForge's
 * {@code LootTableLoadEvent} was named nowhere in the merged base, so the kernel posted it here between NeoForge
 * and Fabric. In 1.21.1 it is the other way round: the merged {@code LootDataType.deserialize} kept
 * MinecraftForge's body, so {@code ForgeEventFactory.onLoadLootTable} runs there natively and untouched, and
 * NeoForge's own body survives only as the private, uncalled {@code LootDataType.lambda$deserialize$3} — a
 * whole-jar scan finds no reachable {@code EventHooks.loadLootTable} call at all. A NeoForge mod that adjusts a
 * loot table on load therefore had no effect before this bridge.
 *
 * <p>So {@link #loadLootTable} calls the native load first — {@code LootDataType.deserialize}, whose Forge hook
 * runs inside it exactly as before, byte-identical — and only a survivor is offered onward, in the same order
 * every other bridge uses: the surviving hook's listeners first (MinecraftForge, natively), then the family the
 * merge dropped (NeoForge, here), then Fabric. {@code LootTable.EMPTY} and a cancelled event both answer
 * {@code null} and drop the table.
 *
 * <p>The {@code HolderLookup.Provider} fabric-loot-api's REPLACE and MODIFY callbacks are typed against comes
 * from the same {@code RegistryOps} the load was parsed with — {@code ReloadableServerRegistries.reload} builds
 * it through {@code HolderLookup.Provider.createSerializationContext}, which wraps the provider in
 * {@code RegistryOps.HolderLookupAdapter}, so {@code CommonHooks.extractLookupProvider} recovers exactly the
 * provider fabric's own 1.21.1 mixin keeps in its ops-keyed map. It is never guessed: an ops that is not a
 * {@code RegistryOps}, or one whose lookup is not a provider adapter, is reported once and the table is kept as
 * the native load returned it rather than handed to a listener that would read a null registry lookup.
 *
 * <p>It is registered as {@link net.forbric.api.GameEventBridge#LOOT_TABLE_LOAD} rather than simply called, so
 * {@code -Dforbric.unifiedEvents=off} leaves the old behaviour and the dead-event audit keeps naming a waiting
 * mod when the link is not there. Note the gate now covers <b>NeoForge's</b> link: MinecraftForge's is the
 * native one and cannot be switched off from here.
 */
public final class KernelLootBridge {
	/**
	 * Whether the link this bridge owns — NeoForge's {@code LootTableLoadEvent}, the family the merge dropped —
	 * is in the chain.
	 *
	 * <p>Flipped by {@link #install()} rather than read from the carrier's presence: the chain runs on every
	 * loaded table, on a worker thread, long after boot, and a per-table {@code Class.forName} in a
	 * {@code catch} is both slower and quieter than one decision made once at the point the rest of the bridges
	 * are installed.
	 */
	private static volatile boolean neoLinked;

	private static volatile boolean warned;

	private KernelLootBridge() {
	}

	/** Puts NeoForge's {@code LootTableLoadEvent} into the chain. Called once, with the other bridges. */
	public static void install() {
		neoLinked = true;
	}

	/**
	 * {@code ReloadableServerRegistries}'s per-table call — the routed replacement for
	 * {@code LootDataType.deserialize}, same operands with the receiver written in.
	 *
	 * <p>Fabric's events follow the native ones for the same reason on both generations: a listener that can only
	 * add to or replace a table has to see what the earlier hooks decided, and a table the native chain dropped
	 * was never loaded at all.
	 */
	public static <T> Optional<T> loadLootTable(LootDataType<T> type, ResourceLocation id, DynamicOps<?> ops, Object json) {
		@SuppressWarnings("unchecked")
		Optional<T> parsed = type.deserialize(id, (DynamicOps<Object>) ops, json);
		if (parsed.isEmpty() || !(parsed.get() instanceof LootTable table)) return parsed;
		HolderLookup.Provider provider;
		try {
			provider = ops instanceof RegistryOps<?> registryOps ? CommonHooks.extractLookupProvider(registryOps) : null;
		} catch (IllegalArgumentException notAProviderAdapter) {
			provider = null;
		}
		if (provider == null) {
			warnOnce("the routed loot-table load for " + id + " carried no HolderLookup.Provider behind its ops ("
					+ ops.getClass().getName() + "), so neither NeoForge's LootTableLoadEvent nor fabric's "
					+ "REPLACE/MODIFY listeners were offered it; the table is kept exactly as the native load "
					+ "returned it — the seam has moved and the anchor needs re-deriving", null);
			return parsed;
		}
		LootTable surviving = table;
		if (neoLinked) {
			LootTable neo = EventHooks.loadLootTable(provider, id, surviving);
			// null is NeoForge's own answer for a cancelled event, and its deserialize turned that into an empty
			// Optional; the caller's ifPresent then registers nothing. Same table dropped, same path.
			if (neo == null) return Optional.empty();
			surviving = neo;
		}
		Object fabric = LootTableEventDispatch.afterLoad(provider,
				ResourceKey.create(Registries.LOOT_TABLE, id), id, surviving);
		@SuppressWarnings("unchecked")
		T offered = fabric instanceof LootTable result ? (T) result : (T) surviving;
		return Optional.of(offered);
	}

	/**
	 * {@code ReloadableServerRegistries}'s per-registry call, at the tail of {@code lambda$scheduleElementParse$4}
	 * where the freshly filled registry is about to be returned.
	 *
	 * <p>1.21.1's vanilla code loads the tags for every registry later, in
	 * {@code ReloadableServerResources.updateRegistryTags}, and there is one loot-table data type among the three
	 * this lambda runs for — which is exactly the check fabric's own mixin makes ({@code type == LootDataType.TABLE})
	 * before it fires {@code ALL_LOADED} with the returned registry. Firing it at the same point keeps that
	 * contract rather than approximating it.
	 */
	public static void registryParsed(LootDataType<?> type, ResourceManager resources, WritableRegistry<?> registry) {
		if (type == LootDataType.TABLE) LootTableEventDispatch.allLoaded(resources, registry);
	}

	private static void warnOnce(String what, Throwable t) {
		if (warned) return;
		warned = true;
		if (t == null) ForbricLog.warn("[Forbric/LootBridge] " + what);
		else ForbricLog.warn("[Forbric/LootBridge] " + what, t);
	}
}
