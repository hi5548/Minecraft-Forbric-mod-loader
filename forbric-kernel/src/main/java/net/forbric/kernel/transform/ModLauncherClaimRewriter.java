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
 * Takes the ModLauncher claim off MinecraftForge's event bus: {@code BusBuilderImpl.useModLauncher()} becomes a
 * no-op that still returns its receiver.
 *
 * <h2>What the claim costs</h2>
 *
 * <p>Forge's event bus has two ways to build the wrapper class it needs for a listener method, and it picks by one
 * flag. {@code EventBus}'s constructor runs {@code builder.modLauncher ? new ModLauncherFactory() :
 * new ClassLoaderFactory()}. The base factory GENERATES the wrapper: an ASM {@code ClassNode}, defined into its own
 * {@code ASMClassLoader}, whose {@code loadClass} hands every name it does not own to the game loader so the
 * generated class can still reference the event and the handler. {@code ModLauncherFactory} does the opposite — it
 * asks the launcher for a class the launcher was supposed to have generated already:
 * {@code Class.forName(getUniqueName(method), true, getClassLoader())}.
 *
 * <p>The kernel is not ModLauncher, so that class never exists. Measured on the 1.21.1 client while registering
 * Forge's own client handlers:
 *
 * <pre>
 * [Render thread/ERROR]: Error registering event handler: class net.minecraftforge.event.level.ChunkEvent$Unload
 *     public static void net.minecraftforge.client.model.data.ModelDataManager.onChunkUnload(...)
 * java.lang.ClassNotFoundException: net.minecraftforge.client.model.data.__ModelDataManager_onChunkUnload_Unload
 *     (game-side, but not found in any kernel-owned jar)
 *   at net.forbric.kernel.classloading.ForbricClassLoader.defineGameClass
 *   at net.minecraftforge.eventbus.ModLauncherFactory.createWrapper(ModLauncherFactory.java:24)
 *   at net.minecraftforge.eventbus.ASMEventHandler.&lt;init&gt;(ASMEventHandler.java:28)
 * </pre>
 *
 * <p>The name is not a class anyone wrote; it is the wrapper ModLauncherFactory computed. Every handler registered
 * through this bus that needs a generated wrapper is lost the same way, so the wrapper is not regenerated here and
 * the reference is not dropped — no reference should have been created. With the flag false the bus generates its own
 * wrappers, which is the path Forge itself runs when no launcher supplies them, so the component whose job it is does
 * the generating.
 *
 * <h2>Why the receiver is returned and not a default</h2>
 *
 * <p>The method is the tail of a builder chain ({@code BusBuilder.builder().useModLauncher()...}), so
 * {@link MethodBodyNeuter} cannot express this one: its {@code returnFor} answers an object type with
 * {@code ACONST_NULL}, and a null builder makes {@code MinecraftForge}'s own static initializer throw where the
 * missing wrapper class used to. The body is replaced with {@code aload_0; areturn} instead, which changes the claim
 * and nothing else.
 *
 * <h2>The same claim is the NeoForge side's</h2>
 *
 * <p>{@code cpw.mods.modlauncher.Launcher.INSTANCE} being null is the same fact, and it is not cosmetic: a NeoForge
 * client dies in {@code LanguageProviderLoader.applyForEach} on it eight seconds in.
 *
 * <p>Three call sites make the claim, all in the interop jar — {@code net.minecraftforge.common.MinecraftForge}
 * (the global bus), {@code net.minecraftforge.fml.javafmlmod.FMLModContainer} and
 * {@code net.minecraftforge.network.NetworkInstance}. {@code modLauncher} is package-private and this method is the
 * only writer, so rewriting this one body covers all three.
 */
public final class ModLauncherClaimRewriter implements ClassTransformer {
	private static final String OWNER = "net.minecraftforge.eventbus.BusBuilderImpl";
	private static final String METHOD = "useModLauncher";
	private static final String DESCRIPTOR = "()Lnet/minecraftforge/eventbus/api/BusBuilder;";

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!OWNER.equals(className)) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		boolean changed = false;
		for (MethodNode method : node.methods) {
			if (!METHOD.equals(method.name) || !DESCRIPTOR.equals(method.desc)) continue;

			InsnList body = new InsnList();
			// VarInsnNode, not InsnNode: ALOAD takes a local index, and an InsnNode(ALOAD) has no operand, so ASM
			// wrote the following ARETURN opcode byte (0xB0 = 176) as the index — `aload 176` — and the class then
			// failed verification with "Local variable table overflow / Local index 176 is invalid" at the first
			// BusBuilder.builder() during Bootstrap, killing every boot.
			body.add(new VarInsnNode(Opcodes.ALOAD, 0));
			body.add(new InsnNode(Opcodes.ARETURN));
			method.instructions = body;
			method.tryCatchBlocks = null;
			method.localVariables = null;
			method.maxStack = 1;
			method.maxLocals = 1;
			changed = true;
			ForbricLog.info("[Forbric/ModLauncher] %s.%s%s now keeps its receiver and sets no flag — the bus builds "
					+ "its event-handler wrappers with its own generator instead of asking a launcher this kernel "
					+ "does not run; a wrapper ModLauncherFactory asked for is a ClassNotFoundException that silently "
					+ "loses the handler", className, METHOD, DESCRIPTOR);
		}
		if (!changed) return classBytes;

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.of(new AnchorSet.Anchor(OWNER, AnchorSet.Severity.REQUIRED,
				"the bus would build its wrappers through ModLauncherFactory again and every handler that needs one "
						+ "would go missing with a ClassNotFoundException that names a class nobody wrote"));
	}

	@Override
	public String name() {
		return "forbric:mod-launcher-claim";
	}
}
