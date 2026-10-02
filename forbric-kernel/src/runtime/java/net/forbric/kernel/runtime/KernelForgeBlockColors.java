/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.neoforged.bus.api.Event;
import net.neoforged.fml.ModLoader;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.client.ForgeHooksClient;

/**
 * Both registration APIs populate the same live BlockColors instance.
 *
 * <p>PORT(1.21.1): the NeoForge event is {@code RegisterColorHandlersEvent.Block}, not 26.2's
 * {@code RegisterColorHandlersEvent.BlockTintSources}. NeoForge 21.1 has only {@code Block}, {@code Item} and
 * {@code ColorResolvers} (javap on the staged {@code neoforge-runtime.jar}); {@code Block} carries the same
 * {@code getBlockColors()} the 26.2 event did, and {@code ForgeHooksClient.onBlockColorsInit(BlockColors)} is
 * still MinecraftForge's half. So the body changes by one type name.
 *
 * <p>The SEAM is what moved, not the body: 26.2's {@code ForgeBlockTintInjector} rewrote the
 * {@code ModLoader.postEvent(<event ctor>)} call inside {@code BlockColors.createDefault}. On 1.21.1
 * {@code createDefault} names no colour-handler event at all; it calls NeoForge's own
 * {@code ClientHooks.onBlockColorsInit(BlockColors)}, which is where the event is built and posted
 * (javap -c against the staged merged base and {@code neoforge-runtime.jar}). The injector has been re-anchored
 * onto that method, so both families' colour-handler registrations reach this one live instance again.
 */
public final class KernelForgeBlockColors {
	private KernelForgeBlockColors() { }

	public static void postBlockTintSources(Event event) {
		RegisterColorHandlersEvent.Block registration = (RegisterColorHandlersEvent.Block) event;
		ModLoader.postEvent(registration);
		if (!"off".equalsIgnoreCase(System.getProperty("forbric.forgeClientInit", "on"))) {
			ForgeHooksClient.onBlockColorsInit(registration.getBlockColors());
		}
	}
}
