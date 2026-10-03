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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.TestFixtures;

/**
 * The merged base refuses a Fabric guest's {@code EntityDataSerializers.registerSerializer} call; the repair
 * routes it into NeoForge's synced registry instead.
 *
 * <p>Both sides of the asymmetry are read from the REAL bytes that produce it. The base is the staged merged jar
 * ({@code -Pforbric.stagedRoot}); the guest is the real Cobblemon Fabric jar, whose entrypoint the console shows
 * dying on this exact call ({@code UnsupportedOperationException} at
 * {@code EntityDataSerializers.registerSerializer} — {@code CobblemonFabric.registerEntityDataSerializers} —
 * {@code Cobblemon.preInitialize}). The reference rig's copy is used when no jar is pointed at with
 * {@code -Dforbric.guestJar}.
 */
class MergedBaseEntityDataSerializersTest {
	private static final String SERIALIZERS = "net/minecraft/network/syncher/EntityDataSerializers";
	private static final String SERIALIZERS_ENTRY = SERIALIZERS.replace('.', '/') + ".class";
	private static final String KERNEL_SERIALIZERS = "net/forbric/kernel/runtime/KernelEntityDataSerializers";
	private static final String REGISTER_SERIALIZER = "registerSerializer";
	private static final String SERIALIZER_DESC = "Lnet/minecraft/network/syncher/EntityDataSerializer;";
	private static final String REGISTER_DESC = "(" + SERIALIZER_DESC + ")V";
	private static final String UNSUPPORTED = "java/lang/UnsupportedOperationException";
	private static final String VANILLA_MAP = "net/minecraft/util/CrudeIncrementalIntIdentityHashBiMap";
	private static final String GUARD_MESSAGE =
			"Modded EntityDataSerializers must be registered to NeoForgeRegistries.ENTITY_DATA_SERIALIZERS instead "
					+ "to prevent ID mismatches between client and server!";

	/**
	 * The whole point: the guard the guest hits is gone, and the class's own registration path is untouched.
	 *
	 * <p>Red before the fix — the method carries the {@code new UnsupportedOperationException} that refuses the
	 * caller. Green after: the refuse block is a kernel call, the {@code ifne} still sends the class's own
	 * {@code <clinit>} down the vanilla road, and the verifier still accepts every frame the compiler emitted.
	 */
	@Test
	void theGuardsRefuseBlockBecomesAKernelRegistration() throws Exception {
		byte[] original = serializers();
		MethodNode before = registerSerializer(parse(original));
		assertNotNull(createdException(before),
				"the staged base is supposed to carry NeoForge's caller-identity guard on this method");
		assertEquals(1, count(before, VANILLA_MAP, "add"),
				"and the vanilla path the guard protects is supposed to be there to preserve");
		int framesBefore = frames(before);

		byte[] repaired = new ForbricMergedBaseCompatTransformer().transform(SERIALIZERS, original, null);
		assertNotSame(original, repaired);
		MethodNode after = registerSerializer(parse(repaired));

		assertEquals(null, createdException(after),
				"the UnsupportedOperationException the guard threw is exactly what a Fabric guest cannot survive");
		assertEquals(null, ldc(after, GUARD_MESSAGE), "and neither is its message constant");
		assertEquals(List.of(KERNEL_SERIALIZERS), registerCalls(after),
				"the refuse block must hand the serializer to the kernel, which registers it into "
						+ "NeoForgeRegistries.ENTITY_DATA_SERIALIZERS on the caller's behalf");
		assertEquals(1, count(after, VANILLA_MAP, "add"),
				"the class's OWN registrations still go through vanilla's map — the fix adds a road, it does not "
						+ "move the one the class itself uses");
		assertEquals(2, branches(before), "the guard's IFNE and the vanilla path's 256-cap check");
		assertEquals(2, branches(after),
				"both survive: the fix replaces the refuse block, not the branch that keeps the class's own "
						+ "registrations on the vanilla road");
		assertEquals(framesBefore, frames(after),
				"the replacement is straight-line and empty-to-empty, so no stack map frame changes");

		new Analyzer<>(new BasicVerifier()).analyze(SERIALIZERS.replace('.', '/'), after);
	}

	/** A second pass finds the kernel owner where the throw was, and must not rewrite the kernel call into itself. */
	@Test
	void aSecondPassLeavesTheMethodAlone() throws Exception {
		ForbricMergedBaseCompatTransformer once = new ForbricMergedBaseCompatTransformer();
		byte[] repaired = once.transform(SERIALIZERS, serializers(), null);
		assertSame(repaired, once.transform(SERIALIZERS, repaired, null),
				"the repaired method has no UnsupportedOperationException left to find");
	}

	/** Nothing else in the base is touched: the repair is scoped to the one class the guard lives in. */
	@Test
	void anotherClassIsNeverEdited() throws Exception {
		Path base = TestFixtures.mergedBase();
		assumeTrue(base != null && Files.isRegularFile(base), "staged merged base absent");
		byte[] other = entry(base, "net/minecraft/network/syncher/SynchedEntityData.class");
		assertFalse(contains(other, KERNEL_SERIALIZERS),
				"the neighbour class does not name the kernel to begin with");
		byte[] repaired = new ForbricMergedBaseCompatTransformer()
				.transform("net.minecraft.network.syncher.SynchedEntityData", other, null);
		assertFalse(contains(repaired, KERNEL_SERIALIZERS),
				"and the guard repair still never edits it — the target is the guard's own class alone");
	}

