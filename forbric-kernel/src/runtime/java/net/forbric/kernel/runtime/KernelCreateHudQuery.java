/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;
import net.forbric.kernel.interop.CreateHudScope;

/**
 * PORT(1.21.1): there is no native contextual-info selection to defer to.
 *
 * <p>On 26.2 this was the landing site for {@code CreateHudContextInjector}, which rewrote Create's
 * {@code Hud.nextContextualInfoState()} call inside {@code net.minecraft.client.gui.Hud.updateContextualBarRenderer}
 * (returning {@code Hud$ContextualInfo}) so that a train overlay could be drawn at the native selection while
 * {@link CreateHudScope}'s callback suppresses the vanilla bar.
 *
 * <p><b>None of that exists on 1.21.1.</b> There is no {@code net.minecraft.client.gui.Hud} class (the HUD is
 * {@code net.minecraft.client.gui.Gui}), no {@code updateContextualBarRenderer}, no {@code nextContextualInfoState},
 * and no {@code Hud$ContextualInfo} — so both the injector's anchor and {@code CreateHudMixinAdapter}'s target check
 * ({@code targets.apply("net/minecraft/client/gui/Hud")}) stand down, and this method has no caller.
 *
 * <p>It is kept because {@code KernelRuntimeClasses} requires the class to exist and load, and kept CORRECT rather
 * than deleted so the seam it names stays visible: the parameter is {@code Object} (the type it used to take no
 * longer exists) and the native query answers {@code null}. Verifying that Create's 1.21.1 contextual bar really has
 * no equivalent selection point is W5 work; if it has one, the injector is re-derived against it and this body
 * becomes a real delegate again.
 */
public final class KernelCreateHudQuery {
	private KernelCreateHudQuery() { }
	/** Returns the scope's answer for {@code hud}, or the native query's — which is nothing on 1.21.1. */
	public static Object next(Object hud) {
		return CreateHudScope.query(hud, () -> null);
	}
}
