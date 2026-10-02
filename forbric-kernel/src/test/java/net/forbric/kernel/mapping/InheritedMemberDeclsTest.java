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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The inherited-member DECLARATION a remap leaves behind, on the real mapping spine.
 *
 * <p>The fixture is the measured betterrailwaysystem shape: an ordinary mod class implementing a Fabric API
 * interface ({@code SimpleSynchronousResourceReloadListener}), whose game supertype ({@code ResourceManagerReloadListener})
 * is the class that declares the method the mod implements. tiny-remapper names a declaration by walking from the
 * class it is read in up to the declaring class, and a Fabric API interface is neither input nor classpath here,
 * so the name comes out intermediary — and the class then satisfies nothing.
 *
 * <p>The first assertion is the red one: the pass must find the declaration exactly as {@code
 * FabricGuestRemapper}'s own output leaves it, which is the difference between this and a test that wrote the
 * broken name itself. Real mapping data is required, because the whole point is a name only the spine can resolve.
 */
class InheritedMemberDeclsTest {
	private static final String GUEST = "com/example/ReloadListener";
	/** What the class declares: the game method it implements, still spelled the way the remap left it. */
	private static final String INTERMEDIARY = "method_14491";
	/** What it must declare: {@code ResourceManagerReloadListener.onResourceManagerReload(ResourceManager)}. */
	private static final String RUNTIME = "onResourceManagerReload";
	private static final String DESC = "(Lnet/minecraft/server/packs/resources/ResourceManager;)V";

	@Test
	void aDeclarationSatisfyingAGameInterfaceThroughAnotherModsClassIsRenamed(@TempDir Path dir) throws Exception {
		ForbricMappings spine = spine();
		Path jar = dir.resolve("guest.jar");
		write(jar, guestClass(INTERMEDIARY, Opcodes.ACC_PUBLIC));

		assertEquals(List.of(INTERMEDIARY), declarations(jar),
				"premise: the declaration is exactly what the remap leaves when the classpath stops at a Fabric "
						+ "API interface — and it satisfies nothing, which is the AbstractMethodError the game threw");

		assertEquals(1, InheritedMemberDecls.translate(jar, spine));

		assertEquals(List.of(RUNTIME), declarations(jar),
				"an implementation that keeps its intermediary name is an abstract method as far as the JVM is "
						+ "concerned, and the first call through the interface throws AbstractMethodError");
		assertEquals(0, InheritedMemberDecls.translate(jar, spine), "nothing left to rename on a second pass");
	}

	/**
	 * A mod's own private member is not a game declaration, and this pass must not touch it however it is spelled:
	 * it is the boundary that keeps an obfuscated helper from being renamed into someone else's member.
	 */
	@Test
	void aPrivateMemberIsLeftAlone(@TempDir Path dir) throws Exception {
		ForbricMappings spine = spine();
		Path jar = dir.resolve("guest.jar");
		write(jar, guestClass(INTERMEDIARY, Opcodes.ACC_PRIVATE));

		assertEquals(0, InheritedMemberDecls.translate(jar, spine));
		assertEquals(List.of(INTERMEDIARY), declarations(jar));
	}

	/** A name the spine cannot resolve globally is left exactly as written — a guess would be worse than the miss. */
	@Test
	void aNameTheSpineCannotResolveIsLeftAlone(@TempDir Path dir) throws Exception {
		ForbricMappings spine = spine();
		Path jar = dir.resolve("guest.jar");
		write(jar, guestClass("method_999999", Opcodes.ACC_PUBLIC));

		assertEquals(0, InheritedMemberDecls.translate(jar, spine));
		assertEquals(List.of("method_999999"), declarations(jar));
	}

	/** A mixin's declarations are named by its refmap and its annotations, not by this table. */
	@Test
	void aMixinsOwnDeclarationIsLeftAlone(@TempDir Path dir) throws Exception {
		ForbricMappings spine = spine();
		Path jar = dir.resolve("guest.jar");
		write(jar, guestClass(INTERMEDIARY, Opcodes.ACC_PUBLIC, "Lorg/spongepowered/asm/mixin/Mixin;"));

		assertEquals(0, InheritedMemberDecls.translate(jar, spine));
		assertEquals(List.of(INTERMEDIARY), declarations(jar));
	}

	private static ForbricMappings spine() {
		Path intermediary = MappingFixtures.intermediary();
		Path mojmap = MappingFixtures.mojmap();
		assumeTrue(Files.isRegularFile(intermediary) && Files.isRegularFile(mojmap),
				"the real mapping data is required: the name is what only it can resolve");
		return FabricGuestMappings.of(intermediary, mojmap).mappings();
	}

	/** The class as the measured mod writes it: the game interface's method, implemented with one body. */
	private static byte[] guestClass(String name, int access, String... classAnnotations) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, GUEST, null, "java/lang/Object",
				new String[] { "net/fabricmc/fabric/api/resource/SimpleSynchronousResourceReloadListener" });
		for (String annotation : classAnnotations) writer.visitAnnotation(annotation, true).visitEnd();

		MethodVisitor method = writer.visitMethod(access, name, DESC, null, null);
		method.visitCode();
		method.visitInsn(Opcodes.RETURN);
		method.visitMaxs(0, 2);
		method.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static void write(Path jar, byte[] bytes) throws Exception {
		try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
			out.putNextEntry(new ZipEntry(GUEST + ".class"));
			out.write(bytes);
			out.closeEntry();
		}
	}

	/** Every method the jar's own class declares. */
	private static List<String> declarations(Path jar) throws Exception {
		List<String> out = new ArrayList<>();
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
				for (MethodNode method : node.methods) out.add(method.name);
			}
		}
		assertTrue(out.size() <= 1, "the fixture declares one method");
		return out;
	}
}
