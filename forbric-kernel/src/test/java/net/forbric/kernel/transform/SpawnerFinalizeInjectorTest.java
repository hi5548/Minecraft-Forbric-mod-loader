/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.util.TraceClassVisitor;

import net.fabricmc.api.EnvType;
import net.forbric.api.CompatibilityFindings;
import net.forbric.kernel.TestFixtures;

/**
 * The 1.21.1 anchor, read off the real merged {@code BaseSpawner}: the hook's mob is the entity
 * {@code EntityType.loadEntityRecursive} just built, and the one local holding the tag that load was given is the
 * argument the splice inserts. The behavioural half of this repair — that the tag then reaches both event
 * families — belongs to {@code KernelSpawnerFinalize}, whose own harness is a separate port.
 */
class SpawnerFinalizeInjectorTest {
	private final SpawnerFinalizeInjector injector = new SpawnerFinalizeInjector();
	private final TransformContext context = new TransformContext(EnvType.SERVER, false, "mojmap");
	@BeforeEach @AfterEach void clearFindings() { CompatibilityFindings.reset(); }

	@Test void explicitOffSwitchLeavesTheLegacyCallerIntactAndMakesNoRequiredAnchorPromise() throws Exception {
		String property = "forbric.spawnerFinalize";
		String previous = System.getProperty(property);
		try {
			byte[] legacy = new ForbricMergedBaseCompatTransformer().transform(SpawnerFinalizeInjector.TARGET,
					stagedSpawner(), context);
			System.setProperty(property, "off");
			assertSame(legacy, injector.transform(SpawnerFinalizeInjector.TARGET, legacy, context));
			assertEquals(SpawnerFinalizeInjector.OLD_DESC, hook(host(parse(legacy))).desc);
			assertTrue(injector.anchors().anchors().isEmpty());
			assertFalse(injector.anchors().isUndeclared());
			assertTrue(CompatibilityFindings.all().isEmpty(), "explicit negative-control disable is not an unknown caller");
			System.setProperty(property, "on");
			assertNotSame(legacy, injector.transform(SpawnerFinalizeInjector.TARGET, legacy, context));
		} finally {
			if (previous == null) System.clearProperty(property); else System.setProperty(property, previous);
		}
	}

	/**
	 * The 1.21.1 premise, stated rather than assumed: one tag read, one entity load, one hook, and the hook's mob
	 * is the entity that load produced. If the merged base stops having this shape the anchor has moved, and this
	 * fails before anything downstream is allowed to guess.
	 */
	@Test void theMergedServerTickStillHasTheShapeTheProofDependsOn() throws Exception {
		MethodNode tick = host(parse(stagedSpawner()));
		assertEquals(1, calls(tick).stream().filter(c -> c.name.equals("finalizeMobSpawnSpawner")).count());
		assertEquals(1, calls(tick).stream().filter(c -> c.name.equals("loadEntityRecursive")).count());
		assertEquals(SpawnerFinalizeInjector.OLD_DESC, hook(tick).desc);
		assertEquals(Opcodes.POP, nextReal(hook(tick)).getOpcode(), "the native event result is still discarded here");
		assertEquals(SpawnerFinalizeInjector.ENTITY_LOAD_DESC, entityLoad(tick).desc);
		assertEquals(SpawnerFinalizeInjector.SPAWN_TAG_DESC, tagRead(tick).desc);
		assertEquals(Opcodes.ASTORE, nextReal(tagRead(tick)).getOpcode(),
				"the tag read's result is stored into the local the splice will use");
		assertTrue(tick.instructions.indexOf(hook(tick)) > tick.instructions.indexOf(entityLoad(tick)),
				"the hook is given the entity the load just produced");
	}

	@Test void realMergedCallerSuppliesTheSameTagThatLoadedItsMobAfterTheLegacyRepair() throws Exception {
		byte[] legacy = new ForbricMergedBaseCompatTransformer().transform(SpawnerFinalizeInjector.TARGET,
				stagedSpawner(), context);
		assertEquals(SpawnerFinalizeInjector.RUNTIME, hook(host(parse(legacy))).owner);
		verifyExchange(legacy);
	}

