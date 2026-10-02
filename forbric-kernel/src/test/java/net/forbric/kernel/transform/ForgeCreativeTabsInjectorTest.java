/*
 * Copyright 2026 The Forbric Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.TestFixtures;

/**
 * PORT(1.21.1): the carriers' {@code onCreativeModeTabBuildContents} takes the tab's {@code ResourceKey} as a
 * second argument, so the merged base's one call is five arguments where 26.2's was four. The kernel method still
 * takes 26.2's four (it re-derives the key from the registry), so the injector DROPS the key and delegates. These
 * tests pin both halves: the carrier call it must find, and the four-argument kernel call it must leave behind.
 */
class ForgeCreativeTabsInjectorTest {
	private static final String TARGET = "net.minecraft.world.item.CreativeModeTab";
	private static final String INTERNAL = TARGET.replace('.', '/');
	private static final String HOST_DESC = "(Lnet/minecraft/world/item/CreativeModeTab$ItemDisplayParameters;)V";
	private static final String HOOK_DESC = "(Lnet/minecraft/world/item/CreativeModeTab;"
			+ "Lnet/minecraft/world/item/CreativeModeTab$DisplayItemsGenerator;"
			+ "Lnet/minecraft/world/item/CreativeModeTab$ItemDisplayParameters;"
			+ "Lnet/minecraft/world/item/CreativeModeTab$Output;)V";
	private static final String CARRIER_DESC = "(Lnet/minecraft/world/item/CreativeModeTab;"
			+ "Lnet/minecraft/resources/ResourceKey;"
			+ "Lnet/minecraft/world/item/CreativeModeTab$DisplayItemsGenerator;"
			+ "Lnet/minecraft/world/item/CreativeModeTab$ItemDisplayParameters;"
			+ "Lnet/minecraft/world/item/CreativeModeTab$Output;)V";
	private static final String NEO = ForeignType.EVENT_HOOKS.internal(Ecosystem.NEOFORGE);
	private static final String FORGE = ForeignType.EVENT_HOOKS.internal(Ecosystem.FORGE);
	private static final String RUNTIME = "net/forbric/kernel/runtime/KernelForgeCreativeTabs";
	private static final String HOOK = "onCreativeModeTabBuildContents";
	private final ForgeCreativeTabsInjector injector = new ForgeCreativeTabsInjector();

	@BeforeEach @AfterEach void reset() { System.clearProperty("forbric.forgeCreativeTabs"); }

	@Test void theCarriersFiveArgumentCallBecomesTheKernelsFourArgumentOne() throws Exception {
		assertTheExchange(write(fixture()));
	}

	@Test void realMergedMethodKeepsEveryInstructionAndFrame() throws Exception {
		byte[] real = staged("merged-base/patched-mc-merged-1.21.1.jar", INTERNAL);
		MethodNode before = parse(real).methods.stream().filter(m -> m.name.equals("buildContents")).findFirst().orElseThrow();
		assertEquals(1, java.util.Arrays.stream(before.instructions.toArray())
				.filter(i -> i instanceof MethodInsnNode c && c.name.equals(HOOK)).count(),
				"the merged base has exactly one contents-building hook to exchange");
		assertTrue(java.util.Arrays.stream(before.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c
				&& c.owner.equals(NEO) && c.name.equals(HOOK) && c.desc.equals(CARRIER_DESC)),
				"the merged base's one creative-tab call must still be the carriers' five-argument hook");
		assertTheExchange(real);
	}

	@Test void switchedOffAndReappliedAreIdentityOperations() {
		byte[] original = write(fixture());
		byte[] changed = injector.transform(TARGET, original, context());
		assertNotSame(original, changed);
		assertSame(changed, injector.transform(TARGET, changed, context()));
		System.setProperty("forbric.forgeCreativeTabs", "off");
		assertSame(original, injector.transform(TARGET, original, context()));
		assertSame(changed, injector.transform(TARGET, changed, context()));
		assertTrue(injector.anchors().anchors().isEmpty(), "a deliberate off switch is not a missed repair");
	}

	@Test void declaresTheRealClassAsRequiredOnlyWhileEnabled() {
		var anchors = injector.anchors().anchors();
		assertEquals(1, anchors.size());
		assertEquals(TARGET, anchors.getFirst().binaryName());
		assertEquals(AnchorSet.Severity.REQUIRED, anchors.getFirst().severity());
	}

