/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * The 1.21.1 FlowerPotBlock shape, whose three edits were all inert (the review's P3).
 *
 * <p>On this generation the vanilla-shaped constructor only DELEGATES to the supplier constructor (so
 * {@code potted = null} moved there and the old edit found nothing), {@code addPlant} takes
 * {@code ResourceLocation} rather than {@code Identifier}, and {@code useItemOn} returns
 * {@code ItemInteractionResult} rather than {@code InteractionResult}. Synthetic bytes, so it runs without a
 * staged base; the real class is fed the same way in the offline probe.
 */
class FlowerPotRepairInjectorTest {
	private static final String OWNER = FlowerPotRepairInjector.OWNER;

	@Test
	void storesThePlantInTheDelegatingConstructorAndLooksUpEveryFamily() throws Exception {
		byte[] original = merged1211();
		byte[] out = new FlowerPotRepairInjector().transform(FlowerPotRepairInjector.TARGET, original, null);
		assertNotSame(original, out, "all three edits must fire on the 1.21.1 shape");
		ClassNode node = node(out);

		// The 2-arg constructor stores its OWN plant (arg1) into potted and POTTED_BY_CONTENT, even though the
		// `potted = null` it used to edit now lives in the delegating constructor.
		MethodNode ctor = method(node, "<init>", FlowerPotRepairInjector.BLOCK_CTOR);
		assertEquals(1, count(ctor, i -> i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.name.equals("potted")));
		assertTrue(find(ctor, i -> i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.name.equals("potted"))
				.getPrevious() instanceof VarInsnNode load && load.var == 1, "potted = the plant");
		assertTrue(ctor.instructions.toArray().length > 0 && find(ctor, i -> i instanceof FieldInsnNode f && f.name.equals("POTTED_BY_CONTENT")) != null,
				"and in POTTED_BY_CONTENT, as vanilla");

		// useItemOn is found by its 1.21.1 return type and rewritten onto KernelFlowerPots.fullPotFor.
		MethodNode use = method(node, "useItemOn", FlowerPotRepairInjector.USE_ITEM_ON);
		assertNull(find(use, i -> i instanceof MethodInsnNode c && (c.name.equals("getDelegateOrThrow") || c.name.equals("getOrDefault"))));
		assertNull(find(use, i -> i instanceof TypeInsnNode t && t.desc.equals("java/util/function/Supplier")));
		assertTrue(find(use, i -> i instanceof MethodInsnNode c && c.owner.equals(FlowerPotRepairInjector.RUNTIME) && c.name.equals("fullPotFor")) != null);

		for (MethodNode m : List.of(ctor, use, method(node, "addPlant", FlowerPotRepairInjector.ADD_PLANT))) {
			new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
		}
		assertEquals(0, FlowerPotRepairInjector.storePlant(node) + FlowerPotRepairInjector.lookUpAllFamilies(node) + FlowerPotRepairInjector.recordAddedPlant(node),
				"a second pass changes nothing");
		assertSame(out, new FlowerPotRepairInjector().transform(FlowerPotRepairInjector.TARGET, out, null));
	}

	private static int count(MethodNode m, java.util.function.Predicate<AbstractInsnNode> test) {
		int n = 0;
		for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) if (test.test(i)) n++;
		return n;
	}

	private static AbstractInsnNode find(MethodNode m, java.util.function.Predicate<AbstractInsnNode> test) {
		for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) if (test.test(i)) return i;
		return null;
	}

	/** The merged 1.21.1 shape: a delegating 2-arg ctor, ResourceLocation addPlant, ItemInteractionResult useItemOn. */
	private static byte[] merged1211() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "potted", "Lnet/minecraft/world/level/block/Block;", null, null).visitEnd();
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "fullPots", "Ljava/util/Map;", null, null).visitEnd();
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "POTTED_BY_CONTENT", "Ljava/util/Map;", null, null).visitEnd();

		// The supplier constructor: this is where `potted = null` moved.
		MethodVisitor supplier = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
				"(Ljava/util/function/Supplier;Ljava/util/function/Supplier;Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;)V", null, null);
		supplier.visitCode();
		supplier.visitVarInsn(Opcodes.ALOAD, 0);
		supplier.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		supplier.visitVarInsn(Opcodes.ALOAD, 0);
		supplier.visitInsn(Opcodes.ACONST_NULL);
		supplier.visitFieldInsn(Opcodes.PUTFIELD, OWNER, "potted", "Lnet/minecraft/world/level/block/Block;");
		supplier.visitInsn(Opcodes.RETURN);
		supplier.visitMaxs(0, 0);
		supplier.visitEnd();

		// The vanilla-shaped constructor: delegate, then return. No putfield potted of its own.
		MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", FlowerPotRepairInjector.BLOCK_CTOR, null, null);
		ctor.visitCode();
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitInsn(Opcodes.ACONST_NULL);
		ctor.visitInsn(Opcodes.ACONST_NULL);
		ctor.visitVarInsn(Opcodes.ALOAD, 2);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, OWNER, "<init>",
				"(Ljava/util/function/Supplier;Ljava/util/function/Supplier;Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;)V", false);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(0, 0);
		ctor.visitEnd();

		// addPlant(ResourceLocation, Supplier): already fills fullPots the way the injector would, so it stands down.
		MethodVisitor add = cw.visitMethod(Opcodes.ACC_PUBLIC, "addPlant", FlowerPotRepairInjector.ADD_PLANT, null, null);
		add.visitCode();
		add.visitVarInsn(Opcodes.ALOAD, 0);
		add.visitFieldInsn(Opcodes.GETFIELD, OWNER, "fullPots", "Ljava/util/Map;");
		add.visitInsn(Opcodes.POP);
		add.visitInsn(Opcodes.RETURN);
		add.visitMaxs(0, 0);
		add.visitEnd();

		// useItemOn(…)ItemInteractionResult: the MinecraftForge lookup expression the injector rewrites.
		MethodVisitor use = cw.visitMethod(Opcodes.ACC_PROTECTED, "useItemOn", FlowerPotRepairInjector.USE_ITEM_ON, null, null);
		use.visitCode();
		use.visitVarInsn(Opcodes.ALOAD, 0);
		use.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OWNER, "getEmptyPot", "()Lnet/minecraft/world/level/block/FlowerPotBlock;", false);
		use.visitFieldInsn(Opcodes.GETFIELD, OWNER, "fullPots", "Ljava/util/Map;");
		use.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraftforge/registries/ForgeRegistries", "BLOCKS", "Lnet/minecraftforge/registries/IForgeRegistry;");
		use.visitVarInsn(Opcodes.ALOAD, 1);
		use.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/item/ItemStack", "getBlock", "()Lnet/minecraft/world/level/block/Block;", false);
		use.visitMethodInsn(Opcodes.INVOKEINTERFACE, "net/minecraftforge/registries/IForgeRegistry", "getKey", "(Ljava/lang/Object;)Lnet/minecraft/resources/ResourceLocation;", true);
		use.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraftforge/registries/ForgeRegistries", "BLOCKS", "Lnet/minecraftforge/registries/IForgeRegistry;");
		use.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/world/level/block/Blocks", "AIR", "Lnet/minecraft/world/level/block/Block;");
		use.visitMethodInsn(Opcodes.INVOKEINTERFACE, "net/minecraftforge/registries/IForgeRegistry", "getDelegateOrThrow", "(Ljava/lang/Object;)Lnet/minecraft/core/Holder$Reference;", true);
		use.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "getOrDefault", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
		use.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Supplier");
		use.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", true);
		use.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/world/level/block/Block");
		use.visitInsn(Opcodes.POP);
		use.visitInsn(Opcodes.ACONST_NULL);
		use.visitInsn(Opcodes.ARETURN);
		use.visitMaxs(0, 0);
		use.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
		throw new AssertionError(name + desc);
	}
}
