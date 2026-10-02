/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.world.item.Item;

/**
 * fabric-content-registries' {@code FuelRegistry}, on the fuel table the merged game actually builds.
 *
 * <p>Fabric mods declare fuels through {@code FuelRegistry.INSTANCE} ({@code add(ItemLike|TagKey, Integer)},
 * {@code remove(...)}), and fabric-api applies them itself: its {@code AbstractFurnaceBlockEntityMixin} injects at
 * the RETURN of vanilla's {@code AbstractFurnaceBlockEntity.getFuel()} and hands the returned map to
 * {@code FuelRegistryImpl.apply(Map)}. The merged server's table comes out of that same {@code getFuel()} — both
 * patch sets build on it, and NeoForge's {@code buildFuels} is what fills it — so a Fabric mod's fuel can land in
 * the table; {@link #apply} is the kernel's own copy of that one call, for the case where fabric's mixin did not
 * attach (the kernel runs Fabric mixins through its own adapter, and a mixin that does not apply is silent).
 *
 * <p>Which is the whole content of the 26.2 class reduced to 1.21.1's carrier: there, Fabric fired
 * {@code FuelValueEvents.BUILD}/{@code EXCLUSIONS} from a wrap in vanilla's {@code FuelValues.vanillaBurnTimes},
 * which the merged server never called, and the kernel had to drive that event pair around the builder NeoForge's
 * {@code DataMapHooks.populateFuelValues} produced. None of those types exists on 1.21.1 (fabric-api 0.116.17+1.21.1
 * has {@code FuelRegistry}, not {@code FuelValueEvents}; vanilla has no {@code FuelValues}); 21.1's Fabric registry
 * is a data holder with one mutating entry point, so the kernel's seam is that call and nothing more.
 *
 * <p>Its second half on 26.2 — {@code throughVanillaReturnHooks}/{@code takePending}, which ran the table through
 * the RETURN hooks on {@code vanillaBurnTimes} that the merge had bypassed — has no 1.21.1 counterpart and is
 * deliberately not ported: the method those hooks attach to is {@code getFuel()} itself, the merged base calls it
 * natively (vanilla's own {@code isFuel} and Forge's {@code ForgeHooks.updateBurns} both do), and a mixin on it
 * therefore runs with no help from the kernel.
 *
 * <h2>PORT(1.21.1): what the injector has to do with this, and one measured gap</h2>
 *
 * <p>The 26.2 injector ({@code FabricFuelValuesInjector}) inserted the call into
 * {@code DataMapHooks.populateFuelValues}; on 1.21.1 the anchor is {@code AbstractFurnaceBlockEntity.getFuel()}
 * (or its {@code buildFuels(ObjIntConsumer)} body), and it must pass the {@code Map<Item, Integer>} that is about
 * to be cached — before the {@code putstatic fuelCache} and before {@code ForgeHooks.updateBurns()} copies the
 * table. That re-derivation is the transform layer's, not this file's.
 *
 * <p>Measured while porting, and it decides the injector's shape: on the 1.21.1 merged base the table this method
 * fills is <em>not</em> on the burn-time path by itself. {@code getBurnDuration} calls
 * {@code ForgeHooks.getBurnTime}, which takes {@code ItemStack.getBurnTime} first — NeoForge's
 * {@code IItemExtension.getBurnTime}, the {@code neoforge:furnace_fuels} data map, returning {@code 0} (not
 * {@code -1}) when the item has no entry (javap: {@code iconst_0} at offset 23 of that default method) — and only
 * consults {@code VANILLA_BURNS}, the snapshot Forge fills from {@code getFuel()}, when that value is {@code -1}.
 * So with this base's patches a Fabric fuel is visible to {@code isFuel} but burns for zero ticks unless the
 * injector also makes the table reach the burn-time lookup. Stated here because the symptom is silent.
 */
public final class KernelFabricFuel {
	private static final AtomicBoolean WARNED = new AtomicBoolean();
	private static volatile boolean resolved;
	private static Object registry;
	private static Method apply;

	private KernelFabricFuel() {
	}

	/**
	 * Called with the fuel table the game is building, before it is cached; returns the table. Fabric's registered
	 * times are merged in through Fabric's own {@code FuelRegistryImpl.apply}, so tag entries expand and a
	 * non-positive value removes, exactly as on native Fabric.
	 */
	public static Map<Item, Integer> apply(Map<Item, Integer> table) {
		try {
			if (table == null || !resolve()) return table;
			apply.invoke(registry, table);
		} catch (Throwable t) {
			if (WARNED.compareAndSet(false, true)) {
				ForbricLog.warn("[Forbric/Fuel] fabric-content-registries' fuel registry could not be applied — "
						+ "Fabric mods' fuels are missing from this game's fuel table", Reflect.unwrap(t));
			}
		}
		return table;
	}

	/** fabric-content-registries, once; false (for good) when it is not installed. */
	private static synchronized boolean resolve() throws ReflectiveOperationException {
		if (resolved) return registry != null;
		resolved = true;
		Class<?> api;
		try {
			api = Class.forName("net.fabricmc.fabric.api.registry.FuelRegistry", true,
					KernelFabricFuel.class.getClassLoader());
		} catch (ClassNotFoundException absent) {
			return false;
		}
		ClassLoader fabric = api.getClassLoader();
		registry = api.getField("INSTANCE").get(null);
		// The implementation rather than the interface: INSTANCE is a FuelRegistryImpl and apply(Map) is the
		// method fabric-api's own furnace mixin calls. Verified against fabric-api 0.116.17+1.21.1.
		apply = Class.forName("net.fabricmc.fabric.impl.content.registry.FuelRegistryImpl", false, fabric)
				.getMethod("apply", Map.class);
		return true;
	}
}
