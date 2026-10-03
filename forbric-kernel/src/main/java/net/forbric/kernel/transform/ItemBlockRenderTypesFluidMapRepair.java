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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Restores {@code ItemBlockRenderTypes.FLUID_RENDER_TYPES}, the unwritten static the merge left null.
 *
 * <p>MinecraftForge adds this field (a {@code Map<Holder.Reference<Fluid>, RenderType>}) and populates it at the
 * end of its {@code <clinit>}; NeoForge does not have it. In the merged base NeoForge's {@code <clinit>} won
 * whole, so the field's assignment went with Forge's initialiser — but the field itself, Forge's readers
 * ({@code getRenderLayer}, {@code setRenderLayer(Fluid, RenderType)}) and even Forge's filler callback survived.
 * The field is therefore null for the life of the process, and the first chunk section to compile dies on
 * {@code ItemBlockRenderTypes.getRenderLayer}: {@code NullPointerException: Cannot invoke "java.util.Map.get(Object)"
 * because "ItemBlockRenderTypes.FLUID_RENDER_TYPES" is null}.
 *
 * <p>This is the same defect class {@code MergedBaseUnwrittenStaticsTest} inventories, and the repair follows the
 * convention of {@code ForbricMergedBaseCompatTransformer}'s {@code LOGGER}/{@code INVALIDATION_COUNTER} repairs:
 * a base that already assigns the field is returned untouched, and the missing assignment is injected at the end
 * of {@code <clinit>}. Here the value is Forge's own — {@code Util.make(new Object2ObjectOpenHashMap(
 * TYPE_BY_FLUID.size(), 0.5f), <filler>)} — so it is reconstructed from the field and the filler that did survive,
 * not invented. The filler is located by its descriptor and its read of {@code ForgeRegistries.FLUIDS}, not by its
 * synthetic name.
 *
 * <p>{@code -Dforbric.itemBlockRenderTypesFluidMap=off} leaves the merged class alone.
 */
public final class ItemBlockRenderTypesFluidMapRepair implements ClassTransformer {
	static final String OWNER = "net.minecraft.client.renderer.ItemBlockRenderTypes";
	/** The internal form, for the owners of the instructions this transformer writes. */
	static final String INTERNAL = "net/minecraft/client/renderer/ItemBlockRenderTypes";
	static final String FIELD = "FLUID_RENDER_TYPES";
	static final String FIELD_DESC = "Ljava/util/Map;";
	static final String SOURCE_FIELD = "TYPE_BY_FLUID";
	static final String HASH_MAP = "it/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap";
	static final String FILLER_DESC = "(L" + HASH_MAP + ";)V";
	static final String FORGE_REGISTRIES = "net/minecraftforge/registries/ForgeRegistries";

	/** The switch, read here as well as at registration so off means the class is not touched at all. */
	public static final String PROPERTY = "forbric.itemBlockRenderTypesFluidMap";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public String name() {
		return "forbric-item-block-render-types-fluid-map";
	}

	@Override
	public AnchorSet anchors() {
		if (!enabled()) {
			return AnchorSet.scanned("ItemBlockRenderTypes.FLUID_RENDER_TYPES stays null with -D" + PROPERTY + "=off");
		}
		return AnchorSet.of(new AnchorSet.Anchor(OWNER, AnchorSet.Severity.REQUIRED,
				"Forge's FLUID_RENDER_TYPES survived the merge without its initialiser, so the first compiled "
						+ "chunk section NPEs in getRenderLayer"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!enabled() || !OWNER.equals(className) || classBytes == null || classBytes.length == 0) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		MethodNode filler = fluidFiller(node);
		if (filler == null || !declaresFluidMap(node) || writesFluidMap(node)) return classBytes;

		MethodNode clinit = clinit(node);
		AbstractInsnNode lastReturn = lastReturn(clinit);
		if (lastReturn == null) return classBytes;

		InsnList inject = new InsnList();
		inject.add(new TypeInsnNode(Opcodes.NEW, HASH_MAP));
		inject.add(new InsnNode(Opcodes.DUP));
		inject.add(new FieldInsnNode(Opcodes.GETSTATIC, INTERNAL, SOURCE_FIELD, FIELD_DESC));
		inject.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "size", "()I", true));
		inject.add(new LdcInsnNode(0.5f));
		inject.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, HASH_MAP, "<init>", "(IF)V", false));
		inject.add(new InsnNode(Opcodes.DUP));
		inject.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTERNAL, filler.name, FILLER_DESC, false));
		inject.add(new FieldInsnNode(Opcodes.PUTSTATIC, INTERNAL, FIELD, FIELD_DESC));
		clinit.instructions.insertBefore(lastReturn, inject);
		clinit.maxStack = Math.max(clinit.maxStack, 4);
		clinit.maxLocals = Math.max(clinit.maxLocals, 2);

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		ForbricLog.info("[Forbric/RenderTypes] %s: restored Forge's FLUID_RENDER_TYPES initialiser the merge "
				+ "dropped — the field, its readers and its filler all survived, and a null map NPEs the first "
				+ "compiled chunk section", className);
		return writer.toByteArray();
	}

	private static boolean declaresFluidMap(ClassNode node) {
		for (FieldNode field : node.fields) {
			if (FIELD.equals(field.name) && FIELD_DESC.equals(field.desc) && (field.access & Opcodes.ACC_STATIC) != 0) {
				return true;
			}
		}
		return false;
	}

	private static boolean writesFluidMap(ClassNode node) {
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC
						&& INTERNAL.equals(f.owner) && FIELD.equals(f.name)) return true;
			}
		}
		return false;
	}

	/** Forge's surviving map filler: a static {@code (Object2ObjectOpenHashMap)V} that reads {@code ForgeRegistries.FLUIDS}. */
	private static MethodNode fluidFiller(ClassNode node) {
		for (MethodNode method : node.methods) {
			if ((method.access & Opcodes.ACC_STATIC) == 0 || !FILLER_DESC.equals(method.desc)) continue;
			boolean readsForgeFluids = false;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC
						&& FORGE_REGISTRIES.equals(f.owner) && "FLUIDS".equals(f.name)) {
					readsForgeFluids = true;
					break;
				}
			}
			if (readsForgeFluids) return method;
		}
		return null;
	}

	private static MethodNode clinit(ClassNode node) {
		for (MethodNode method : node.methods) {
			if ("<clinit>".equals(method.name) && "()V".equals(method.desc)) return method;
		}
		return null;
	}

	private static AbstractInsnNode lastReturn(MethodNode clinit) {
		if (clinit == null) return null;
		AbstractInsnNode last = null;
		for (AbstractInsnNode insn : clinit.instructions) {
			if (insn.getOpcode() == Opcodes.RETURN) last = insn;
		}
		return last;
	}
}
