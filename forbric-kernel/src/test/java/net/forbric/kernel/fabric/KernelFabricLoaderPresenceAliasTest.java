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

package net.forbric.kernel.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;

import net.forbric.kernel.boot.DuplicateModArbiter;
import net.forbric.kernel.boot.KernelFabricEcosystem;
import net.forbric.kernel.boot.MultiLoaderArbiter;

/**
 * An id and a {@code provides} ALIAS are different questions, and an alias must not outrank the mod that owns the
 * id.
 *
 * <p>Measured on simple-hats-collection: accessories nests {@code owo-sentinel}, whose metadata is
 * {@code id "owo-sentinel"}, {@code provides ["owo","owo-lib"]} — the "please install oωo" stub. It was discovered
 * before the real {@code owo-lib} (whose id IS {@code owo}), claimed the id through its alias, and the real mod
 * was then read as a duplicate and dropped. Its mixins never applied, so
 * {@code DataComponentType$Builder.endec(Endec, SerializationContext)} — a method owo's
 * {@code ComponentTypeBuilderMixin} adds — did not exist, and accessories died with {@code NoSuchMethodError}
 * before the subject ever ran. On Fabric both are loaded; only the alias question is answered by {@code provides}.
 */
class KernelFabricLoaderPresenceAliasTest {
	@TempDir
	Path gameDir;

	@BeforeEach
	@AfterEach
	void reset() {
		KernelFabricLoader.resetForTests();
		KernelFabricEcosystem.resetPhasesForTests();
		KernelLanguageAdapters.reset();
		MultiLoaderArbiter.reset();
		DuplicateModArbiter.reset();
	}

	@Test
	void theModThatOwnsAnIdTakesItFromAnAliasThatOnlyProvidesIt() {
		KernelFabricLoader loader = loader();
		KernelModContainer sentinel = container("{\"schemaVersion\":1,\"id\":\"owo-sentinel\",\"version\":\"0.12.15.4\","
				+ "\"provides\":[\"owo\",\"owo-lib\"]}");
		KernelModContainer owoLib = container("{\"schemaVersion\":1,\"id\":\"owo\",\"version\":\"0.13.0-alpha.15\"}");

		loader.register(sentinel);
		assertTrue(loader.isModLoaded("owo"), "the sentinel's alias answers before the real mod is discovered");
		assertSame(sentinel, loader.getModContainer("owo").orElseThrow());

		loader.register(owoLib);

		assertSame(owoLib, loader.getModContainer("owo").orElseThrow(),
				"the id's own mod must win: an alias answers isModLoaded, it does not own the mod");
		assertTrue(loader.getAllMods().contains(sentinel), "the sentinel is still a mod of its own");
		assertTrue(loader.getAllMods().contains(owoLib), "and the real mod must actually have been registered");
	}

	@Test
	void aRealDuplicateIdIsStillKeptOnce() {
		KernelFabricLoader loader = loader();
		KernelModContainer first = container("{\"schemaVersion\":1,\"id\":\"twice\",\"version\":\"1\"}");
		KernelModContainer second = container("{\"schemaVersion\":1,\"id\":\"twice\",\"version\":\"2\"}");

		loader.register(first);
		loader.register(second);

		assertSame(first, loader.getModContainer("twice").orElseThrow(),
				"two mods claiming the same id is still one mod, and the first one wins");
		assertFalse(loader.getAllMods().contains(second));
		assertEquals(1, loader.getAllMods().stream().filter(m -> "twice".equals(m.getMetadata().getId())).count());
	}

	private KernelFabricLoader loader() {
		try {
			Files.createDirectories(gameDir);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		return KernelFabricLoader.create(EnvType.CLIENT, gameDir, gameDir.resolve("config"), new String[0], "1.21.1");
	}

	private static KernelModContainer container(String json) {
		return new KernelModContainer(FabricModMetadataParser.read(new StringReader(json)), null, null);
	}
}
