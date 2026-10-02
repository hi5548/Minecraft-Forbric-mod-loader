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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reproduces {@code run/build-patched-forge.sh} in pure Java: produce the traditional-MinecraftForge-patched,
 * Mojmap-named Minecraft game jar Forbric loads under Knot. Pipeline:
 * <ol>
 *   <li>{@code installertools BUNDLER_EXTRACT} the Mojang server jar; client jar is the user's own install.</li>
 *   <li>{@code mergetool --merge ... --ann API} (Forge binpatches target the {@code @OnlyIn}-annotated merge).</li>
 *   <li>{@code binarypatcher --apply joined.lzma} (patches + ATs extracted from the Forge {@code -userdev} jar).</li>
 *   <li>overlay the patched classes onto the clean merge + strip the Mojang jar signature.</li>
 *   <li>apply Forge's access transformers via Forge's own {@code AccessTransformerEngine}.</li>
 * </ol>
 * The result embeds Mojang code, so it is built here and never redistributed.
 */
final class PatchedMcBuilder {

	// Tool coordinates the userdev config.json does NOT carry (the dev script hard-pins these versions).
	private static final String MERGETOOL = "net.minecraftforge:mergetool:1.2.5:fatjar";
	private static final String INSTALLERTOOLS = "net.minecraftforge:installertools:1.3.2:fatjar";
	private static final String LOG4J_API = "org.apache.logging.log4j:log4j-api:2.24.3";
	private static final String LOG4J_CORE = "org.apache.logging.log4j:log4j-core:2.24.3";
	private static final String[] ASM_ARTIFACTS = {"asm", "asm-tree", "asm-commons", "asm-util", "asm-analysis"};

	private final ForgeArtifacts fa;
	private final Http http;
	private final ForgeTool tool;
	private final Path mcDir;
	private final Path workDir;
	private final Path dlDir;
	private final Path outJar;
	private final Consumer<String> log;

	PatchedMcBuilder(ForgeArtifacts fa, Http http, Path mcDir, Path workDir, Path outJar, Consumer<String> log) {
		this.fa = fa;
		this.http = http;
		this.tool = new ForgeTool(log);
		this.mcDir = mcDir;
		this.workDir = workDir;
		this.dlDir = workDir.resolve("dl");
		this.outJar = outJar;
		this.log = log;
	}

