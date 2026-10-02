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
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;

/**
 * Prepends a kernel call to {@code ClientModLoader.begin(Minecraft, PackRepository, ReloadableResourceManager)}, so
 * the kernel can serve the ecosystem jars' assets to the REAL client {@code PackRepository}.
 *
 * <p>PORT(1.21.1): 26.2 had a dedicated static {@code setupModResourcePacks(PackRepository)} on each family's
 * {@code ClientModLoader}, and this injector replaced that whole body. NeoForge 21.1 and Forge 52 have no such
 * method: both take the live repository as the second argument of {@code begin(Minecraft, PackRepository,
 * ReloadableResourceManager)}, called from {@code Minecraft.<init>} before the client's first resource reload —
 * the same moment, one argument over. This is a PREPEND, not a replacement, because each carrier's own body is
 * what posts {@code AddPackFindersEvent} (NeoForge through {@code ResourcePackLoader.populatePackRepository},
 * MinecraftForge through {@code ResourcePackLoader.loadResourcePacks} plus its own explicit event): replacing it
 * silently stopped every Forge-family mod's built-in client pack from registering.
 */
public final class ClientPackHookInjector implements ClassTransformer {
	private static final String HOOK_OWNER = "net/forbric/kernel/boot/KernelLifecycle";
	private static final String HOOK_NAME = "onClientResourcePacks";
	private static final String METHOD = "begin";
	private static final String DESC = "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/server/packs/repository/PackRepository;"
			+ "Lnet/minecraft/server/packs/resources/ReloadableResourceManager;)V";
	// The hook is BOOT-side and cannot name net.minecraft types at compile time, so it takes Object. Passing the
	// PackRepository into an Object parameter is a widening reference conversion — the verifier accepts it.
	private static final String HOOK_DESC = "(Ljava/lang/Object;)V";

	// Both families declare begin(Minecraft, PackRepository, ReloadableResourceManager) on 1.21.1 and both take
	// the repository at slot 1, so both are live seams now — neither is a hedge.
	private static final String[] OWNERS = {
		ForeignType.CLIENT_MOD_LOADER.binary(Ecosystem.NEOFORGE),
		ForeignType.CLIENT_MOD_LOADER.binary(Ecosystem.FORGE),
	};

	@Override
	public String name() {
		return "forbric-client-pack-hook";
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.of(
				new AnchorSet.Anchor(OWNERS[0], AnchorSet.Severity.REQUIRED,
						"the kernel would never receive the live PackRepository, so no mod's client assets are "
								+ "served -- missing textures and models, with nothing in the log naming the loader"),
				new AnchorSet.Anchor(OWNERS[1], AnchorSet.Severity.REQUIRED,
						"the kernel would never receive the live PackRepository, so no mod's client assets are "
								+ "served -- missing textures and models, with nothing in the log naming the loader"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		boolean target = false;
		for (String owner : OWNERS) {
			if (owner.equals(className)) {
				target = true;
				break;
			}
		}
		if (!target) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		boolean changed = false;
		for (MethodNode m : node.methods) {
			if (!m.name.equals(METHOD) || !m.desc.equals(DESC)) continue;
			if (hooked(m)) continue; // a second pass finds its own call and leaves the class alone
			// PREPENDS, never replaces — the same shape DataPackHookInjector uses on the server side, and for the
			// same reason, learned the hard way here.
			//
			// The carrier's own body is what posts AddPackFindersEvent — NeoForge through
			// ResourcePackLoader.populatePackRepository(repo, CLIENT_RESOURCES, false), MinecraftForge through
			// ResourcePackLoader.loadResourcePacks(repo, true) followed by its own event post — and that is how
			// EVERY Forge-family mod registers a built-in client resource pack. Replacing the body once threw that
			// away: an optional pack stopped appearing in the resource-pack screen and an alwaysActive one left the
			// mod rendering missing textures, with no crash, no log and nothing naming the loader.
			InsnList prologue = new InsnList();
			prologue.add(new VarInsnNode(Opcodes.ALOAD, 1)); // the PackRepository: arg 1 of the static begin(...)
			prologue.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false));
			m.instructions.insert(prologue);
			m.maxStack = Math.max(m.maxStack, 1);
			changed = true;
			ForbricLog.info("[Forbric/ClientPacks] prepended KernelLifecycle.%s to %s.%s — the kernel serves the "
					+ "ecosystem jars' assets and the carrier's own body still posts AddPackFindersEvent, which is "
					+ "how mods register built-in client packs", HOOK_NAME, className, METHOD);
		}
		if (!changed) return classBytes;

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** Whether this method already carries the kernel's prepended call. */
	private static boolean hooked(MethodNode method) {
		for (var insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && HOOK_OWNER.equals(call.owner) && HOOK_NAME.equals(call.name)) {
				return true;
			}
		}
		return false;
	}
}
