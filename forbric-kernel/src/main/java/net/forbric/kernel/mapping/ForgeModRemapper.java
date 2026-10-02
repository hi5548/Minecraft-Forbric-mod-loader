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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;

import net.forbric.api.Ecosystem;

/**
 * Drives tiny-remapper over a guest mod jar with {@link ForbricMappings}, in either direction the spine serves.
 *
 * <p>Two callers, two directions, one engine:
 * <ul>
 *   <li>{@link #remapJar(Path, Path, ForbricMappings, List)} with {@link #mappingProvider} — Mojmap to
 *       intermediary, the weld-era direction, when the runtime is intermediary-named.</li>
 *   <li>{@code provider(mappings, INTERMEDIARY, NAMED)} — intermediary to Mojmap, which is what the sovereign
 *       kernel needs on 1.21.x: the merged base is Mojmap-named and a Fabric guest ships compiled against
 *       intermediary, so its bytecode is renamed before it joins the classpath ({@code FabricGuestRemapper}).
 *       The reverse direction also produces the Mojmap game jar used as a remap classpath.</li>
 * </ul>
 *
 * tiny-remapper resolves inheritance from the game/library jars passed as the remap classpath (in the TARGET
 * namespace), so inherited members map correctly too.
 */
public final class ForgeModRemapper {
	private ForgeModRemapper() {
	}

	/**
	 * Builds a tiny-remapper mapping provider that renames a Forge mod from the named (Mojmap) namespace to
	 * intermediary, derived from the merged mapping tree.
	 */
	public static IMappingProvider mappingProvider(ForbricMappings mappings) {
		return provider(mappings, ForbricMappings.NAMED, ForbricMappings.INTERMEDIARY);
	}

	/**
	 * Builds a tiny-remapper provider for an arbitrary namespace pair held in the merged tree
	 * ({@code named}, {@code official}, {@code intermediary}). For example {@code provider(m, "official", "named")}
	 * produces the mapping that deobfuscates the vanilla game jar to Mojmap (used to build the remap classpath).
	 */
	public static IMappingProvider provider(ForbricMappings mappings, String fromNs, String toNs) {
		MappingTree tree = mappings.tree();
		String srcNs = tree.getSrcNamespace();

		boolean fromIsSrc = fromNs.equals(srcNs);
		boolean toIsSrc = toNs.equals(srcNs);
		int fromId = fromIsSrc ? -1 : tree.getNamespaceId(fromNs);
		int toId = toIsSrc ? -1 : tree.getNamespaceId(toNs);

		if (!fromIsSrc && fromId < 0) throw new IllegalArgumentException("unknown source namespace: " + fromNs);
		if (!toIsSrc && toId < 0) throw new IllegalArgumentException("unknown target namespace: " + toNs);

		return acceptor -> {
			for (MappingTree.ClassMapping cls : tree.getClasses()) {
				String src = fromIsSrc ? cls.getSrcName() : cls.getName(fromId);
				String dst = toIsSrc ? cls.getSrcName() : cls.getName(toId);
				if (src == null || dst == null) continue;

				acceptor.acceptClass(src, dst);

				for (MappingTree.FieldMapping field : cls.getFields()) {
					String fieldDst = toIsSrc ? field.getSrcName() : field.getName(toId);
					String fieldName = fromIsSrc ? field.getSrcName() : field.getName(fromId);
					String fieldDesc = fromIsSrc ? field.getSrcDesc() : field.getDesc(fromId);
					if (fieldDst == null || fieldName == null) continue;

					acceptor.acceptField(new IMappingProvider.Member(src, fieldName, fieldDesc), fieldDst);
				}

				for (MappingTree.MethodMapping method : cls.getMethods()) {
					String methodDst = toIsSrc ? method.getSrcName() : method.getName(toId);
					String methodName = fromIsSrc ? method.getSrcName() : method.getName(fromId);
					String methodDesc = fromIsSrc ? method.getSrcDesc() : method.getDesc(fromId);
					if (methodDst == null || methodName == null) continue;

					acceptor.acceptMethod(new IMappingProvider.Member(src, methodName, methodDesc), methodDst);
				}
			}
		};
	}

