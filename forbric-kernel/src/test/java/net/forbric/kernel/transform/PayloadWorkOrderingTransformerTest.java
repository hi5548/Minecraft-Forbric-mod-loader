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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.TestFixtures;

/**
 * The shape test the ordering repair owes: the real {@code ClientPayloadContext} comes out of the NeoForge
 * carrier with its {@code isSameThread()} shortcut gone, on no JVM.
 *
 * <p><b>What this proves, and what it must not be read as proving.</b> It proves the branch is gone — the
 * transformed {@code enqueueWork(Runnable)} has no {@code INVOKEVIRTUAL isSameThread} followed by a conditional
 * jump, and the {@code GOTO} that replaced the {@code IFEQ} lands on the same submit-path block the {@code IFEQ}
 * targeted. It does <b>not</b> prove that removing the branch is semantically safe for handlers that rely on the
 * inline form: work enqueued on the main thread now runs at the next queue drain instead of at that moment, and no
 * shape test can see a handler that depended on the old immediacy. That is why the falsification is a client arm
 * ({@code joined world via quick-play} on a kernel pinned to the commit carrying the transform), not this test.
 */
class PayloadWorkOrderingTransformerTest {
	private static final String OWNER = "net.neoforged.neoforge.network.handling.ClientPayloadContext";
	private static final String ENTRY = "net/neoforged/neoforge/network/handling/ClientPayloadContext.class";
	private static final String METHOD = "enqueueWork";
	private static final String RUNNABLE_DESC = "(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;";

	@Test
	void theSameThreadShortcutIsReplacedByAnUnconditionalJumpToTheSubmitPath() throws Exception {
		byte[] raw = clientPayloadContext();

		MethodNode before = method(parse(raw), RUNNABLE_DESC);
		assertNotNull(before, "the carrier must still carry enqueueWork(Runnable)");
		JumpInsnNode shortcut = isSameThreadThenConditionalJump(before);
		assertNotNull(shortcut, "the real class must still carry the isSameThread shortcut, or this test measures "
				+ "nothing — a carrier bump that changed the shape must fail here, not pass");
		List<String> submitPath = blockAfter(before, shortcut.label, 8);

		byte[] transformed = new PayloadWorkOrderingTransformer().transform(OWNER, raw, null);
		assertNotSame(raw, transformed, "the transform must edit the real class, not hand it back");

		// Step 4 of the receipt: read the transformed class back with ASM. CheckClassAdapter is not on the boot
		// classpath, so a ClassReader round-trip plus the assertions below is the honest equivalent available.
		MethodNode after = method(parse(roundTrip(transformed)), RUNNABLE_DESC);
		assertNotNull(after, "the transformed class must still carry enqueueWork(Runnable)");

		assertNull(isSameThreadThenConditionalJump(after),
				"no isSameThread-then-conditional-jump may remain in the transformed method");

		JumpInsnNode go = popThenGoto(after);
		assertNotNull(go, "the removed IFEQ must be replaced by POP + GOTO");
		// Object identity of the label does not survive serialization, so "the GOTO names the IFEQ's label" is
		// checked as "it lands on the same block": the instructions at the target must be the submit path.
		assertEquals(submitPath, blockAfter(after, go.label, 8),
				"the GOTO must land on the submit-path block the IFEQ targeted");
		assertTrue(submitPath.stream().anyMatch(step -> step.contains("BlockableEventLoop.submit")),
				"the target must be the submit call, not the inline block: " + submitPath);
		assertTrue(submitPath.stream().anyMatch(step -> step.contains("NetworkRegistry.guard")),
				"and the submit path is guarded, as it is on a real network thread: " + submitPath);
	}

	/** {@code -Dforbric.payloadWorkOrdering=off} is a documented switch, and off means the class is untouched. */
	@Test
	void theKillSwitchLeavesTheClassAlone() throws Exception {
		byte[] raw = clientPayloadContext();
		String previous = System.getProperty(PayloadWorkOrderingTransformer.PROPERTY);
		try {
			System.setProperty(PayloadWorkOrderingTransformer.PROPERTY, "off");
			assertSame(raw, new PayloadWorkOrderingTransformer().transform(OWNER, raw, null),
					"with the switch off the class must come back byte-for-byte unchanged");
		} finally {
			if (previous == null) System.clearProperty(PayloadWorkOrderingTransformer.PROPERTY);
			else System.setProperty(PayloadWorkOrderingTransformer.PROPERTY, previous);
		}
	}

	private static byte[] clientPayloadContext() throws Exception {
		Path jar = TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar");
		TestFixtures.requireFiles("the staged NeoForge carrier", jar);
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(ENTRY);
			assertNotNull(entry, ENTRY + " absent from " + jar);
			return zip.getInputStream(entry).readAllBytes();
		}
	}

	/** A ClassReader -> ClassWriter -> ClassReader pass, so a shape ASM cannot re-serialize fails here. */
	private static byte[] roundTrip(byte[] bytes) {
		ClassNode node = parse(bytes);
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String descriptor) {
		for (MethodNode method : node.methods) {
			if (METHOD.equals(method.name) && descriptor.equals(method.desc)) return method;
		}
		return null;
	}

	/** The {@code IFEQ}/{@code IFNE}/... that consumes a just-pushed {@code isSameThread()} boolean, or null. */
	private static JumpInsnNode isSameThreadThenConditionalJump(MethodNode method) {
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof MethodInsnNode call)) continue;
			if (!"isSameThread".equals(call.name) || !"()Z".equals(call.desc)) continue;
			if (insn.getNext() instanceof JumpInsnNode jump && isConditional(jump.getOpcode())) return jump;
		}
		return null;
	}

	private static JumpInsnNode popThenGoto(MethodNode method) {
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.POP) continue;
			if (insn.getNext() instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO) return jump;
		}
		return null;
	}

	private static boolean isConditional(int opcode) {
		return opcode != Opcodes.GOTO && opcode != Opcodes.JSR && opcode != Opcodes.RET;
	}

	/**
	 * The first {@code count} real instructions at {@code label}, as a comparable textual signature. Line, frame
	 * and label pseudo-nodes are skipped: they are bookkeeping, and counting them would silently truncate the
	 * signature before the call the assertion is about.
	 */
	private static List<String> blockAfter(MethodNode method, LabelNode label, int count) {
		List<String> block = new ArrayList<>();
		if (!method.instructions.contains(label)) return block;
		for (AbstractInsnNode insn = label.getNext(); insn != null && block.size() < count; insn = insn.getNext()) {
			if (insn instanceof LabelNode || insn instanceof org.objectweb.asm.tree.LineNumberNode
					|| insn instanceof org.objectweb.asm.tree.FrameNode) continue;
			block.add(describe(insn));
		}
		return block;
	}

	private static String describe(AbstractInsnNode insn) {
		if (insn instanceof MethodInsnNode m) return "CALL " + m.owner + "." + m.name + m.desc;
		if (insn instanceof FieldInsnNode f) return "FIELD " + f.owner + "." + f.name + ":" + f.desc;
		if (insn instanceof VarInsnNode v) return "VAR " + v.getOpcode() + " " + v.var;
		if (insn instanceof TypeInsnNode t) return "TYPE " + t.getOpcode() + " " + t.desc;
		if (insn instanceof JumpInsnNode j) return "JUMP " + j.getOpcode();
		if (insn instanceof LabelNode) return "LABEL";
		if (insn instanceof LdcInsnNode l) return "LDC " + l.cst;
		if (insn instanceof InsnNode i) return "INSN " + i.getOpcode();
		return insn.getClass().getSimpleName() + " " + insn.getOpcode();
	}
}
