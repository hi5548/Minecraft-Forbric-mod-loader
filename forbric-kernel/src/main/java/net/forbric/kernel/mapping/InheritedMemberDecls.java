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
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ByteScan;
import net.forbric.kernel.util.ForbricLog;

/**
 * Renames the member DECLARATIONS a remap leaves in intermediary because the class that declares the member it
 * satisfies is not on the classpath the engine was given — {@link InheritedMemberRefs}' other half, for the same
 * reason and with the same {@link ForbricMappings#mapMemberName} fallback.
 *
 * <p>tiny-remapper resolves a member by walking the classpath from the class it is read in up to the class that
 * declares it. For a DECLARATION that walk is what names it: the guest's own class is not in the mappings (it is
 * the mod's), so the engine looks at the supertypes, and a supertype that is neither input nor classpath stops it.
 * A Fabric API interface is exactly that shape: the jar is remapped on its own, and the classpath is the game.
 *
 * <p>Measured on betterrailwaysystem 0.1.0 (`BetterRailwaySystem-0.1.0-mc1.21.1.jar`, the corpus build; the same
 * class on the 1.21.1 fixture pack):
 * {@code org/dcstudio/config/BetterRailwaySystemDataReloadListener implements
 * net/fabricmc/fabric/api/resource/SimpleSynchronousResourceReloadListener}, whose game supertype
 * ({@code ResourceManagerReloadListener}) is the class that declares the method it implements. The engine cannot
 * reach {@code SimpleSynchronousResourceReloadListener}, so the declaration came out of the remap still spelled
 * {@code method_14491(Lnet/minecraft/server/packs/resources/ResourceManager;)V} — its descriptor named, its name
 * not — and the class then satisfied nothing: every reload of the server's data packs died with
 * {@code AbstractMethodError: Method org/dcstudio/config/BetterRailwaySystemDataReloadListener
 * .onResourceManagerReload(...)V is abstract}, thrown from {@code ResourceManagerReloadListener}'s own
 * {@code lambda$reload$0}, before "Preparing level". The base is not at fault — its interface still declares
 * {@code onResourceManagerReload(ResourceManager)} exactly as vanilla does.
 *
 * <p>Why a post-pass rather than an extra mapping for tiny-remapper, and why declarations need one at all. The
 * reference half of this family was measured the other way round ({@link InheritedMemberRefs}: a mapping for an
 * owner the engine never reads is accepted and never consulted); here the class IS the input, so an added mapping
 * would work — but it would have to be derived by the same walk that just failed, one class at a time, and it
 * would leave the engine's own answer for the references beside it unreachable in the same way. The name table
 * answers both from one fact: intermediary names a member once across the whole game, so {@code method_14491}
 * belongs to exactly one member no matter which class a mod reached it through.
 *
 * <p>Safe by construction, and narrower than the reference half on purpose:
 * <ul>
 *   <li>only a name still spelled {@code method_\d+} — a member the engine already named is the engine's answer,
 *       which is why the owner-scoped lookup is asked first and a hit stands this pass down;</li>
 *   <li>only a {@code public} method. A game member a class IMPLEMENTS or overrides is public by Java's own rule,
 *       so this cannot reach a mod's private helper — and a mod that ships its own members obfuscated names them
 *       {@code a}/{@code b}/{@code c}, not {@code method_1234}, which is the spelling this whole pass is about.
 *       Fields are not declarations of a game member at all (Java fields are not overridden), so a field left in
 *       intermediary is a mixin's {@code @Shadow}, which {@link MixinShadowMembers} owns;</li>
 *   <li>no class carrying {@code @Mixin}. Inside a mixin the declared name IS the binding name Mixin resolves
 *       through the refmap and the annotation strings, and {@link MixinNames} translates that namespace; renaming
 *       a declaration there from the name table would answer a question that is not this pass's.</li>
 * </ul>
 *
 * <p>The name table only. No owner is rewritten, no descriptor is touched, the method body and its flags stay
 * where they are, and references to the renamed declaration are already this family's business: a call whose owner
 * is the guest's own class is renamed by the engine or by {@link InheritedMemberRefs}, both of which reach
 * {@code method_14491} the same way. Every spare class is skipped by a byte scan before it is parsed, and the jar
 * is not rewritten at all when nothing changed.
 */
public final class InheritedMemberDecls {
	/** {@code method_1514}: the only spelling a DECLARATION this pass may rewrite. */
	private static final Pattern INTERMEDIARY_METHOD = Pattern.compile("method_\\d+");
	private static final byte[] METHOD_NEEDLE = ByteScan.needle("method_");
	/** A mixin's own class: its declarations are named by its refmap and its annotations, not by this table. */
	private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

	private InheritedMemberDecls() {
	}

	/**
	 * Rewrites the leftover intermediary member declarations in {@code jar} in place; returns how many classes
	 * changed, and does not write the jar at all when none did.
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
			if (!ByteScan.contains(bytes, METHOD_NEEDLE)) continue;

			byte[] fixed = translateDecls(bytes, spine);
			if (fixed == null) continue;
			entries.put(entry.getKey(), fixed);
			rewritten.add(entry.getKey());
		}

		if (rewritten.isEmpty()) return 0;

		Path tmp = jar.resolveSibling(jar.getFileName() + ".decls.tmp");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				out.putNextEntry(new ZipEntry(entry.getKey()));
				out.write(entry.getValue());
				out.closeEntry();
			}
		}
		Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);

		ForbricLog.info("[Forbric/Mapping] %s: %d class(es) declared a game member it implements through a class "
				+ "the remap classpath does not carry, named by the table — the declaration satisfied nothing while "
				+ "it kept its intermediary name, so the first call through the interface was AbstractMethodError",
				jar.getFileName(), rewritten.size());
		return rewritten.size();
	}

	/** The class with its leftover intermediary declarations renamed, or null when it had none to rename. */
	private static byte[] translateDecls(byte[] bytes, ForbricMappings spine) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		if (isMixin(node) || node.methods == null) return null;

		boolean changed = false;
		for (MethodNode method : node.methods) {
			if ((method.access & Opcodes.ACC_PUBLIC) == 0) continue;

			String renamed = renameOrNull(spine, node.name, method.name, method.desc);
			if (renamed == null) continue;
			method.name = renamed;
			changed = true;
		}
		if (!changed) return null;

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * The runtime name for a declaration, or null when it must be left exactly as written: only a name that is
	 * still an intermediary spelling, that an owner-scoped lookup does not resolve (the shape being repaired — a
	 * declaration the spine names under its own owner is one the engine already renamed), and that the name table
	 * does resolve is renamed.
	 */
	private static String renameOrNull(ForbricMappings spine, String owner, String name, String desc) {
		if (owner == null || name == null || desc == null) return null;
		if (!INTERMEDIARY_METHOD.matcher(name).matches()) return null;

		String ownerScoped = spine.mapMethod(ForbricMappings.INTERMEDIARY, ForbricMappings.NAMED, owner, name, desc);
		if (!name.equals(ownerScoped)) return null;

		String byName = spine.mapMemberName(name);
		return byName == null || byName.equals(name) ? null : byName;
	}

	/** Whether the class is a mixin: {@code @Mixin}, visible or invisible, is the whole test. */
	private static boolean isMixin(ClassNode node) {
		return carries(node.visibleAnnotations, MIXIN) || carries(node.invisibleAnnotations, MIXIN);
	}

	private static boolean carries(List<AnnotationNode> annotations, String descriptor) {
		if (annotations == null) return false;
		for (AnnotationNode annotation : annotations) {
			if (descriptor.equals(annotation.desc)) return true;
		}
		return false;
	}
}
