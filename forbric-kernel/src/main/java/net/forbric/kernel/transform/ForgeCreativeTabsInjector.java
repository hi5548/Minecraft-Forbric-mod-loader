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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.api.GameEventBridge;
import net.forbric.api.EventBridges;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;

/** Serves both creative-tab event families through the merged base's one contents-building call. */
public final class ForgeCreativeTabsInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.forgeCreativeTabs";
	static final String TARGET = "net.minecraft.world.item.CreativeModeTab";
	static final String METHOD = "buildContents";
	static final String METHOD_DESC = "(Lnet/minecraft/world/item/CreativeModeTab$ItemDisplayParameters;)V";
	static final String HOOK = "onCreativeModeTabBuildContents";
	/**
	 * What the kernel's {@code KernelForgeCreativeTabs.buildContents} takes, and what 26.2's carriers declared.
	 *
	 * <p>PORT(1.21.1): the carriers inserted the tab's {@code ResourceKey} as a second argument, so the merged
	 * base's own call is {@code CARRIER_DESC} (five arguments). The kernel method still takes the 26.2 four — it
	 * resolves the key itself from the registry — so the injector drops the key before delegating.
	 */
	static final String HOOK_DESC = "(Lnet/minecraft/world/item/CreativeModeTab;"
			+ "Lnet/minecraft/world/item/CreativeModeTab$DisplayItemsGenerator;"
			+ "Lnet/minecraft/world/item/CreativeModeTab$ItemDisplayParameters;"
			+ "Lnet/minecraft/world/item/CreativeModeTab$Output;)V";
	static final String CARRIER_DESC = "(Lnet/minecraft/world/item/CreativeModeTab;"
			+ "Lnet/minecraft/resources/ResourceKey;"
			+ "Lnet/minecraft/world/item/CreativeModeTab$DisplayItemsGenerator;"
			+ "Lnet/minecraft/world/item/CreativeModeTab$ItemDisplayParameters;"
			+ "Lnet/minecraft/world/item/CreativeModeTab$Output;)V";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelForgeCreativeTabs";
	private static final String NEO = ForeignType.EVENT_HOOKS.internal(Ecosystem.NEOFORGE);
	private static final String FORGE = ForeignType.EVENT_HOOKS.internal(Ecosystem.FORGE);

	@Override public String name() { return "forbric-forge-creative-tabs"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("switched off by -D" + PROPERTY);
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"MinecraftForge mods cannot add their items to other creative tabs"));
	}

	private static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!enabled() || !TARGET.equals(className) || classBytes == null || classBytes.length == 0) return classBytes;
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		if (!TARGET.replace('.', '/').equals(node.name)) return classBytes;

		MethodNode target = null;
		MethodNode caller = null;
		MethodInsnNode hook = null;
		int declarations = 0;
		int calls = 0;
		for (MethodNode method : node.methods) {
			if (METHOD.equals(method.name) && METHOD_DESC.equals(method.desc)) {
				target = method;
				declarations++;
			}
			for (var instruction : method.instructions) {
				if (!(instruction instanceof MethodInsnNode call)) continue;
				// Already applied, or an unfamiliar partial composition: neither may post an event twice.
				if (RUNTIME.equals(call.owner) && METHOD.equals(call.name)) return classBytes;
				if (!HOOK.equals(call.name) || !(NEO.equals(call.owner) || FORGE.equals(call.owner))) continue;
				calls++;
				caller = method;
				hook = call;
			}
		}
		if (declarations != 1 || calls != 1 || caller != target
				|| (target.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
				|| hook.getOpcode() != Opcodes.INVOKESTATIC || hook.itf
				|| !NEO.equals(hook.owner) || !CARRIER_DESC.equals(hook.desc)) return classBytes;

		// [tab, key, generator, parameters, output] -> [tab, generator, parameters, output], then the kernel call.
		// The key is the one argument the kernel re-resolves from the registry, so it is dropped, not replaced. The
		// three scratch locals sit above the method's own slots, are live only across these straight-line
		// instructions, and no branch or frame sits between them.
		int scratch = target.maxLocals;
		InsnList replacement = new InsnList();
		replacement.add(new VarInsnNode(Opcodes.ASTORE, scratch + 2));       // output
		replacement.add(new VarInsnNode(Opcodes.ASTORE, scratch + 1));       // item display parameters
		replacement.add(new VarInsnNode(Opcodes.ASTORE, scratch));           // generator
		replacement.add(new InsnNode(Opcodes.POP));                          // the ResourceKey the kernel re-derives
		replacement.add(new VarInsnNode(Opcodes.ALOAD, scratch));            // generator
		replacement.add(new VarInsnNode(Opcodes.ALOAD, scratch + 1));        // item display parameters
		replacement.add(new VarInsnNode(Opcodes.ALOAD, scratch + 2));        // output
		replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, METHOD, HOOK_DESC, false));
		target.instructions.insertBefore(hook, replacement);
		target.instructions.remove(hook);
		target.maxLocals = scratch + 3;
		EventBridges.installed(GameEventBridge.CREATIVE_TAB_CONTENTS);
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		ForbricLog.info("[Forbric/CreativeTabs] creative-tab contents now run NeoForge and MinecraftForge's "
				+ "registration events through the same output (the carrier's ResourceKey is re-derived by the kernel)");
		return writer.toByteArray();
	}
}
