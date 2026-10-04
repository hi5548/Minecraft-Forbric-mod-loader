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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Version model + Maven URL layer + userdev {@code config.json} reader for the install-time Forge build. Everything
 * version-specific is read from the Forge {@code -userdev} jar's {@code config.json} (its {@code libraries},
 * {@code universal}, {@code binpatcher}, {@code binpatches}, {@code ats}), so retargeting a different Forge build is
 * data, not code — matching the pins in {@code assemble-minecraftforge-runtime.sh}/{@code build-patched-forge.sh}.
 */
final class ForgeArtifacts {

	static final String FORGE_MVN = "https://maven.minecraftforge.net";
	static final String CENTRAL = "https://repo1.maven.org/maven2";

	/** Forbric coordinate group for the built (never-redistributed) artifacts staged into {@code libraries/}. */
	static final String OUT_GROUP = "net.forbric";

	final String mcVersion;     // e.g. "1.21.1" (Pins.MINECRAFT)
	final String forgeVersion;  // "1.21.1-52.1.16" (Pins.FORGE)
	final String fmlVersion;    // "52.1.16"  (the part after '-' — the FML/language-provider version domain)

	ForgeArtifacts(String mcVersion, String forgeVersion) {
		this.mcVersion = mcVersion;
		this.forgeVersion = forgeVersion;
		int dash = forgeVersion.indexOf('-');
		this.fmlVersion = dash >= 0 ? forgeVersion.substring(dash + 1) : forgeVersion;
	}

	// ---- Maven URLs (Forge Maven primary, Central fallback) ----

	/** Forge-Maven URL for a coordinate (a possibly-classified {@code g:a:v[:classifier]}, {@code @ext} stripped). */
	String forgeUrl(String coordinate) {
		return FORGE_MVN + "/" + Util.coordinateToPath(stripExtension(coordinate));
	}

	/** Maven-Central URL for a coordinate — the scripts' curl fallback host. */
	String centralUrl(String coordinate) {
		return CENTRAL + "/" + Util.coordinateToPath(stripExtension(coordinate));
	}

	/** The {@code forge-<ver>-userdev.jar} coordinate/URL — the source of {@code config.json}, patches, and ATs. */
	String userdevCoordinate() {
		return "net.minecraftforge:forge:" + forgeVersion + ":userdev";
	}

	// ---- output coordinates (staged into libraries/ under net.forbric) ----

	/** The merged Knot-loaded Forge runtime jar ({@code fabric.mod.json id="forge"}). */
	String runtimeCoordinate() {
		return OUT_GROUP + ":forge-runtime:" + forgeVersion;
	}

	/** The Forge-patched, Mojmap-named Minecraft game jar (never redistributed). */
	String patchedMcCoordinate() {
		return OUT_GROUP + ":patched-mc-forge:" + forgeVersion;
	}

	/**
	 * The Forge installer jar. Carries {@code data/client.lzma} and {@code data/server.lzma} — the per-side
	 * binpatches the official install applies to the renamed (Mojmap) jars — plus the {@code install_profile}
	 * whose processor list the official-pipeline port mirrors.
	 */
	String installerCoordinate() {
		return "net.minecraftforge:forge:" + forgeVersion + ":installer";
	}

