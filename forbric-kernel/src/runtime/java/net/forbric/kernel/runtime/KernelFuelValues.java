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

package net.forbric.kernel.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.event.ForgeEventFactory;
import net.neoforged.neoforge.event.EventHooks;

/**
 * Lets both ecosystems say how long something burns, instead of only the one that won the merge.
 *
 * <h2>The shape, which is the other way round from the bridges</h2>
 *
 * <p>Every other seam in this package is NeoForge-won: its hook survives and MinecraftForge's is re-emitted
 * from a listener. This one is the reverse. {@code AbstractFurnaceBlockEntity.getBurnDuration} on the merged
 * base calls MinecraftForge's {@code ForgeHooks.getBurnTime} and nothing else, so NeoForge's
 * {@code FurnaceFuelBurnTimeEvent} is never posted — and {@code balm}, in the test pack, subscribes to it.
 *
 * <p>A listener cannot fix that direction: NeoForge's hook is a static call, not something to subscribe to. So
 * the call site is redirected here and both are asked, which is also why this lives beside the bridges rather
 * than among them.
 *
 * <h2>Chained, not both-from-the-same-start</h2>
 *
 * <p>MinecraftForge is asked first with the value the game computed, and NeoForge is asked with whatever
 * MinecraftForge returned. Asking both from the original and picking one would silently discard a mod's answer;
 * chaining means two mods from two ecosystems can each adjust a burn time, which is the whole premise of
 * running them together.
 *
 * <h2>PORT(1.21.1): the receiver the hook used to need is gone</h2>
 *
 * <p>On 26.2 both hooks carried the {@code FuelValues} the call site was inside —
 * {@code EventHooks.getItemBurnTime(ItemStack, int, RecipeType, FuelValues)} — which is why the redirect pushed
 * the receiver as well. 1.21.1 has no {@code FuelValues} class at all: the fuel table is
 * {@code AbstractFurnaceBlockEntity.getFuel()}, the burn time is the instance method
 * {@code getBurnDuration(ItemStack)}, and NeoForge 21.1's hook is
 * {@code EventHooks.getItemBurnTime(ItemStack, int, RecipeType)} — verified with {@code javap} against
 * {@code neoforge-runtime.jar} (21.1.252). The fourth parameter is dropped rather than replaced: there is no
 * 1.21.1 object that would mean the same thing.
 *
 * <p>Both the redirect's anchor and its descriptor therefore have to be re-derived for 1.21.1
 * ({@code getBurnDuration(ItemStack)I} instead of {@code FuelValues.burnDuration(ItemStack, RecipeType)}), which
 * is the transform layer's ({@code FabricFuelValuesInjector}/{@code ForbricMergedBaseCompatTransformer}) and not
 * this file's. Until it is done the merged base still asks MinecraftForge only.
 */
public final class KernelFuelValues {
	private static final AtomicBoolean WARNED = new AtomicBoolean();
	private static final AtomicBoolean PROVED = new AtomicBoolean();

	private KernelFuelValues() {
	}

	/**
	 * Both ecosystems' burn-time hooks, in order.
	 *
	 * @param base what the game's own table says, before either ecosystem is consulted
	 */
	public static int burnDuration(ItemStack stack, int base, RecipeType<?> type) {
		int value = base;
		try {
			value = ForgeEventFactory.getItemBurnTime(stack, value, type);
		} catch (Throwable t) {
			warnOnce("MinecraftForge", t);
		}
		try {
			// PORT(1.21.1): three arguments, not four — 21.1's hook carries no FuelValues (see the class doc).
			value = EventHooks.getItemBurnTime(stack, value, type);
			if (PROVED.compareAndSet(false, true)) {
				ForbricLog.info("[Forbric/Fuel] both ecosystems now set burn times — the merged "
						+ "AbstractFurnaceBlockEntity.getBurnDuration asked only MinecraftForge, so NeoForge's "
						+ "FurnaceFuelBurnTimeEvent was never posted");
			}
		} catch (Throwable t) {
			warnOnce("NeoForge", t);
		}
		return value;
	}

	/**
	 * One line per side, ever.
	 *
	 * <p>This runs for every fuel lookup in every furnace, so a per-call line would be the loudest thing in the
	 * log; and a throw here would take the smelt with it, which is a worse outcome than one ecosystem not being
	 * asked. The value carried so far is returned either way.
	 */
	private static void warnOnce(String family, Throwable t) {
		if (WARNED.compareAndSet(false, true)) {
			ForbricLog.warn("[Forbric/Fuel] " + family + "'s burn-time hook failed — mods on that side cannot "
					+ "change how long anything burns", Reflect.unwrap(t));
		}
	}
}
