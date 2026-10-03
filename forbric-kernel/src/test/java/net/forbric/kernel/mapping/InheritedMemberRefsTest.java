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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The inherited-member reference a remap leaves behind, on the real mapping spine.
 *
 * <p>The fixture is the measured builders-delight shape: an ordinary mod class calling a game member
 * ({@code BlockBehaviour$Properties.ofFullCopy}, intermediary {@code method_9630}) with ANOTHER MOD's class —
 * fabric-api's {@code FabricBlockSettings}, its subclass — as the call's owner. tiny-remapper resolves such a member
 * by walking the classpath from the owner to the class that declares it, and a guest owner is not on that classpath,
 * so the name comes out intermediary and the mod dies at its first lookup.
 *
 * <p>The first assertion is the red one: the jar as written violates the invariant the stage's own test states ("a
 * member reference whose owner the guest does not define must not be left in intermediary"). Real mapping data is
 * required, because the whole point is a name only the spine can resolve — a synthetic table would prove nothing.
 */
class InheritedMemberRefsTest {
	/** {@code method_1514}: an intermediary member name. */
	private static final Pattern INTERMEDIARY_MEMBER = Pattern.compile("(?:method|field)_\\d+");
	private static final String FABRIC_OWNER = "net/fabricmc/fabric/api/object/builder/v1/block/FabricBlockSettings";
	private static final String MEMBER_DESC = "(Lnet/minecraft/class_4970;)Lnet/minecraft/class_4970$class_2251;";
	private static final String GAME_OWNER = "net/minecraft/class_4970$class_2251";

	@Test
	void aGameMemberReachedThroughAnotherModsClassIsRenamedByItsName(@TempDir Path dir) throws Exception {
		ForbricMappings spine = spine();
		Path jar = dir.resolve("guest.jar");
		write(jar, guestClass(FABRIC_OWNER, "method_9630"));

		assertEquals(List.of(FABRIC_OWNER + ".method_9630"), leftovers(jar),
				"premise: the reference is exactly what the enum's invariant calls a survivor");

		assertEquals(1, InheritedMemberRefs.translate(jar, spine));

		assertEquals(List.of(), leftovers(jar),
				"a survivor is a NoSuchMethodError at the mod's first use of the member");
		assertEquals(List.of(FABRIC_OWNER + "#ofFullCopy"), members(jar),
				"the OWNER stays (another mod's class has no mapping) and the member takes its runtime name");
		assertEquals(0, InheritedMemberRefs.translate(jar, spine), "nothing left to rename on a second pass");
	}

	/** A name the spine cannot resolve globally is left exactly as written — a guess would be worse than the miss. */
	@Test
	void aNameTheSpineCannotResolveIsLeftAlone(@TempDir Path dir) throws Exception {
		ForbricMappings spine = spine();
		Path jar = dir.resolve("guest.jar");
		write(jar, guestClass(FABRIC_OWNER, "method_999999"));

		assertEquals(0, InheritedMemberRefs.translate(jar, spine));
		assertEquals(List.of(FABRIC_OWNER + "#method_999999"), members(jar));
	}

	/**
	 * A reference whose OWNER the spine knows is the engine's business, not this pass's: the owner-scoped lookup
	 * succeeds, so the shape being repaired is absent and the name is left where it is.
	 */
	@Test
	void aReferenceTheOwnerAlreadyResolvesIsLeftToTheEngine(@TempDir Path dir) throws Exception {
		ForbricMappings spine = spine();
		Path jar = dir.resolve("guest.jar");
		write(jar, guestClass(GAME_OWNER, "method_9630"));

		assertEquals(0, InheritedMemberRefs.translate(jar, spine));
		assertEquals(List.of(GAME_OWNER + "#method_9630"), members(jar));
	}

	private static ForbricMappings spine() {
		Path intermediary = MappingFixtures.intermediary();
		Path mojmap = MappingFixtures.mojmap();
		assumeTrue(Files.isRegularFile(intermediary) && Files.isRegularFile(mojmap),
				"the real mapping data is required: the name is what only it can resolve");
		return FabricGuestMappings.of(intermediary, mojmap).mappings();
	}

	/** The class as the measured mod writes it: one static call to {@code owner.name}, returning the member's type. */
	private static byte[] guestClass(String owner, String name) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/ModBlocks", null, "java/lang/Object", null);
		MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "of", MEMBER_DESC, null, null);
		method.visitCode();
		method.visitVarInsn(Opcodes.ALOAD, 0);
		method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, MEMBER_DESC, false);
		method.visitInsn(Opcodes.ARETURN);
		method.visitMaxs(1, 1);
		method.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static void write(Path jar, byte[] bytes) throws Exception {
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
			out.putNextEntry(new ZipEntry("com/example/ModBlocks.class"));
			out.write(bytes);
			out.closeEntry();
		}
	}

	/** Every {@code owner#name} a member reference in the jar names. */
	private static List<String> members(Path jar) throws Exception {
		List<String> out = new ArrayList<>();
		for (ClassNode node : classes(jar)) {
			for (MethodNode method : node.methods) {
				for (AbstractInsnNode instruction : method.instructions) {
					if (instruction instanceof MethodInsnNode call) out.add(call.owner + "#" + call.name);
					else if (instruction instanceof FieldInsnNode field) out.add(field.owner + "#" + field.name);
				}
			}
		}
		return out;
	}

	/** References the jar leaves in intermediary naming an owner it does not itself define — the stage's invariant. */
	private static List<String> leftovers(Path jar) throws Exception {
		Set<String> defined = new HashSet<>();
		List<String> out = new ArrayList<>();
		for (ClassNode node : classes(jar)) defined.add(node.name);
		for (String member : members(jar)) {
			int hash = member.indexOf('#');
			String owner = member.substring(0, hash);
			String name = member.substring(hash + 1);
			if (!defined.contains(owner) && INTERMEDIARY_MEMBER.matcher(name).matches()) out.add(owner + "." + name);
		}
		return out;
	}

	private static List<ClassNode> classes(Path jar) throws Exception {
		List<ClassNode> out = new ArrayList<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (Enumeration<? extends ZipEntry> it = zip.entries(); it.hasMoreElements(); ) {
				ZipEntry entry = it.nextElement();
				if (!entry.getName().endsWith(".class")) continue;
				byte[] bytes;
				try (InputStream in = zip.getInputStream(entry)) {
					bytes = in.readAllBytes();
				}
				ClassNode node = new ClassNode();
				new ClassReader(bytes).accept(node, 0);
				out.add(node);
			}
		}
		return out;
	}
}