	/**
	 * The PRODUCTION universal coordinate, read from the Forge installer's {@code version.json}.
	 *
	 * <p>For an obfuscated game the userdev config names {@code ...:universal-srg}, whose members are SRG names
	 * that dangle against a Mojmap base — measured at 3,628 of 3,664 dangling references in the first 1.21.1
	 * link check. What an actual install puts on the classpath is the plain {@code :universal} the installer
	 * profile lists, so that list is the source rather than a classifier rule.
	 *
	 * @return the production coordinate, or null when the profile does not name one (26.2's does)
	 */
	String productionUniversal(Http http, Path dlDir) throws IOException {
		String coordinate = installerCoordinate();
		Path installer = dlDir.resolve(Util.coordinateToPath(stripExtension(coordinate))
				.substring(Util.coordinateToPath(stripExtension(coordinate)).lastIndexOf('/') + 1));
		http.ensureWithFallback(forgeUrl(coordinate), centralUrl(coordinate), installer);
		byte[] raw = Zips.readEntry(installer, "version.json");
		if (raw == null) return null;
		Object root = Json.parse(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
		if (!(root instanceof Map<?, ?> map)) return null;
		Object libraries = map.get("libraries");
		if (!(libraries instanceof List<?> list)) return null;
		String prefix = "net.minecraftforge:forge:" + forgeVersion + ":";
		for (Object entry : list) {
			if (!(entry instanceof Map<?, ?> lib)) continue;
			Object name = lib.get("name");
			if (name instanceof String s && s.startsWith(prefix) && s.substring(prefix.length()).equals("universal")) {
				return s;
			}
		}
		return null;
	}

	/** The Forbric bridge {@code @Mod} that opens the Fabric-content window inside Forge's registration span. */
	String bridgeCoordinate() {
		return OUT_GROUP + ":forbric-bridge:" + forgeVersion;
	}

	/** Strip a Maven {@code @extension} suffix (e.g. {@code :universal@jar} → {@code :universal}). */
	static String stripExtension(String coordinate) {
		int at = coordinate.indexOf('@');
		return at >= 0 ? coordinate.substring(0, at) : coordinate;
	}

	// ---- userdev config.json ----

	/** The subset of {@code config.json} the builders need. */
	static final class UserdevConfig {
		final List<String> libraries;    // runtime lib coordinates (superset of the merged-runtime inputs)
		final String universalCoordinate; // net.minecraftforge:forge:<fv>:universal (extension stripped)
		final String binpatchesEntry;    // "joined.lzma" — the binpatch resource inside the userdev jar
		final List<String> ats;          // ["ats/accesstransformer.cfg"] — AT resources inside the userdev jar
		final String binpatcherCoordinate; // net.minecraftforge:binarypatcher:1.3.0:fatjar
		final List<String> binpatcherArgs;  // ["--clean","{clean}","--output","{output}","--apply","{patch}"]

		UserdevConfig(List<String> libraries, String universalCoordinate, String binpatchesEntry, List<String> ats,
		              String binpatcherCoordinate, List<String> binpatcherArgs) {
			this.libraries = libraries;
			this.universalCoordinate = universalCoordinate;
			this.binpatchesEntry = binpatchesEntry;
			this.ats = ats;
			this.binpatcherCoordinate = binpatcherCoordinate;
			this.binpatcherArgs = binpatcherArgs;
		}
	}

	/** Read {@code config.json} out of a downloaded Forge {@code -userdev} jar. */
	@SuppressWarnings("unchecked")
	static UserdevConfig readConfig(Path userdevJar) throws IOException {
		byte[] bytes = Zips.readEntry(userdevJar, "config.json");
		if (bytes == null) throw new IOException("no config.json in " + userdevJar.getFileName() + " (not a Forge userdev jar?)");
		Map<String, Object> root;
		try {
			root = (Map<String, Object>) Json.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
		} catch (RuntimeException e) {
			throw new IOException("malformed config.json in " + userdevJar.getFileName() + ": " + e.getMessage(), e);
		}

		List<String> libraries = asStringList(root.get("libraries"));
		String universal = stripExtension(str(root.get("universal"), "config.universal"));

		Object binpatches = root.get("binpatches");
		String binpatchesEntry = binpatches instanceof String ? (String) binpatches : "joined.lzma";

		List<String> ats = asStringList(root.get("ats"));
		if (ats.isEmpty()) ats = List.of("ats/accesstransformer.cfg");

		Map<String, Object> binpatcher = root.get("binpatcher") instanceof Map
				? (Map<String, Object>) root.get("binpatcher") : Map.of();
		String binpatcherCoord = binpatcher.get("version") instanceof String
				? (String) binpatcher.get("version") : "net.minecraftforge:binarypatcher:1.3.0:fatjar";
		List<String> binpatcherArgs = asStringList(binpatcher.get("args"));
		if (binpatcherArgs.isEmpty()) {
			binpatcherArgs = List.of("--clean", "{clean}", "--output", "{output}", "--apply", "{patch}");
		}

		return new UserdevConfig(libraries, universal, binpatchesEntry, ats, binpatcherCoord, binpatcherArgs);
	}

	private static List<String> asStringList(Object o) {
		List<String> out = new ArrayList<>();
		if (o instanceof List) {
			for (Object e : (List<?>) o) if (e instanceof String) out.add((String) e);
		}
		return out;
	}

	private static String str(Object o, String what) throws IOException {
		if (!(o instanceof String)) throw new IOException("expected a string for " + what);
		return (String) o;
	}
}
