/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * A Fabric mod's fuels go into the fuel table the merged game actually builds.
 *
 * <p>PORT(1.21.1): 26.2 built fuels in NeoForge's {@code DataMapHooks.populateFuelValues} from a {@code FuelValues}
 * object, and this injector drove Fabric's {@code FuelValueEvents} around its builder and through
 * {@code vanillaBurnTimes}' return hooks. None of those types exists here: the fuel table is
 * {@code AbstractFurnaceBlockEntity.getFuel()}, built once from {@code buildFuels} into a local map and then cached
 * in {@code fuelCache}. Fabric's {@code FuelRegistryImpl.apply(Map)} is the whole 21.1 seam, so the kernel hands the
 * just-built map to {@link KernelFabricFuel#apply} immediately before it is cached — after {@code buildFuels}, before
 * {@code putstatic fuelCache} — where a Fabric mixin on the same method would have run. The 26.2 return-hook half is
 * deliberately not ported: the mixin that used it attaches to {@code getFuel()} itself, which the merged base calls
 * natively.
 *
 * <p>{@code -Dforbric.fabricFuel=off} leaves the method as shipped.
 */
public final class FabricFuelValuesInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.fabricFuel";
	static final String FURNACE = "net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity";
	static final String METHOD = "getFuel";
	static final String DESC = "()Ljava/util/Map;";
	static final String FIELD = "fuelCache";
	static final String BUILD_FUELS = "buildFuels";
	static final String KERNEL_FUEL = "net/forbric/kernel/runtime/KernelFabricFuel";
	static final String APPLY_DESC = "(Ljava/util/Map;)Ljava/util/Map;";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-fabric-fuel"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("Fabric fuels left out of the merged fuel table with -D" + PROPERTY + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(FURNACE, AnchorSet.Severity.REQUIRED,
				"a Fabric mod's fuels cannot go in a furnace"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !FURNACE.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (!repair(node)) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Fuel] AbstractFurnaceBlockEntity.getFuel hands its freshly built table to "
				+ "fabric-content-registries before caching it — the merged game builds its fuels there, and Fabric's "
				+ "own mixin on the same method may not have attached");
		return writer.toByteArray();
	}

	/** Points the built map at the kernel; false when the site is not the one this was written against. */
	static boolean repair(ClassNode furnace) {
		for (MethodNode method : furnace.methods) {
			if (!method.name.equals(METHOD) || !method.desc.equals(DESC) || (method.access & Opcodes.ACC_STATIC) == 0) continue;
			FieldInsnNode cache = null;
			int builds = 0, stores = 0;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call) {
					if (call.owner.equals(KERNEL_FUEL)) return false;         // already applied
					if (call.owner.equals(furnace.name) && call.name.equals(BUILD_FUELS)) builds++;
				}
				if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
						&& field.owner.equals(furnace.name) && field.name.equals(FIELD)) {
					cache = field;
					stores++;
				}
			}
			if (builds != 1 || stores != 1 || cache == null) return false;
			AbstractInsnNode previous = cache.getPrevious();
			while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
			if (!(previous instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD) return false;
			method.instructions.insert(previous, new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_FUEL, "apply", APPLY_DESC, false));
			return true;
		}
		return false;
	}
}
