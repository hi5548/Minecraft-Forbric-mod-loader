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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.TestFixtures;

/**
 * The two 1.21.1 seams over the REAL merged {@code ReloadableServerRegistries} — the class's one
 * {@code LootDataType.deserialize} call and the registry-parse lambda's tail — and the reason the kernel has to
 * post NeoForge's hook here: the merged {@code LootDataType} kept MinecraftForge's body and left NeoForge's as an
 * uncalled private lambda.
 */
class LootTableEventBridgeInjectorTest {
	private static final String LOOT_DATA_TYPE = "net/minecraft/world/level/storage/loot/LootDataType";
	private static final String DESERIALIZE = "(Lnet/minecraft/resources/ResourceLocation;"
			+ "Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Ljava/util/Optional;";
	private static final String REGISTRIES = "net/minecraft/server/ReloadableServerRegistries";
	private static final String LOOT_DATA = "net/minecraft/world/level/storage/loot/LootDataType";

	@AfterEach
	void reset() {
		System.clearProperty(LootTableEventBridgeInjector.PROPERTY);
	}

	@Test
	void bothSeamsAreRoutedOnceAndTheOriginalsAreGone() throws Exception {
		byte[] raw = staged(REGISTRIES);
		byte[] routed = new LootTableEventBridgeInjector().transform(LootTableEventBridgeInjector.TARGET, raw, null);
		assertNotSame(raw, routed);
		ClassNode node = parse(routed);
		assertEquals(0, calls(node, LOOT_DATA_TYPE, "deserialize").size(), "the native call must not survive beside its route");
		List<MethodInsnNode> bridged = bridged(node);
		assertEquals(2, bridged.size());
		MethodInsnNode load = bridged.stream().filter(c -> c.name.equals(LootTableEventBridgeInjector.LOAD_LOOT_TABLE)).findFirst().orElseThrow();
		MethodInsnNode parsed = bridged.stream().filter(c -> c.name.equals(LootTableEventBridgeInjector.REGISTRY_PARSED)).findFirst().orElseThrow();
		assertEquals(Opcodes.INVOKESTATIC, load.getOpcode());
		assertEquals(LootTableEventBridgeInjector.LOAD_LOOT_TABLE_DESC, load.desc);
		assertEquals(LootTableEventBridgeInjector.REGISTRY_PARSED_DESC, parsed.desc);
		assertEquals("lambda$scheduleElementParse$3", methodHolding(node, load));
		assertEquals("lambda$scheduleElementParse$4", methodHolding(node, parsed));
		// The registry call is inserted where the return value is still on its way out: right before `aload v; areturn`.
		AbstractInsnNode next = nextReal(parsed);
		assertTrue(next instanceof VarInsnNode loaded && loaded.getOpcode() == Opcodes.ALOAD,
				"the registry load must still follow the kernel call");
		assertEquals(Opcodes.ARETURN, nextReal(next).getOpcode());
		for (MethodNode method : node.methods) {
			new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
		}
	}

	@Test
	void aSecondPassChangesNothingFurther() throws Exception {
		LootTableEventBridgeInjector injector = new LootTableEventBridgeInjector();
		byte[] once = injector.transform(LootTableEventBridgeInjector.TARGET, staged(REGISTRIES), null);
		assertSame(once, injector.transform(LootTableEventBridgeInjector.TARGET, once, null));
		assertEquals(2, injector.routedSites());
	}

