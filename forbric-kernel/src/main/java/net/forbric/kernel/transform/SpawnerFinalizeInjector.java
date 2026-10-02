/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.List;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.kernel.util.ForbricLog;

/**
 * Adds the caller's proven spawn tag to the spawner hook; register after the legacy merged-base repair.
 *
 * <p><b>PORT(1.21.1): the carrier changed, the proof did not.</b> 26.2 threaded the entity's {@code ValueInput}
 * from {@code TagValueInput.create} through {@code EntityType.loadEntityRecursive} to the hook. 1.21.1 has
 * neither class: its one entity load is
 * {@code EntityType.loadEntityRecursive(CompoundTag, Level, Function)}, called from
 * {@code BaseSpawner.serverTick} with the tag {@code SpawnData.getEntityToSpawn()} read once per attempt
 * ({@code astore 7} at offset 78 of the merged base's disassembly). That tag is the carrier, and MinecraftForge's
 * 1.21.1 {@code onFinalizeSpawnSpawner} takes the same {@code CompoundTag} where 26.2 took the {@code ValueInput}
 * — so the kernel entry can hand both families one value and the splice is the same shape: one {@code ALOAD} of
 * the proven local, then the descriptor with the extra trailing parameter.
 *
 * <p>The evidence is the same shape of argument, re-derived against the 1.21.1 disassembly rather than assumed.
 * {@code serverTick} calls the hook at offset 620 (the class's only {@code finalizeMobSpawnSpawner} call):
 * <pre>
 *   382: aload 7                      // the tag, read at 78 from SpawnData.getEntityToSpawn()
 *   396: invokestatic EntityType.loadEntityRecursive(CompoundTag, Level, Function)Entity
 *   ...
 *   542: checkcast Mob ; astore 20     // the mob the hook is given IS that entity's widening
 *   620: invokestatic EventHooks.finalizeMobSpawnSpawner(Mob, ServerLevelAccessor, DifficultyInstance,
 *                                                       MobSpawnType, SpawnGroupData, IOwnedSpawner, Z)
 *                                                       FinalizeSpawnEvent
 *   623: pop                          // the returned event is still discarded here
 * </pre>
 * The two facts pinned below are read off the real frames: the seventh-from-top operand of the hook traces back
 * (through the {@code ASTORE 18}/{@code ALOAD 18}, the {@code instanceof}, {@code CHECKCAST}, and the
 * {@code ASTORE 20}/{@code ALOAD 20}) to the {@code loadEntityRecursive} call, and that call's first operand
 * traces back to the one {@code SpawnData.getEntityToSpawn()} whose result is also what the live local holds at
 * the hook. A local whose value reaches through anything but those unambiguous copies and casts — a branch join,
 * an overwrite, a second reaching store — is refused rather than guessed.
 */
