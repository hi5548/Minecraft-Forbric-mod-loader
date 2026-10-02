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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;

/**
 * Pins {@link ClientPackHookInjector} for BOTH Forge families.
 *
 * <p>PORT(1.21.1): it prepends a kernel call to {@code ClientModLoader.begin(Minecraft, PackRepository,
 * ReloadableResourceManager)} — 26.2's standalone {@code setupModResourcePacks(PackRepository)} does not exist on
 * this generation. The repository is the SECOND argument (slot 1), not slot 0, and the method is static. If the
 * descriptor ever stops matching, nothing is rewritten and nothing is logged; the client simply comes up without
 * any mod's assets, which looks like missing textures rather than a loader error. The load-bearing detail is that
 * the prepend keeps the carrier's own body: that body is what posts {@code AddPackFindersEvent}, and replacing it
 * silently stops every Forge-family mod's built-in client pack from registering.
 */
class ClientPackHookInjectorTest {
	private static final Path RUN = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run").normalize();
	private static final String METHOD = "begin";
	private static final String DESC = "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/server/packs/repository/PackRepository;"
			+ "Lnet/minecraft/server/packs/resources/ReloadableResourceManager;)V";
	private static final String HOOK_OWNER = "net/forbric/kernel/boot/KernelLifecycle";

	private final ClientPackHookInjector injector = new ClientPackHookInjector();

	/** Both families declare {@code begin} statically with the repository at slot 1 — the premise of the prepend. */
	@Test
	void bothCarriersDeclareBeginStaticallyWithTheRepositoryAtSlotOne() throws Exception {
		for (Ecosystem eco : List.of(Ecosystem.NEOFORGE, Ecosystem.FORGE)) {
			byte[] carrier = carrier(eco);
			assumeTrue(carrier != null, "staged " + eco + " carrier absent");
			MethodNode m = method(parse(carrier), METHOD, DESC);
			assertNotNull(m, eco + ": " + METHOD + DESC + " is gone — the client pack hook silently stops applying "
					+ "and no mod's assets are mounted");
			assertTrue((m.access & Opcodes.ACC_STATIC) != 0, eco + ": " + METHOD + " is no longer static, so the "
					+ "injector's ALOAD 1 would hand the kernel the Minecraft instance instead of the PackRepository");
		}
	}

	@Test
	void bothCarriersAreRewrittenToCallTheKernelAndKeepTheirOwnBody() throws Exception {
		for (Ecosystem eco : List.of(Ecosystem.NEOFORGE, Ecosystem.FORGE)) {
			byte[] real = carrier(eco);
			assumeTrue(real != null, "staged " + eco + " carrier absent");

			MethodNode before = method(parse(real), METHOD, DESC);
			List<Integer> beforeOpcodes = opcodes(before);

			byte[] out = injector.transform(ForeignType.CLIENT_MOD_LOADER.binary(eco), real, ctx());
			assertTrue(out != real, eco + ": not rewritten");

			ClassNode node = parse(out);
			MethodNode m = method(node, METHOD, DESC);
			assertNotNull(hookCall(m), eco + ": the kernel hook call is missing");
			assertEquals(Opcodes.ALOAD, firstReal(m).getOpcode());
			assertEquals(1, ((org.objectweb.asm.tree.VarInsnNode) firstReal(m)).var,
					"the PackRepository is arg 1 of begin(...)");
			// PREPENDED, not replaced: two instructions go in front, every original opcode survives behind them.
			assertEquals(List.of(Opcodes.ALOAD, Opcodes.INVOKESTATIC), opcodes(m).subList(0, 2));
			assertEquals(beforeOpcodes, opcodes(m).subList(2, opcodes(m).size()),
					"the carrier's own body must survive — it is the only thing that posts AddPackFindersEvent");
			new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
			assertSame(out, injector.transform(ForeignType.CLIENT_MOD_LOADER.binary(eco), out, ctx()),
					eco + ": a second pass must find the hook and leave the class alone");
		}
	}

	/** The first instruction that is not a label, line number or frame. */
	private static AbstractInsnNode firstReal(MethodNode m) {
		AbstractInsnNode insn = m.instructions.getFirst();
		while (insn != null && insn.getOpcode() < 0) insn = insn.getNext();
		return insn;
	}

	/** A same-named method with a different descriptor is not the one, and must be left running. */
	@Test
	void aDifferentDescriptorIsNotRewritten() {
		byte[] in = synthetic("(Ljava/lang/Object;)V", true);
		assertSame(in, injector.transform(ForeignType.CLIENT_MOD_LOADER.binary(Ecosystem.NEOFORGE), in, ctx()));
	}

	@Test
	void anyOtherClassIsHandedBackUntouched() {
		byte[] in = synthetic(DESC, true);
		assertSame(in, injector.transform("net.example.Other", in, ctx()));
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	private static TransformContext ctx() {
		return new TransformContext(EnvType.CLIENT, false, "intermediary");
	}

	private static byte[] carrier(Ecosystem eco) throws Exception {
		Path jar = eco == Ecosystem.NEOFORGE
				? RUN.resolve("neoforge-runtime/neoforge-runtime.jar")
				: RUN.resolve("forge-runtime/forge-runtime.jar");
		if (!Files.isRegularFile(jar)) return null;

		String entry = ForeignType.CLIENT_MOD_LOADER.internal(eco) + ".class";
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry e = zip.getEntry(entry);
			if (e == null) return null;
			try (InputStream in = zip.getInputStream(e)) {
				return in.readAllBytes();
			}
		}
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name) && m.desc.equals(desc)) return m;
		}
		return null;
	}

	private static MethodInsnNode hookCall(MethodNode m) {
		for (var insn : m.instructions.toArray()) {
			if (insn instanceof MethodInsnNode call && HOOK_OWNER.equals(call.owner)) return call;
		}
		return null;
	}

	private static List<Integer> opcodes(MethodNode method) {
		List<Integer> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn.getOpcode() >= 0) out.add(insn.getOpcode());
		}
		return out;
	}

	private static byte[] synthetic(String desc, boolean isStatic) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC,
				ForeignType.CLIENT_MOD_LOADER.internal(Ecosystem.NEOFORGE), null, "java/lang/Object", null);

		MethodVisitor mv = cw.visitMethod(
				Opcodes.ACC_PUBLIC | (isStatic ? Opcodes.ACC_STATIC : 0), METHOD, desc, null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}
}
