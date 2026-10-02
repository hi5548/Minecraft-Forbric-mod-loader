/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;

/**
 * Preserve native listener names; give foreign listeners stable, collision-free graph keys.
 *
 * <p>PORT(1.21.1): NeoForge 21.1 has no {@code net.neoforged.neoforge.resource.VanillaServerListeners}, and no
 * identifier-keyed reload-listener graph for it to answer from. 26.2's {@code getNameForClass(Class)} returned the
 * vanilla {@code Identifier} NeoForge had assigned a native listener, so a native listener kept its name in the
 * graph; on 1.21.1 vanilla orders reload listeners by object identity ({@code SimpleReloadInstance} /
 * {@code ReloadableServerResources}), not by name, so there are no native names here to preserve. Every listener
 * — native or foreign — gets the deterministic {@code forbric:foreign_reload/<hex>} key instead.
 *
 * <p>PORT(1.21.1): the key type is {@code net.minecraft.resources.ResourceLocation}, 1.21.1's spelling of 26.2's
 * {@code net.minecraft.resources.Identifier} ({@code fromNamespaceAndPath} and {@code parse} keep their names).
 */
public final class KernelServerReloadNames {
	private KernelServerReloadNames() { }

	public static ResourceLocation nameFor(Class<? extends PreparableReloadListener> type) {
		return ResourceLocation.fromNamespaceAndPath("forbric", "foreign_reload/"
				+ HexFormat.of().formatHex(type.getName().getBytes(StandardCharsets.UTF_8)));
	}
}
