package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.TestFixtures;

/**
 * MinecraftForge's Hurt, Damage and player-Attack seams, placed in the real merged damage pipeline.
 *
 * <p>The carrier shapes differ (26.2 leads with a {@code ServerLevel}; 1.21.1 does not), so the test reads the
 * staged merged base by whatever version this checkout was built for — {@link TestFixtures#mergedBase()} — rather
 * than naming a jar, and looks the seams up in the shape that carrier has. What it proves is version-independent:
 * Hurt sits between the invulnerability guard and armour, Damage replaces the health the body applies, and the
 * player attack runs at the head of the hurt entry.
 */
@ResourceLock("system-properties")
class ForgeDamageSeamsInjectorTest {
	private static final String LIVING = ForgeDamageSeamsInjector.LIVING, PLAYER = ForgeDamageSeamsInjector.PLAYER;

	@AfterEach void reset() { System.clearProperty(ForgeDamageSeamsInjector.PROPERTY); }

	@Test void hurtBeforeArmourAndDamageAfterAbsorptionInBothBodies() throws Exception {
		Path merged = mergedBase();
		for (String owner : List.of(LIVING, PLAYER)) {
			byte[] original = NativeCoremodParityTest.read(merged, owner);
			byte[] out = new ForgeDamageSeamsInjector().transform(owner.replace('/', '.'), original, null);
			assertNotSame(original, out, owner);
			MethodNode hurt = actuallyHurt(node(out));
			List<String> order = new ArrayList<>();
			for (AbstractInsnNode insn : hurt.instructions) {
				if (insn instanceof MethodInsnNode call && (call.owner.equals(ForgeDamageSeamsInjector.RUNTIME)
						|| call.name.equals("isInvulnerableTo") || call.name.equals("getDamageAfterArmorAbsorb")
						|| call.name.equals("onLivingDamagePre") || call.name.equals("setAbsorptionAmount")
						|| call.name.equals("setHealth"))) order.add(call.name);
			}
			assertEquals(List.of("isInvulnerableTo", "hurt", "getDamageAfterArmorAbsorb", "onLivingDamagePre",
					"setAbsorptionAmount", "damage", "setHealth"), order.subList(0, 7), owner + ": " + order);
			MethodInsnNode damage = calls(hurt, "damage").getFirst();
			assertTrue(damage.getNext() instanceof VarInsnNode store && store.getOpcode() == Opcodes.FSTORE,
					"the Damage answer replaces the health damage the body goes on to apply");
			new Analyzer<>(new BasicVerifier()).analyze(owner, hurt);
			assertSame(out, new ForgeDamageSeamsInjector().transform(owner.replace('/', '.'), out, null), "a second pass changes nothing");
		}
	}

	@Test void thePlayerIsAskedAtTheHeadOfTheHurtEntryAndTellsTheForward() throws Exception {
		ClassNode player = node(new ForgeDamageSeamsInjector().transform(PLAYER.replace('/', '.'),
				NativeCoremodParityTest.read(mergedBase(), PLAYER), null));
		MethodNode server = hurtEntry(player);
		AbstractInsnNode first = server.instructions.getFirst();
		while (first.getOpcode() < 0) first = first.getNext();
		for (int i = 0; i < 3; i++) first = first.getNext();
		assertTrue(first instanceof MethodInsnNode call && call.name.equals("playerAttack"), "before difficulty scaling and the zero-damage return");
		new Analyzer<>(new BasicVerifier()).analyze(PLAYER, server);
		assertEquals(1, calls(method(player, "<clinit>", "()V"), "notePlayerSeam").size());
		ClassNode living = node(new ForgeDamageSeamsInjector().transform(LIVING.replace('/', '.'),
				NativeCoremodParityTest.read(mergedBase(), LIVING), null));
		assertTrue(calls(method(living, "<clinit>", "()V"), "notePlayerSeam").isEmpty(), "only Player carries the attack seam");
	}

	@Test void minecraftForgesOwnPipelineAndTheSwitchAreLeftAlone() throws Exception {
		Path forge = forgeBase();
		for (String owner : List.of(LIVING, PLAYER)) {
			byte[] bytes = NativeCoremodParityTest.read(forge, owner);
			assertSame(bytes, new ForgeDamageSeamsInjector().transform(owner.replace('/', '.'), bytes, null),
					owner + " already calls MinecraftForge's hooks");
		}
		System.setProperty(ForgeDamageSeamsInjector.PROPERTY, "off");
		byte[] merged = NativeCoremodParityTest.read(mergedBase(), LIVING);
		assertSame(merged, new ForgeDamageSeamsInjector().transform(LIVING.replace('/', '.'), merged, null));
	}

	private static Path mergedBase() {
		Path merged = TestFixtures.mergedBase();
		TestFixtures.require(merged != null, "the staged merged base");
		return merged;
	}

	private static Path forgeBase() {
		Path forge = TestFixtures.stagedJar("forge-patched", "patched-mc-forge-");
		TestFixtures.require(forge != null, "the staged MinecraftForge base");
		return forge;
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	/** {@code actuallyHurt} in whichever shape this carrier has — its descriptor is the injector's business. */
	private static MethodNode actuallyHurt(ClassNode node) {
		for (MethodNode method : node.methods) if (method.name.equals("actuallyHurt")) return method;
		throw new AssertionError("no actuallyHurt in " + node.name);
	}

	/** The player hurt entry: {@code hurtServer} on 26.2, {@code hurt} on 1.21.1. */
	private static MethodNode hurtEntry(ClassNode player) {
		for (MethodNode method : player.methods) {
			if ((method.name.equals("hurtServer") || method.name.equals("hurt")) && method.desc.endsWith(")Z")) return method;
		}
		throw new AssertionError("no player hurt entry in " + player.name);
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow();
	}

	private static List<MethodInsnNode> calls(MethodNode method, String name) {
		List<MethodInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(ForgeDamageSeamsInjector.RUNTIME) && call.name.equals(name)) out.add(call);
		}
		return out;
	}
}
