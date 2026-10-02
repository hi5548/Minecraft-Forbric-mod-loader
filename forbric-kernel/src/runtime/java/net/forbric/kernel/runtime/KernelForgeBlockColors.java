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
 * {@code ModLoader.postEvent(<event ctor>)} call inside {@code BlockColors.createDefault}, and the merged 1.21.1
 * base names no colour-handler event anywhere in that class (checked against the class's constant pool), so the
 * injector's REQUIRED anchor no longer matches and this method has no caller until the seam is re-derived —
 * against whichever 1.21.1 method really posts the event. It is kept CORRECT rather than deleted, because
 * deleting it would hide the seam that has to come back.
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