public final class SpawnerFinalizeInjector implements ClassTransformer {
	static final String PROPERTY = "forbric.spawnerFinalize";
	private static boolean enabled() { return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on")); }
	static final String TARGET = "net.minecraft.world.level.BaseSpawner";
	static final String HOST_DESC = "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)V";
	static final String NEO = "net/neoforged/neoforge/event/EventHooks";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelSpawnerFinalize";
	static final String FORGE = "net/minecraftforge/event/ForgeEventFactory";
	static final String FORGE_HOOK = "onFinalizeSpawnSpawner";
	/** The one thing the two ecosystems agree on in 1.21.1: the spawn tag, not 26.2's ValueInput. */
	static final String INPUT = "Lnet/minecraft/nbt/CompoundTag;";
	/** MinecraftForge 1.21.1 — {@code (Mob, ServerLevelAccessor, DifficultyInstance, SpawnGroupData, tag, spawner)}. */
	static final String FORGE_DESC = "(Lnet/minecraft/world/entity/Mob;Lnet/minecraft/world/level/ServerLevelAccessor;"
			+ "Lnet/minecraft/world/DifficultyInstance;Lnet/minecraft/world/entity/SpawnGroupData;" + INPUT
			+ "Lnet/minecraft/world/level/BaseSpawner;)"
			+ "Lnet/minecraftforge/event/entity/living/MobSpawnEvent$FinalizeSpawn;";
	static final String OLD_DESC = "(Lnet/minecraft/world/entity/Mob;Lnet/minecraft/world/level/ServerLevelAccessor;"
			+ "Lnet/minecraft/world/DifficultyInstance;Lnet/minecraft/world/entity/MobSpawnType;"
			+ "Lnet/minecraft/world/entity/SpawnGroupData;Lnet/neoforged/neoforge/common/extensions/IOwnedSpawner;Z)"
			+ "Lnet/neoforged/neoforge/event/entity/living/FinalizeSpawnEvent;";
	static final String NEW_DESC = OLD_DESC.replace(")", INPUT + ")");
	static final String ENTITY_TYPE = "net/minecraft/world/entity/EntityType";
	static final String ENTITY_LOAD_DESC = "(" + INPUT + "Lnet/minecraft/world/level/Level;"
			+ "Ljava/util/function/Function;)Lnet/minecraft/world/entity/Entity;";
	static final String SPAWN_DATA = "net/minecraft/world/level/SpawnData";
	static final String SPAWN_TAG_DESC = "()" + INPUT;

	@Override public String name() { return "forbric-spawner-finalize-input"; }
	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("spawner input repair explicitly disabled with -D" + PROPERTY + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"Forge spawner listeners need the actual spawn tag before the only mob initialization"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled()) return bytes;
		if (!TARGET.equals(className) || bytes == null || bytes.length == 0) return bytes;
		ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
		if (!TARGET.replace('.', '/').equals(node.name)) return bytes;
		List<MethodNode> methods = node.methods.stream().filter(m -> m.name.equals("serverTick") && m.desc.equals(HOST_DESC)).toList();
		if (methods.size() != 1) return declined(bytes, "serverTick declaration is missing or ambiguous");
		MethodNode host = methods.getFirst();
		if ((host.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return declined(bytes, "serverTick is not a concrete instance method");
		if (carriesForgeFinalize(host)) return standDown(bytes);
		MethodInsnNode target = null;
		int count = 0;
		for (AbstractInsnNode instruction : host.instructions) {
			if (!(instruction instanceof MethodInsnNode call) || !call.name.equals("finalizeMobSpawnSpawner")) continue;
			if (call.owner.equals(RUNTIME) && call.desc.equals(NEW_DESC)) return bytes;
			if (call.owner.equals(RUNTIME) || call.owner.equals(NEO)) { target = call; count++; }
		}
		if (count != 1 || target.getOpcode() != Opcodes.INVOKESTATIC || target.itf || !target.desc.equals(OLD_DESC)) {
			return declined(bytes, "expected exactly one descriptor-matching native or legacy hook");
		}
		if (nextReal(target) == null || nextReal(target).getOpcode() != Opcodes.POP) return declined(bytes, "the native event result is no longer discarded at this caller");
		int inputSlot;
		try { inputSlot = inputSlot(node.name, host, target); }
		catch (AnalyzerException malformed) { return declined(bytes, "cannot establish the caller's data flow: " + malformed.getMessage()); }
		if (inputSlot < 0) return declined(bytes, "no unique live spawn tag from SpawnData.getEntityToSpawn reaches the hook");
		host.instructions.insertBefore(target, new VarInsnNode(Opcodes.ALOAD, inputSlot));
		target.owner = RUNTIME; target.desc = NEW_DESC;
		host.maxStack++;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer);
		ForbricLog.info("[Forbric/Spawner] finalization now supplies the proven spawn tag before both event families decide");
		return writer.toByteArray();
	}

	/**
	 * Whether the caller already posts MinecraftForge's finalize event itself, as a base that restored the call
	 * would. The kernel entry dispatches Forge and finalizes the mob once; routing NeoForge's call there as well
	 * would post Forge's event a second time and could finalize twice. The legacy repair asks the same question.
	 */
	static boolean carriesForgeFinalize(MethodNode host) {
		for (AbstractInsnNode instruction : host.instructions) {
			if (instruction instanceof MethodInsnNode call && call.owner.equals(FORGE) && call.name.equals(FORGE_HOOK)) return true;
		}
		return false;
	}

	/** SourceInterpreter preserves each reaching ASTORE, including branch/handler joins and overwrites. */
	private static int inputSlot(String owner, MethodNode host, MethodInsnNode target) throws AnalyzerException {
		Frame<SourceValue>[] frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, host);
		Frame<SourceValue> atCall = frames[host.instructions.indexOf(target)];
		if (atCall == null || atCall.getStackSize() < 7) return -1;
		MethodInsnNode entityLoad = producer(host, frames, atCall.getStack(atCall.getStackSize() - 7));
		if (entityLoad == null || entityLoad.getOpcode() != Opcodes.INVOKESTATIC || entityLoad.itf
				|| !entityLoad.owner.equals(ENTITY_TYPE)
				|| !entityLoad.name.equals("loadEntityRecursive") || !entityLoad.desc.equals(ENTITY_LOAD_DESC)) return -1;
		Frame<SourceValue> atEntityLoad = frames[host.instructions.indexOf(entityLoad)];
		if (atEntityLoad == null || atEntityLoad.getStackSize() < 3) return -1;
		MethodInsnNode tagRead = producer(host, frames, atEntityLoad.getStack(atEntityLoad.getStackSize() - 3));
		if (tagRead == null || tagRead.getOpcode() != Opcodes.INVOKEVIRTUAL || tagRead.itf
				|| !tagRead.owner.equals(SPAWN_DATA)
				|| !tagRead.name.equals("getEntityToSpawn") || !tagRead.desc.equals(SPAWN_TAG_DESC)) return -1;
		int found = -1;
		for (int slot = 0; slot < atCall.getLocals(); slot++) {
			if (producer(host, frames, atCall.getLocal(slot)) != tagRead) continue;
			if (found >= 0) return -1;
			found = slot;
		}
		return found;
	}

