package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** The Ender Dragon's parts, NeoForge-typed again on the merged base; on the real classes. */
@ResourceLock("system-properties")
class DragonPartsInjectorTest {
	private static final Path STAGED = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"));
	private static final Path MERGED = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path NEO = STAGED.resolve("neoforge-patched/patched-mc-neoforge-26.2.jar");
	private static final String PART = "net/minecraft/world/entity/boss/enderdragon/EnderDragonPart";
	private static final String DRAGON = "net/minecraft/world/entity/boss/enderdragon/EnderDragon";
	private static final String HITBOXES = "net/minecraft/client/renderer/debug/EntityHitboxDebugRenderer";

	@AfterEach void reset() { System.clearProperty(DragonPartsInjector.PROPERTY); }

	@Test void thePartIsANeoForgePartEntity() throws Exception {
		byte[] original = NativeCoremodParityTest.read(MERGED, PART);
		assertEquals(DragonPartsInjector.FORGE_PART, node(original).superName, "premise: the merge put it under MinecraftForge's");
		byte[] out = new DragonPartsInjector().transform(DragonPartsInjector.PART, original, null);
		ClassNode part = node(out);
		assertEquals(DragonPartsInjector.NEO_PART, part.superName);
		assertTrue(part.signature.startsWith("L" + DragonPartsInjector.NEO_PART + "<"), part.signature);
		MethodNode ctor = part.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
		assertTrue(Arrays.stream(ctor.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c && c.owner.equals(DragonPartsInjector.NEO_PART)
				&& c.name.equals("<init>")), "its super constructor call is NeoForge's");
		assertFalse(new String(out, java.nio.charset.StandardCharsets.ISO_8859_1).contains(DragonPartsInjector.FORGE_PART), "no Forge-typed reference is left");
		assertSame(out, new DragonPartsInjector().transform(DragonPartsInjector.PART, out, null));
	}