	ArtifactResult build(Path userdevJar, ForgeArtifacts.UserdevConfig cfg, Path forgeRuntimeJar) throws IOException {
		String coordinate = fa.patchedMcCoordinate();
		// Keep the merge's named base reachable even when the patched jar itself is served from the cache: the
		// rename step does not rerun, but the merge downstream still needs a same-namespace fallback jar.
		Path cachedNamed = workDir.resolve("client-official.jar");
		if (Files.isRegularFile(cachedNamed)) namedVanilla = cachedNamed;
		if (BuildStamp.isFresh(outJar)) {
			log.accept("[patched] up-to-date: " + outJar.getFileName());
			return new ArtifactResult(coordinate, outJar, Util.sha1(outJar), Files.size(outJar));
		}
		Files.createDirectories(dlDir);

		// 1) tools
		log.accept("[patched] fetching tools + Forge userdev");
		Path installertools = dl(INSTALLERTOOLS);
		Path binarypatcher = dl(cfg.binpatcherCoordinate);
		String atCoord = findLib(cfg, "net.minecraftforge", "accesstransformers", "net.minecraftforge:accesstransformers:8.2.2");
		String asmVersion = asmVersion(cfg);
		Path atJar = dl(atCoord);
		List<Path> engineCp = new ArrayList<>();
		engineCp.add(atJar);
		for (String a : ASM_ARTIFACTS) engineCp.add(dlCentral("org.ow2.asm:" + a + ":" + asmVersion));
		engineCp.add(dlCentral(LOG4J_API));
		engineCp.add(dlCentral(LOG4J_CORE));

		// 2) client jar from the user's install
		Path clientJar = mcDir.resolve("versions").resolve(fa.mcVersion).resolve(fa.mcVersion + ".jar");
		if (!Files.isRegularFile(clientJar)) {
			throw new IOException("client jar not found: " + clientJar + " — install/download vanilla "
					+ fa.mcVersion + " first");
		}

		// 3) server jar via <mc>.json downloads.server
		Path serverJar = dlDir.resolve("server.jar");
		downloadServer(serverJar);

		// 4) BUNDLER_EXTRACT the server jar (both pipelines need it)
		Path serverMain = workDir.resolve("server-main.jar");
		tool.runJar(installertools, List.of("--task", "BUNDLER_EXTRACT",
				"--input", serverJar.toString(), "--output", serverMain.toString(), "--jar-only"),
				"installertools BUNDLER_EXTRACT");

		// 5) the userdev AT config (both pipelines)
		Zips.extractEntries(userdevJar, workDir, cfg.ats.get(0));
		Path atCfg = workDir.resolve(cfg.ats.get(0));

		// 6) patch the game. Two pipelines, decided by the game jar itself:
		//    - Mojmap-native (26.2): merge the two sides, then the userdev joined.lzma targets that merge.
		//    - Obfuscated (1.21.x): joined.lzma targets SRG on a jar Forge's dev toolchain built and does not
		//      apply here; the official install pipeline does — rename each side to Mojmap, apply the
		//      per-side binpatches the installer ships, then merge. See 实验/forbric/p0/P0-FINDINGS.md (F1).
		Path patchedFull;
		if (isObfuscated(clientJar)) {
			patchedFull = officialPipeline(clientJar, serverMain, binarypatcher);
		} else {
			Path mergetool = dl(MERGETOOL);
			Path clean = workDir.resolve("clean.jar");
			tool.runJar(mergetool, List.of("--merge", "--client", clientJar.toString(),
					"--server", serverMain.toString(), "--output", clean.toString(),
					"--keep-data", "--keep-meta", "--ann", "API"),
					"mergetool --merge --ann API");
			Zips.extractEntries(userdevJar, workDir, cfg.binpatchesEntry);
			Path joinedLzma = workDir.resolve(cfg.binpatchesEntry);
			Path patchedSubset = workDir.resolve("patched-subset.jar");
			tool.runJar(binarypatcher, binpatcherArgs(cfg, clean, patchedSubset, joinedLzma),
					"binarypatcher --apply " + cfg.binpatchesEntry);
			log.accept("[patched] overlay patched classes onto clean + strip signatures");
			patchedFull = workDir.resolve("patched-full.jar");
			overlay(clean, patchedSubset, patchedFull);
		}

		// 8) apply access transformers
		Path patchedAt = workDir.resolve("patched-at.jar");
		tool.applyAccessTransformers(engineCp, atCfg, patchedFull, patchedAt);

		// 9) inject a concrete covariant self() into MC classes implementing a public-self() Forge interface
		//    (IForgeLivingEntity/LivingEntity) — Forge's public interface default does not resolve on subclasses
		//    under Forbric's flat Knot classloader (ServerPlayer AbstractMethodError). Reuses the AT step's ASM.
		Files.createDirectories(outJar.getParent());
		int injected = tool.injectCovariantSelf(engineCp, forgeRuntimeJar, patchedAt, outJar);
		log.accept("[patched] injected concrete self() into " + injected + " class(es)");

		String sha1 = Util.sha1(outJar);
		long size = Files.size(outJar);
		log.accept("[patched] wrote " + outJar.getFileName() + " (" + (size / (1024 * 1024)) + " MB)");
		BuildStamp.write(outJar);
		return new ArtifactResult(coordinate, outJar, sha1, size);
	}

	// ---- steps ----

	/**
	 * The renamed (named/Mojmap) but UNPATCHED client jar, once the official pipeline has made one. The merge
	 * needs it as its fallback base: feeding the raw obfuscated vanilla jar there copies every obfuscated class
	 * whose name matches nothing in the patched jars — measured at 8,047 classes and 4,321 new dangling
	 * references before this existed.
	 */
	java.util.Optional<Path> namedVanilla() {
		return java.util.Optional.ofNullable(namedVanilla);
	}

	private Path namedVanilla;