	/**
	 * The NeoForge-only game has the same caller the merged base kept, so it is routed the same way. The
	 * Forge-only game does not: there MinecraftForge's own hook IS the caller, and a kernel dispatch beside it
	 * would post {@code MobSpawnEvent.FinalizeSpawn} a second time — so that game stands down, loudly.
	 */
	@Test void theNeoForgeCallerIsRoutedAndTheForgeOnlyCallerStandsDown() throws Exception {
		verifyExchange(staged("neoforge-patched", "patched-mc-neoforge-"));
		byte[] forgeOnly = staged("forge-patched", "patched-mc-forge-");
		MethodNode forgeTick = host(parse(forgeOnly));
		assertTrue(SpawnerFinalizeInjector.carriesForgeFinalize(forgeTick));
		assertSame(forgeOnly, injector.transform(SpawnerFinalizeInjector.TARGET, forgeOnly, context));
		var findings = CompatibilityFindings.all();
		assertEquals(1, findings.size());
		assertEquals("spawner-finalize-direct-composition", findings.getFirst().id());
		assertFalse(findings.getFirst().required());
	}

	@Test void wrongEntityOriginOverwrittenInputAndAmbiguousReachingStoresAreRejected() throws Exception {
		for (Consumer<ClassNode> mutation : List.<Consumer<ClassNode>>of(
				n -> tagRead(host(n)).owner = "example/OtherInput",
				n -> entityLoad(host(n)).name = "loadDifferentEntity",
				n -> {
					MethodNode m = host(n); int slot = ((VarInsnNode) nextReal(tagRead(m))).var;
					InsnList overwrite = new InsnList(); overwrite.add(new InsnNode(Opcodes.ACONST_NULL)); overwrite.add(new VarInsnNode(Opcodes.ASTORE, slot));
					m.instructions.insertBefore(hook(m), overwrite); m.maxStack++;
				},
				n -> {
					MethodNode m = host(n); int slot = ((VarInsnNode) nextReal(tagRead(m))).var; LabelNode join = new LabelNode();
					InsnList conditional = new InsnList(); conditional.add(new InsnNode(Opcodes.ICONST_0));
					conditional.add(new JumpInsnNode(Opcodes.IFEQ, join)); conditional.add(new InsnNode(Opcodes.ACONST_NULL));
					conditional.add(new VarInsnNode(Opcodes.ASTORE, slot)); conditional.add(join);
					m.instructions.insertBefore(hook(m), conditional); m.maxStack++;
				})) {
			ClassNode n = parse(stagedSpawner());
			mutation.accept(n); byte[] bytes = write(n);
			assertSame(bytes, injector.transform(SpawnerFinalizeInjector.TARGET, bytes, context));
			assertTrue(CompatibilityFindings.all().stream().anyMatch(f -> f.detail().contains("no unique live spawn tag")),
					"a refused caller must say which of its shapes could not be proved");
			CompatibilityFindings.reset();
		}
	}

