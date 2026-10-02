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

import java.util.List;

import net.forbric.api.EventBridges;
import net.forbric.api.GameEventBridge;
import net.forbric.kernel.util.ForbricLog;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Posts NeoForge's {@code ItemTooltipEvent} beside MinecraftForge's, on the same list.
 *
 * <p>The merged {@code ItemStack.getTooltipLines} carries exactly one event call —
 * {@code ForgeEventFactory.onItemTooltip}, MinecraftForge's. NeoForge's event is never constructed, so a
 * NeoForge mod that adds a line to an item's tooltip adds it to nothing: Architectury and RarityCore both do,
 * and on a 97-jar pack both were reported as "partly did not run" with no other symptom a player could see.
 *
 * <p>Both events hold the SAME mutable {@code List<Component>} the caller is building, which is what makes one
 * extra post the whole fix — NeoForge's listeners append to the list MinecraftForge's listeners just appended to,
 * in that order, and the method returns it. Nothing is copied back and nothing can be lost.
 *
 * <p>NeoForge's event carries an {@code Item.TooltipContext} MinecraftForge's does not; it is already in scope at
 * the call site as the method's own parameter, so the injected call passes the real one rather than a placeholder.
 * A mod reading {@code getContext()} for a registry lookup gets the lookup the game is actually using.
 *
 * <p>PORT(1.21.1): 26.2's {@code ItemTooltipEvent} also takes a tooltip-display value, a type 1.21.1 does not
 * have. 1.21.1's constructor is {@code (ItemStack, Player, List<Component>, TooltipFlag, Item.TooltipContext)},
 * so {@link #postNeoForge} drops that parameter and the injected call passes the five remaining arguments. The
 * boot-side {@code ForbricMergedBaseCompatTransformer} must match this descriptor.
 *
 * <p>Failures are swallowed with one warning per process. A tooltip is drawn every frame an item is hovered, and
 * a mod that throws here would otherwise replace the tooltip with a crash, once per frame.
 */
public final class KernelItemTooltips {
	static final String PROPERTY = "forbric.itemTooltipBridge";
	private static volatile boolean announced;
	private static volatile boolean warned;

	private KernelItemTooltips() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/**
	 * Called from the merged {@code ItemStack.getTooltipLines}, right after MinecraftForge's own event.
	 *
	 * <p>After, not before, so the two families see the tooltip in the order each one's loader gives it: on
	 * MinecraftForge the mod's lines are appended to whatever the game built, and on NeoForge the same.
	 */
	public static void postNeoForge(ItemStack stack, Player player, List<Component> tooltip, TooltipFlag flag,
			Item.TooltipContext context) {
		if (!enabled()) return;
		try {
			// PORT(1.21.1): the tooltip-display argument 26.2 had does not exist on 1.21.1's event.
			NeoForge.EVENT_BUS.post(new ItemTooltipEvent(stack, player, tooltip, flag, context));
			if (!announced) {
				announced = true;
				EventBridges.installed(GameEventBridge.ITEM_TOOLTIP);
				ForbricLog.info("[Forbric/Tooltips] NeoForge's ItemTooltipEvent is posted beside MinecraftForge's, "
						+ "on the same tooltip list — the merged getTooltipLines carries only MinecraftForge's call, "
						+ "so a NeoForge mod's tooltip lines went into a list nobody built");
			}
		} catch (Throwable t) {
			if (!warned) {
				warned = true;
				ForbricLog.warn("[Forbric/Tooltips] a NeoForge ItemTooltipEvent listener failed — tooltips are drawn "
						+ "every frame an item is hovered, so this is reported once and then swallowed rather than "
						+ "replacing the tooltip with a crash", t);
			}
		}
	}

	/** Whether the bridge has posted at least once, for the gate to assert on rather than infer. */
	public static boolean posted() {
		return announced;
	}
}
