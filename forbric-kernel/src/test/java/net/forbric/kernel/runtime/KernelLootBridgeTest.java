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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Source-text pins on the runtime shim, in the {@code KernelGameTickEventsTest} shape: the runtime set compiles
 * only against the staged game jars, which are not on every machine, but the FILE always is. What matters is
 * ORDER and the one guard — the native load first (that is where MinecraftForge's hook runs in the merged base),
 * the family the merge dropped next, Fabric only for a survivor.
 */
class KernelLootBridgeTest {
	private static final Path SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelLootBridge.java");

	private static String bodyOf(String signature) throws Exception {
		String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
		int start = source.indexOf(signature);
		assertTrue(start >= 0, signature + " is gone from " + SOURCE + " — the injector routes a call to it by name and "
				+ "descriptor, so its absence is a NoSuchMethodError at the first datapack load, not a compile error");
		int end = source.indexOf("\n\t}", start);
		assertTrue(end > start, "could not find the end of " + signature);
		return source.substring(start, end);
	}

	@Test
	void theNativeLoadRunsFirstAndItsNullDropsTheTableBeforeFabricIsAsked() throws Exception {
		String body = bodyOf("public static <T> Optional<T> loadLootTable(LootDataType<T> type, ResourceLocation id, "
				+ "DynamicOps<?> ops, Object json)");
		int nativeLoad = body.indexOf("type.deserialize(");
		int dropped = body.indexOf("EventHooks.loadLootTable(");
		int guard = body.indexOf("if (neo == null) return Optional.empty();");
		int fabric = body.indexOf("LootTableEventDispatch.afterLoad(");
		assertTrue(nativeLoad >= 0, "the native load must be called, not re-implemented — it is where the surviving "
				+ "hook (MinecraftForge's, in the merged 1.21.1 base) runs");
		assertTrue(dropped > nativeLoad, "the family the merge orphaned is posted after the surviving one, never instead of it");
		assertTrue(guard > dropped, "a null from NeoForge (EMPTY, or a cancelled event) must drop the table exactly as "
				+ "its own deserialize did");
		assertTrue(fabric > guard, "Fabric is offered only a survivor");
		assertTrue(body.contains("Registries.LOOT_TABLE"), "the ResourceKey is built for the LOOT_TABLE registry");
		assertTrue(body.contains("CommonHooks.extractLookupProvider("),
				"the HolderLookup.Provider fabric's callbacks are typed against comes from the ops the load was parsed with");
		assertTrue(body.contains("neoLinked"),
				"the switch gates the DROPPED family; MinecraftForge's link is native here and cannot be switched off");
	}

	@Test
	void allLoadedFiresOnlyForTheLootTableRegistryItJustBuilt() throws Exception {
		String body = bodyOf("public static void registryParsed(LootDataType<?> type, ResourceManager resources, "
				+ "WritableRegistry<?> registry)");
		int table = body.indexOf("type == LootDataType.TABLE");
		int all = body.indexOf("LootTableEventDispatch.allLoaded(");
		assertTrue(table >= 0, "ALL_LOADED is for the loot-table data type only — the same check fabric's own 1.21.1 "
				+ "mixin makes before firing it");
		assertTrue(all > table, "ALL_LOADED fires at the point the registry is returned, not before it is filled");
	}
}
