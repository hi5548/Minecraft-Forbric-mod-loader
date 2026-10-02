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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.TestFixtures;

/**
 * Covers 1.21.1's burn-time redirect against the REAL staged base: the site, the descriptor, and the three
 * {@code ForgeHooks.getBurnTime} call sites the class has, only one of which is the burn path.
 *
 * <p>The 26.2 anchor was {@code FuelValues.burnDuration(ItemStack, RecipeType)} — a class that does not exist on
 * this base at all — and the redirect widened the descriptor to carry the receiver NeoForge's hook then needed.
 * The assertions here are the 1.21.1 facts instead: the one call inside {@code getBurnDuration}, an UNCHANGED
 * descriptor, and no stack movement.
 */
class MergedBaseBurnTimeTest {
	private static final String FURNACE = "net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity";
	private static final String FURNACE_ENTRY = FURNACE.replace('.', '/') + ".class";
	private static final String FORGE_HOOKS = "net/minecraftforge/common/ForgeHooks";
	private static final String KERNEL_FUEL = "net/forbric/kernel/runtime/KernelFuelValues";
	private static final String BURN_DURATION_DESC =
			"(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/crafting/RecipeType;)I";
	private static final String ITEM_STACK_ONLY = "(Lnet/minecraft/world/item/ItemStack;)I";

	@Test
	void theBurnsPathCallGoesThroughTheKernelWithTheSameDescriptor() throws Exception {
		byte[] original = furnace();
		MethodNode before = method(parse(original), "getBurnDuration", ITEM_STACK_ONLY);
		assertEquals(List.of(FORGE_HOOKS), burnTimeCallers(before),
				"the anchored site is supposed to be one MinecraftForge burn-time call and nothing else");

		byte[] repaired = new ForbricMergedBaseCompatTransformer().transform(FURNACE, original, null);
		assertNotSame(original, repaired);
		MethodNode after = method(parse(repaired), "getBurnDuration", ITEM_STACK_ONLY);

		List<String> callers = burnTimeCallers(after);
		assertEquals(List.of(KERNEL_FUEL), callers,
				"getBurnDuration must ask the kernel, which chains both ecosystems and the Fabric fuel table");

		MethodInsnNode call = burnTimeCalls(after).getFirst();
		assertEquals("burnDuration", call.name);
		assertEquals(BURN_DURATION_DESC, call.desc,
				"the 1.21.1 seam takes the same arguments as MinecraftForge's, so no slot may move");
		assertEquals(opcodes(before), opcodes(after),
				"the redirect is an owner/name swap: the instruction sequence must be byte-for-byte the same shape");
		assertEquals(before.maxStack, after.maxStack, "nothing was pushed, so the stack bound cannot have changed");
		new Analyzer<>(new BasicVerifier()).analyze(FURNACE.replace('.', '/'), after);
	}

	/**
	 * The other two {@code ForgeHooks.getBurnTime} callers in this class — {@code isFuel} and
	 * {@code canPlaceItem} — are fuel QUESTIONS, not the burn path, and NeoForge posts no event for them. They
	 * must come back untouched, or the redirect would ask both ecosystems for some fuels and not others.
	 */
	@Test
	void theSitesBesideItAreLeftAlone() throws Exception {
		byte[] repaired = new ForbricMergedBaseCompatTransformer().transform(FURNACE, furnace(), null);
		ClassNode node = parse(repaired);
		MethodNode isFuel = node.methods.stream()
				.filter(m -> m.name.equals("isFuel") && m.desc.equals("(Lnet/minecraft/world/item/ItemStack;)Z"))
				.findFirst().orElseThrow();
		assertEquals(List.of(FORGE_HOOKS), burnTimeCallers(isFuel));
		for (MethodNode method : node.methods) {
			if (!method.name.equals("canPlaceItem") || !method.desc.equals("(ILnet/minecraft/world/item/ItemStack;)Z")) {
				continue;
			}
			assertEquals(List.of(FORGE_HOOKS), burnTimeCallers(method));
		}
	}

	@Test
	void aSecondPassLeavesTheClassAlone() throws Exception {
		ForbricMergedBaseCompatTransformer once = new ForbricMergedBaseCompatTransformer();
		byte[] repaired = once.transform(FURNACE, furnace(), null);
		assertSame(repaired, once.transform(FURNACE, repaired, null),
				"a second pass finds the kernel owner where MinecraftForge's was and must stand down");
	}

	private static List<String> burnTimeCallers(MethodNode method) {
		return burnTimeCalls(method).stream().map(call -> call.owner).toList();
	}

	private static List<MethodInsnNode> burnTimeCalls(MethodNode method) {
		List<MethodInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
					&& "getBurnTime".equals(call.name) && BURN_DURATION_DESC.equals(call.desc)) {
				out.add(call);
			}
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
					&& KERNEL_FUEL.equals(call.owner) && "burnDuration".equals(call.name)
					&& BURN_DURATION_DESC.equals(call.desc)) {
				out.add(call);
			}
		}
		return out;
	}

	private static List<Integer> opcodes(MethodNode method) {
		return java.util.Arrays.stream(method.instructions.toArray())
				.filter(insn -> insn.getOpcode() >= 0).map(AbstractInsnNode::getOpcode).toList();
	}

	private static byte[] furnace() throws IOException {
		Path base = TestFixtures.mergedBase();
		assumeTrue(base != null && Files.isRegularFile(base), "staged merged base absent");
		try (ZipFile zip = new ZipFile(base.toFile())) {
			ZipEntry entry = zip.getEntry(FURNACE_ENTRY);
			assumeTrue(entry != null, FURNACE + " absent from this base");
			return zip.getInputStream(entry).readAllBytes();
		}
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) {
			if (method.name.equals(name) && method.desc.equals(desc)) return method;
		}
		throw new AssertionError(node.name + " has no " + name + desc);
	}
}
