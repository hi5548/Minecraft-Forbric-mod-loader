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
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Makes NeoForge's client mod-loading wrapper return the runnable it was given — the kernel owns the client window,
 * so the genuine loader must not also decide what happens at the end of it.
 *
 * <h2>What the wrapper decides</h2>
 *
 * <p>Vanilla's {@code Minecraft.buildInitialScreens} ends by handing its screen-and-world runnable to
 * {@code ClientModLoader.completeModLoading(Runnable)} (javap of the merged base: {@code invokedynamic} for the
 * screen chain, then {@code invokestatic completeModLoading:(Ljava/lang/Runnable;)Ljava/lang/Runnable;}, then
 * {@code areturn}); {@code onGameLoadFinished} runs whatever comes back. NeoForge's own body, read from
 * {@code neoforge-runtime.jar} 21.1.252, has two branches that silently replace that runnable:
 *
 * <ul>
 *   <li>{@code error != null} → returns a runnable that dumps a mod-loading crash report and shows it, so the
 *       screen chain — and with it quick-play, and with it every world — is never run;</li>
 *   <li>the loader recorded warnings and {@code NeoForgeConfig.CLIENT.showLoadWarnings} (true by default) → returns
 *       a runnable that shows the warnings screen <em>first</em>. Nothing dismisses a screen in a headless boot, so
 *       the client waits there forever.</li>
 * </ul>
 *
 * <p>Measured on this box, that is the shape of every client run so far: no world, no exception anywhere on the
 * world-load path ({@code WorldOpenFlows} appears in logs only as stack frames and is absent), the loading overlay
 * rendering on.
 *
 * <h2>Why this method and not {@code finish()}</h2>
 *
 * <p>PORT(1.21.1): the kernel's client neuter list names {@code ClientModLoader.finish()V} and
 * {@code completeModLoading()Z}, for both ecosystems. 21.1.252's NeoForge has neither — it has {@code begin},
 * {@code completeModLoading(Runnable)Runnable} and {@code isLoading()Z} — so both neuters report "anchor is gone"
 * on every boot ("the neuter for finish()V, completeModLoading()Z would not be applied") and the genuine wrapper
 * runs unowned. Neutering the real one with {@link MethodBodyNeuter} is not possible either: it answers an object
 * return type with {@code ACONST_NULL}, and a null runnable means {@code onGameLoadFinished} never sets a screen at
 * all. The body is replaced with {@code aload_0; areturn} instead, which hands vanilla its own chain back —
 * Forge's real wrapper, minus the decisions the kernel has taken over.
 *
 * <p>Not a general dispensation: only this one method on the two client-mod-loader classes is rewritten, and only
 * to its own argument.
 */
public final class ClientModLoadingWrapperRewriter implements ClassTransformer {
	// BINARY (dotted) names, which is what ClassTransformer.transform is handed — MethodBodyNeuter compares its
	// targets the same way. Written slashed first, this matched nothing and the rewrite silently did nothing at all;
	// caught by defining the rewritten class offline and calling the method, which is the only check that can see a
	// no-op like this (a boot would just show the old behaviour, with no line saying why).
	private static final String NEOFORGE_OWNER = "net.neoforged.neoforge.client.loading.ClientModLoader";
	private static final String FORGE_OWNER = "net.minecraftforge.client.loading.ClientModLoader";
	private static final String METHOD = "completeModLoading";
	private static final String DESCRIPTOR = "(Ljava/lang/Runnable;)Ljava/lang/Runnable;";

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!NEOFORGE_OWNER.equals(className) && !FORGE_OWNER.equals(className)) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		boolean changed = false;
		for (MethodNode method : node.methods) {
			if (!METHOD.equals(method.name) || !DESCRIPTOR.equals(method.desc)) continue;
			if (returnsItsArgument(method)) continue;

			InsnList body = new InsnList();
			// VarInsnNode, not InsnNode: ALOAD carries a local index, and an InsnNode(ALOAD) writes the next opcode
			// byte as that index — the class then fails verification at define time.
			body.add(new VarInsnNode(Opcodes.ALOAD, 0));
			body.add(new InsnNode(Opcodes.ARETURN));
			method.instructions = body;
			method.tryCatchBlocks = null;
			method.localVariables = null;
			method.maxStack = 1;
			method.maxLocals = 1;
			changed = true;
			ForbricLog.info("[Forbric/ClientLoading] %s.%s%s now returns the runnable it was given — the genuine "
					+ "loader no longer replaces the screen-and-world chain with a crash-report or warnings screen "
					+ "the kernel never dismisses", className, METHOD, DESCRIPTOR);
		}
		if (!changed) return classBytes;

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** Whether the body is already exactly {@code aload_0; areturn}, so a second pass is a no-op. */
	private static boolean returnsItsArgument(MethodNode method) {
		if (method.instructions == null || method.instructions.size() != 2) return false;
		var first = method.instructions.getFirst();
		var second = method.instructions.getLast();
		return first != null && first.getOpcode() == Opcodes.ALOAD && second != null
				&& second.getOpcode() == Opcodes.ARETURN;
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.of(new AnchorSet.Anchor(NEOFORGE_OWNER, AnchorSet.Severity.REQUIRED,
				"the genuine client loader would decide the end of the kernel's own mod-loading window again: a "
						+ "recorded error or a loading warning replaces vanilla's screen-and-world runnable with a "
						+ "screen nothing dismisses, and no client ever reaches a world"));
	}

	@Override
	public String name() {
		return "forbric:client-mod-loading-wrapper";
	}
}