	/**
	 * Whether this game ships obfuscated. Probed from the jar rather than pinned, so the pipeline follows the
	 * artifact: 26.2 is Mojmap-native and the merge-then-patch path applies; 1.21.x is not and the official
	 * install pipeline does.
	 */
	private static boolean isObfuscated(Path clientJar) throws IOException {
		return !Zips.isMojmapNamed(clientJar);
	}

	/**
	 * The official install pipeline for an obfuscated game: rename each side to Mojmap with Mojang's own
	 * mappings, apply the per-side binpatches the Forge installer ships, then merge the two patched sides.
	 *
	 * <p>Why not the userdev {@code joined.lzma}: its targets are SRG member names on a jar Forge's dev
	 * toolchain built, and it refuses the obfuscated merge on the first class whose checksum it can compare
	 * (P0, see {@code 实验/forbric/p0/P0-FINDINGS.md} F1). The official binpatches apply cleanly to the
	 * renamed jars (rc=0, measured).
	 */
	private Path officialPipeline(Path clientJar, Path serverMain, Path binarypatcher) throws IOException {
		log.accept("[patched] obfuscated game: official install pipeline (rename → per-side binpatch → merge)");
		Path installertools = dl(Pins.INSTALLERTOOLS);
		Path fart = dl(Pins.FART);
		Zips.extractEntries(dl(fa.installerCoordinate()), workDir, "data/client.lzma", "data/server.lzma");

		Path clientPatched = patchSide(installertools, fart, binarypatcher, "client", clientJar,
				workDir.resolve("data/client.lzma"));
		Path serverPatched = patchSide(installertools, fart, binarypatcher, "server", serverMain,
				workDir.resolve("data/server.lzma"));

		Path merged = workDir.resolve("patched-full.jar");
		tool.runJar(dl(MERGETOOL), List.of("--merge", "--client", clientPatched.toString(),
				"--server", serverPatched.toString(), "--output", merged.toString(),
				"--keep-data", "--keep-meta", "--ann", "API"),
				"mergetool --merge (patched client+server)");
		return merged;
	}

	/** Rename one side of the game to Mojmap and apply that side's official binpatches. */
	private Path patchSide(Path installertools, Path fart, Path binarypatcher, String side, Path input,
			Path binpatches) throws IOException {
		Path mappings = workDir.resolve(side + "-mojmaps.tsrg");
		tool.runJar(installertools, List.of("--task", "DOWNLOAD_MOJMAPS", "--sanitize",
				"--version", fa.mcVersion, "--side", side, "--output", mappings.toString()),
				"installertools DOWNLOAD_MOJMAPS (" + side + ")");
		Path named = workDir.resolve(side + "-official.jar");
		tool.runJar(fart, List.of("--input", input.toString(), "--output", named.toString(),
				"--names", mappings.toString(), "--ann-fix", "--ids-fix", "--src-fix", "--record-fix",
				"--strip-sigs", "--reverse"),
				"FART --reverse (obf → Mojmap, " + side + ")");
		Path patched = workDir.resolve(side + "-patched.jar");
		tool.runJar(binarypatcher, List.of("--clean", named.toString(), "--output", patched.toString(),
				"--apply", binpatches.toString(), "--data", "--unpatched"),
				"binarypatcher --apply data/" + side + ".lzma");
		if ("client".equals(side)) namedVanilla = named;
		return patched;
	}

