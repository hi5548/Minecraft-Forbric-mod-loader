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

package net.forbric.kernel.mapping;

import java.nio.file.Path;

import net.forbric.kernel.TestFixtures;

/**
 * The real W2 fixtures: the two mapping files the installer stages, a real Fabric guest jar compiled against
 * intermediary, and the merged base the guests are remapped to.
 *
 * <p>They are not in the tree — the mapping files are Mojang's and Fabric's data, fetched at install time and
 * never redistributed (the project's rule for every game-derived file), and the guest is a released mod build.
 * The installer stages the mapping files under {@code <mcDir>/.forbric/mappings/}, named for the version, and
 * that is where these defaults look; a developer who keeps them elsewhere points the properties at them.
 *
 * <p>A missing fixture skips the test rather than failing it, which is how every staged-fixture test in this
 * suite behaves, so a bare checkout stays green.
 */
final class MappingFixtures {
	/** The game version the W2 fixtures belong to; overridable so a retarget moves one value, not five. */
	static final String MC_VERSION = System.getProperty("forbric.mcVersion", "1.21.1");

	private MappingFixtures() {
	}

	/** Fabric's intermediary mappings for {@link #MC_VERSION} (a jar holding {@code mappings/mappings.tiny}). */
	static Path intermediary() {
		return staged("forbric.intermediaryMappings", "intermediary-" + MC_VERSION + ".jar");
	}

	/** Mojang's client mappings for {@link #MC_VERSION} (ProGuard, {@code named -> official}). */
	static Path mojmap() {
		return staged("forbric.mojmapMappings", "client-" + MC_VERSION + ".txt");
	}

	/**
	 * A real Fabric guest: a module of the fabric-api build for this version (extracted from the aggregator jar's
	 * {@code META-INF/jars/}, exactly the unit the kernel remaps once discovery has extracted the tree),
	 * compiled against intermediary.
	 */
	static Path guestJar() {
		return staged("forbric.fabricGuestJar", "guest-fabric-transfer-api-v1-" + MC_VERSION + ".jar");
	}

	/**
	 * The merged base — the game in the TARGET (Mojmap) namespace — used as the remap classpath so inherited
	 * members resolve. Read from {@code forbric.stagedRoot}, the staged {@code run/} the test task already hands
	 * the whole suite.
	 */
	static Path mergedBase() {
		String staged = System.getProperty("forbric.stagedRoot");
		Path base = staged == null || staged.isBlank()
				? TestFixtures.minecraftDir().resolve(".forbric-build/out/patched-mc-merged-" + MC_VERSION + ".jar")
				: Path.of(staged).resolve("merged-base/patched-mc-merged-" + MC_VERSION + ".jar");
		TestFixtures.requireFiles("forbric.stagedRoot (merged base)", base);
		return base;
	}

	private static Path staged(String property, String name) {
		String configured = System.getProperty(property);
		Path file = configured == null || configured.isBlank()
				? TestFixtures.minecraftDir().resolve(".forbric").resolve("mappings").resolve(name)
				: Path.of(configured);
		TestFixtures.requireFiles(property, file);
		return file;
	}
}