	@Test void aCallerThatAlreadyCarriesMinecraftForgesFinalizeHookIsNotRedirectedASecondTime() throws Exception {
		ClassNode restored = parse(stagedSpawner());
		MethodNode tick = host(restored);
		InsnList forge = new InsnList();
		for (int i = 0; i < 5; i++) forge.add(new InsnNode(Opcodes.ACONST_NULL));
		forge.add(new VarInsnNode(Opcodes.ALOAD, 0));
		forge.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SpawnerFinalizeInjector.FORGE, SpawnerFinalizeInjector.FORGE_HOOK,
				SpawnerFinalizeInjector.FORGE_DESC, false));
		forge.add(new InsnNode(Opcodes.POP));
		tick.instructions.insert(nextReal(hook(tick)), forge); tick.maxStack += 6;
		byte[] bytes = write(restored);
		new Analyzer<>(new BasicVerifier()).analyze(restored.name, host(parse(bytes)));

		byte[] legacy = new ForbricMergedBaseCompatTransformer().transform(SpawnerFinalizeInjector.TARGET, bytes, context);
		assertEquals(SpawnerFinalizeInjector.NEO, hook(host(parse(legacy))).owner,
				"the legacy repair must not route a caller that already posts MinecraftForge's event itself");
		assertSame(legacy, injector.transform(SpawnerFinalizeInjector.TARGET, legacy, context));
		MethodNode after = host(parse(legacy));
		assertEquals(SpawnerFinalizeInjector.OLD_DESC, hook(after).desc);
		assertEquals(1, calls(after).stream().filter(c -> c.owner.equals(SpawnerFinalizeInjector.FORGE)).count());
		assertTrue(calls(after).stream().noneMatch(c -> c.owner.equals(SpawnerFinalizeInjector.RUNTIME)));
		var findings = CompatibilityFindings.all(); assertEquals(1, findings.size());
		assertEquals("spawner-finalize-direct-composition", findings.getFirst().id());
		assertEquals(net.forbric.api.CompatibilityFinding.Confidence.SUSPECTED, findings.getFirst().confidence());
		assertFalse(findings.getFirst().required());

		// Negative control: the same caller without the restored call is still routed through the kernel.
		CompatibilityFindings.reset();
		byte[] routed = injector.transform(SpawnerFinalizeInjector.TARGET,
				new ForbricMergedBaseCompatTransformer().transform(SpawnerFinalizeInjector.TARGET, stagedSpawner(), context), context);
		assertEquals(SpawnerFinalizeInjector.RUNTIME, hook(host(parse(routed))).owner);
		assertTrue(CompatibilityFindings.all().isEmpty());
	}

	@Test void unknownSignaturesOrPartiallyChangedCallersAreNotGuessed() throws Exception {
		for (Consumer<ClassNode> mutation : List.<Consumer<ClassNode>>of(
				n -> hook(host(n)).desc = "()V",
				n -> hook(host(n)).itf = true,
				n -> host(n).access |= Opcodes.ACC_STATIC,
				n -> host(n).instructions.insert(hook(host(n)), new InsnNode(Opcodes.NOP)),
				n -> host(n).instructions.insert(hook(host(n)).clone(null)))) {
			ClassNode n = parse(stagedSpawner());
			mutation.accept(n); byte[] bytes = write(n); assertSame(bytes, injector.transform(SpawnerFinalizeInjector.TARGET, bytes, context));
		}
	}

	@Test
	void theSplicedDescriptorIsTheOneTheGameSideEntryActuallyDeclares() throws Exception {
		Path compiled = Path.of(System.getProperty("forbric.test.runtimeClasses", "build/classes/java/runtime"),
				"net/forbric/kernel/runtime/KernelSpawnerFinalize.class");
		assertTrue(Files.isRegularFile(compiled), "the game side must be compiled for this pin to mean anything: " + compiled);
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node,
				ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		List<String> descriptors = node.methods.stream().filter(m -> m.name.equals("finalizeMobSpawnSpawner"))
				.map(m -> m.desc).toList();
		// The splice rewrites the call to this exact descriptor: a mismatch is a NoSuchMethodError at the first
		// spawner tick, and the 7-argument native path beside it is what an unrouted caller still uses.
		assertTrue(descriptors.contains(SpawnerFinalizeInjector.NEW_DESC), descriptors.toString());
		assertTrue(descriptors.contains(SpawnerFinalizeInjector.OLD_DESC), descriptors.toString());
	}

	/**
	 * The splice itself, cross-checked structurally rather than by re-running the injector's own reasoning: the
	 * local it inserts is the one the caller stored the {@code SpawnData.getEntityToSpawn()} result into, and no
	 * other instruction moves.
	 */
	private void verifyExchange(byte[] original) throws Exception {
		ClassNode before = parse(original); MethodNode beforeHost = host(before); MethodInsnNode beforeCall = hook(beforeHost);
		int actualInputSlot = ((VarInsnNode) nextReal(tagRead(beforeHost))).var;
		byte[] changed = injector.transform(SpawnerFinalizeInjector.TARGET, original, context); assertNotSame(original, changed);
		ClassNode after = parse(changed); MethodNode afterHost = host(after); MethodInsnNode afterCall = hook(afterHost);
		assertEquals(SpawnerFinalizeInjector.RUNTIME, afterCall.owner); assertEquals(SpawnerFinalizeInjector.NEW_DESC, afterCall.desc);
		assertInstanceOf(VarInsnNode.class, previousReal(afterCall)); VarInsnNode input = (VarInsnNode) previousReal(afterCall);
		assertEquals(Opcodes.ALOAD, input.getOpcode()); assertEquals(actualInputSlot, input.var);
		assertEquals(beforeHost.maxLocals, afterHost.maxLocals); new Analyzer<>(new BasicVerifier()).analyze(after.name, afterHost);
		assertEquals(0, calls(afterHost).stream().filter(c -> c.name.equals("finalizeSpawn")).count(), "the caller must not add a second finalizer");
		assertSame(changed, injector.transform(SpawnerFinalizeInjector.TARGET, changed, context));
		afterHost.instructions.remove(input); afterCall.owner = beforeCall.owner; afterCall.desc = beforeCall.desc;
		afterHost.maxStack = beforeHost.maxStack;
		assertEquals(trace(before), trace(after), "only one parameter load and call descriptor may change; the native continuation stays intact");
	}

	private static MethodNode host(ClassNode n) { return n.methods.stream().filter(m -> m.name.equals("serverTick")).findFirst().orElseThrow(); }
	private static List<MethodInsnNode> calls(MethodNode m) { return java.util.Arrays.stream(m.instructions.toArray()).filter(i -> i instanceof MethodInsnNode).map(i -> (MethodInsnNode) i).toList(); }
	private static MethodInsnNode hook(MethodNode m) { return calls(m).stream().filter(c -> c.name.equals("finalizeMobSpawnSpawner")).findFirst().orElseThrow(); }
	private static MethodInsnNode entityLoad(MethodNode m) { return calls(m).stream().filter(c -> c.name.equals("loadEntityRecursive")).findFirst().orElseThrow(); }
	/** The tag read the injector pins: the one whose result the caller stores and hands to the entity load. */
	private static MethodInsnNode tagRead(MethodNode m) {
		return calls(m).stream().filter(c -> c.owner.equals(SpawnerFinalizeInjector.SPAWN_DATA)
				&& c.name.equals("getEntityToSpawn")).findFirst().orElseThrow();
	}
	private static AbstractInsnNode nextReal(AbstractInsnNode n) { do { n = n.getNext(); } while (n != null && n.getOpcode() < 0); return n; }
	private static AbstractInsnNode previousReal(AbstractInsnNode n) { do { n = n.getPrevious(); } while (n != null && n.getOpcode() < 0); return n; }
	private static ClassNode parse(byte[] bytes) { ClassNode n = new ClassNode(); new ClassReader(bytes).accept(n, 0); return n; }
	private static byte[] write(ClassNode n) { ClassWriter w = new ClassWriter(0); n.accept(w); return w.toByteArray(); }
	private static String trace(ClassNode n) { StringWriter out = new StringWriter(); n.accept(new TraceClassVisitor(new PrintWriter(out))); return out.toString(); }
	private static byte[] stagedSpawner() throws Exception {
		return staged("merged-base", "patched-mc-merged-", "net/minecraft/world/level/BaseSpawner");
	}
	private static byte[] staged(String subdirectory, String prefix) throws Exception {
		return staged(subdirectory, prefix, "net/minecraft/world/level/BaseSpawner");
	}
	private static byte[] staged(String subdirectory, String prefix, String entry) throws Exception {
		Path jar = TestFixtures.stagedJar(subdirectory, prefix);
		assumeTrue(jar != null && Files.isRegularFile(jar), "staged artifact absent: " + subdirectory + "/" + prefix + "*.jar");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry found = zip.getEntry(entry + ".class");
			assertNotNull(found, entry + " is gone from " + jar);
			return zip.getInputStream(found).readAllBytes();
		}
	}
}
