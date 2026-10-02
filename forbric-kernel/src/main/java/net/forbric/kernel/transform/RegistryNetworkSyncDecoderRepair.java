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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * The merge kept MinecraftForge's network registry-sync driver with NeoForge's element loader, and the two
 * disagree about who wraps the element decoder in an {@code Optional}.
 *
 * <p>{@code RegistryDataLoader.loadContentsFromNetwork} is the client-side path that applies a registry the server
 * sent during configuration. For an entry whose {@code RegistrationInfo} names a pack the client already has (a
 * <em>known pack</em> — vanilla's {@code minecraft:core} above all), the server sends no payload and the client
 * loads the entry from its own resources, through {@code loadElementFromResource}.
 *
 * <p>Measured on the merged base, the two halves of that call come from opposite families:
 *
 * <ul>
 *   <li>{@code loadContentsFromNetwork} is <b>MinecraftForge's</b>: it wraps the element codec with
 *       {@code net.minecraftforge.common.crafting.conditions.ConditionCodec.wrap}, whose result is a
 *       {@code Decoder<Optional<E>>} — empty meaning "this entry's conditions were not met".</li>
 *   <li>{@code loadElementFromResource} is <b>NeoForge's</b>: its parameter is a plain {@code Decoder<E>}, and it
 *       wraps it again with {@code NeoForgeExtraCodecs.decodeOnly} + {@code ConditionalOps.createConditionalCodec}
 *       (producing {@code Optional<E>}) and unwraps exactly one level with {@code ifPresentOrElse}.</li>
 * </ul>
 *
 * <p>So the decoder is wrapped twice and unwrapped once: the element registered is the inner {@code Optional<E>},
 * and the registry's {@code Holder.Reference.value} is an {@code Optional}. The login packet then carries that
 * holder, and {@code Level.<init>} does {@code holder.value()} followed by {@code checkcast DimensionType}, which
 * fails as {@code ClassCastException: class java.util.Optional cannot be cast to class
 * net.minecraft.world.level.dimension.DimensionType}. The level is never created; the data-map sync that runs next
 * dereferences the still-null {@code Minecraft.level} and the client disconnects with {@code Network Protocol
 * Error}. The server never takes this path — it loads registries through {@code loadContentsFromManager}, whose
 * decoder is the plain one, which is why only the client died.
 *
 * <p>The repair deletes the extra wrap: {@code loadContentsFromNetwork} passes the decoder it was given straight
 * to {@code loadElementFromResource}, so the loader wraps and unwraps once, as NeoForge's pair does. This gives up
 * MinecraftForge's own condition gate on these known-pack entries (NeoForge's conditional codec still skips an
 * entry whose NeoForge conditions are unmet); those entries are the ones the client already has from a pack it
 * declared known, i.e. core game and carrier data, not mod content. The alternative — keeping the Forge wrap and
 * making the loader Forge-shaped — would break the datapack path, which shares the same loader with the plain
 * decoder. {@code -Dforbric.registryNetworkSyncDecoder=off} leaves the merged class alone.
 */
public final class RegistryNetworkSyncDecoderRepair implements ClassTransformer {
	static final String LOADER = "net.minecraft.resources.RegistryDataLoader";
	static final String METHOD = "loadContentsFromNetwork";
	static final String WRAP_OWNER = "net/minecraftforge/common/crafting/conditions/ConditionCodec";
	static final String WRAP_NAME = "wrap";
	static final String WRAP_DESC = "(Lcom/mojang/serialization/Decoder;)Lcom/mojang/serialization/Decoder;";

	/** The switch, read here as well as at registration so off means the class is not touched at all. */
	public static final String PROPERTY = "forbric.registryNetworkSyncDecoder";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public String name() {
		return "forbric-registry-network-sync-decoder";
	}

	@Override
	public AnchorSet anchors() {
		if (!enabled()) {
			return AnchorSet.scanned("the merged client registry sync keeps MinecraftForge's Optional wrap with "
					+ "-D" + PROPERTY + "=off");
		}
		return AnchorSet.of(new AnchorSet.Anchor(LOADER, AnchorSet.Severity.REQUIRED,
				"a known-pack registry entry is registered Optional-wrapped on the client, so Level.<init> casts an "
						+ "Optional to DimensionType, no level is created and the join dies with Network Protocol Error"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!enabled() || !LOADER.equals(className) || classBytes == null || classBytes.length == 0) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		int removed = repair(node);
		if (removed == 0) return classBytes;

		// Deleting a straight-line instruction changes no basic block and no frame state, so the carrier's own
		// frames stay valid; COMPUTE_FRAMES would only need to resolve game classes it does not have to.
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		ForbricLog.info("[Forbric/Registry] %s: dropped MinecraftForge's ConditionCodec wrap in %s — the merged base "
				+ "kept Forge's Optional-wrapping driver with NeoForge's one-unwrap loader, so a known-pack entry "
				+ "registered as an Optional and Level.<init> cast it to DimensionType", className, METHOD);
		return writer.toByteArray();
	}

	/** Removes the {@code ConditionCodec.wrap} call, leaving the raw decoder on the stack for the store below it. */
	static int repair(ClassNode node) {
		int removed = 0;
		for (MethodNode method : node.methods) {
			if (!METHOD.equals(method.name)) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
				AbstractInsnNode next = insn.getNext();
				if (insn instanceof MethodInsnNode call && WRAP_OWNER.equals(call.owner)
						&& WRAP_NAME.equals(call.name) && WRAP_DESC.equals(call.desc)) {
					method.instructions.remove(insn);
					removed++;
				}
				insn = next;
			}
		}
		return removed;
	}
}
