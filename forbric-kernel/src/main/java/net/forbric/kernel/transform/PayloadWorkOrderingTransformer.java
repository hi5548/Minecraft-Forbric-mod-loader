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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * PORT(1.21.1): makes NeoForge's payload work go through the main-thread queue even when the payload is already
 * being handled on the main thread, so the work cannot run ahead of the point the level exists.
 *
 * <p>{@code ClientPayloadContext.enqueueWork} hands off conditionally — {@code isSameThread()} runs the work
 * INLINE, otherwise it is submitted. NeoForge's data-map sync handler dereferences
 * {@code Minecraft.getInstance().level} with no null guard, so it is safe only if that work runs after
 * {@code ClientPacketListener.handleLogin} created the level. The submit branch gives that ordering on a real
 * network thread; when the payload is handled on the main thread the inline branch runs it at that moment, and if
 * the moment is inside the login window the join dies with {@code Network Protocol Error}.
 *
 * <p>Measured: {@code ClientboundCustomPayloadPacket.handle} and
 * {@code ClientPacketListener.handleCustomPayload} carry NO {@code ensureRunningOnSameThread} call (0 of 318 lines,
 * against 105 elsewhere in that class, {@code handleLogin} among them), and that absence is upstream in both
 * patched sides. Receipts: run/compat/reports/2026-10-03-datamap-sync-before-level/.
 *
 * <p>Removes the branch only: the {@code INVOKEVIRTUAL isSameThread} + {@code IFEQ} pair becomes a {@code POP}
 * (the boolean is already on the stack) followed by the same jump target, so the submit path is entered
 * unconditionally. The now-dead inline block is left in place, and the class is written with
 * {@code COMPUTE_FRAMES} because that new layout invalidates the carrier's {@code StackMapTable} — preserving the
 * carrier's frames was measured to produce {@code VerifyError: Expecting a stack map frame} at the instruction
 * after the GOTO, i.e. at the start of the dead block. The method has no reference-typed merge, so recomputation
 * resolves no game class.
 *
 * <p>It changes semantics for EVERY payload, not just the data-map one (work enqueued while on the main thread
 * moves from "inline now" to "next queue drain, same thread, same tick, later point in it"), and nothing in the
 * artifacts says whether any handler depends on the immediate form. The shape test
 * ({@code PayloadWorkOrderingTransformerTest}) proves only that the branch is gone; the confirmation that the
 * deferral is safe is the client arm, not the test: {@code joined world via quick-play} on a client pinned to the
 * commit that carries this. {@code -Dforbric.payloadWorkOrdering=off} leaves {@code enqueueWork} untouched.
 *
 * <p>The {@code enqueueWork(Supplier)} overload carries the same shortcut and is deliberately NOT touched here:
 * the failed join enqueued a {@code Runnable}, and widening the repair to the returning form is a separate change
 * with its own arm.
 */
public final class PayloadWorkOrderingTransformer implements ClassTransformer {
	private static final String OWNER = "net.neoforged.neoforge.network.handling.ClientPayloadContext";
	private static final String METHOD = "enqueueWork";
	private static final String DESCRIPTOR = "(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;";

	/** The switch, read here so that off means the class is not touched at all. */
	public static final String PROPERTY = "forbric.payloadWorkOrdering";

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!enabled() || !OWNER.equals(className)) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		boolean changed = false;
		for (MethodNode method : node.methods) {
			if (!METHOD.equals(method.name) || !DESCRIPTOR.equals(method.desc)) continue;

			AbstractInsnNode call = nextIsSameThread(method.instructions);
			if (call == null) continue;
			AbstractInsnNode branch = call.getNext();
			if (!(branch instanceof JumpInsnNode jump)) continue;

			// INVOKEVIRTUAL leaves the boolean on the stack and IFEQ consumes it. POP replaces that consumption and
			// the GOTO keeps the original target, so the submit path below runs unconditionally. VarInsnNode-class
			// care is not needed here: POP and GOTO take no operand, unlike ALOAD's local index.
			InsnList replacement = new InsnList();
			replacement.add(new InsnNode(Opcodes.POP));
			replacement.add(new JumpInsnNode(Opcodes.GOTO, jump.label));
			method.instructions.insert(branch, replacement);
			method.instructions.remove(branch);

			changed = true;
			ForbricLog.info("[Forbric/PayloadOrdering] %s.%s now submits its work unconditionally — the inline "
					+ "same-thread path ran payload work before the client level existed, which is how the data-map "
					+ "sync died with \"Network Protocol Error\"", className, METHOD);
		}
		if (!changed) return classBytes;

		// COMPUTE_FRAMES, NOT ClassWriter(0): replacing the conditional branch changes the control flow, and the
		// carrier's own StackMapTable then describes a layout the verifier rejects — "Expecting a stack map frame"
		// at the instruction after the GOTO, i.e. at the start of the now-dead inline block. The method has no
		// merge of two reference types (the only branch left is the GOTO), so ASM's frame computation never needs
		// to resolve a game class and does not have to load one.
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** The {@code INVOKEVIRTUAL isSameThread()} inside {@code enqueueWork}, or null if the shape has moved. */
	private static AbstractInsnNode nextIsSameThread(InsnList instructions) {
		for (AbstractInsnNode insn = instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof MethodInsnNode call)) continue;
			if ("isSameThread".equals(call.name) && "()Z".equals(call.desc)) return insn;
		}
		return null;
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.of(new AnchorSet.Anchor(OWNER, AnchorSet.Severity.REQUIRED,
				"payload work enqueued on the main thread would run inline again and can precede the level, which "
						+ "kills the join with Network Protocol Error"));
	}

	@Override
	public String name() {
		return "forbric:payload-work-ordering";
	}
}
