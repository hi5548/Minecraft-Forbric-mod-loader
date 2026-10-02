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
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.adapter.MappingNsRenamer;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

/**
 * The Forbric mapping spine: it exposes the game in all three namespaces a mod can be compiled against —
 * Mojang's "<b>named</b>" (Mojmap), Fabric's <b>intermediary</b>, and the shared obfuscated column.
 *
 * <p>It is built by joining two permitted, non-MCP sources on that shared obfuscated column:
 * <ul>
 *   <li>Fabric <b>intermediary</b> ({@code official → intermediary}), and</li>
 *   <li>Mojang official "<b>Mojmap</b>" mappings (ProGuard {@code named → official}).</li>
 * </ul>
 * The result is a tree keyed by the <b>named</b> (Mojmap) namespace whose values include intermediary and the
 * obfuscated name, so any of the three can be translated into any other — on 1.21.x the kernel runs the merged
 * base under Mojmap ({@code named}) and a Fabric guest ships compiled against {@code intermediary}, so the load
 * path needs {@code intermediary → named} and everything else is there for the tools and loaders that ask.
 * (SRG can be synthesized from the same join when older Forge mods need it; not bundled here.) No MCP data is
 * ever read — see {@code MAPPINGS.md}.
 */
public final class ForbricMappings {
	/** The namespace a Mojmap-compiled Forge/NeoForge mod references the game in — and the kernel's runtime namespace on 1.21.x. */
	public static final String NAMED = "named";
	/** Fabric's stable namespace: what a Fabric mod's bytecode references the game by. */
	public static final String INTERMEDIARY = "intermediary";
	/** The obfuscated namespace shared by both mapping sources (the join key). */
	public static final String OFFICIAL = "official";

	private final MemoryMappingTree namedKeyed;
	/**
	 * The tree's classes indexed by each namespace's own name. The tree itself is keyed by {@code named} (its
	 * source namespace) and mapping-io only looks a class up by that key, but a runtime resolver is asked in
	 * whichever namespace the caller speaks — a Fabric mod asks about {@code intermediary}, a tool about the
	 * obfuscated column — so every namespace needs its own index.
	 */
	private final Map<String, Map<String, MappingTree.ClassMapping>> byNamespace = new HashMap<>();

	private ForbricMappings(MemoryMappingTree namedKeyed) {
		this.namedKeyed = namedKeyed;

		for (String namespace : List.of(NAMED, INTERMEDIARY, OFFICIAL)) {
			int ns = namespaceId(namespace);

			if (ns == MappingTreeView.NULL_NAMESPACE_ID) {
				throw new IllegalStateException("merged mappings are missing the " + namespace + " namespace");
			}

			Map<String, MappingTree.ClassMapping> index = new HashMap<>(namedKeyed.getClasses().size());
			for (MappingTree.ClassMapping cls : namedKeyed.getClasses()) {
				String name = nameIn(cls, ns);
				if (name != null) index.putIfAbsent(name, cls);
			}
			byNamespace.put(namespace, index);
		}
	}

	/**
	 * The tree's id for a namespace. {@code NAMED} is the tree's source namespace, which mapping-io spells
	 * {@code SRC_NAMESPACE_ID} rather than an index; a namespace the tree does not carry is
	 * {@code NULL_NAMESPACE_ID}. Both are negative, so they are told apart by name and never by sign.
	 */
	private int namespaceId(String namespace) {
		return NAMED.equals(namespace) ? MappingTreeView.SRC_NAMESPACE_ID : namedKeyed.getNamespaceId(namespace);
	}

	private static String nameIn(MappingTree.ClassMapping cls, int ns) {
		return ns == MappingTreeView.SRC_NAMESPACE_ID ? cls.getSrcName() : cls.getName(ns);
	}

	private static String nameIn(MappingTree.MemberMapping member, int ns) {
		return ns == MappingTreeView.SRC_NAMESPACE_ID ? member.getSrcName() : member.getName(ns);
	}

