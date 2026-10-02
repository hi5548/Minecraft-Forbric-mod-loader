/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.kernel.util.ForbricLog;

/**
 * PORT(1.21.1): the 26.2 item-tooltip appender dispatcher does not exist on 1.21.1, so this class has no work.
 *
 * <p>On 26.2 this class rebuilt NeoForge's tooltip appenders and bridged fabric-item-api's component tooltip
 * providers into them. Every premise of that design is absent here:
 *
 * <ul>
 * <li>PORT(1.21.1): NeoForge 21.1 has no {@code net.neoforged.neoforge.common.tooltip.ItemTooltipHandler}, no
 * {@code TooltipAppender}, no {@code TooltipLocation} and no {@code RegisterTooltipAppendersEvent}. 26.2 replaced
 * vanilla's single tooltip body with those appender lists; 21.1 keeps vanilla's body ({@code
 * Item.appendHoverText} / {@code ItemStack.getTooltipLines}) and exposes a single hook, {@code
 * net.neoforged.neoforge.event.entity.player.ItemTooltipEvent}, which mods subscribe to like any other event. There
 * is nothing to build once, so {@link #init()} does nothing.</li>
 * <li>PORT(1.21.1): fabric-item-api-v1 {@code 0.116.17+1.21.1} has no {@code ItemComponentTooltipProviderRegistry}
 * (that registry and its {@code preAppendComponentTooltip}/{@code postAppendComponentTooltip} injectors are a
 * 26.2-era Fabric API feature). The kernel's bridge — pruning those injectors and redrawing the registry's lines
 * from NeoForge's appenders — can therefore not be expressed, and is not needed: on 1.21.1 Fabric draws its own
 * tooltip lines natively through {@code net.fabricmc.fabric.mixin.item.client.ItemStackMixin#getTooltip}, which
 * targets the vanilla body that still exists here. That injector must be left in place (see the boot-side note in
 * the class-level record), not pruned.</li>
 * </ul>
 *
 * <p>What 26.2's version carried that is now lost: Fabric component-tooltip providers are a 26.2-only API, so no
 * 1.21.1 mod can register one; and NeoForge's appender ordering ({@code POST_CUSTOM}/{@code PRE_ITEM_INFO}
 * placement, per-component {@code around}) has no 1.21.1 equivalent — mods order their lines themselves in
 * {@code ItemTooltipEvent}.
 *
 * <p>Boot-side follow-ups this port leaves to the seam: {@code NeoTooltipAppendersInjector} (its target
 * {@code ItemTooltipHandler} and {@code RegisterTooltipAppendersEvent} are gone) and {@code
 * TooltipOrderScrapeInjector} (nothing scrapes an order into) are dead on 1.21.1, and {@code GuestInjectorPruner}
 * must stop pruning fabric-item-api's tooltip injectors — {@code -Dforbric.neoTooltipAppenders} has no meaning
 * here.
 */
public final class KernelNeoTooltips {
	private static final AtomicBoolean REPORTED = new AtomicBoolean();

	private KernelNeoTooltips() {
	}

	/**
	 * PORT(1.21.1): a no-op kept only so the boot seam that calls {@code KernelNeoTooltips.init} after mod
	 * registration keeps resolving. 26.2 built NeoForge's appender lists here; 1.21.1 has none, and item tooltips
	 * (enchantments, lore, attributes, durability) are drawn by vanilla's own body.
	 */
	public static void init() {
		if (REPORTED.compareAndSet(false, true)) {
			ForbricLog.debug("[Forbric/Tooltips] 1.21.1 draws item tooltips through vanilla's body "
					+ "(Item.appendHoverText) and NeoForge's ItemTooltipEvent — no tooltip appenders to build");
		}
	}
}
