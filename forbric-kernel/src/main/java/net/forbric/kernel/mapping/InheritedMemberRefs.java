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
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ByteScan;
import net.forbric.kernel.util.ForbricLog;

/**
 * Renames the member REFERENCES a remap leaves in intermediary because the call's owner is not the member's
 * declaring class — the same post-pass convention {@link MixinNames} and
 * {@link net.forbric.kernel.access.AccessWidenerRemapper} use for a namespace the engine cannot reach, resolved
 * with the same {@link ForbricMappings#mapMemberName} fallback they use.
 *
 * <p>tiny-remapper resolves an inherited member by walking the classpath from the call's owner up to the class that
 * declares it. The game is on that classpath, so a game owner resolves. Another MOD's class is not: a guest jar is
 * neither input nor classpath for the jar being remapped, so the walk stops at a class the mappings do not know and
 * the intermediary member name survives into the output.
 *
 * <p>Measured on builders-delight 2.2.0 (`builders-delight-2.2.0.1.21.1.jar`, `com/zrollus/bd/block/ModBlocks`),
 * whose constant pool carries ONE member under both owners — {@code class_4970$class_2251.method_9630} (the game
 * owner, remapped) and {@code FabricBlockSettings.method_9630} (the fabric-api subclass owner, left intermediary) —
 * and whose main entrypoint then died at {@code ModBlocks.<clinit>} with
 * {@code NoSuchMethodError: 'BlockBehaviour$Properties FabricBlockSettings.method_9630(BlockBehaviour)'}. The member
 * is {@code BlockBehaviour$Properties.ofFullCopy(BlockBehaviour)}, which the spine resolves from the name alone:
 * intermediary names a member once across the game, so {@code method_9630} belongs to exactly one member even though
 * the class declaring it is reached here through a subclass.
 *
 * <p>Why a post-pass rather than an extra mapping for tiny-remapper. A mapping accepted for
 * {@code (FabricBlockSettings, method_9630, …)} is never consulted: tiny-remapper keys member mappings by the classes
 * it has READ, and that owner is read neither as input nor as classpath (measured — the mapping is accepted and the
 * reference still comes out intermediary). Handing it the declaring jar instead would put every guest jar on every
 * other guest's classpath for the same answer this pass takes from the name table.
 *
 * <p>Safe by construction: a reference is renamed only when its name is STILL an intermediary spelling, an
 * owner-scoped lookup returns nothing (which is the shape being repaired), and the name resolves globally in the
 * spine. The owner is never rewritten — it is another mod's class and has no mapping. Every spare class is skipped by
 * a byte scan before it is parsed, and the jar is not rewritten at all when nothing changed.
 */
public final class InheritedMemberRefs {
	/** {@code method_1514} / {@code field_3835}: the only spelling this pass may rewrite. */
	private static final Pattern INTERMEDIARY_MEMBER = Pattern.compile("(?:method|field)_\\d+");
	private static final byte[] METHOD_NEEDLE = ByteScan.needle("method_");
	private static final byte[] FIELD_NEEDLE = ByteScan.needle("field_");

	private InheritedMemberRefs() {
	}

	/**
	 * Rewrites the leftover inherited-member references in {@code jar} in place; returns how many classes changed,
	 * and does not write the jar at all when none did.
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
			if (!ByteScan.contains(bytes, METHOD_NEEDLE) && !ByteScan.contains(bytes, FIELD_NEEDLE)) continue;

			byte[] fixed = translateRefs(bytes, spine);
			if (fixed == null) continue;
			entries.put(entry.getKey(), fixed);
			rewritten.add(entry.getKey());
		}

		if (rewritten.isEmpty()) return 0;

		Path tmp = jar.resolveSibling(jar.getFileName() + ".refs.tmp");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				out.putNextEntry(new ZipEntry(entry.getKey()));
				out.write(entry.getValue());
				out.closeEntry();
			}
		}
		Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);

		ForbricLog.info("[Forbric/Mapping] %s: %d class(es) carried a game member reached through another mod's "
				+ "class, renamed by name — a remap engine cannot walk to a declaring class it is not given",
				jar.getFileName(), rewritten.size());
		return rewritten.size();
	}

	/** The class with its inherited-member references renamed, or null when it had none to rename. */
	private static byte[] translateRefs(byte[] bytes, ForbricMappings spine) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		boolean changed = false;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode instruction : method.instructions) {
				if (instruction instanceof MethodInsnNode call) {
					String renamed = renameOrNull(spine, call.owner, call.name, call.desc, true);
					if (renamed != null) {
						call.name = renamed;
						changed = true;
					}
				} else if (instruction instanceof FieldInsnNode field) {
					String renamed = renameOrNull(spine, field.owner, field.name, field.desc, false);
					if (renamed != null) {
						field.name = renamed;
						changed = true;
					}
				} else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
					Handle bootstrap = renamedHandle(spine, dynamic.bsm);
					if (bootstrap != dynamic.bsm) {
						dynamic.bsm = bootstrap;
						changed = true;
					}
					for (int i = 0; i < dynamic.bsmArgs.length; i++) {
						if (!(dynamic.bsmArgs[i] instanceof Handle handle)) continue;
						Handle replacement = renamedHandle(spine, handle);
						if (replacement == handle) continue;
						dynamic.bsmArgs[i] = replacement;
						changed = true;
					}
				} else if (instruction instanceof LdcInsnNode constant && constant.cst instanceof Handle handle) {
					Handle replacement = renamedHandle(spine, handle);
					if (replacement != handle) {
						constant.cst = replacement;
						changed = true;
					}
				}
			}
		}
		if (!changed) return null;

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** {@code handle} with an inherited member reference renamed, or the handle itself when it needs none. */
	private static Handle renamedHandle(ForbricMappings spine, Handle handle) {
		boolean method = handle.getTag() <= Opcodes.H_PUTSTATIC;
		String renamed = renameOrNull(spine, handle.getOwner(), handle.getName(), handle.getDesc(), method);
		return renamed == null ? handle
				: new Handle(handle.getTag(), handle.getOwner(), renamed, handle.getDesc(), handle.isInterface());
	}

	/**
	 * The runtime name for a reference, or null when it must be left exactly as written: only a name that is still an
	 * intermediary spelling, that an owner-scoped lookup does not resolve (the shape being repaired), and that the
	 * name table does resolve is renamed.
	 */
	private static String renameOrNull(ForbricMappings spine, String owner, String name, String desc, boolean method) {
		if (owner == null || name == null || desc == null) return null;
		if (!INTERMEDIARY_MEMBER.matcher(name).matches()) return null;

		String ownerScoped = method
				? spine.mapMethod(ForbricMappings.INTERMEDIARY, ForbricMappings.NAMED, owner, name, desc)
				: spine.mapField(ForbricMappings.INTERMEDIARY, ForbricMappings.NAMED, owner, name, desc);
		if (!name.equals(ownerScoped)) return null;

		String byName = spine.mapMemberName(name);
		return byName == null || byName.equals(name) ? null : byName;
	}
}
