# Ordering transform — the located change, as an artifact

Status: **LANDED** (2026-10-03) — the class below is now
`forbric-kernel/src/main/java/net/forbric/kernel/transform/PayloadWorkOrderingTransformer.java` and is registered
in `KernelBoot`, with the switch `-Dforbric.payloadWorkOrdering=off`. Compiled and shape-tested (see `README.md`
§9 and §10); the behavioural half — that removing the inline branch is safe for every payload — is still
**unverified** and is what the client arm falsifies. The arm ran (README §10): `joined world via quick-play` does
**not** appear — the transform fires and the class it produces then fails verification at load
(`VerifyError: Expecting a stack map frame` at `enqueueWork(Runnable) @16`), so the client disconnects before the
join and the semantic question was never reached. **Fixed in `9bfc1e1b`** by writing the class with
`COMPUTE_FRAMES` (README §11): the branch rewrite invalidates the carrier's `StackMapTable`, so it cannot be
preserved. The shape test gained a link-verification gate; the arm is re-run against the fix. What follows is the
change as written for review, kept
unchanged so the landed file can be diffed against the spec: the landed file adds only the standard kill switch
(`PROPERTY`/`enabled()`/guard) and drops the "not landed yet" paragraph from the javadoc.

## The one-line rule for applying it

Add the class below as
`forbric-kernel/src/main/java/net/forbric/kernel/transform/PayloadWorkOrderingTransformer.java`, and register it in
`KernelBoot` beside the other COREMOD registrations:

```java
chain.register(TransformPhase.COREMOD, new PayloadWorkOrderingTransformer());
```

That is the whole insertion — one new file plus one `chain.register` line. Nothing existing changes.

## Why this shape, in one paragraph

`ClientPayloadContext.enqueueWork` hands off conditionally: `isSameThread() → runnable.run()` inline, else
`getMainThreadEventLoop().submit(...)`. NeoForge's data-map sync handler dereferences `Minecraft.getInstance().level`
with no null guard, so it is safe only if its work runs after `handleLogin` created the level — guaranteed on a real
network thread by the `submit` branch, not guaranteed when the payload is already handled on the main thread, which
is our case. Removing only the branch (so the `submit` path is taken unconditionally) mirrors exactly what the queue
does for a real network thread, applies to every payload rather than special-casing one, and deletes a shortcut
rather than adding logic.

## The class

```java
/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
 * unconditionally. The now-dead inline block is left in place — removing it would move frame offsets for no gain.
 *
 * <p>NOT LANDED ANYWHERE YET, deliberately: it changes semantics for EVERY payload (work enqueued while on the
 * main thread moves from "inline now" to "next queue drain, same thread, same tick, later point in it"), and
 * nothing in the artifacts says whether any handler depends on the immediate form. Land it with its shape test and
 * a client arm in the same window.
 */
public final class PayloadWorkOrderingTransformer implements ClassTransformer {
	private static final String OWNER = "net.neoforged.neoforge.network.handling.ClientPayloadContext";
	private static final String METHOD = "enqueueWork";
	private static final String DESCRIPTOR = "(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;";

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!OWNER.equals(className)) return classBytes;

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

		ClassWriter writer = new ClassWriter(0);
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
```

## The shape test (no JVM needed)

Transform the real class and assert the shape, in the style of the other anchor/transform tests:

1. Load `net/neoforged/neoforge/network/handling/ClientPayloadContext.class` from `neoforge-runtime.jar`.
2. Run `PayloadWorkOrderingTransformer` over it.
3. Assert the transformed method contains **no** `INVOKEVIRTUAL isSameThread` followed by a conditional jump, and
   that the `GOTO` now names the label the `IFEQ` named.
4. Assert bytecode verification of the *shape* by reading it back with ASM (`CheckClassAdapter` is not on the boot
   classpath; a `ClassReader` round-trip plus the two assertions above is the honest equivalent available here).

**What that test does not cover, and must not be read as covering**: it proves the branch is gone, not that
removing it is safe for handlers that currently rely on the inline form. That is why the falsification is an arm,
not a test.

## Falsification criterion

A client arm pinned to the commit carrying the transform, `compatibility_policy` and `mixin_fit` recorded on the
row: **`joined world via quick-play`** is the confirmation, and its absence with the same `Network Protocol Error`
means the hand-off was not the ordering's cause and candidate 2's mechanism needs re-reading.
