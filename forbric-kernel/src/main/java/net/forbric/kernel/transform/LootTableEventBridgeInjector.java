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
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Routes the two points in {@code ReloadableServerRegistries} that <em>are</em> the 1.21.1 loot-table load
 * through the kernel, so fabric-loot-api-v3's {@code LootTableEvents} can fire from the same place their own
 * mixin fires them.
 *
 * <p><b>PORT(1.21.1): both 26.2 anchors are gone, and the two seams moved.</b> 26.2's
 * {@code ReloadableServerRegistries} had {@code lambda$scheduleRegistryLoad$0/$1}, one calling NeoForge's
 * {@code EventHooks.loadLootTable} per table and one calling {@code TagLoader.loadTagsForRegistry} per
 * registry. 1.21.1's class is {@code scheduleElementParse}/{@code reload}/{@code apply} and calls neither: it
 * holds no loot or tag hook at all (verified by a whole-jar scan — the only {@code loadLootTable} in the merged
 * base is NeoForge's own dead body inside {@code LootDataType}). The two points that matter, read off the merged
 * base's real disassembly, are:
 * <ul>
 *   <li>{@code lambda$scheduleElementParse$3}'s <b>one</b> {@code LootDataType.deserialize} call — the per-file
 *       loot-table load. Fabric's 1.21.1 mixin hooks the {@code Optional.ifPresent} right after it, and it is
 *       {@code LootDataType} that holds the surviving native hook (MinecraftForge's
 *       {@code ForgeEventFactory.onLoadLootTable}, called inside {@code deserialize} after
 *       {@code setLootTableId}; NeoForge's {@code EventHooks.loadLootTable} body survives only as the uncalled
 *       private {@code lambda$deserialize$3}, so <b>NeoForge's {@code LootTableLoadEvent} never fires in the
 *       merged base</b> — the mirror image of 26.2, where Forge's was the dead family).</li>
 *   <li>{@code lambda$scheduleElementParse$4}'s tail — the registry has just been filled and is returned. That
 *       is exactly where fabric's 1.21.1 {@code onLootTablesLoaded} fires {@code ALL_LOADED} from, as an
 *       {@code @Inject(at = RETURN)} keyed on {@code LootDataType.TABLE}.</li>
 * </ul>
 * The first is an owner swap with no instruction, frame or stack change: the receiver the {@code invokevirtual}
 * already pushes becomes the kernel method's first parameter ({@code LootDataType, ResourceLocation, DynamicOps,
 * Object}) so the erased operand list is identical, only the opcode, owner and descriptor change. The second is
 * a splice — three {@code ALOAD}s and one call inserted before the closing {@code ALOAD registry; ARETURN} —
 * guarded by the same rule as the spawner repair: the tail must be exactly that pair, and the local it returns
 * must be the one and only local stored from this method's {@code new MappedRegistry(...)}.
 *
 * <p>Both-or-nothing, per class: exactly one {@code deserialize} call and one provable tail, or the class is
 * returned untouched. {@code -Dforbric.lootBridge=off}: the injector stands down and the dispatch returns
 * identity, so the native load and the dead NeoForge hook behave exactly as they do without the kernel.
 */
