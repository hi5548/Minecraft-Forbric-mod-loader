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

package net.forbric.installer.kernel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Builds the three jars a Forbric instance runs on, here, on the machine that will run them.
 *
 * <p>They cannot be shipped. The merged base is Minecraft with two loaders' patches applied and then byte-merged;
 * the two runtimes are assembled from MinecraftForge's and NeoForge's own distributions. All three carry code
 * this project has no right to hand out, so an installer that shipped them would be redistributing Mojang's,
 * MinecraftForge's and NeoForge's work. Building them from the upstreams' own Mavens, on the user's machine, is
 * the only lawful shape this can take — and it is the shape the previous generation's installer already used for
 * the Forge half.
 *
 * <p>The pipeline, with the two halves independent until the merge:
 *
 * <pre>
 *   forge userdev ─┬→ forge-runtime ───────────────┬→ patched-mc-forge ─┐
 *                  └───────────────────────────────┘                    ├→ patched-mc-merged
 *   neoforge userdev ─┬→ neoforge-runtime ──────────────────────────────┤
 *                     └→ NFRT → patched-mc-neoforge ────────────────────┘
 *   vanilla 26.2.jar ───────────────────────────────────────────────────┘
 *
 *   forge-runtime ────────────────────────────────→ forge-runtime-interop   (what actually gets staged)
 * </pre>
 *
 * <p>Everything lands under {@code <mcDir>/.forbric-build/}, one directory that can be deleted wholesale, and
 * each step short-circuits on a finished output so an interrupted install resumes rather than restarts.
 */
final class ArtifactBuilder {

	/** The coordinates {@link Installer} stages and the profile names, without their version suffix. */
	static final String MERGED = "net.forbric:patched-mc-merged";
	static final String FORGE_RUNTIME = "net.forbric:forge-runtime";
	static final String NEOFORGE_RUNTIME = "net.forbric:neoforge-runtime";

	private final Consumer<String> log;

	ArtifactBuilder(Consumer<String> log) {
		this.log = log;
	}

	/**
	 * Produces all three, reusing whatever is already built.
	 *
	 * @param mcDir     the Minecraft directory; its {@code versions/<mc>/<mc>.jar} is the vanilla input and its
	 *                  {@code .forbric-build/} holds every intermediate
	 * @param jvm       the JVM the build tools run under
	 * @return coordinate (without version) to the finished file, in the shape {@link GameArtifacts#all()} returns
	 */
	Map<String, Path> build(Path mcDir, String mcVersion, JdkLocator.Jvm jvm) throws IOException {
		Path build = mcDir.resolve(".forbric-build");
		Path dl = build.resolve("dl");
		Path tools = build.resolve("tools");
		Path out = build.resolve("out");
		Files.createDirectories(dl);
		Files.createDirectories(out);

		Path vanilla = mcDir.resolve("versions").resolve(mcVersion).resolve(mcVersion + ".jar");
		if (!Files.isRegularFile(vanilla)) {
			throw new IOException("the vanilla " + mcVersion + " jar is missing: " + vanilla);
		}

		log.accept("");
		log.accept("Building the game artifacts. The first run downloads a few hundred megabytes and takes");
		log.accept("several minutes; afterwards it is cached in " + build + ".");
		log.accept("pins: " + Pins.stamp());

		Http http = new Http(log);

		// ---- MinecraftForge ----
		log.accept("");
		log.accept("== MinecraftForge " + Pins.FORGE + " ==");
		ForgeArtifacts fa = new ForgeArtifacts(mcVersion, Pins.FORGE);
		Path forgeUserdev = dl.resolve("forge-userdev.jar");
		http.ensureWithFallback(fa.forgeUrl(fa.userdevCoordinate()), fa.centralUrl(fa.userdevCoordinate()),
				forgeUserdev);
		ForgeArtifacts.UserdevConfig forgeCfg = ForgeArtifacts.readConfig(forgeUserdev);

		// An obfuscated game's runtime must come from the PRODUCTION universal: the userdev config names
		// ...-srg, whose classes reference the game by SRG member names and dangle against the Mojmap base
		// (measured: 3,628 of 3,664 dangling references on the first 1.21.1 link check).
		String universalOverride = Zips.isMojmapNamed(vanilla) ? null : fa.productionUniversal(http, dl);
		ArtifactResult forgeRuntime = new ForgeRuntimeBuilder(fa, http, build, out.resolve("forge-runtime.jar"), log)
				.build(forgeCfg, universalOverride);
		PatchedMcBuilder patchedMc = new PatchedMcBuilder(fa, http, mcDir, build,
				out.resolve("patched-mc-forge-" + mcVersion + ".jar"), log);
		ArtifactResult forgePatched = patchedMc.build(forgeUserdev, forgeCfg, forgeRuntime.file);

		// ---- NeoForge ----
		log.accept("");
		log.accept("== NeoForge " + Pins.NEOFORGE + " ==");
		NeoForgeArtifacts nfa = new NeoForgeArtifacts(mcVersion, Pins.NEOFORGE);
		Path neoUserdev = dl.resolve("neoforge-userdev.jar");
		http.ensureWithFallback(nfa.neoforgedUrl(nfa.userdevCoordinate()), nfa.centralUrl(nfa.userdevCoordinate()),
				neoUserdev);
		// The same NeoForm userdev config shape on both sides, so the Forge reader serves; see NeoForgeArtifacts.
		ForgeArtifacts.UserdevConfig neoCfg = ForgeArtifacts.readConfig(neoUserdev);

		ArtifactResult neoRuntime = new NeoForgeRuntimeBuilder(nfa, http, build,
				out.resolve("neoforge-runtime.jar"), log).build(neoCfg);
		ArtifactResult neoPatched = new NfrtRunner(http, tools, build.resolve("nfrt"),
				build.resolve("nfrt-work"), log)
				.run(jvm, mcDir, out.resolve("patched-mc-neoforge-" + mcVersion + ".jar"),
						nfa.patchedMcCoordinate(), mcVersion, build.resolve("dl").resolve("server.jar"));

		// ---- the merge, and the interop patch the merge makes necessary ----
		log.accept("");
		log.accept("== merging ==");
		MergedBaseTool merge = new MergedBaseTool(tools, log);
		// The merge's fallback base must be in the SAME namespace as the patched jars. 26.2's vanilla already
		// is (Mojmap-native). An obfuscated game's is not: feeding it in copies every obfuscated class whose
		// name matches nothing in the patched jars straight into the merged base (measured: 8,047 classes,
		// 4,321 new dangling references). When the patcher renamed the game, its named client is the input.
		Path mergeBase = patchedMc.namedVanilla().orElse(vanilla);
		ArtifactResult merged = merge.merge(jvm, mergeBase, forgePatched.file, neoPatched.file,
				forgeRuntime.file, neoRuntime.file,
				out.resolve("patched-mc-merged-" + mcVersion + ".jar"),
				out.resolve("merge-conflicts.txt"),
				MERGED + ":" + mcVersion);
		ArtifactResult interop = merge.interop(jvm, forgeRuntime.file,
				out.resolve("forge-runtime-interop.jar"), FORGE_RUNTIME + ":" + mcVersion);
		// After the interop patch, not before: the check resolves against what actually gets staged.
		merge.linkCheck(jvm, merged.file, neoRuntime.file, interop.file);

		Map<String, Path> result = new LinkedHashMap<>();
		result.put(MERGED, merged.file);
		// The INTEROP jar, not the raw runtime: the merge widened interfaces on NeoForge's behalf that the raw
		// jar's own classes no longer satisfy. It keeps the forge-runtime coordinate so nothing downstream moves.
		result.put(FORGE_RUNTIME, interop.file);
		result.put(NEOFORGE_RUNTIME, neoRuntime.file);

		log.accept("");
		log.accept("game artifacts ready:");
		for (Map.Entry<String, Path> e : result.entrySet()) {
			log.accept("  " + e.getKey() + "  →  " + e.getValue().getFileName()
					+ " (" + (Files.size(e.getValue()) / (1024 * 1024)) + " MB)");
		}
		return result;
	}