	/**
	 * Remaps {@code input} to {@code output}, mapping Mojmap → intermediary. Non-class files are copied through.
	 *
	 * @param remapClasspath the game + library jars (in the named namespace) used for inheritance resolution;
	 *                       may be empty for simple mods that only reference declared members directly.
	 */
	public static void remapJar(Path input, Path output, ForbricMappings mappings, List<Path> remapClasspath) throws IOException {
		remapJar(input, output, mappingProvider(mappings), remapClasspath);
	}

	/** Remaps {@code input} to {@code output} using an explicit provider (e.g. {@code provider(m, "official", "named")}). */
	public static void remapJar(Path input, Path output, IMappingProvider provider, List<Path> remapClasspath) throws IOException {
		remapJar(input, output, provider, remapClasspath, false);
	}

	/**
	 * PORT(1.21.1): as above, optionally running tiny-remapper's own {@code MixinExtension}.
	 *
	 * <p>tiny-remapper rewrites classes, members, descriptors and class literals — bytecode references. A Fabric
	 * guest's mixin ANNOTATION STRINGS are not references: {@code @Mixin(targets = "net.minecraft.class_245")},
	 * {@code @Inject(method = "method_1234(…)V")}, {@code @Accessor("field_5678")}, {@code @At(target = "…")} and
	 * the mixin's own {@code @Shadow}/{@code @Overwrite} declarations keep the intermediary names they were written
	 * with. Mixin then looks for a class or member that does not exist in the merged base ("@Mixin target
	 * net.minecraft.class_245 was not found"), the mixin is left out, and the kernel records a required finding —
	 * measured on ferrite-core 7.0.3, whose {@code BlockStateCacheMixin} was suppressed for exactly that reason.
	 *
	 * <p>The extension is the mapping-aware pass for those strings, shipped inside the pinned tiny-remapper 0.14.0
	 * ({@code net.fabricmc.tinyremapper.extension.mixin.MixinExtension}), so this is the engine's own answer rather
	 * than a hand-rolled annotation walk. It reads the same {@link IMappingProvider} the remap does. Only guest
	 * remaps want it: the reverse (named→intermediary) classpath conversion and the weld-era Forge remaps carry no
	 * mixins, and running it there would inspect every annotation for nothing.
	 */
	public static void remapJar(Path input, Path output, IMappingProvider provider, List<Path> remapClasspath,
			boolean mixinAnnotations) throws IOException {
		Files.deleteIfExists(output);

		TinyRemapper.Builder builder = TinyRemapper.newRemapper()
				.withMappings(provider)
				.renameInvalidLocals(false)
				.threads(1);
		if (mixinAnnotations) {
			builder.extension(new net.fabricmc.tinyremapper.extension.mixin.MixinExtension());
		}
		TinyRemapper remapper = builder.build();

		try (OutputConsumerPath out = new OutputConsumerPath.Builder(output).build()) {
			out.addNonClassFiles(input);

			if (remapClasspath != null) {
				for (Path cp : remapClasspath) {
					remapper.readClassPath(cp);
				}
			}

			remapper.readInputs(input);
			remapper.apply(out);
		} finally {
			remapper.finish();
		}

		stripSigningMetadata(output);
	}

