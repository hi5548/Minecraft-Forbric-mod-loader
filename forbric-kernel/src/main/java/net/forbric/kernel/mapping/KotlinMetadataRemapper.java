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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import net.forbric.kernel.util.ByteScan;
import net.forbric.kernel.util.ForbricLog;

/**
 * Rewrites the game names a Kotlin class carries in its {@code @kotlin.Metadata} annotation — the namespace the
 * bytecode remapper cannot reach, because it lives in an annotation's string table rather than in a descriptor.
 *
 * <h2>Why {@code d2} is a namespace</h2>
 *
 * <p>Kotlin compiles every declaration's JVM types into the {@code @Metadata} annotation twice: as the protobuf in
 * {@code d1}, and as a plain string table in {@code d2} that {@code d1} indexes by position. A Fabric mod is
 * compiled against <b>intermediary</b>, so its {@code d2} holds entries like
 * {@code Lnet/minecraft/class_2960;} and {@code ()Lnet/minecraft/class_2960;} while the remapped bytecode of the
 * same class says {@code net.minecraft.resources.ResourceLocation}. On Fabric that gap is invisible — the game's
 * runtime namespace IS intermediary — but the kernel's merged base runs <b>named</b> (Mojmap), so
 * {@code kotlin-reflect} reads a name the game does not have and dies. Measured on
 * {@code Cobblemon-fabric-1.8.1+1.21.1.jar} (the real bytes in the corpus): Cobblemon's Fabric entrypoint aborted
 * at {@code SpeciesAdditions.<clinit>} with
 * {@code ClassNotFoundException: net.minecraft.class_2960 (game-side, but not found in any kernel-owned jar)} out
 * of {@code KDeclarationContainerImpl.parseType}, so none of its registrations ever ran.
 *
 * <p>{@code d1} is deliberately left alone: it is protobuf whose type entries reference {@code d2} <em>by array
 * index</em>, so replacing {@code d2} entries in place changes names without moving a single reference.
 *
 * <p>Only {@code d2} entries that are JVM descriptors or internal class names are considered, and the mapping is
 * the same {@link ForbricMappings#mapClass} fallback the other post-passes use: a name the spine does not know
 * ({@code kotlin/...}, the mod's own classes) comes back unchanged, so member names and Kotlin's own types are
 * never touched.
 */
public final class KotlinMetadataRemapper {
	private static final byte[] METADATA_NEEDLE = ByteScan.needle("kotlin/Metadata");
	private static final String METADATA_DESC = "Lkotlin/Metadata;";
	private static final String STRING_TABLE = "d2";

	private KotlinMetadataRemapper() {
	}

	/**
	 * Rewrites the leftover Kotlin-metadata names in {@code jar} in place; returns how many classes changed, and
	 * does not write the jar at all when none did.
	 */
	public static int translate(Path jar, ForbricMappings spine) throws IOException {
		Map<String, byte[]> entries = new LinkedHashMap<>();

		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (Enumeration<? extends ZipEntry> it = zip.entries(); it.hasMoreElements(); ) {
				ZipEntry entry = it.nextElement();
				if (entry.isDirectory()) continue;
				try (InputStream in = zip.getInputStream(entry)) {
					entries.put(entry.getName(), in.readAllBytes());
				}
			}
		}

		List<String> rewritten = new ArrayList<>();
		for (Map.Entry<String, byte[]> entry : new ArrayList<>(entries.entrySet())) {
			if (!entry.getKey().endsWith(".class")) continue;

			byte[] bytes = entry.getValue();
			if (!ByteScan.isClass(bytes) || !ByteScan.contains(bytes, METADATA_NEEDLE)) continue;

			byte[] fixed = translateClass(bytes, spine);
			if (fixed == null) continue;
			entries.put(entry.getKey(), fixed);
			rewritten.add(entry.getKey());
		}

		if (rewritten.isEmpty()) return 0;

		Path tmp = jar.resolveSibling(jar.getFileName() + ".kotlin.tmp");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				out.putNextEntry(new ZipEntry(entry.getKey()));
				out.write(entry.getValue());
				out.closeEntry();
			}
		}
		Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);

		ForbricLog.info("[Forbric/Mapping] %s: %d class(es) had game names in their Kotlin @Metadata rewritten — "
				+ "kotlin-reflect resolves types from that string table, which a bytecode remapper never sees, and "
				+ "the runtime namespace here is named where the mod was compiled against intermediary",
				jar.getFileName(), rewritten.size());
		return rewritten.size();
	}

	/** The class with its {@code @Metadata} string table renamed, or null when nothing needed renaming. */
	static byte[] translateClass(byte[] bytes, ForbricMappings spine) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		Remapper names = new Remapper() {
			@Override
			public String map(String internalName) {
				return spine.mapClass(ForbricMappings.INTERMEDIARY, ForbricMappings.NAMED, internalName);
			}
		};

		boolean changed = false;
		changed |= rewrite(node.visibleAnnotations, names, spine);
		changed |= rewrite(node.invisibleAnnotations, names, spine);
		if (!changed) return null;

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** Fixes every {@code @kotlin.Metadata}'s {@code d2} array in {@code annotations}; true when one changed. */
	private static boolean rewrite(List<AnnotationNode> annotations, Remapper names, ForbricMappings spine) {
		if (annotations == null) return false;
		boolean changed = false;
		for (AnnotationNode annotation : annotations) {
			if (!METADATA_DESC.equals(annotation.desc) || annotation.values == null) continue;
			for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
				if (!STRING_TABLE.equals(annotation.values.get(i))) continue;
				if (annotation.values.get(i + 1) instanceof List<?> table) changed |= rewriteTable(table, names, spine);
			}
		}
		return changed;
	}

	@SuppressWarnings("unchecked")
	private static boolean rewriteTable(List<?> table, Remapper names, ForbricMappings spine) {
		List<Object> entries = (List<Object>)table;
		boolean changed = false;
		for (int i = 0; i < entries.size(); i++) {
			if (!(entries.get(i) instanceof String text)) continue;
			String mapped = mapEntry(text, names, spine);
			if (mapped.equals(text)) continue;
			entries.set(i, mapped);
			changed = true;
		}
		return changed;
	}

	/**
	 * One {@code d2} entry, in the only three shapes it takes. A descriptor ({@code L…;}, {@code […},
	 * {@code (…)…}) goes through ASM's descriptor remapper so the component type of an array is covered too; a
	 * bare internal name goes through the class map; anything else — every member name, every Kotlin built-in —
	 * is left alone.
	 */
	private static String mapEntry(String text, Remapper names, ForbricMappings spine) {
		if (text.isEmpty()) return text;
		char first = text.charAt(0);
		if (first == 'L' || first == '[' || first == '(') {
			try {
				return names.mapDesc(text);
			} catch (RuntimeException notADescriptor) {
				return text;
			}
		}
		if (text.indexOf('/') >= 0) {
			return spine.mapClass(ForbricMappings.INTERMEDIARY, ForbricMappings.NAMED, text);
		}
		return text;
	}
}
