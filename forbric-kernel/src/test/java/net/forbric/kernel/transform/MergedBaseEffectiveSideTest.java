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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
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
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * MinecraftForge's {@code EffectiveSide} is taught the merged base's thread groups, in real bytecode.
 *
 * <p>The merge kept NeoForge's half of {@code MinecraftServer.spin} and {@code ServerConnectionListener}, so the
 * server thread and the Netty event loops sit in {@code net.neoforged.fml.util.thread.SidedThreadGroups.SERVER} —
 * a different class from the {@code net.minecraftforge.fml.util.thread.SidedThreadGroup} the reader tests for —
 * and Forge's {@code EffectiveSide.get()} answered CLIENT on both. {@code ForgeHooks.onCustomPayload} then
 * disconnected the player on the first serverbound play payload. The repair replaces only the CLIENT fallback,
 * with one stack slot's worth of work, so the frame and the max-stack must come through the transform untouched.
 *
 * <p>When the staged forge-runtime jar is absent the real-bytecode tests self-skip.
 */
class MergedBaseEffectiveSideTest {
	// The forge-runtime jar carries net.minecraftforge.fml.util.thread.EffectiveSide (a passive ABI carrier here).
	private static final Path FORGE_RUNTIME =
			Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"),
					"run", "forge-runtime", "forge-runtime.jar").normalize();

	private static final String EFFECTIVE_SIDE = "net/minecraftforge/fml/util/thread/EffectiveSide";
	private static final String GET_DESC = "()Lnet/minecraftforge/fml/LogicalSide;";
	private static final String FORGE_SIDED_THREADS = "net/forbric/kernel/boot/ForgeSidedThreads";
	private static final String SIDED_THREAD_GROUP = "net/minecraftforge/fml/util/thread/SidedThreadGroup";

	@Test
	void theClientFallbackAsksTheBridgedReaderInsteadOfNamingTheConstant() throws Exception {
		assumeTrue(Files.isRegularFile(FORGE_RUNTIME), "staged forge-runtime.jar absent — skipping real-bytecode check");
		byte[] original = readClass(FORGE_RUNTIME, EFFECTIVE_SIDE + ".class");
		assumeTrue(original != null, "EffectiveSide not found in forge-runtime.jar");

		MethodNode before = get(parse(original));
		assertTrue(namesThePlainClientConstant(before), "the carrier must still answer CLIENT for anything that is "
				+ "not one of its own SidedThreadGroups, or this repair is about a shape the base no longer has");

		byte[] out = new ForbricMergedBaseCompatTransformer().transform(EFFECTIVE_SIDE.replace('/', '.'), original, null);
		assertTrue(out != original, "the transform must have edited EffectiveSide");

		MethodNode after = get(parse(out));
		assertTrue(!namesThePlainClientConstant(after), "the constant fallback goes");
		assertTrue(refs(after).contains(FORGE_SIDED_THREADS + ".groupFor"),
				"the fallback becomes the bridge that answers for NeoForge's groups: " + refs(after));

		// The Forge-group branch above it is untouched: the SidedThreadGroup instanceof and its getSide() stay.
		assertTrue(refs(after).contains("SidedThreadGroup") && refs(after).contains("getSide"),
				"a thread already in a MinecraftForge group must still be read from it: " + refs(after));

		// The whole point of injecting at the fallback rather than adding a branch: nothing about the frame or the
		// stack changes, so the carrier's own StackMapTable must still verify.
		new Analyzer<>(new BasicVerifier()).analyze(EFFECTIVE_SIDE.replace('/', '.'), after);
	}

	@Test
	void aSecondPassLeavesTheRepairedMethodAlone() throws Exception {
		assumeTrue(Files.isRegularFile(FORGE_RUNTIME), "staged forge-runtime.jar absent — skipping real-bytecode check");
		byte[] once = new ForbricMergedBaseCompatTransformer()
				.transform(EFFECTIVE_SIDE.replace('/', '.'), readClass(FORGE_RUNTIME, EFFECTIVE_SIDE + ".class"), null);

		assertSame(once, new ForbricMergedBaseCompatTransformer().transform(EFFECTIVE_SIDE.replace('/', '.'), once, null));
	}

	// --- helpers ---

	private static MethodNode get(ClassNode node) {
		for (MethodNode method : node.methods) {
			if (method.name.equals("get") && method.desc.equals(GET_DESC)) return method;
		}
		throw new AssertionError("EffectiveSide.get" + GET_DESC + " not present");
	}

	private static boolean namesThePlainClientConstant(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions.toArray()) {
			if (insn instanceof FieldInsnNode field && insn.getOpcode() == Opcodes.GETSTATIC
					&& field.owner.equals("net/minecraftforge/fml/LogicalSide") && field.name.equals("CLIENT")) {
				return true;
			}
		}
		return false;
	}

	private static String refs(MethodNode method) {
		List<String> found = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions.toArray()) {
			if (insn instanceof MethodInsnNode call) found.add(call.owner + "." + call.name);
			if (insn instanceof FieldInsnNode field) found.add(field.owner + "." + field.name);
			if (insn instanceof TypeInsnNode type) found.add(type.desc);
		}
		return String.join(" ", found);
	}

	private static ClassNode parse(byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		return node;
	}

	private static byte[] readClass(Path jar, String entryName) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(entryName);
			if (entry == null) return null;
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}
