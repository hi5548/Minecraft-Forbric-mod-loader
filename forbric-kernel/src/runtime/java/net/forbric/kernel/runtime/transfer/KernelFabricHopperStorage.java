/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime.transfer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import net.forbric.kernel.util.ForbricLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * A hopper against Fabric item storages NeoForge's hopper cannot see.
 *
 * <p>fabric-transfer-api-v1's {@code HopperBlockEntityMixin} asks {@code ItemStorage.SIDED} when vanilla's hopper found
 * no container: after {@code getAttachedContainer} in {@code ejectItems}, after {@code getSourceContainer} in
 * {@code suckInItems}. The merged hopper is NeoForge's, which calls neither, so the mixin never attached. NeoForge
 * sees a Fabric storage only through the kernel's capability bridge, and that bridge exposes only a slotted storage on
 * a block entity: a {@code CombinedStorage}, a drawer or network storage, a block with no block entity — or every
 * Fabric storage with {@code -Dforbric.transferBridge=off} — was invisible, and the hopper moved nothing.
 *
 * <p>HopperFabricStorageInjector calls these on NeoForge's two "found nothing" branches, which is where Fabric's own
 * injectors sit relative to the lookups: after the block's container and the entity containers, before the hopper
 * picks up item entities. The bodies are Fabric's: the same lookup, faces, {@code InventoryStorage} view and
 * one-item move, and the same answer, so the hopper's cooldown follows as it does natively. A NeoForge-visible
 * storage never reaches here, so nothing moves twice.
 *
 * <h2>PORT(1.21.1): why the three Fabric calls are method handles</h2>
 *
 * <p>Two things changed at once on 1.21.1 and only the second is a rename.
 *
 * <p>The rename: 26.2's fabric-api exposes {@code ContainerStorage.of(container, side)}; fabric-api
 * 0.116.17+1.21.1 has {@code InventoryStorage.of(container, side)} — verified against the module's own
 * {@code HopperBlockEntityMixin} in {@code fabric-transfer-api-v1}, which is the body being copied, and confirmed
 * absent/present with {@code javap} on the module jar.
 *
 * <p>The other: fabric-api for 1.21.1 is compiled against <em>intermediary</em> names, and the kernel compiles
 * against the Mojmap merged base, so a direct {@code ItemStorage.SIDED.find(level, pos, side)} cannot be compiled:
 * javac resolves the descriptor {@code (Lnet/minecraft/class_1937;Lnet/minecraft/class_2338;Ljava/lang/Object;)...}
 * and the class file for {@code class_1937} does not exist on this classpath (it is exactly the "cannot access
 * class_1937" error this file produced before the port). The three calls are therefore resolved as method handles
 * against the names the <em>remapped</em> Fabric module exposes at runtime, once, and every failure degrades to
 * "no Fabric storage here" with one warning — the same contract the two {@code NOT_FOUND} answers already have, and
 * the same reflective discipline {@link net.forbric.kernel.runtime.KernelFabricFluidBehaviors} uses for the same
 * reason. The Minecraft-side types stay statically typed, so the lookup's arguments are still compiler-checked.
 */
public final class KernelFabricHopperStorage {
	/** {@link #extract}: Fabric has no storage above the hopper either; NeoForge's own path continues. */
	public static final int NOT_FOUND = -1;
	private static final Set<String> NOTED = ConcurrentHashMap.newKeySet();
	/** Fabric's own one-unit move asks this about every resource it meets; it never says no. */
	private static final Predicate<Object> ANY = resource -> true;

	private static volatile boolean resolved;
	private static volatile boolean warned;
	/** {@code ItemStorage.SIDED}; the context is a {@code Direction}. */
	private static Object sided;
	private static MethodHandle find;
	/** {@code InventoryStorage.of(Container, Direction)}. */
	private static MethodHandle inventoryOf;
	/** {@code StorageUtil.move(Storage, Storage, Predicate, long, TransactionContext)}. */
	private static MethodHandle move;

	private KernelFabricHopperStorage() {
	}

	/** {@code ejectItems}, nothing on the hopper's facing side for NeoForge: Fabric's answer, or false as before. */
	public static boolean insert(Object level, Object pos, Object hopper) {
		if (!(level instanceof Level world) || !(pos instanceof BlockPos at) || !(hopper instanceof HopperBlockEntity blockEntity)) return false;
		if (!resolve()) return false;
		Direction facing = blockEntity.getBlockState().getValue(HopperBlock.FACING);
		try {
			Object target = find.invoke(sided, world, at.relative(facing), facing.getOpposite());
			if (target == null) return false;
			note("inserted into", target);
			return moveOne(inventoryOf.invoke(blockEntity, facing), target) == 1;
		} catch (Throwable t) {
			warnOnce("inserting", t);
			return false;
		}
	}

	/**
	 * {@code suckInItems}, nothing above for NeoForge: 1 or 0 when a Fabric storage is there — the hopper's answer,
	 * and it does not pick up item entities, as natively — or {@link #NOT_FOUND}.
	 */
	public static int extract(Object level, Object hopper) {
		if (!(level instanceof Level world) || !(hopper instanceof Hopper container)) return NOT_FOUND;
		if (!resolve()) return NOT_FOUND;
		try {
			Object source = find.invoke(sided, world,
					BlockPos.containing(container.getLevelX(), container.getLevelY() + 1.0, container.getLevelZ()), Direction.DOWN);
			if (source == null) return NOT_FOUND;
			note("extracted from", source);
			return moveOne(source, inventoryOf.invoke(container, Direction.UP));
		} catch (Throwable t) {
			warnOnce("extracting", t);
			return NOT_FOUND;
		}
	}

	/** Fabric's move of one unit, outside any transaction: 1, 0, or {@link #NOT_FOUND} without a source. */
	private static int moveOne(Object from, Object to) throws Throwable {
		if (from == null) return NOT_FOUND;
		return (long) move.invoke(from, to, ANY, 1L, null) == 1 ? 1 : 0;
	}

	private static void note(String how, Object storage) {
		String key = how + ' ' + storage.getClass().getName();
		if (NOTED.add(key)) {
			ForbricLog.info("[Forbric/Hopper] a hopper %s Fabric storage %s through Fabric's own lookup (NeoForge's found "
					+ "nothing there)", how, storage.getClass().getName());
		}
	}

	private static void warnOnce(String what, Throwable t) {
		if (!warned) {
			warned = true;
			ForbricLog.warn("[Forbric/Hopper] Fabric's transfer API could not be called while " + what
					+ " — a Fabric storage beside this hopper stays invisible", t);
		}
	}

	/**
	 * fabric-transfer-api-v1's lookup and views, once. False (for good) when the module is absent or its shape is
	 * not the one this was written for; the hopper then behaves exactly as it did before the bridge existed.
	 */
	private static boolean resolve() {
		if (resolved) return sided != null;
		synchronized (KernelFabricHopperStorage.class) {
			if (resolved) return sided != null;
			try {
				ClassLoader loader = KernelFabricHopperStorage.class.getClassLoader();
				Class<?> lookup = Class.forName("net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup", false, loader);
				Class<?> itemStorage = Class.forName("net.fabricmc.fabric.api.transfer.v1.item.ItemStorage", false, loader);
				Class<?> inventoryStorage = Class.forName("net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage", false, loader);
				Class<?> storage = Class.forName("net.fabricmc.fabric.api.transfer.v1.storage.Storage", false, loader);
				Class<?> storageUtil = Class.forName("net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil", false, loader);
				Class<?> context = Class.forName("net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext", false, loader);
				Class<?> container = Class.forName("net.minecraft.world.Container", false, loader);

				MethodHandles.Lookup handles = MethodHandles.publicLookup();
				sided = itemStorage.getField("SIDED").get(null);
				// BlockApiLookup.find(World, BlockPos, C) erases to (Level, BlockPos, Object) -> Object; the third
				// parameter is the generic context, which is why it is Object here and not Direction.
				find = handles.findVirtual(lookup, "find",
						MethodType.methodType(Object.class, Level.class, BlockPos.class, Object.class));
				inventoryOf = handles.findStatic(inventoryStorage, "of",
						MethodType.methodType(inventoryStorage, container, Direction.class));
				// StorageUtil.move(Storage<T>, Storage<T>, Predicate<T>, long, TransactionContext) erases to this.
				move = handles.findStatic(storageUtil, "move", MethodType.methodType(long.class, storage, storage,
						Predicate.class, long.class, context));
				resolved = true;
				return true;
			} catch (Throwable absent) {
				resolved = true;
				warnOnce("resolving", absent);
				return false;
			}
		}
	}
}