	/**
	 * The guest half, on the real jar the console's failure came from: its Fabric entrypoint calls the vanilla
	 * overload eight times — the exact call the repaired base now serves.
	 *
	 * <p>The jar is intermediary, so the call reads {@code class_2943.method_12720} here and
	 * {@code EntityDataSerializers.registerSerializer} after the kernel's remap; the console stack shows both
	 * spellings on either side of it. Skipped when no guest jar is staged.
	 */
	@Test
	void theRealGuestsEntrypointCallsTheVanillaOverloadEightTimes() throws Exception {
		Path guest = guestJar();
		assumeTrue(guest != null && Files.isRegularFile(guest), "no staged Cobblemon jar");
		byte[] bytes = entry(guest, "com/cobblemon/mod/fabric/CobblemonFabric.class");

		ClassNode node = parse(bytes);
		int calls = 0;
		for (MethodNode method : node.methods) {
			if (!"registerEntityDataSerializers".equals(method.name)) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
						&& "net/minecraft/class_2943".equals(call.owner) && "method_12720".equals(call.name)
						&& "(Lnet/minecraft/class_2941;)V".equals(call.desc)) {
					calls++;
				}
			}
		}
		assertEquals(8, calls, "Cobblemon registers eight entity-data serializers through the vanilla overload — "
				+ "the call the merged base's guard refused before this fix, aborting its preInitialize");
		assertTrue(calls > 0, "and that is the descriptor the kernel helper takes");
	}

	private static MethodNode registerSerializer(ClassNode node) {
		for (MethodNode method : node.methods) {
			if (REGISTER_SERIALIZER.equals(method.name) && REGISTER_DESC.equals(method.desc)) return method;
		}
		throw new AssertionError(node.name + " has no " + REGISTER_SERIALIZER + REGISTER_DESC);
	}

	/** The {@code new UnsupportedOperationException} the guard builds, or null once it is gone. */
	private static TypeInsnNode createdException(MethodNode method) {
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW && UNSUPPORTED.equals(type.desc)) {
				return type;
			}
		}
		return null;
	}

	private static LdcInsnNode ldc(MethodNode method, String text) {
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof LdcInsnNode ldc && text.equals(ldc.cst)) return ldc;
		}
		return null;
	}

	private static List<String> registerCalls(MethodNode method) {
		List<String> owners = new ArrayList<>();
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
					&& "register".equals(call.name) && REGISTER_DESC.equals(call.desc)) {
				owners.add(call.owner);
			}
		}
		return owners;
	}

	/** A cheap constant-pool-level check: is this internal name mentioned anywhere in the class? */
	private static boolean contains(byte[] classBytes, String internalName) {
		byte[] needle = internalName.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
		outer:
		for (int i = 0; i + needle.length <= classBytes.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (classBytes[i + j] != needle[j]) continue outer;
			}
			return true;
		}
		return false;
	}

	private static int count(MethodNode method, String owner, String name) {
		int found = 0;
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && owner.equals(call.owner) && name.equals(call.name)) found++;
		}
		return found;
	}

	/** Every conditional branch in the method: the guard is the one that keeps the two roads apart. */
	private static int branches(MethodNode method) {
		int found = 0;
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof org.objectweb.asm.tree.JumpInsnNode jump && jump.getOpcode() != Opcodes.GOTO) found++;
		}
		return found;
	}

	private static int frames(MethodNode method) {
		int found = 0;
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof FrameNode) found++;
		}
		return found;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] serializers() throws IOException {
		Path base = TestFixtures.mergedBase();
		assumeTrue(base != null && Files.isRegularFile(base), "staged merged base absent");
		return entry(base, SERIALIZERS_ENTRY);
	}

	private static byte[] entry(Path jar, String name) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(name);
			assumeTrue(entry != null, name + " absent from " + jar.getFileName());
			return zip.getInputStream(entry).readAllBytes();
		}
	}

	/**
	 * The staged Cobblemon jar, whatever the caller pointed {@code -Dforbric.guestJar} at and otherwise the
	 * reference rig's copy ({@code /tmp/w7-fabric-ref/mods}) — the same bytes the Fabric reference loaded 43/43
	 * registries from. Null when neither is present, so the test skips rather than inventing a fixture.
	 */
	private static Path guestJar() {
		String pointed = System.getProperty("forbric.guestJar");
		if (pointed != null && !pointed.isBlank()) return Path.of(pointed);
		for (Path candidate : List.of(
				Path.of("/tmp/w7-fabric-ref/mods/Cobblemon-fabric-1.8.1+1.21.1.jar"),
				Path.of("/tmp/w7-stage/guest/Cobblemon-fabric-1.8.1+1.21.1.jar"))) {
			if (Files.isRegularFile(candidate)) return candidate;
		}
		return null;
	}
}