	private static String descIn(MappingTree.MemberMapping member, int ns) {
		return ns == MappingTreeView.SRC_NAMESPACE_ID ? member.getSrcDesc() : member.getDesc(ns);
	}

	/** The class the tree knows by {@code name} in {@code namespace}, or null when it knows none. */
	private MappingTree.ClassMapping classIn(String namespace, String name) {
		Map<String, MappingTree.ClassMapping> index = byNamespace.get(namespace);
		return index == null ? null : index.get(name);
	}

	/**
	 * Whether the tree knows a class by {@code name} in {@code namespace}. Used to tell a jar the mapping covers
	 * (the game) from one it does not (a library, a loader runtime) without reading a single class out of it.
	 */
	public boolean knowsClass(String namespace, String name) {
		return classIn(namespace, name) != null;
	}

	/**
	 * Builds the spine from a Fabric intermediary mapping file ({@code official → intermediary}, Tiny v1/v2)
	 * and a Mojang ProGuard mapping file ({@code named → official}).
	 *
	 * <p>The intermediary argument may be either the plain Tiny file or the maven JAR Fabric publishes it in
	 * ({@code net.fabricmc:intermediary}, which holds {@code mappings/mappings.tiny}) — the installer downloads
	 * the artifact as it is, and a developer pointing this at their Gradle cache should not have to unpack it.
	 */
	public static ForbricMappings load(Path intermediaryMappings, Path mojmapProguard) throws IOException {
		// Start keyed by 'official' (obf): intermediary already is.
		MemoryMappingTree officialKeyed = new MemoryMappingTree();
		readMappings(intermediaryMappings, null, officialKeyed);

		// ProGuard exposes (source=named, target=obf); rename target→official + source→named, then re-root on official.
		readMappings(mojmapProguard, MappingFormat.PROGUARD_FILE, new MappingNsRenamer(
				new MappingSourceNsSwitch(officialKeyed, OFFICIAL),
				Map.of("source", NAMED, "target", OFFICIAL)));

		// Re-root the merged tree on 'named' so Forge mods (which speak Mojmap) can be looked up directly.
		MemoryMappingTree namedKeyed = new MemoryMappingTree();
		officialKeyed.accept(new MappingSourceNsSwitch(namedKeyed, NAMED));

		return new ForbricMappings(namedKeyed);
	}

	/** The entry Fabric's {@code net.fabricmc:intermediary} artifact holds its Tiny data in. */
	private static final String MAVEN_MAPPINGS_ENTRY = "mappings/mappings.tiny";

	/**
	 * Feeds one mapping file to {@code visitor}, in whichever of the two shapes it arrives: a plain mapping file
	 * (format read from its own content when {@code format} is null), or a maven JAR holding
	 * {@link #MAVEN_MAPPINGS_ENTRY}.
	 */
	private static void readMappings(Path file, MappingFormat format, MappingVisitor visitor) throws IOException {
		if (!isZip(file)) {
			if (format == null) {
				MappingReader.read(file, visitor);
			} else {
				MappingReader.read(file, format, visitor);
			}
			return;
		}

		try (ZipFile zip = new ZipFile(file.toFile())) {
			ZipEntry entry = zip.getEntry(MAVEN_MAPPINGS_ENTRY);
			if (entry == null) throw new IOException(file + " is a jar but holds no " + MAVEN_MAPPINGS_ENTRY);

			String text;
			try (InputStream in = zip.getInputStream(entry)) {
				text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}

			MappingFormat actual = format == null ? MappingReader.detectFormat(new StringReader(text)) : format;
			MappingReader.read(new StringReader(text), actual, visitor);
		}
	}