	@Test void unexpectedShapesStandDownWithoutPartialChanges() {
		reject("wrong method descriptor", n -> method(n).desc = "()V");
		reject("static host", n -> method(n).access |= Opcodes.ACC_STATIC);
		reject("four-argument carrier call", n -> call(n).desc = HOOK_DESC);
		reject("wrong invocation kind", n -> call(n).setOpcode(Opcodes.INVOKEVIRTUAL));
		reject("interface call", n -> call(n).itf = true);
		reject("unexpected owner", n -> call(n).owner = "example/OtherHooks");
		reject("Forge already owns the hook", n -> call(n).owner = FORGE);
		reject("duplicate call", n -> method(n).instructions.insertBefore(call(n),
				new MethodInsnNode(Opcodes.INVOKESTATIC, NEO, HOOK, CARRIER_DESC, false)));
		reject("partially composed", n -> method(n).instructions.insertBefore(call(n),
				new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "buildContents", HOOK_DESC, false)));
		reject("additional caller", n -> {
			MethodNode extra = new MethodNode(Opcodes.ACC_PUBLIC, "anotherCaller", HOST_DESC, null, null);
			extra.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, NEO, HOOK, CARRIER_DESC, false));
			extra.instructions.add(new InsnNode(Opcodes.RETURN));
			n.methods.add(extra);
		});
		reject("duplicate declaration", n -> n.methods.add(new MethodNode(Opcodes.ACC_PUBLIC,
				"buildContents", HOST_DESC, null, null)));
		reject("class bytes do not match the supplied name", n -> n.name = "example/OtherClass");
		byte[] original = write(fixture());
		assertSame(original, injector.transform("example.OtherClass", original, context()));
		assertNull(injector.transform(TARGET, null, context()));
		byte[] empty = new byte[0];
		assertSame(empty, injector.transform(TARGET, empty, context()));
	}

	@Test void realCarriersPreserveTheParentAndSearchVisibilityUnion() throws Exception {
		ClassNode forge = parse(staged("forge-runtime/forge-runtime.jar", FORGE));
		ClassNode neo = parse(staged("neoforge-runtime/neoforge-runtime.jar", NEO));
		MethodNode forgeHook = named(forge, HOOK, CARRIER_DESC);
		MethodNode neoHook = named(neo, HOOK, CARRIER_DESC);
		assertNotNull(forgeHook, "MinecraftForge's five-argument hook must exist");
		assertNotNull(neoHook, "NeoForge's five-argument hook must exist");
		assertTrue((forgeHook.access & Opcodes.ACC_STATIC) != 0);
		assertTrue((neoHook.access & Opcodes.ACC_STATIC) != 0);

		String visibility = "Lnet/minecraft/world/item/CreativeModeTab$TabVisibility;";
		String mergeDesc = "(Lnet/minecraft/world/item/ItemStack;" + visibility + visibility + ")" + visibility;
		List<Handle> merges = new ArrayList<>();
		for (var instruction : forgeHook.instructions) {
			if (instruction instanceof InvokeDynamicInsnNode dynamic) {
				for (Object argument : dynamic.bsmArgs) {
					if (argument instanceof Handle handle && handle.getOwner().equals(FORGE)
							&& handle.getDesc().equals(mergeDesc)) merges.add(handle);
				}
			}
		}
		assertEquals(1, merges.size(), "the collector must use the carrier's measured visibility merge");
		MethodNode merge = named(forge, merges.getFirst().getName(), mergeDesc);
		assertNotNull(merge);
		assertEquals(List.of(Opcodes.GETSTATIC, Opcodes.ARETURN), opcodes(merge));
		FieldInsnNode result = (FieldInsnNode) java.util.Arrays.stream(merge.instructions.toArray())
				.filter(i -> i.getOpcode() >= 0).findFirst().orElseThrow();
		assertEquals("PARENT_AND_SEARCH_TABS", result.name);
		assertEquals("net/minecraft/world/item/CreativeModeTab$TabVisibility", result.owner);

		List<String> emitted = new ArrayList<>();
		int outputs = 0;
		for (var instruction : neoHook.instructions) {
			if (instruction instanceof FieldInsnNode field && field.owner.equals(result.owner)) emitted.add(field.name);
			if (instruction instanceof MethodInsnNode invoke
					&& invoke.owner.equals("net/minecraft/world/item/CreativeModeTab$Output") && invoke.name.equals("accept")) outputs++;
		}
		assertEquals(List.of("PARENT_TAB_ONLY", "SEARCH_TAB_ONLY"), emitted);
		assertEquals(2, outputs, "Neo's separate parent/search emissions must both enter Forge's collector");
		ClassNode map = parse(staged("forge-runtime/forge-runtime.jar", "net/minecraftforge/common/util/MutableHashedLinkedMap"));
		MethodNode put = named(map, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
		assertNotNull(put);
		assertTrue(java.util.Arrays.stream(put.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c
				&& c.owner.equals("net/minecraftforge/common/util/MutableHashedLinkedMap$MergeFunction") && c.name.equals("apply")),
				"repeated stacks must invoke the merge function instead of keeping the last visibility");
	}

	private void assertTheExchange(byte[] original) throws Exception {
		ClassNode before = parse(original);
		assertEquals(NEO, call(before).owner);
		assertEquals(CARRIER_DESC, call(before).desc);
		int beforeOpcodes = opcodes(method(before)).size();

		byte[] changed = injector.transform(TARGET, original, context());
		assertNotSame(original, changed);
		ClassNode after = parse(changed);
		assertEquals(0, java.util.Arrays.stream(method(after).instructions.toArray())
				.filter(i -> i instanceof MethodInsnNode c && NEO.equals(c.owner)).count(),
				"the five-argument carrier call must be gone");
		MethodInsnNode redirected = java.util.Arrays.stream(method(after).instructions.toArray())
				.filter(i -> i instanceof MethodInsnNode c && c.owner.equals(RUNTIME))
				.map(i -> (MethodInsnNode) i).findFirst().orElseThrow();
		assertEquals("buildContents", redirected.name);
		assertEquals(HOOK_DESC, redirected.desc, "the kernel takes 26.2's four arguments; the key is dropped");
		assertEquals(Opcodes.INVOKESTATIC, redirected.getOpcode());
		assertFalse(redirected.itf);
		// Three stores + a pop + three loads replace the one popped key: the exchange is seven instructions longer.
		assertEquals(beforeOpcodes + 7, opcodes(method(after)).size());
		new Analyzer<>(new BasicVerifier()).analyze(after.name, method(after));
		assertSame(changed, injector.transform(TARGET, changed, context()), "a second pass changes nothing");
	}

	private void reject(String reason, Consumer<ClassNode> mutation) {
		ClassNode node = fixture(); mutation.accept(node); byte[] bytes = write(node);
		assertSame(bytes, injector.transform(TARGET, bytes, context()), reason);
	}
	private static ClassNode fixture() {
		ClassNode node = new ClassNode(); node.version = Opcodes.V21; node.access = Opcodes.ACC_PUBLIC;
		node.name = INTERNAL; node.superName = "java/lang/Object";
		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "buildContents", HOST_DESC, null, null);
		for (int i = 0; i < 5; i++) method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
		method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, NEO, HOOK, CARRIER_DESC, false));
		method.instructions.add(new InsnNode(Opcodes.RETURN)); method.maxStack = 5; method.maxLocals = 2;
		node.methods.add(method); return node;
	}
	private static MethodNode method(ClassNode node) {
		return node.methods.stream().filter(m -> m.name.equals("buildContents")).findFirst().orElseThrow();
	}
	private static MethodInsnNode call(ClassNode node) {
		return java.util.Arrays.stream(method(node).instructions.toArray())
				.filter(i -> i instanceof MethodInsnNode call && call.name.equals(HOOK))
				.map(i -> (MethodInsnNode) i).findFirst().orElseThrow();
	}
	private static MethodNode named(ClassNode node, String name, String descriptor) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst().orElse(null);
	}
	private static List<Integer> opcodes(MethodNode method) {
		return java.util.Arrays.stream(method.instructions.toArray()).map(i -> i.getOpcode()).filter(i -> i >= 0).toList();
	}
	private static byte[] write(ClassNode node) { ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray(); }
	private static ClassNode parse(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
	private static TransformContext context() { return new TransformContext(EnvType.CLIENT, false, "mojmap"); }

	/** The staged jar, resolved through TestFixtures so a retarget moves the version with the build, not the test. */
	private static byte[] staged(String jar, String entry) throws Exception {
		Path path;
		if (jar.startsWith("merged-base/")) {
			path = TestFixtures.mergedBase();
		} else {
			path = TestFixtures.stagedRoot().resolve(jar);
		}
		assumeTrue(path != null && Files.isRegularFile(path), "staged artifact absent: " + path);
		try (ZipFile zip = new ZipFile(path.toFile())) {
			assertNotNull(zip.getEntry(entry + ".class"), entry + " missing from " + path);
			return zip.getInputStream(zip.getEntry(entry + ".class")).readAllBytes();
		}
	}
}
