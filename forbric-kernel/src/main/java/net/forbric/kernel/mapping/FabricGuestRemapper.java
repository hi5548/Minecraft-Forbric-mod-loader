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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.fabricmc.tinyremapper.IMappingProvider;

import net.forbric.kernel.util.ForbricLog;

/**
 * The W2 remap stage: renames a Fabric guest jar from the <b>intermediary</b> namespace its bytecode was compiled
 * against to the <b>named</b> (Mojmap) namespace the kernel's merged base runs in, before the jar joins the owned
 * classpath.
 *
 * <p>On 1.21.1 this is not optional: the game ships obfuscated, Fabric mods ship compiled against intermediary
 * (measured on the fixture pack: 49 of 50 jars, and fabric-api's own modules are nested inside its outer jar), and
 * the kernel runs Mojmap — the namespace the Forge and NeoForge halves of the same instance already speak. Without
 * this stage a Fabric mod's classes are defined under {@code class_…}/{@code method_…} names that resolve to
 * nothing in the merged base.
 *
 * <p><b>Every jar the loader defines, including JiJ children.</b> {@code FabricModDiscovery} already extracted a
 * parent's nested jars and put each on the classpath (they are the ones the transfer bridge is compiled against),
 * so the caller hands this stage that same flattened list and each entry is remapped on its own. The nested jars
 * still embedded in a parent as resources are copied through untouched — nothing defines classes out of them.
 *
 * <p><b>The classpath has to be in the SOURCE namespace.</b> tiny-remapper renames a member reference only after
 * resolving the member in the declaring class it has read, and it looks that class up by the name the reference
 * uses — here the intermediary name. The game jars a launch has are in the TARGET namespace (the merged base is
 * Mojmap), so passing them straight in would leave every member reference into the game untouched: a stage that
 * renames class names and nothing else, and a guest that then dies on the first {@code NoSuchFieldError}. So the
 * game jars are first remapped <em>into</em> intermediary, cached beside the outputs, and those are the classpath.
 * Measured on the 1.21.1 fixture pack: 222 distinct intermediary member names survived with the Mojmap classpath,
 * 11 with the intermediary one (all 11 are the guest's own {@code @Shadow} members — see the class docs on what
 * this stage deliberately does not do).
 *
 * <p><b>Cached by content.</b> Outputs are keyed on the mapping data plus the input jar's SHA-256, through
 * {@link ForbricCache}, so an unchanged jar — guest or game — is remapped once ever rather than on every boot. The
 * cache lives in the caller-chosen directory (the kernel uses {@code <gameDir>/.forbric-kernel/remap}) and its
 * entries are ordinary jars: deleting them costs one remap, and nothing else reads them.
 *
 * <p>A boot with no mapping data ({@link FabricGuestMappings#none()}) or no Fabric guests is the identity, which
 * is the 26.2 shape. Mapping data that was staged but cannot be read throws out of here: see
 * {@link FabricGuestMappings#mappings()}.
 *
 * <h2>What this stage deliberately does not rename</h2>
 * A mixin's OWN members (its {@code @Shadow} field or {@code @Invoker} method) keep the intermediary name they
 * were written with, because they are not references into the game: they are declarations of the guest, and Mixin
 * binds them to the target's member by name at class-load time. Renaming them correctly is a mixin-aware pass
 * (the refmap and the target's own member names decide it, and Mixin may be told a member name outright in an
 * annotation string, which no bytecode remapper rewrites) — that is the follow-up this stage's classpath fix
 * narrows the problem to: 11 declarations in the reference pack, all of them shadowed game members.
 */
public final class FabricGuestRemapper {
	/**
	 * Bumped whenever this stage's OUTPUT changes for the same mapping data and the same input jar.
	 *
	 * <p>The rest of the key is content-addressed — the two mapping files and the guest jar's SHA-256 — which is
	 * right for an input change and wrong for a CODE change: a kernel upgrade then silently reuses jars produced by
	 * the previous stage. Measured 2026-10-03: a warm shared remap cache carried fabric-api modules whose refmap
	 * values and selector strings predated {@link MixinNames}' refmap-aware pass at all, so a run reported the old
	 * selector-resolution losses while the new stage was never given the jar. Bumping this constant is the one-line
	 * answer, and it costs one re-remap of the tree per cache directory.
	 */
	private static final String REMAP_VERSION = "1.21.1-7-widener-suffix-case";

	private FabricGuestRemapper() {
	}

