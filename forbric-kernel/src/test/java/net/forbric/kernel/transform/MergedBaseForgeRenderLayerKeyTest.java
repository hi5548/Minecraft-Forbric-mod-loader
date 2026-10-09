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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 * The real merged {@code ItemBlockRenderTypes} keys {@code BLOCK_RENDER_TYPES} by the raw {@code Block} when it is
 * READ ({@code getRenderLayers}) and by NeoForge's {@code register}, but MinecraftForge's surviving
 * {@code setRenderLayer(Block, ChunkRenderTypeSet)} overload writes it through
 * {@code ForgeRegistries.BLOCKS.getDelegateOrThrow} — a {@code Holder.Reference}, never equal to the raw
 * {@code Block}. Every render layer a Forge mod registers through that overload is therefore stored where nothing
 * reads it, and the block draws with the default layer.
 *
 * <p>The repair re-keys to the raw {@code Block} AND converts the Forge {@code ChunkRenderTypeSet} to NeoForge's,
 * the type the surviving reader {@code checkcast}s to: the raw-key strip alone would turn the silent miss into a
 * {@code ClassCastException} on the first frame that draws the block. This test pins both halves against the real
 * bytes, and pins the fluid pair left alone.
 */
class MergedBaseForgeRenderLayerKeyTest {
	private static final String OWNER = "net.minecraft.client.renderer.ItemBlockRenderTypes";
	private static final String ENTRY = "net/minecraft/client/renderer/ItemBlockRenderTypes.class";
	private static final String FORGE_WRITER_DESC =
			"(Lnet/minecraft/world/level/block/Block;Lnet/minecraftforge/client/ChunkRenderTypeSet;)V";

	@Test
	void theForgeRenderLayerOverloadIsReKeyedToTheRawBlockAndConvertedToNeoForge() throws Exception {
		byte[] raw = itemBlockRenderTypes();
		ClassNode before = parse(raw);
		assertEquals(1, delegates(writer(before)).size(), "the premise: Forge's block overload keys by a delegate");
		assertEquals(4, delegates(before).size(), "four Forge-delegate sites (block writer + fluid writer/reader/filler)");

		byte[] transformed = new ForbricMergedBaseCompatTransformer().transform(OWNER, raw, null);
		assertTrue(transformed != raw, "the transform must edit the real class, not hand it back");

		ClassNode after = parse(transformed);
		MethodNode writer = writer(after);
		assertEquals(0, delegates(writer).size(), "the Holder key must be gone");
		assertTrue(!namesForgeBlocks(writer), "no ForgeRegistries.BLOCKS read must survive in the writer");

		List<AbstractInsnNode> body = real(writer);
		// The key reaching Map.put is the raw Block: ALOAD map, ALOAD 0, then the converted value.
		assertEquals(Opcodes.GETSTATIC, body.get(1).getOpcode(), "the map load");
		assertTrue(body.get(2).getOpcode() == Opcodes.ALOAD
						&& ((org.objectweb.asm.tree.VarInsnNode) body.get(2)).var == 0,
				"the raw Block (aload_0) must be the key");
		assertTrue(call(body, Opcodes.INVOKEVIRTUAL, "net/minecraftforge/client/ChunkRenderTypeSet", "asList"),
				"the Forge set must be read as a list");
		assertTrue(call(body, Opcodes.INVOKESTATIC, "net/neoforged/neoforge/client/ChunkRenderTypeSet", "of"),
				"and rebuilt as the NeoForge type the reader casts to");
		assertEquals(3, delegates(after).size(), "the three fluid delegate sites must be left alone");

		// BasicVerifier checks the edited straight-line body's stack/locals without loading the game classes.
		for (MethodNode method : after.methods) {
			new Analyzer<>(new BasicVerifier()).analyze(after.name, method);
		}
	}

	@Test
	void aSecondPassLeavesTheRepairedClassAlone() throws Exception {
		byte[] once = new ForbricMergedBaseCompatTransformer().transform(OWNER, itemBlockRenderTypes(), null);
		byte[] twice = new ForbricMergedBaseCompatTransformer().transform(OWNER, once, null);
		assertSame(once, twice, "the repaired class must come back byte-for-byte unchanged");
	}

	@Test
	void aShapeThisRepairDidNotMeasureIsLeftAlone() throws Exception {
		ClassNode perturbed = parse(itemBlockRenderTypes());
		((FieldInsnNode) real(writer(perturbed)).get(2)).owner = "net/minecraftforge/registries/ForgeRegistriesX";
		byte[] bytes = write(perturbed);
		assertSame(bytes, new ForbricMergedBaseCompatTransformer().transform(OWNER, bytes, null),
				"a body this repair did not measure must be refused, not half-re-keyed");
	}

	private static MethodNode writer(ClassNode node) {
		for (MethodNode method : node.methods) {
			if ("setRenderLayer".equals(method.name) && FORGE_WRITER_DESC.equals(method.desc)) return method;
		}
		throw new AssertionError("the merged class must still declare Forge's block overload");
	}

	private static List<AbstractInsnNode> delegates(ClassNode node) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (MethodNode method : node.methods) out.addAll(delegates(method));
		return out;
	}

	private static List<AbstractInsnNode> delegates(MethodNode method) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && "net/minecraftforge/registries/IForgeRegistry".equals(call.owner)
					&& "getDelegateOrThrow".equals(call.name)) out.add(insn);
		}
		return out;
	}

	private static List<AbstractInsnNode> real(MethodNode method) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() >= 0) out.add(insn);
		}
		return out;
	}

	private static boolean namesForgeBlocks(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof FieldInsnNode f && "net/minecraftforge/registries/ForgeRegistries".equals(f.owner)
					&& "BLOCKS".equals(f.name)) return true;
		}
		return false;
	}

	private static boolean call(List<AbstractInsnNode> body, int opcode, String owner, String name) {
		for (AbstractInsnNode insn : body) {
			if (insn instanceof MethodInsnNode m && m.getOpcode() == opcode && owner.equals(m.owner) && name.equals(m.name)) {
				return true;
			}
		}
		return false;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] write(ClassNode node) {
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
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
}