public final class LootTableEventBridgeInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.lootBridge";

	static final String TARGET = "net.minecraft.server.ReloadableServerRegistries";
	static final String BRIDGE = "net/forbric/kernel/runtime/KernelLootBridge";

	/** The Java-visible loot-data manager: its {@code deserialize} is what actually loads a loot table. */
	static final String LOOT_DATA_TYPE = "net/minecraft/world/level/storage/loot/LootDataType";
	static final String LOAD_LOOT_TABLE = "loadLootTable";
	/** {@code LootDataType.deserialize(ResourceLocation, DynamicOps, V)} as the merged base calls it. */
	static final String DESERIALIZE_DESC =
			"(Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)"
					+ "Ljava/util/Optional;";
	/** The kernel's replacement: the same operands with the receiver made explicit, so the stack does not move. */
	static final String LOAD_LOOT_TABLE_DESC = "(L" + LOOT_DATA_TYPE + ";" + DESERIALIZE_DESC.substring(1);

	/** The element-parse lambda whose return value is the freshly filled registry. */
	static final String PARSE_HOST = "lambda$scheduleElementParse$4";
	static final String PARSE_HOST_DESC = "(L" + LOOT_DATA_TYPE
			+ ";Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/resources/RegistryOps;)"
			+ "Lnet/minecraft/core/WritableRegistry;";
	/** The registry this data type's parse built, which is what fabric's {@code ALL_LOADED} is handed. */
	static final String REGISTRY = "net/minecraft/core/WritableRegistry";
	static final String REGISTRY_PARSED = "registryParsed";
	static final String REGISTRY_PARSED_DESC = "(L" + LOOT_DATA_TYPE
			+ ";Lnet/minecraft/server/packs/resources/ResourceManager;L" + REGISTRY + ";)V";
	/** {@code new MappedRegistry(ResourceKey, Lifecycle)} — what the returned local must be stored from. */
	static final String MAPPED_REGISTRY = "net/minecraft/core/MappedRegistry";
	static final String MAPPED_REGISTRY_DESC =
			"(Lnet/minecraft/resources/ResourceKey;Lcom/mojang/serialization/Lifecycle;)V";

	private int routed;

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public String name() {
		return "forbric:loot-table-event-bridge";
	}

	@Override
	public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("switched off by -D" + PROPERTY);
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"every Fabric mod's LootTableEvents.REPLACE/MODIFY/ALL_LOADED listener is registered and never called, "
						+ "and NeoForge's own LootTableLoadEvent is dead in the merged base"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!enabled() || classBytes == null || classBytes.length == 0 || !TARGET.equals(className)) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		List<MethodInsnNode> loot = new ArrayList<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call)) continue;
				if (BRIDGE.equals(call.owner)) return classBytes;    // already routed
				if (call.getOpcode() != Opcodes.INVOKEVIRTUAL || !LOOT_DATA_TYPE.equals(call.owner)
						|| !"deserialize".equals(call.name) || !DESERIALIZE_DESC.equals(call.desc)) continue;
				loot.add(call);
			}
		}
		MethodNode parse = node.methods.stream()
				.filter(m -> m.name.equals(PARSE_HOST) && m.desc.equals(PARSE_HOST_DESC)).findFirst().orElse(null);
		VarInsnNode registryReturn = parse == null ? null : registryReturn(parse);
		if (loot.size() != 1 || registryReturn == null) {
			ForbricLog.warn("[Forbric/LootBridge] %s has %d loot-table load site(s) and %s — the seam has drifted, so "
					+ "Fabric LootTableEvents and NeoForge's own LootTableLoadEvent stay unfired", className, loot.size(),
					parse == null ? "no " + PARSE_HOST + " of the expected shape" : "no provable registry local in "
							+ PARSE_HOST);
			return classBytes;
		}

		MethodInsnNode load = loot.getFirst();
		load.setOpcode(Opcodes.INVOKESTATIC);
		load.owner = BRIDGE;
		load.name = LOAD_LOOT_TABLE;
		load.desc = LOAD_LOOT_TABLE_DESC;

		// Before the closing `aload registry; areturn`, where the stack is empty: the block is stack-neutral
		// overall, so every frame the method already carries still describes the same program point.
		InsnList after = new InsnList();
		after.add(new VarInsnNode(Opcodes.ALOAD, 0));              // the LootDataType
		after.add(new VarInsnNode(Opcodes.ALOAD, 1));              // the ResourceManager
		after.add(new VarInsnNode(Opcodes.ALOAD, registryReturn.var)); // the registry just filled
		after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, REGISTRY_PARSED, REGISTRY_PARSED_DESC, false));
		parse.instructions.insertBefore(registryReturn, after);
		parse.maxStack = Math.max(parse.maxStack, 3);

		routed += 2;
		ForbricLog.info("[Forbric/LootBridge] routed 1 loot-table load site and 1 registry-parse site in %s through "
				+ "the kernel — the merged base's ReloadableServerRegistries holds no loot or tag hook, so "
				+ "LootTableEvents fire from the points Fabric's own 1.21.1 mixin uses and NeoForge's dropped "
				+ "LootTableLoadEvent is posted again", className);

		// One owner/opcode swap plus one stack-neutral insert: no other instruction, frame or local changes.
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * The local {@code lambda$scheduleElementParse$4} returns, proved rather than assumed: the method's last real
	 * instruction is {@code ALOAD v} (its one {@code ARETURN} follows), and <em>v</em> is stored by the one
	 * {@code ASTORE} directly after this method's {@code new MappedRegistry(...)}. Anything else — a second
	 * return, a second store, a different construction — is a shifted tail, not a seam.
	 */
	private static VarInsnNode registryReturn(MethodNode parse) {
		AbstractInsnNode exit = null;
		for (AbstractInsnNode insn = parse.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.ARETURN) continue;
			if (exit != null) return null;               // two returns: which one is the tail is a guess
			exit = insn;
		}
		if (exit == null) return null;
		AbstractInsnNode load = exit.getPrevious();
		while (load != null && load.getOpcode() < 0) load = load.getPrevious();
		if (!(load instanceof VarInsnNode returned) || returned.getOpcode() != Opcodes.ALOAD) return null;
		int stores = 0;
		for (AbstractInsnNode insn = parse.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE
					|| store.var != returned.var) continue;
			AbstractInsnNode before = store.getPrevious();
			while (before != null && before.getOpcode() < 0) before = before.getPrevious();
			if (!(before instanceof MethodInsnNode construction) || construction.getOpcode() != Opcodes.INVOKESPECIAL
					|| !MAPPED_REGISTRY.equals(construction.owner)
					|| !MAPPED_REGISTRY_DESC.equals(construction.desc)) return null;
			stores++;
		}
		return stores == 1 ? returned : null;
	}

	/** How many call sites were routed, for the boot summary. */
	public int routedSites() {
		return routed;
	}
}