	/** Follow only unambiguous local copies/casts, never guess a value after a control-flow join. */
	private static MethodInsnNode producer(MethodNode host, Frame<SourceValue>[] frames, SourceValue value) {
		Set<AbstractInsnNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		while (value != null && value.insns.size() == 1) {
			AbstractInsnNode source = value.insns.iterator().next();
			if (!visited.add(source)) return null;
			if (source instanceof MethodInsnNode call) return call;
			Frame<SourceValue> before = frames[host.instructions.indexOf(source)];
			if (before == null) return null;
			if (source instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ALOAD) {
				value = before.getLocal(variable.var);
			} else if ((source instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ASTORE)
					|| (source instanceof TypeInsnNode type && type.getOpcode() == Opcodes.CHECKCAST)) {
				if (before.getStackSize() == 0) return null;
				value = before.getStack(before.getStackSize() - 1);
			} else return null;
		}
		return null;
	}

	private static AbstractInsnNode nextReal(AbstractInsnNode instruction) {
		AbstractInsnNode next = instruction.getNext();
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		return next;
	}
	/**
	 * No reviewed shape exists for a caller carrying both native finalize hooks, so nothing is proved about it
	 * except that adding a kernel dispatch would duplicate Forge's. Leave it native and say what is unknown.
	 */
	private static byte[] standDown(byte[] bytes) {
		String reason = "The spawner caller already contains MinecraftForge's own finalize hook next to NeoForge's; the kernel adds no second Forge dispatch or finalization, and whether this caller finalizes the mob exactly once has not been proved.";
		CompatibilityFindings.record(new CompatibilityFinding("spawner-finalize-direct-composition", "forbric", "Spawner finalization",
				"SpawnerFinalizeInjector", CompatibilityFinding.Confidence.SUSPECTED, false, reason, List.of(TARGET + "#serverTick" + HOST_DESC, reason)));
		return bytes;
	}

	private static byte[] declined(byte[] bytes, String reason) {
		CompatibilityFindings.record(new CompatibilityFinding("spawner-finalize-callsite", "forbric", "Spawner finalization",
				"SpawnerFinalizeInjector", CompatibilityFinding.Confidence.SUSPECTED, false,
				"The spawner input repair declined an unfamiliar caller: " + reason, List.of(TARGET + "#serverTick", reason)));
		return bytes;
	}
}