	private List<String> binpatcherArgs(ForgeArtifacts.UserdevConfig cfg, Path clean, Path output, Path patch) {
		List<String> out = new ArrayList<>();
		for (String tok : cfg.binpatcherArgs) {
			switch (tok) {
				case "{clean}":  out.add(clean.toString()); break;
				case "{output}": out.add(output.toString()); break;
				case "{patch}":  out.add(patch.toString()); break;
				default:         out.add(tok);
			}
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private void downloadServer(Path dest) throws IOException {
		Path versionJson = mcDir.resolve("versions").resolve(fa.mcVersion).resolve(fa.mcVersion + ".json");
		if (!Files.isRegularFile(versionJson)) {
			throw new IOException("version json not found: " + versionJson + " — download vanilla " + fa.mcVersion + " first");
		}
		Map<String, Object> root;
		try {
			root = (Map<String, Object>) Json.parse(Files.readString(versionJson));
		} catch (RuntimeException e) {
			throw new IOException("malformed " + versionJson.getFileName() + ": " + e.getMessage(), e);
		}
		Object downloads = root.get("downloads");
		Map<String, Object> server = downloads instanceof Map ? (Map<String, Object>) ((Map<String, Object>) downloads).get("server") : null;
		if (server == null || server.get("url") == null) {
			throw new IOException(fa.mcVersion + ".json has no downloads.server.url (a client-only version can't be Forge-patched)");
		}
		String url = (String) server.get("url");
		String sha1 = (String) server.get("sha1");
		if (Files.isRegularFile(dest) && Files.size(dest) > 0) {
			// trust a cached server jar only if its sha1 still matches
			if (sha1 == null || sha1.equalsIgnoreCase(Util.sha1(dest))) return;
		}
		log.accept("[patched] downloading " + fa.mcVersion + " server jar …");
		http.downloadToFile(url, dest);
		ForgeTool.verifySha1(dest, sha1, fa.mcVersion + " server.jar");
	}

	/** Overlay patched classes onto the clean merge, strip signatures, keep only the manifest main section. */
	static void overlay(Path clean, Path patchedSubset, Path out) throws IOException {
		LinkedHashMap<String, byte[]> pe = Zips.readAll(patchedSubset);
		LinkedHashMap<String, byte[]> cleanAll = Zips.readAll(clean);
		LinkedHashMap<String, byte[]> result = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> e : cleanAll.entrySet()) {
			String n = e.getKey();
			if (Zips.isSignatureFile(n)) continue;
			if (n.equals("META-INF/MANIFEST.MF")) {
				result.put(n, manifestMainSection(e.getValue()));
				continue;
			}
			result.put(n, pe.containsKey(n) ? pe.get(n) : e.getValue());
		}
		for (Map.Entry<String, byte[]> e : pe.entrySet()) {
			if (!result.containsKey(e.getKey())) result.put(e.getKey(), e.getValue());
		}
		Zips.writeJar(out, result);
	}

	/** Keep only the manifest's main section (re-serialized classes fail the original per-file SHA digests). */
	private static byte[] manifestMainSection(byte[] raw) {
		String text = new String(raw, StandardCharsets.UTF_8);
		String main = text.split("\r\n\r\n", 2)[0];
		main = main.split("\n\n", 2)[0];
		main = rstrip(main) + "\r\n";
		return main.getBytes(StandardCharsets.UTF_8);
	}

	// ---- download helpers ----

	private Path dl(String coordinate) throws IOException {
		String file = fileName(coordinate);
		Path dest = dlDir.resolve(file);
		http.ensureWithFallback(fa.forgeUrl(coordinate), fa.centralUrl(coordinate), dest);
		return dest;
	}

	private Path dlCentral(String coordinate) throws IOException {
		String file = fileName(coordinate);
		Path dest = dlDir.resolve(file);
		http.ensureWithFallback(fa.centralUrl(coordinate), fa.forgeUrl(coordinate), dest);
		return dest;
	}

	private static String fileName(String coordinate) {
		String rel = Util.coordinateToPath(ForgeArtifacts.stripExtension(coordinate));
		return rel.substring(rel.lastIndexOf('/') + 1);
	}

	private static String findLib(ForgeArtifacts.UserdevConfig cfg, String group, String artifact, String dflt) {
		for (String lib : cfg.libraries) {
			String[] p = lib.split(":");
			if (p.length >= 2 && p[0].equals(group) && p[1].equals(artifact)) return lib;
		}
		return dflt;
	}

	private static String asmVersion(ForgeArtifacts.UserdevConfig cfg) {
		for (String lib : cfg.libraries) {
			String[] p = lib.split(":");
			if (p.length >= 3 && p[0].equals("org.ow2.asm") && p[1].equals("asm")) return p[2];
		}
		return "9.9.1";
	}

	private static String rstrip(String s) {
		int end = s.length();
		while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
		return s.substring(0, end);
	}
}
