/*
 * Copyright 2026 The Forbric Project
 * Licensed under the Apache License, Version 2.0. See the LICENSE file for the full text.
 */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import net.forbric.kernel.TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * NeoForge's {@code AbstractFurnaceBlockEntity.getFuel} hands its freshly built fuel table to
 * {@code KernelFabricFuel.apply} just before caching it.
 *
 * <p>PORT(1.21.1): the 26.2 anchors ({@code DataMapHooks.populateFuelValues}, {@code FuelValues.vanillaBurnTimes})
 * do not exist on this base; the seam is vanilla's {@code getFuel()}.
 */
@ResourceLock("system-properties")
class FabricFuelValuesInjectorTest {
	private static final String FURNACE = "net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity";
	private static final String ENTRY = FURNACE.replace('.', '/') + ".class";

	@AfterEach void reset() { System.clearProperty(FabricFuelValuesInjector.PROPERTY); }

	@Test void theFreshTableGoesThroughFabricBeforeItIsCached() throws Exception {
		byte[] original = furnace();
		ClassNode before = parse(original);
		MethodNode getFuel = method(before, "getFuel");
		assertEquals(1, calls(getFuel, FURNACE.replace('.', '/'), "buildFuels").size(), "premise: one buildFuels");
		assertEquals(1, putStatics(getFuel, "fuelCache").size(), "premise: one cache store");

		byte[] changed = new FabricFuelValuesInjector().transform(FURNACE, original, null);
		assertNotSame(original, changed);
		ClassNode after = parse(changed);
		MethodNode repaired = method(after, "getFuel");
		new Analyzer<>(new BasicVerifier()).analyze(after.name, repaired);

		MethodInsnNode apply = calls(repaired, FabricFuelValuesInjector.KERNEL_FUEL, "apply").get(0);
		assertEquals("(Ljava/util/Map;)Ljava/util/Map;", apply.desc);
		assertEquals(Opcodes.INVOKESTATIC, apply.getOpcode());
		AbstractInsnNode previous = realPrevious(apply), next = realNext(apply);
		assertTrue(previous instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD,
				"applied to the local the method just built");
		assertTrue(next instanceof FieldInsnNode store && store.getOpcode() == Opcodes.PUTSTATIC
				&& store.name.equals("fuelCache"), "and before the table is cached");

		assertSame(changed, new FabricFuelValuesInjector().transform(FURNACE, changed, null), "a second pass changes nothing");
	}

	@Test void theSwitchAndUnrelatedClassesAreIdentity() throws Exception {
		byte[] original = furnace();
		System.setProperty(FabricFuelValuesInjector.PROPERTY, "off");
		assertSame(original, new FabricFuelValuesInjector().transform(FURNACE, original, null));
		System.clearProperty(FabricFuelValuesInjector.PROPERTY);
		assertSame(original, new FabricFuelValuesInjector().transform("net.example.Other", original, null));
	}

	@Test void aRecognisableButDifferentShapeStandsDown() {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21; node.access = Opcodes.ACC_PUBLIC;
		node.name = FURNACE.replace('.', '/'); node.superName = "java/lang/Object";
		MethodNode getFuel = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getFuel", "()Ljava/util/Map;", null, null);
		getFuel.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashMap"));
		getFuel.instructions.add(new InsnNode(Opcodes.DUP));
		getFuel.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false));
		getFuel.instructions.add(new InsnNode(Opcodes.ARETURN)); // no buildFuels, no putstatic
		getFuel.maxStack = 2; getFuel.maxLocals = 1;
		node.methods.add(getFuel);
		ClassWriter writer = new ClassWriter(0); node.accept(writer);
		byte[] bytes = writer.toByteArray();
		assertSame(bytes, new FabricFuelValuesInjector().transform(FURNACE, bytes, null));
	}

	private static MethodNode method(ClassNode node, String name) {
		return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
	}

	private static java.util.List<MethodInsnNode> calls(MethodNode method, String owner, String name) {
		java.util.List<MethodInsnNode> out = new java.util.ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) out.add(call);
		}
		return out;
	}

	private static java.util.List<FieldInsnNode> putStatics(MethodNode method, String name) {
		java.util.List<FieldInsnNode> out = new java.util.ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC && field.name.equals(name)) out.add(field);
		}
		return out;
	}

	private static AbstractInsnNode realPrevious(AbstractInsnNode insn) {
		AbstractInsnNode p = insn.getPrevious();
		while (p != null && p.getOpcode() < 0) p = p.getPrevious();
		return p;
	}

	private static AbstractInsnNode realNext(AbstractInsnNode insn) {
		AbstractInsnNode n = insn.getNext();
		while (n != null && n.getOpcode() < 0) n = n.getNext();
		return n;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] furnace() throws Exception {
		Path base = TestFixtures.mergedBase();
		assumeTrue(base != null && Files.isRegularFile(base), "staged merged base absent");
		try (ZipFile zip = new ZipFile(base.toFile())) {
			assertNotNull(zip.getEntry(ENTRY), ENTRY + " absent");
			return zip.getInputStream(zip.getEntry(ENTRY)).readAllBytes();
		}
	}
}