	@Test void theDragonAnswersNeoForgesGetPartsAndMinecraftForgesWithNothing() throws Exception {
		byte[] out = new DragonPartsInjector().transform(DragonPartsInjector.DRAGON, NativeCoremodParityTest.read(MERGED, DRAGON), null);
		ClassNode dragon = node(out);
		MethodNode neo = method(dragon, "getParts", DragonPartsInjector.NEO_GET_PARTS);
		assertTrue(Arrays.stream(neo.instructions.toArray()).anyMatch(i -> i instanceof FieldInsnNode f && f.name.equals("subEntities")));
		MethodNode forge = method(dragon, "getParts", DragonPartsInjector.FORGE_GET_PARTS);
		List<AbstractInsnNode> real = Arrays.stream(forge.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
		assertEquals(List.of(Opcodes.ICONST_0, Opcodes.ANEWARRAY, Opcodes.ARETURN), real.stream().map(AbstractInsnNode::getOpcode).toList(),
				"no Forge-typed part exists any more: an empty array, which every Forge-typed caller in the game handles");
		new Analyzer<>(new BasicVerifier()).analyze(DRAGON, neo);
		new Analyzer<>(new BasicVerifier()).analyze(DRAGON, forge);
		assertSame(out, new DragonPartsInjector().transform(DragonPartsInjector.DRAGON, out, null));
	}

	@Test void theDebugHitboxesReadNeoForgesParts() throws Exception {
		byte[] out = new DragonPartsInjector().transform(DragonPartsInjector.HITBOXES, NativeCoremodParityTest.read(MERGED, HITBOXES), null);
		assertFalse(new String(out, java.nio.charset.StandardCharsets.ISO_8859_1).contains(DragonPartsInjector.FORGE_PART));
		MethodNode show = node(out).methods.stream().filter(m -> m.name.equals("showHitboxes")).findFirst().orElseThrow();
		assertTrue(Arrays.stream(show.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c && c.name.equals("getParts")
				&& c.desc.equals(DragonPartsInjector.NEO_GET_PARTS)));
		new Analyzer<>(new BasicVerifier()).analyze(HITBOXES, show);
	}

	@Test void neoForgesOwnClassesAreLeftAlone() throws Exception {
		for (String name : List.of(PART, DRAGON)) {
			byte[] own = NativeCoremodParityTest.read(NEO, name);
			assertSame(own, new DragonPartsInjector().transform(name.replace('/', '.'), own, null), name);
		}
	}

	@Test void theClientsTrackingCallbacksAreLeftToThePartTrackingRepairs() throws Exception {
		// KernelBoot runs this repair with ClientPartTrackingInjector and ForgePartTrackingInjector. When it retyped these
		// callbacks to NeoForge's parts, the first lost its anchor: a MinecraftForge mod's parts threw on sight, and
		// NeoForge's stayed in partEntities, where the second's Level.getEntities casts them to MinecraftForge's PartEntity.
		byte[] merged = NativeCoremodParityTest.read(MERGED, ClientPartTrackingInjector.CALLBACKS_INTERNAL);
		byte[] out = new DragonPartsInjector().transform(ClientPartTrackingInjector.CALLBACKS, merged, null);
		assertSame(merged, out, "the client's tracking callbacks are left as merged");
		assertNotSame(out, new ClientPartTrackingInjector().transform(ClientPartTrackingInjector.CALLBACKS, out, null),
				"the client part tracking still finds its anchor after this repair");
	}

	@Test void theThreePartRepairsComeOutTheSameInAnyOrder() throws Exception {
		// TransformerRegistrationOrderTest pins that KernelBoot registers each of them, not in which order. That is safe
		// only while every class any of them edits comes out byte for byte the same whichever runs first.
		List<List<Integer>> orders = List.of(List.of(0, 1, 2), List.of(0, 2, 1), List.of(1, 0, 2), List.of(1, 2, 0),
				List.of(2, 0, 1), List.of(2, 1, 0));
		for (String name : List.of(PART, DRAGON, HITBOXES, ClientPartTrackingInjector.CALLBACKS_INTERNAL,
				ForgePartTrackingInjector.SERVER_CALLBACKS_INTERNAL, ForgePartTrackingInjector.LEVEL_INTERNAL)) {
			byte[] merged = NativeCoremodParityTest.read(MERGED, name);
			byte[] first = null;
			for (List<Integer> order : orders) {
				byte[] bytes = merged;
				for (int which : order) bytes = repair(which).transform(name.replace('/', '.'), bytes, null);
				if (first == null) {
					assertNotSame(merged, bytes, name + " is edited by one of them");
					first = bytes;
				} else {
					assertArrayEquals(first, bytes, name + " comes out differently in the order " + order);
				}
			}
		}
	}

	@Test void theSwitchLeavesAllThreeAlone() throws Exception {
		System.setProperty(DragonPartsInjector.PROPERTY, "off");
		for (String name : List.of(PART, DRAGON, HITBOXES)) {
			byte[] merged = NativeCoremodParityTest.read(MERGED, name);
			assertSame(merged, new DragonPartsInjector().transform(name.replace('/', '.'), merged, null), name);
		}
	}

	/**
	 * On 1.21.1 the part already extends NeoForge's PartEntity, so {@code rebasePart} is a no-op by design and the
	 * PART anchor must be a HEDGE (a REQUIRED Miss is the per-launch false alarm the review flagged). The anchors
	 * for the real work on this generation stay REQUIRED.
	 */
	@Test void theAlreadyNeoForgePartIsAHedgeAndTheRealWorkStaysRequired() {
		List<AnchorSet.Anchor> anchors = new DragonPartsInjector().anchors().anchors();
		assertEquals(AnchorSet.Severity.HEDGE, anchor(anchors, DragonPartsInjector.PART).severity());
		assertEquals(AnchorSet.Severity.REQUIRED, anchor(anchors, DragonPartsInjector.DRAGON).severity());
		assertEquals(AnchorSet.Severity.REQUIRED, anchor(anchors, DragonPartsInjector.HITBOXES).severity());
	}

	private static AnchorSet.Anchor anchor(List<AnchorSet.Anchor> anchors, String binaryName) {
		return anchors.stream().filter(a -> a.binaryName().equals(binaryName)).findFirst().orElseThrow(() -> new AssertionError(binaryName));
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow(() -> new AssertionError(name + desc));
	}

	private static ClassTransformer repair(int which) {
		return switch (which) {
			case 0 -> new DragonPartsInjector();
			case 1 -> new ClientPartTrackingInjector();
			default -> new ForgePartTrackingInjector(name -> {
				try {
					return NativeCoremodParityTest.read(MERGED, name);
				} catch (Exception unreadable) {
					return null;
				}
			});
		};
	}
}
