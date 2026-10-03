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

package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.TestFixtures;

/**
 * The 1.21.1 half of {@code surviveTheMissingForgeModelDataManager}: the merged render path dereferences
 * MinecraftForge's {@code ModelDataManager}, which the merged level never provides, so it falls through to an
 * interface default that returns null and the destroy-block particle NPEs. This is the shape §17 read; the repair
 * is the kernel's existing one, extended from the 26.2 overlay ({@link MergedBaseBlockBreakingOverlayTest}) to the
 * shape-scanning form.
 */
class MergedBaseForgeModelDataLookupTest {
	private static final String FORGE_MANAGER = "net/minecraftforge/client/model/data/ModelDataManager";
	private static final String FORGE_MODEL_DATA = "net/minecraftforge/client/model/data/ModelData";
	private static final String SHAPER = "net/minecraft/client/renderer/block/BlockModelShaper";
	private static final String SECTION_COMPILER = "net/minecraft/client/renderer/chunk/SectionCompiler";

	@Test
	void theRenderPathNoLongerAsksForAManagerThatIsAlwaysNull() throws Exception {
		byte[] in = readClass(SHAPER + ".class");
		assertTrue(callsForgeManager(parse(in)), "the merged base must still carry the crash — if not, re-derive this");

		byte[] out = transform(SHAPER, in);
		assertNotSame(in, out, "the transform must edit the real class");
		ClassNode after = parse(out);
		assertTrue(!callsForgeManager(after), "nothing may dereference MinecraftForge's model-data manager");

		MethodNode texture = method(after, "getTexture");
		int empties = 0;
		for (AbstractInsnNode insn : texture.instructions) {
			if (insn.getOpcode() == Opcodes.GETSTATIC && insn instanceof FieldInsnNode field
					&& FORGE_MODEL_DATA.equals(field.owner) && "EMPTY".equals(field.name)) {
				empties++;
			}
		}
		assertTrue(empties >= 1, "the lookup must be replaced by MinecraftForge's own empty model data (the method's "
				+ "own fallback may leave a second one)");
		new Analyzer<>(new BasicVerifier()).analyze(after.name, texture);
	}

	/**
	 * The descriptor filter, pinned: {@code SectionCompiler} has a same-named {@code getAt} that returns a
	 * {@code Map}, not {@code ModelData}, and substituting empty model data there would be a verify error. It must
	 * be left exactly as it is.
	 */
	@Test
	void theMapReturningLookupIsLeftAlone() throws Exception {
		byte[] in = readClass(SECTION_COMPILER + ".class");
		MethodInsnNode mapLookup = forgeManagerCallReturning(parse(in), "Ljava/util/Map;");
		assertNotNull(mapLookup, "the SectionCompiler Map-returning getAt is the control; if it is gone, re-derive this");

		byte[] out = transform(SECTION_COMPILER, in);
		assertNotNull(forgeManagerCallReturning(parse(out), "Ljava/util/Map;"),
				"the Map-returning lookup must survive — the replacement is only valid where ModelData is expected");
	}

	@Test
	void aSecondPassLeavesTheRepairedClassAlone() throws Exception {
		byte[] once = transform(SHAPER, readClass(SHAPER + ".class"));
		assertSame(once, transform(SHAPER, once), "a class with no such lookup left is coherent and must not be touched");
	}

	private static byte[] transform(String internal, byte[] bytes) {
		return new ForbricMergedBaseCompatTransformer().transform(internal.replace('/', '.'), bytes, null);
	}

	private static boolean callsForgeManager(ClassNode node) {
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call && FORGE_MANAGER.equals(call.owner)) return true;
			}
		}
		return false;
	}

	private static MethodInsnNode forgeManagerCallReturning(ClassNode node, String returnDesc) {
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call && FORGE_MANAGER.equals(call.owner)
						&& call.desc.endsWith(returnDesc)) return call;
			}
		}
		return null;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name)) return m;
		}
		throw new AssertionError("no method " + name + " in " + node.name);
	}

	private static byte[] readClass(String entry) throws Exception {
		Path jar = TestFixtures.mergedBase();
		TestFixtures.require(jar != null && Files.isRegularFile(jar), "the staged merged base");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			assertNotNull(found, entry + " missing from the staged merged base");
			return zip.getInputStream(found).readAllBytes();
		}
	}
}