	/**
	 * The reason the kernel posts NeoForge's event rather than only routing Fabric's: on 1.21.1 the merge kept
	 * MinecraftForge's {@code deserialize}, so {@code onLoadLootTable} is what the live path calls, and NeoForge's
	 * {@code EventHooks.loadLootTable} survives only in the private lambda nothing can reach.
	 */
	@Test
	void theLivePathIsMinecraftForgesHookAndNeoForgesSurvivesOnlyAsAnOrphan() throws Exception {
		ClassNode lootData = parse(staged(LOOT_DATA));
		MethodNode deserialize = lootData.methods.stream().filter(m -> m.name.equals("deserialize")).findFirst().orElseThrow();
		assertTrue(calls(deserialize).stream().anyMatch(c -> c.owner.equals("net/minecraftforge/event/ForgeEventFactory")
				&& c.name.equals("onLoadLootTable")), "the native load must still post MinecraftForge's event");
		assertTrue(calls(deserialize).stream().noneMatch(c -> c.owner.equals("net/neoforged/neoforge/event/EventHooks")),
				"NeoForge's hook must not be what the native load calls — the bridge would then post it twice");
		List<String> where = new ArrayList<>();
		for (MethodNode method : lootData.methods) {
			for (MethodInsnNode call : calls(method)) {
				if (call.owner.equals("net/neoforged/neoforge/event/EventHooks") && call.name.equals("loadLootTable")) {
					where.add(method.name);
				}
			}
		}
		assertEquals(List.of("lambda$deserialize$3"), where,
				"NeoForge's hook is only in the lambda the merge orphaned; anything else means the base changed and "
						+ "the bridge would double-post it");
		for (MethodNode method : lootData.methods) {
			assertTrue(calls(method).stream().noneMatch(c -> c.name.equals("lambda$deserialize$3")),
					"the orphan must have no caller: " + method.name);
		}
	}

	/** The synthetic seam in both its halves routes as well; the real class is the evidence, this is the shape. */
	@Test
	void bothSeamsOfASyntheticClassAreRoutedTogether() {
		byte[] both = seam(true, true, true);
		byte[] routed = new LootTableEventBridgeInjector().transform(LootTableEventBridgeInjector.TARGET, both, null);
		assertNotSame(both, routed);
		assertEquals(2, bridged(parse(routed)).size());
	}

	/** Half a seam is worse than none: with only one of the two present the class must come back untouched. */
	@Test
	void bothOrNothing_aClassWithOnlyOneSeamIsLeftAlone() {
		byte[] onlyLoot = seam(true, false, true);
		assertSame(onlyLoot, new LootTableEventBridgeInjector().transform(LootTableEventBridgeInjector.TARGET, onlyLoot, null));
		byte[] onlyParse = seam(false, true, true);
		assertSame(onlyParse, new LootTableEventBridgeInjector().transform(LootTableEventBridgeInjector.TARGET, onlyParse, null));
	}

	/** A parse lambda whose tail shifted returns something that is not the registry it built. Not the seam. */
	@Test
	void aParseLambdaWithAShiftedTailIsNotAHook() {
		byte[] shifted = seam(true, true, false);
		assertSame(shifted, new LootTableEventBridgeInjector().transform(LootTableEventBridgeInjector.TARGET, shifted, null));
	}

	@Test
	void anUnrelatedClassPassesThroughByIdentity() {
		byte[] other = seam(true, true, true);
		assertSame(other, new LootTableEventBridgeInjector().transform("example/Other", other, null));
	}

	@Test
	void theRoutedNameAndDescriptorMatchTheCompiledGameSideClass() throws Exception {
		Path compiled = Path.of(System.getProperty("forbric.test.runtimeClasses", "build/classes/java/runtime"),
				"net/forbric/kernel/runtime/KernelLootBridge.class");
		assertTrue(Files.isRegularFile(compiled), "the game side must be compiled for this pin to mean anything: " + compiled);
		Map<String, String> methods = new HashMap<>();
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node,
				ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		for (MethodNode method : node.methods) methods.put(method.name, method.desc);
		// The injector routes by owner+name+DESCRIPTOR, so a mismatch here is a NoSuchMethodError on the first
		// datapack load, not a compile error — and never a build failure.
		assertEquals(LootTableEventBridgeInjector.LOAD_LOOT_TABLE_DESC, methods.get(LootTableEventBridgeInjector.LOAD_LOOT_TABLE));
		assertEquals(LootTableEventBridgeInjector.REGISTRY_PARSED_DESC, methods.get(LootTableEventBridgeInjector.REGISTRY_PARSED));
	}