	/**
	 * PORT(1.21.1): drops the input jar's signing metadata from a remapped output.
	 *
	 * <p>A remap rewrites class bytes, and a jar that carried per-entry digests in its manifest fails verification
	 * the first time a rewritten class is read: measured on the released ferrite-core jar, whose manifest holds
	 * {@code SHA-384-Digest} per entry — Mixin's own reader is a verifying {@code JarFile} and died with
	 * "SHA-384 digest error for malte0811/ferritecore/mixin/accessors/ArrayVSAccess.class", which surfaced as
	 * "Error initialising mixin config ferritecore.accessors.mixin.json". Those digests describe the PRE-remap
	 * bytes and cannot be recomputed without the signing key, so they are removed along with the signature files
	 * ({@code META-INF/*.SF|.DSA|.RSA|.EC}, {@code META-INF/SIG-*}). The manifest's other attributes are kept: a
	 * mod that reads {@code Implementation-Version} still finds it.
	 *
	 * <p>Harmless on unsigned jars — there is nothing to remove — which is why it runs for every remap, guest,
	 * classpath conversion or weld-era alike.
	 */
	private static void stripSigningMetadata(Path jar) throws IOException {
		Path tmp = jar.resolveSibling(jar.getFileName() + ".tmp");
		boolean changed = false;

		try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(Files.newInputStream(jar));
				java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(tmp))) {
			java.util.zip.ZipEntry entry;
			while ((entry = in.getNextEntry()) != null) {
				String name = entry.getName();
				if (isSignatureFile(name)) {
					changed = true;
					continue;
				}

				byte[] bytes;
				if ("META-INF/MANIFEST.MF".equalsIgnoreCase(name)) {
					bytes = withoutDigests(in.readAllBytes());
					changed = true;
				} else {
					bytes = in.readAllBytes();
				}

				java.util.zip.ZipEntry copy = new java.util.zip.ZipEntry(name);
				copy.setTime(entry.getTime());
				out.putNextEntry(copy);
				out.write(bytes);
				out.closeEntry();
			}
		}

		if (changed) {
			Files.move(tmp, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} else {
			Files.deleteIfExists(tmp);
		}
	}

	private static boolean isSignatureFile(String name) {
		String upper = name.toUpperCase(java.util.Locale.ROOT);
		if (!upper.startsWith("META-INF/")) return false;
		if (upper.startsWith("META-INF/SIG-")) return true;
		return upper.endsWith(".SF") || upper.endsWith(".DSA") || upper.endsWith(".RSA") || upper.endsWith(".EC");
	}

	/** The manifest without any Digest attribute or per-entry section: those describe bytes this remap replaced. */
	private static byte[] withoutDigests(byte[] manifestBytes) throws IOException {
		java.util.jar.Manifest manifest = new java.util.jar.Manifest(new java.io.ByteArrayInputStream(manifestBytes));

		java.util.jar.Attributes main = manifest.getMainAttributes();
		java.util.List<Object> stale = new java.util.ArrayList<>();
		for (Object key : main.keySet()) {
			if (key instanceof java.util.jar.Attributes.Name attribute
					&& attribute.toString().toUpperCase(java.util.Locale.ROOT).contains("DIGEST")) {
				stale.add(key);
			}
		}
		for (Object key : stale) main.remove(key);
		manifest.getEntries().clear();

		java.io.ByteArrayOutputStream rewritten = new java.io.ByteArrayOutputStream();
		manifest.write(rewritten);
		return rewritten.toByteArray();
	}

	/** @see #automaticModuleName(String, Ecosystem) */
	public static String automaticModuleName(String modId) {
		return automaticModuleName(modId, Ecosystem.FORGE);
	}

	/**
	 * Deterministic, always-valid module name for a wrapped Forge-family mod. The two runtimes resolve a mod's
	 * module differently, so the name must match what each looks up:
	 * <ul>
	 *   <li><b>MinecraftForge</b> — {@code FMLModContainer} resolves by the ModFile's {@code moduleName()}
	 *       (the securejar name); Forbric namespaces it {@code forbricmod.<id>} to avoid collisions.</li>
	 *   <li><b>NeoForge</b> — {@code FMLModContainer} resolves by {@code IModFile.getId()}, which is the mod's
	 *       toml {@code modId}; the layer module must therefore be named EXACTLY that id (NeoForge modIds match
	 *       {@code [a-z][a-z0-9_]*}, already valid Java module names — no prefix, no sanitize needed).</li>
	 * </ul>
	 */
	public static String automaticModuleName(String modId, Ecosystem ecosystem) {
		String seg = modId == null || modId.isEmpty() ? "mod" : modId.replaceAll("[^A-Za-z0-9_]", "_");
		if (Character.isDigit(seg.charAt(0))) seg = "_" + seg;
		if (ecosystem == Ecosystem.NEOFORGE) return seg;
		return "forbricmod." + seg;
	}
}
