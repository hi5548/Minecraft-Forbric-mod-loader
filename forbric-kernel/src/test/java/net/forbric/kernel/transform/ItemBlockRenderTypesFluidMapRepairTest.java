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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.TestFixtures;

/**
 * The shape test for the unwritten-static repair: the real merged {@code ItemBlockRenderTypes} declares Forge's
 * {@code FLUID_RENDER_TYPES} and never assigns it, and the transformer puts the assignment back into
 * {@code <clinit>} using the field and the filler callback that did survive.
 *
 * <p>Asserting the assignment exists is the defect itself — a null field NPEs the first compiled chunk section —
 * so the shape assertion is also the semantic one. Whether the client then enters the world AND survives the
 * render path is the arm's to show: run/compat/reports/2026-10-03-datamap-sync-before-level/ §15.
 */
class ItemBlockRenderTypesFluidMapRepairTest {
	private static final String OWNER = "net.minecraft.client.renderer.ItemBlockRenderTypes";
	private static final String ENTRY = "net/minecraft/client/renderer/ItemBlockRenderTypes.class";
	private static final String FIELD = "FLUID_RENDER_TYPES";
	private static final String FILLER_DESC = "(Lit/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap;)V";

	@Test
	void theUnwrittenFluidMapIsAssignedInClinitByItsOwnFiller() throws Exception {
		byte[] raw = itemBlockRenderTypes();
		ClassNode before = parse(raw);
		assertNotNull(field(before, FIELD), "the merged carrier must still declare " + FIELD);
		assertTrue(!writesFluidMap(before), "the premise: nothing assigns it in the base");
		assertNotNull(filler(before), "and Forge's filler callback must still be present to build it from");

		byte[] transformed = new ItemBlockRenderTypesFluidMapRepair().transform(OWNER, raw, null);
		assertNotSame(raw, transformed, "the transform must edit the real class, not hand it back");

		ClassNode after = parse(transformed);
		assertTrue(writesFluidMap(after), "the repair must assign " + FIELD);
		assertEqualsClinit(after);
		// The injected store is fed by the surviving filler: ... DUP; INVOKESTATIC <filler>; PUTSTATIC.
		MethodInsnNode call = storeFillerCall(after);
		assertNotNull(call, "the assignment must call Forge's filler, not a hand-rolled map");
		assertTrue(call.desc.equals(FILLER_DESC), "the filler takes the map it fills: " + call.desc);

		// The injected instructions must name their owners in INTERNAL form: a dotted owner lands in the constant
		// pool as an illegal class name and the JVM refuses to define the class (ClassFormatError: Illegal class
		// name). Measured on the arm before this check existed.
		FieldInsnNode store = fluidStore(after);
		assertNotNull(store, "the injected store must be there");
		assertEquals("net/minecraft/client/renderer/ItemBlockRenderTypes", store.owner,
				"the store's owner must be the internal name, not the dotted one");
		assertTrue(!call.owner.contains("."), "the filler call's owner must be the internal name: " + call.owner);

		// The injected block is straight-line bytecode; BasicVerifier checks its stack depth and locals without
		// loading anything (SimpleVerifier cannot resolve the game classes from the test classpath).
		for (MethodNode method : after.methods) {
			new Analyzer<>(new BasicVerifier()).analyze(after.name, method);
		}
	}

	/**
	 * The check the arm earned: a dotted owner in the injected instructions lands in the constant pool as an
	 * illegal class name, and the JVM refuses to define the class ({@code ClassFormatError: Illegal class name}).
	 * {@code initialize=false} keeps {@code <clinit>} from running, so the definition needs no game classes — it is
	 * the class-file format itself that is under test.
	 */
	@Test
	void theTransformedClassIsDefinable() throws Exception {
		byte[] transformed = new ItemBlockRenderTypesFluidMapRepair().transform(OWNER, itemBlockRenderTypes(), null);
		ClassLoader defining = new ClassLoader(getClass().getClassLoader()) {
			@Override
			protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				if (OWNER.equals(name)) {
					Class<?> loaded = findLoadedClass(name);
					if (loaded == null) loaded = defineClass(name, transformed, 0, transformed.length);
					if (resolve) resolveClass(loaded);
					return loaded;
				}
				return super.loadClass(name, resolve);
			}
		};
		assertNotNull(Class.forName(OWNER, false, defining));
	}

	@Test
	void aSecondPassLeavesTheRepairedClassAlone() throws Exception {
		byte[] once = new ItemBlockRenderTypesFluidMapRepair().transform(OWNER, itemBlockRenderTypes(), null);
		assertSame(once, new ItemBlockRenderTypesFluidMapRepair().transform(OWNER, once, null),
				"a class whose field is already assigned must not be touched again");
	}

	@Test
	void theKillSwitchLeavesTheClassAlone() throws Exception {
		byte[] raw = itemBlockRenderTypes();
		String previous = System.getProperty(ItemBlockRenderTypesFluidMapRepair.PROPERTY);
		try {
			System.setProperty(ItemBlockRenderTypesFluidMapRepair.PROPERTY, "off");
			assertSame(raw, new ItemBlockRenderTypesFluidMapRepair().transform(OWNER, raw, null),
					"with the switch off the class must come back byte-for-byte unchanged");
		} finally {
			if (previous == null) System.clearProperty(ItemBlockRenderTypesFluidMapRepair.PROPERTY);
			else System.setProperty(ItemBlockRenderTypesFluidMapRepair.PROPERTY, previous);
		}
	}

	private static void assertEqualsClinit(ClassNode node) {
		for (MethodNode method : node.methods) {
			if (!"<clinit>".equals(method.name)) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC && FIELD.equals(f.name)) {
					return;
				}
			}
		}
		throw new AssertionError(FIELD + " must be assigned by <clinit>, not lazily by its first reader");
	}

	private static FieldInsnNode fluidStore(ClassNode node) {
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC && FIELD.equals(f.name)) {
					return f;
				}
			}
		}
		return null;
	}

	private static MethodInsnNode storeFillerCall(ClassNode node) {
		for (MethodNode method : node.methods) {
			AbstractInsnNode previous = null;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC && FIELD.equals(f.name)
						&& previous instanceof MethodInsnNode call) {
					return call;
				}
				previous = insn;
			}
		}
		return null;
	}

	private static boolean writesFluidMap(ClassNode node) {
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC && FIELD.equals(f.name)) {
					return true;
				}
			}
		}
		return false;
	}

	private static FieldNode field(ClassNode node, String name) {
		for (FieldNode field : node.fields) {
			if (name.equals(field.name)) return field;
		}
		return null;
	}

	private static MethodNode filler(ClassNode node) {
		for (MethodNode method : node.methods) {
			if (FILLER_DESC.equals(method.desc)) return method;
		}
		return null;
	}

	private static byte[] itemBlockRenderTypes() throws Exception {
		Path jar = TestFixtures.mergedBase();
		TestFixtures.require(jar != null && Files.isRegularFile(jar), "the staged merged base");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(ENTRY);
			assertNotNull(entry, ENTRY + " absent from " + jar);
			return zip.getInputStream(entry).readAllBytes();
		}
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}
}
