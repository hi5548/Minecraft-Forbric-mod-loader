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

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;

/**
 * The carrier-side edit that lets MinecraftForge biome/structure modifiers run inside NeoForge's pass.
 *
 * <p>PORT(1.21.1): 26.2 carried two further repairs on the Forge carrier, both built around
 * {@code net.minecraft.util.random.WeightedList$Builder} — widening {@code addAll(Iterable)} to
 * {@code addAll(Collection)} by descriptor, and routing {@code removeIf(Predicate)} through
 * {@code KernelForgeWorldgen.removeIfValue}. 1.21.1 HAS NO {@code WeightedList$Builder} at all (that class is
 * from a later generation), and Forge 52's {@code RemoveSpawnsBiomeModifier} calls {@code java.util.List.removeIf}
 * directly on {@code MobSpawnSettingsBuilder.getSpawner(MobCategory)} — so neither pattern could ever match on
 * this carrier. Both are deleted rather than left as scans that promise work they cannot do, and their REQUIRED
 * anchors with them. The splice below is the one that still has its anchors.
 *
 * <p>The splice on NeoForge's {@code ServerLifecycleHooks.runModifiers}: after each of the two
 * {@code Stream.toList} that materialise its biome and structure modifier lists (immediately before
 * {@code astore_2} / {@code astore_3}), one {@code invokestatic KernelForgeWorldgen.withMinecraftForge*Modifiers
 * (List)List} — List in, List out, no frame moves. Exactly two matches or nothing is edited. (1.21.1's
 * {@code runModifiers} still materialises both lists into locals 2 and 3, verified by javap, so the anchors hold.)
 *
 * <p>{@code -Dforbric.forgeWorldgen=off}: the target is returned untouched and the anchor is declared as
 * scanned, not missed.
 */
public final class ForgeWorldModifierInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.forgeWorldgen";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelForgeWorldgen";
	static final String LIST_TO_LIST = "(Ljava/util/List;)Ljava/util/List;";
	private static final String LIFECYCLE_HOOKS = ForeignType.SERVER_LIFECYCLE_HOOKS.binary(Ecosystem.NEOFORGE);
	private static final String NEO_KEYS = ForeignType.MODIFIER_REGISTRY_KEYS.internal(Ecosystem.NEOFORGE);
	private static volatile boolean warnedOff;

	@Override
	public String name() {
		return "forbric-forge-world-modifiers";
	}

	@Override
	public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("switched off by -D" + PROPERTY);
		return AnchorSet.of(
				new AnchorSet.Anchor(LIFECYCLE_HOOKS, AnchorSet.Severity.REQUIRED,
						"MinecraftForge biome/structure modifiers never reach the world"));
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (!LIFECYCLE_HOOKS.equals(className)) return classBytes;
		if (!enabled()) {
			if (!warnedOff) {
				warnedOff = true;
				ForbricLog.warn("[Forbric/Worldgen] -D%s=off — MinecraftForge biome/structure modifiers are not bridged "
						+ "into NeoForge's pass", PROPERTY);
			}
			return classBytes;
		}
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		boolean changed = spliceBothModifierLists(node);
		if (!changed) return classBytes;
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** Both lists or nothing: a Forge biome modifier without its structure twin would be a half-bridged world. */
	private static boolean spliceBothModifierLists(ClassNode node) {
		MethodNode run = null;
		for (MethodNode method : node.methods) {
			if ("runModifiers".equals(method.name) && "(Lnet/minecraft/server/MinecraftServer;)V".equals(method.desc)) run = method;
		}
		if (run == null) return false;
		List<MethodInsnNode> materialisations = new ArrayList<>();
		List<String> helpers = new ArrayList<>();
		String lastKey = null;
		for (AbstractInsnNode insn = run.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) return false;   // already spliced
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC && NEO_KEYS.equals(field.owner)) {
				lastKey = field.name;
			}
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEINTERFACE
					&& "java/util/stream/Stream".equals(call.owner) && "toList".equals(call.name)) {
				AbstractInsnNode next = call.getNext();
				while (next != null && next.getOpcode() < 0) next = next.getNext();
				if (!(next instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) continue;
				if (store.var == 2 && "BIOME_MODIFIERS".equals(lastKey)) {
					materialisations.add(call);
					helpers.add("withMinecraftForgeBiomeModifiers");
				} else if (store.var == 3 && "STRUCTURE_MODIFIERS".equals(lastKey)) {
					materialisations.add(call);
					helpers.add("withMinecraftForgeStructureModifiers");
				}
			}
		}
		if (materialisations.size() != 2) {
			ForbricLog.warn("[Forbric/Worldgen] %s.runModifiers materialises %d recognisable modifier list(s), not two — "
					+ "MinecraftForge modifiers are not spliced in, because bridging one family's list and not the "
					+ "other would be a half-bridged world", node.name.replace('/', '.'), materialisations.size());
			return false;
		}
		for (int i = 0; i < materialisations.size(); i++) {
			run.instructions.insert(materialisations.get(i),
					new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, helpers.get(i), LIST_TO_LIST, false));
		}
		ForbricLog.info("[Forbric/Worldgen] NeoForge's runModifiers now appends MinecraftForge's biome and structure "
				+ "modifiers to its own lists (2 splice(s)) — Forge's own pass links against accessors the merged "
				+ "base does not declare and is never run");
		return true;
	}
}
