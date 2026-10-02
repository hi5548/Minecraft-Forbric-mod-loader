/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;
import org.objectweb.asm.*;import org.objectweb.asm.tree.*;

/**
 * PORT(1.21.1): stands down — there is no native contextual-info selection to wrap.
 *
 * <p>On 26.2 this redirected Create's {@code Hud.nextContextualInfoState()} call inside
 * {@code net.minecraft.client.gui.Hud.updateContextualBarRenderer()} so a train overlay could suppress the vanilla
 * contextual bar at the selection point. None of that is on 1.21.1: there is no {@code net.minecraft.client.gui.Hud}
 * class (the HUD is {@code net.minecraft.client.gui.Gui}), no {@code updateContextualBarRenderer} and no
 * {@code nextContextualInfoState}. {@link net.forbric.kernel.runtime.KernelCreateHudQuery} says the same and answers
 * nothing; the seam is kept visible for a re-derivation if 1.21.1's Create turns out to have an equivalent point.
 *
 * <p>Because the target class does not exist, this declares no anchor and its {@code transform} is a no-op on every
 * class.
 */
public final class CreateHudContextInjector implements ClassTransformer {
	/** 26.2's target, kept only to name the seam that is gone. */
	public static final String TARGET = "net.minecraft.client.gui.Hud";

	@Override public AnchorSet anchors() {
		return AnchorSet.scanned("PORT(1.21.1): net.minecraft.client.gui.Hud and its contextual-info selection do not "
				+ "exist on this generation (the HUD is Gui); the Create train-overlay suppression has no seam here");
	}

	@Override public byte[] transform(String name, byte[] bytes, TransformContext context) { return bytes; }
}