	/**
	 * The two mapping files the kernel reads at load time, in the order the launch argument names them.
	 */
	record Mappings(Path intermediary, Path mojmap) {
	}

	/**
	 * Fetches and stages the mapping data the kernel needs to run Fabric guests, and nothing else needs.
	 *
	 * <p>On 1.21.x the game ships obfuscated and a Fabric mod ships compiled against <b>intermediary</b>, while
	 * the merged base runs Mojmap. Neither mapping file can reach from one to the other alone: Fabric's
	 * intermediary mappings join the obfuscated column to intermediary, Mojang's client mappings join the same
	 * obfuscated column to Mojmap, and the kernel joins them on that shared column. So both are fetched, exactly
	 * like the game artifacts: at install time, on the user's machine, never redistributed.
	 *
	 * <p>They land in {@code <mcDir>/.forbric/mappings/}, named for the version they belong to — a data
	 * directory, not {@code libraries/}: the kernel opens them by path, they are never classpath entries, and
	 * they must not be confused with the jars a launcher resolves. {@link Installer} writes their absolute paths
	 * into the profile's game arguments.
	 *
	 * @param clientMappingsUrl the base version JSON's {@code downloads.client_mappings.url}
	 */
	Mappings fetchMappings(Path mcDir, String mcVersion, String clientMappingsUrl) throws IOException {
		if (clientMappingsUrl == null || clientMappingsUrl.isBlank()) {
			throw new IOException("Mojang's version JSON for " + mcVersion + " lists no client_mappings download,"
					+ " and the kernel cannot rename Fabric guests from intermediary to Mojmap without it");
		}

		Path dir = mcDir.resolve(".forbric").resolve("mappings");
		Files.createDirectories(dir);
		Path intermediary = dir.resolve("intermediary-" + mcVersion + ".jar");
		Path mojmap = dir.resolve("client-" + mcVersion + ".txt");

		Http http = new Http(log);
		// The same coordinate Fabric's own toolchain resolves: net.fabricmc:intermediary:<mcVersion>.
		http.ensure("https://maven.fabricmc.net/net/fabricmc/intermediary/" + mcVersion
				+ "/intermediary-" + mcVersion + ".jar", intermediary);
		http.ensure(clientMappingsUrl, mojmap);

		log.accept("staged the mapping data the kernel remaps Fabric mods with:");
		log.accept("  " + intermediary);
		log.accept("  " + mojmap);
		return new Mappings(intermediary, mojmap);
	}
}