	@Test
	void switchedOffItStandsDownAndDeclaresNoAnchor() throws Exception {
		byte[] raw = staged(REGISTRIES);
		System.setProperty(LootTableEventBridgeInjector.PROPERTY, "off");
		LootTableEventBridgeInjector injector = new LootTableEventBridgeInjector();
		assertSame(raw, injector.transform(LootTableEventBridgeInjector.TARGET, raw, null));
		assertTrue(injector.anchors().anchors().isEmpty());
		assertFalse(injector.anchors().isUndeclared());
		assertFalse(LootTableEventBridgeInjector.enabled());
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static List<MethodInsnNode> bridged(ClassNode node) {
		List<MethodInsnNode> found = new ArrayList<>();
		for (MethodNode method : node.methods) {
			for (MethodInsnNode call : calls(method)) {
				if (LootTableEventBridgeInjector.BRIDGE.equals(call.owner)) found.add(call);
			}
		}
		return found;
	}

	private static String methodHolding(ClassNode node, MethodInsnNode sought) {
		for (MethodNode method : node.methods) {
			for (MethodInsnNode call : calls(method)) if (call == sought) return method.name;
		}
		throw new AssertionError("the call is in no method");
	}

	private static List<MethodInsnNode> calls(ClassNode node, String owner, String name) {
		List<MethodInsnNode> found = new ArrayList<>();
		for (MethodNode method : node.methods) {
			for (MethodInsnNode call : calls(method)) if (call.owner.equals(owner) && call.name.equals(name)) found.add(call);
		}
		return found;
	}

	private static List<MethodInsnNode> calls(MethodNode method) {
		List<MethodInsnNode> found = new ArrayList<>();
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call) found.add(call);
		}
		return found;
	}

	private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
		do { insn = insn.getNext(); } while (insn != null && insn.getOpcode() < 0);
		return insn;
	}

	/**
	 * A class shaped like the seam: optionally the per-file lambda with its {@code deserialize} call, optionally
	 * the parse lambda, and a tail that either returns the registry it built or does not. Deliberately minimal —
	 * the injector reads it and nothing ever loads it, so there are no frames and no reachable types.
	 */
	private static byte[] seam(boolean loot, boolean parseMethod, boolean honestTail) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = LootTableEventBridgeInjector.TARGET.replace('.', '/');
		node.superName = "java/lang/Object";

		MethodNode perFile = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "lambda$scheduleElementParse$3",
				"(" + ref(LOOT_DATA_TYPE) + "Lnet/minecraft/resources/ResourceLocation;"
						+ "Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)V", null, null);
		if (loot) {
			perFile.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			perFile.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
			perFile.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
			perFile.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
			perFile.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LOOT_DATA_TYPE, "deserialize", DESERIALIZE, false));
			perFile.instructions.add(new InsnNode(Opcodes.POP));
		}
		perFile.instructions.add(new InsnNode(Opcodes.RETURN));
		perFile.maxStack = 4; perFile.maxLocals = 4;
		node.methods.add(perFile);

		if (parseMethod) {
			MethodNode built = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, LootTableEventBridgeInjector.PARSE_HOST,
					LootTableEventBridgeInjector.PARSE_HOST_DESC, null, null);
			built.instructions.add(new TypeInsnNode(Opcodes.NEW, LootTableEventBridgeInjector.MAPPED_REGISTRY));
			built.instructions.add(new InsnNode(Opcodes.DUP));
			built.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			built.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LOOT_DATA_TYPE, "registryKey",
					"()Lnet/minecraft/resources/ResourceKey;", false));
			built.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
			built.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, LootTableEventBridgeInjector.MAPPED_REGISTRY,
					"<init>", LootTableEventBridgeInjector.MAPPED_REGISTRY_DESC, false));
			built.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
			built.instructions.add(new VarInsnNode(Opcodes.ALOAD, honestTail ? 3 : 2));
			built.instructions.add(new InsnNode(Opcodes.ARETURN));
			built.maxStack = 4; built.maxLocals = 4;
			node.methods.add(built);
		}
		return write(node);
	}

	private static String ref(String internalName) {
		return "L" + internalName + ";";
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
		return node;
	}

	private static byte[] write(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static byte[] staged(String entry) throws Exception {
		Path jar = TestFixtures.mergedBase();
		assertTrue(jar != null && Files.isRegularFile(jar), "staged merged base absent");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry found = zip.getEntry(entry + ".class");
			assertTrue(found != null, entry + " is gone from " + jar);
			return zip.getInputStream(found).readAllBytes();
		}
	}
}
