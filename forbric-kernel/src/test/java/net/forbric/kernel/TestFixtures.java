/*
 * Copyright 2026 The Forbric Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package net.forbric.kernel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;

/**
 * Fixtures a test reads that a clean checkout does not have: the staged game artifacts, the compiled game side
 * (built only when those artifacts are present), and the local mod packs under {@code run/}.
 *
 * <p>CI is such a checkout, and it must stay green: a missing fixture skips the test there. A compatibility run
 * sets {@code FORBRIC_COMPAT_FIXTURES_REQUIRED=1}, and then a missing fixture is a failure, so a machine that is
 * supposed to have them cannot pass by quietly skipping.
 */
public final class TestFixtures {
	private TestFixtures() {
	}

	/** Skips the test when {@code present} is false, or fails it when fixtures are required. */
	public static void require(boolean present, String what) {
		if ("1".equals(System.getenv("FORBRIC_COMPAT_FIXTURES_REQUIRED"))) assertTrue(present, what);
		Assumptions.assumeTrue(present, what);
	}

	/** {@link #require} for files: every path must be a regular file. */
	public static void requireFiles(String what, Path... files) {
		for (Path file : files) require(Files.isRegularFile(file), what + ": " + file);
	}

	/** {@link #require} for a directory, such as a local mod pack. */
	public static void requireDirectory(String what, Path directory) {
		require(Files.isDirectory(directory), what + ": " + directory);
	}

	/**
	 * The staged {@code run/} directory the build compiled against: {@code -Pforbric.stagedRoot} when the build
	 * handed it over (it does, as {@code forbric.stagedRoot}), else {@code FORBRIC_OLD + "/run"} as the run/
	 * scripts and every other staged test resolve it. Reading the property first is what keeps a test honest on a
	 * retarget: the root moved, and a test that still named {@code ../forbric-loader/run} proved nothing about it.
	 */
	public static Path stagedRoot() {
		String staged = System.getProperty("forbric.stagedRoot");
		if (staged != null && !staged.isBlank()) return Path.of(staged).normalize();
		return Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"),
				"run").normalize();
	}

	/**
	 * The one staged jar in {@code subdirectory} whose file name starts with {@code prefix} — the merged base and
	 * the two patched game jars carry the Minecraft version in their names, and that version is a build parameter
	 * ({@code -Pforbric.mcVersion}), not a constant of the test. Null when the directory is absent or empty.
	 */
	public static Path stagedJar(String subdirectory, String prefix) {
		Path directory = stagedRoot().resolve(subdirectory);
		if (!Files.isDirectory(directory)) return null;
		try (java.util.stream.Stream<Path> entries = Files.list(directory)) {
			return entries.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().startsWith(prefix))
					.filter(path -> path.getFileName().toString().endsWith(".jar"))
					.findFirst().orElse(null);
		} catch (java.io.IOException unreadable) {
			return null;
		}
	}

	/** The staged merged game jar, whatever version this checkout was built for. */
	public static Path mergedBase() {
		return stagedJar("merged-base", "patched-mc-merged-");
	}

	/** The Minecraft directory the game-side compile read its libraries from. Gradle hands it to every test task as
	 * {@code MC_DIR} (tools/dev.py's {@code .dev/minecraft} when prepared); outside Gradle it falls back to the
	 * launcher's usual location on this platform, the same default build.gradle uses.
	 */
	public static Path minecraftDir() {
		String env = System.getenv("MC_DIR");
		if (env != null && !env.isBlank()) return Path.of(env);
		String home = System.getProperty("user.home");
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("windows")) {
			String appData = System.getenv("APPDATA");
			return Path.of(appData != null ? appData : home + "/AppData/Roaming", ".minecraft");
		}
		if (os.contains("mac")) return Path.of(home, "Library", "Application Support", "minecraft");
		return Path.of(home, ".minecraft");
	}

	/** Minecraft 26.2's own client jar inside {@link #minecraftDir()}. */
	public static Path vanillaJar() {
		return minecraftDir().resolve("versions/26.2/26.2.jar");
	}

	/**
	 * The named (Mojmap) game jar the merge is built FROM, for the version this checkout is built for.
	 *
	 * <p>Different from {@link #vanillaJar()}: a launcher's {@code versions/<v>/<v>.jar} ships obfuscated, while a
	 * merge needs the renamed jar, which is a build intermediate. {@code MC_DIR} points at the staging tree the
	 * game side was compiled from ({@code -Pforbric.mcLibraries=<mc>/libraries}), so the NeoForm output sits beside
	 * it; a version whose launcher install happens to carry a named jar is preferred when one exists.
	 */
	public static Path namedGameJar() {
		String version = System.getProperty("forbric.mcVersion", "1.21.1");
		// The NeoForm intermediates first: a modern launcher jar is OBFUSCATED (`yv$15`, not `ByteBufCodecs$22`),
		// so picking it by name would derive a census from the wrong namespace and skip on the anonymous-class
		// count. Measured: the 1.21.1 launcher jar yields 6 matches for the `$N` shape, the named one 689.
		for (String name : new String[]{"client-official.jar", "server-official.jar"}) {
			Path neoform = minecraftDir().resolve(".forbric-build/" + name);
			if (Files.isRegularFile(neoform)) return neoform;
		}
		return minecraftDir().resolve("versions/" + version + "/" + version + ".jar");
	}

	/**
	 * Netty's codec library under {@link #minecraftDir()}: 26.2 ships netty 4.2's split {@code netty-codec-base},
	 * which a launcher directory that also holds older versions keeps beside their {@code netty-codec}.
	 */
	public static String nettyCodecLibrary() {
		return Files.isDirectory(minecraftDir().resolve("libraries/io/netty/netty-codec-base"))
				? "io/netty/netty-codec-base" : "io/netty/netty-codec";
	}

	/** The Fabric API build the real-bytecode tests are written against. */
	public static final String FABRIC_API_JAR = "fabric-api-0.155.2+26.2.jar";

	/**
	 * {@link #FABRIC_API_JAR}: the local merged pack's copy when there is one, else the jar the game side compiled
	 * against ({@code forbric.fabricApi}, which tools/dev.py fills with the same pinned file). Another build passed
	 * there is not used: these tests read classes and mixins of this exact one.
	 */
	public static Path fabricApi() {
		Path pack = Path.of("run/client-merged-pack/mods", FABRIC_API_JAR);
		if (Files.isRegularFile(pack)) return pack;
		String configured = System.getProperty("forbric.fabricApi");
		if (configured == null || configured.isBlank()) return pack;
		Path compiled = Path.of(configured);
		return compiled.getFileName().toString().equals(FABRIC_API_JAR) ? compiled : pack;
	}
}