	/** Whether the file starts with a zip local-file header; a mapping file's first byte is never {@code 'P'}. */
	private static boolean isZip(Path file) throws IOException {
		byte[] magic = new byte[4];

		try (InputStream in = Files.newInputStream(file)) {
			if (in.readNBytes(magic, 0, magic.length) < magic.length) return false;
		}

		return magic[0] == 'P' && magic[1] == 'K' && magic[2] == 3 && magic[3] == 4;
	}

	/**
	 * Maps a class name from one namespace the tree carries to another. A name it does not know, or a namespace
	 * it does not carry, comes back unchanged — the same honest no-op the single-direction helpers have.
	 */
	public String mapClass(String fromNamespace, String toNamespace, String name) {
		if (name == null || fromNamespace.equals(toNamespace)) return name;

		MappingTree.ClassMapping cls = classIn(fromNamespace, name);
		int toId = namespaceId(toNamespace);
		if (cls == null || toId == MappingTreeView.NULL_NAMESPACE_ID) return name;

		return orSelf(nameIn(cls, toId), name);
	}

	/** Maps a field from one namespace to another. {@code desc} is in the {@code fromNamespace}; null matches by name. */
	public String mapField(String fromNamespace, String toNamespace, String owner, String name, String desc) {
		if (name == null || fromNamespace.equals(toNamespace)) return name;

		MappingTree.ClassMapping cls = classIn(fromNamespace, owner);
		int fromId = namespaceId(fromNamespace);
		int toId = namespaceId(toNamespace);
		if (cls == null || fromId == MappingTreeView.NULL_NAMESPACE_ID
				|| toId == MappingTreeView.NULL_NAMESPACE_ID) {
			return name;
		}

		for (MappingTree.FieldMapping field : cls.getFields()) {
			if (!name.equals(nameIn(field, fromId))) continue;
			if (desc != null && !desc.equals(descIn(field, fromId))) continue;

			return orSelf(nameIn(field, toId), name);
		}

		return name;
	}

	/** Maps a method from one namespace to another. {@code desc} is in the {@code fromNamespace}; null matches by name. */
	public String mapMethod(String fromNamespace, String toNamespace, String owner, String name, String desc) {
		if (name == null || fromNamespace.equals(toNamespace)) return name;

		MappingTree.ClassMapping cls = classIn(fromNamespace, owner);
		int fromId = namespaceId(fromNamespace);
		int toId = namespaceId(toNamespace);
		if (cls == null || fromId == MappingTreeView.NULL_NAMESPACE_ID
				|| toId == MappingTreeView.NULL_NAMESPACE_ID) {
			return name;
		}

		for (MappingTree.MethodMapping method : cls.getMethods()) {
			if (!name.equals(nameIn(method, fromId))) continue;
			if (desc != null && !desc.equals(descIn(method, fromId))) continue;

			return orSelf(nameIn(method, toId), name);
		}

		return name;
	}

	/** Maps a Mojmap class internal name (e.g. {@code net/minecraft/world/phys/Vec3}) to intermediary, or returns the input if unmapped. */
	public String mapClass(String namedInternalName) {
		return mapClass(NAMED, INTERMEDIARY, namedInternalName);
	}

	/** Maps a Mojmap field to its intermediary name. */
	public String mapField(String namedOwner, String namedFieldName, String namedDesc) {
		return mapField(NAMED, INTERMEDIARY, namedOwner, namedFieldName, namedDesc);
	}

	/** Maps a Mojmap method to its intermediary name. {@code namedDesc} may be {@code null} to match by name only. */
	public String mapMethod(String namedOwner, String namedMethodName, String namedDesc) {
		return mapMethod(NAMED, INTERMEDIARY, namedOwner, namedMethodName, namedDesc);
	}

	/** The merged tree (named-keyed), for driving a bytecode remapper (e.g. tiny-remapper) in the DEOBF_REMAP phase. */
	public MappingTree tree() {
		return namedKeyed;
	}

	public int classCount() {
		return namedKeyed.getClasses().size();
	}

	private static String orSelf(String mapped, String fallback) {
		return mapped == null ? fallback : mapped;
	}
}