	/**
	 * Remaps every guest jar, returning the jars that should go on the classpath in their place.
	 *
	 * @param jars          the Fabric guests (and their extracted JiJ children) in classpath order
	 * @param mappings      this launch's mapping data, or null
	 * @param cacheDir      where remapped jars are cached, created on demand
	 * @param gameClasspath game + library jars in the RUNTIME namespace, used to resolve inherited members; they
	 *                      are converted to the source namespace internally. May be empty for a guest that only
	 *                      references members it declares itself.
	 */
	public static List<Path> remapAll(List<Path> jars, FabricGuestMappings mappings, Path cacheDir,
			List<Path> gameClasspath) throws IOException {
		if (jars.isEmpty() || mappings == null || !mappings.present()) return jars;

		ForbricMappings spine = mappings.mappings();
		ForbricCache cache = new ForbricCache(cacheDir);
		// The mapping data's own hash is part of every key: restaging a different intermediary build for the same
		// game version must invalidate every cached output, not silently reuse jars remapped with the old one. The
		// stage's own version rides along for the other half of the same problem — see REMAP_VERSION.
		String mappingsKey = ForbricCache.key(REMAP_VERSION, mappings.intermediary(), mappings.mojmap());
		List<Path> sourceClasspath = sourceNamespaceClasspath(gameClasspath, spine, cacheDir, mappingsKey);
		IMappingProvider provider = ForgeModRemapper.provider(spine, ForbricMappings.INTERMEDIARY,
				ForbricMappings.NAMED);

		List<Path> remapped = new ArrayList<>(jars.size());

		for (Path jar : jars) {
			Path out = cache.resolve(stem(jar), ForbricCache.key(mappingsKey, jar), ".jar");

			if (ForbricCache.isCached(out)) {
				ForbricLog.debug("[Forbric/Mapping] reusing remapped guest %s", out.getFileName());
			} else {
				// Mixin-aware: the guest's annotation strings and its own shadowed declarations are names too.
				IMappingProvider jarProvider = MixinShadowMembers.withRenames(provider, jar, spine);
				ForgeModRemapper.remapJar(jar, out, jarProvider, sourceClasspath, true);
				// Mixin resolves names through the mod's refmap before it looks at the game, so the refmap is a
				// namespace too: its selector strings, and the values of its refmap, are intermediary and must
				// become named (see MixinNames — the extension translates neither).
				MixinNames.translate(out, spine);
				// And the access widener is a namespace too: its directives name class_*/method_* the runtime
				// class does not have, so a pass that never rewrites it widens nothing (see AccessWidenerRemapper).
				net.forbric.kernel.access.AccessWidenerRemapper.remap(out, spine);
				ForbricLog.info("[Forbric/Mapping] remapped %s → %s (%s → %s, mixin annotations included)",
						jar.getFileName(), out.getFileName(), ForbricMappings.INTERMEDIARY, ForbricMappings.NAMED);
			}

			remapped.add(out);
		}

		return remapped;
	}

	/**
	 * The classpath as tiny-remapper needs it: the source namespace. Only the jars the mapping actually covers are
	 * converted — detected from their entry names, without reading a class — because a library (brigadier, gson)
	 * and a Forge/NeoForge runtime are already correct under their own names and converting them would cost a
	 * pass each for nothing.
	 */
	private static List<Path> sourceNamespaceClasspath(List<Path> classpath, ForbricMappings spine,
			Path cacheDir, String mappingsKey) throws IOException {
		IMappingProvider toSource = ForgeModRemapper.provider(spine, ForbricMappings.NAMED,
				ForbricMappings.INTERMEDIARY);
		ForbricCache cache = new ForbricCache(cacheDir.resolve("classpath"));
		List<Path> converted = new ArrayList<>(classpath.size());

		for (Path jar : classpath) {
			if (!holdsGameClasses(jar, spine)) {
				converted.add(jar);
				continue;
			}

			Path out = cache.resolve(stem(jar) + "-" + ForbricMappings.INTERMEDIARY,
					ForbricCache.key(mappingsKey, jar), ".jar");

			if (!ForbricCache.isCached(out)) {
				ForgeModRemapper.remapJar(jar, out, toSource, List.of());
				ForbricLog.info("[Forbric/Mapping] prepared %s as a %s remap classpath (%s)", jar.getFileName(),
						ForbricMappings.INTERMEDIARY, out.getFileName());
			}

			converted.add(out);
		}

		return converted;
	}

	/** Whether any class in the jar is one the mapping knows in the namespace the jar is written in. */
	private static boolean holdsGameClasses(Path jar, ForbricMappings spine) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
				String name = entries.nextElement().getName();

				if (!name.endsWith(".class")) continue;

				if (spine.knowsClass(ForbricMappings.NAMED, name.substring(0, name.length() - ".class".length()))) {
					return true;
				}
			}
		}

		return false;
	}

	/** The jar's file name without its extension, so a cache entry still says which mod or jar it holds. */
	private static String stem(Path jar) {
		String name = jar.getFileName().toString();
		return name.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
	}
}
