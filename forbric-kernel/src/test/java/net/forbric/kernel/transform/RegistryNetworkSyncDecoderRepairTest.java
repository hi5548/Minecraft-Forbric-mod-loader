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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.TestFixtures;

/**
 * The shape test for the client registry-sync repair: the merged {@code RegistryDataLoader} comes back out with
 * MinecraftForge's {@code ConditionCodec.wrap} gone from {@code loadContentsFromNetwork}, so the raw decoder — not
 * an {@code Optional}-yielding one — reaches NeoForge's one-unwrap {@code loadElementFromResource}.
 *
 * <p>That is the whole defect, so the shape assertion is also the semantic one: with the wrap present the decoder
 * is wrapped twice and unwrapped once, and the registry element is an {@code Optional} (which {@code Level.<init>}
 * then casts to {@code DimensionType}). What this test cannot see is whether the client actually joins — only the
 * arm can show that; see run/compat/reports/2026-10-03-datamap-sync-before-level/ §13.
 */
class RegistryNetworkSyncDecoderRepairTest {
	private static final String LOADER = "net.minecraft.resources.RegistryDataLoader";
	private static final String ENTRY = "net/minecraft/resources/RegistryDataLoader.class";
	private static final String METHOD = "loadContentsFromNetwork";
	private static final String WRAP_OWNER = "net/minecraftforge/common/crafting/conditions/ConditionCodec";
	private static final String WRAP_NAME = "wrap";
	private static final String WRAP_DESC = "(Lcom/mojang/serialization/Decoder;)Lcom/mojang/serialization/Decoder;";

	@Test
	void theForgeWrapIsRemovedAndTheRawDecoderReachesTheLoader() throws Exception {
		byte[] raw = registryDataLoader();
		MethodNode before = method(parse(raw), METHOD);
		assertNotNull(before, "the merged carrier must still carry " + METHOD);
		assertNotNull(wrapCall(before), "the merged carrier must still carry Forge's ConditionCodec.wrap, or this "
				+ "test measures nothing — a merge that changed the shape must fail here, not pass");

		byte[] transformed = new RegistryNetworkSyncDecoderRepair().transform(LOADER, raw, null);
		assertNotSame(raw, transformed, "the transform must edit the real class, not hand it back");

		MethodNode after = method(parse(transformed), METHOD);
		assertNotNull(after, "the transformed class must still carry " + METHOD);
		assertNull(wrapCall(after), "the Forge Optional wrap must be gone");

		// The decoder slot (local 9 in the carrier) is written straight from the method's decoder parameter
		// (local 4); before the repair the instruction before the store is the ConditionCodec.wrap call.
		VarInsnNode store = storeTo(after, 9);
		assertNotNull(store, "the decoder slot must still be stored");
		AbstractInsnNode beforeStore = previousReal(store);
		assertTrue(beforeStore instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 4,
				"the decoder stored for the loader must be the raw parameter, not a wrapped one: " + beforeStore);
	}

	@Test
	void aSecondPassLeavesTheRepairedClassAlone() throws Exception {
		byte[] once = new RegistryNetworkSyncDecoderRepair().transform(LOADER, registryDataLoader(), null);
		assertSame(once, new RegistryNetworkSyncDecoderRepair().transform(LOADER, once, null),
				"a class whose wrap is already gone must not be touched again");
	}

	@Test
	void theKillSwitchLeavesTheClassAlone() throws Exception {
		byte[] raw = registryDataLoader();
		String previous = System.getProperty(RegistryNetworkSyncDecoderRepair.PROPERTY);
		try {
			System.setProperty(RegistryNetworkSyncDecoderRepair.PROPERTY, "off");
			assertSame(raw, new RegistryNetworkSyncDecoderRepair().transform(LOADER, raw, null),
					"with the switch off the class must come back byte-for-byte unchanged");
		} finally {
			if (previous == null) System.clearProperty(RegistryNetworkSyncDecoderRepair.PROPERTY);
			else System.setProperty(RegistryNetworkSyncDecoderRepair.PROPERTY, previous);
		}
	}

	private static byte[] registryDataLoader() throws Exception {
		Path jar = TestFixtures.mergedBase();
		TestFixtures.require(jar != null && java.nio.file.Files.isRegularFile(jar), "the staged merged base");
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

	private static MethodNode method(ClassNode node, String name) {
		for (MethodNode method : node.methods) {
			if (name.equals(method.name)) return method;
		}
		return null;
	}

	private static MethodInsnNode wrapCall(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && WRAP_OWNER.equals(call.owner)
					&& WRAP_NAME.equals(call.name) && WRAP_DESC.equals(call.desc)) return call;
		}
		return null;
	}

	private static VarInsnNode storeTo(MethodNode method, int slot) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE && store.var == slot) {
				return store;
			}
		}
		return null;
	}

	private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
		for (AbstractInsnNode insn = instruction.getPrevious(); insn != null; insn = insn.getPrevious()) {
			if (insn instanceof LabelNode || insn instanceof LineNumberNode || insn instanceof FrameNode) continue;
			return insn;
		}
		return null;
	}
}
