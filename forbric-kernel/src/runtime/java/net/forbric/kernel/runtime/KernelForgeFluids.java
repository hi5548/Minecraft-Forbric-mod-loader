/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.textures.FluidSpriteCache;

/**
 * Lets a MinecraftForge fluid supply its own sprites and tint from the merged {@code LiquidBlockRenderer.tesselate}.
 *
 * <p>Genuine MinecraftForge patches that method to take both from the fluid's client extensions: the sprite array
 * from {@code ForgeHooksClient.getFluidSprites(level, pos, state)} and the colour from
 * {@code IClientFluidTypeExtensions.of(state).getTintColor(state, level, pos)}. The merged base kept NeoForge's
 * body — verified with {@code javap -c}: the merged call site reaches
 * {@code net.neoforged.neoforge.client.textures.FluidSpriteCache.getFluidSprites(...)} and NeoForge's
 * {@code IClientFluidTypeExtensions}, and there is no {@code net/minecraftforge/} reference in the method at all
 * (both the {@code patched-mc-forge-1.21.1.jar} and {@code patched-mc-neoforge-1.21.1.jar} bodies were compared).
 * So every MinecraftForge modded fluid drew with NeoForge's sprites and colour.
 *
 * <p>Each site is one same-descriptor substitution, which is what the transformer wants: NeoForge's
 * {@code FluidSpriteCache.getFluidSprites(BlockAndTintGetter, BlockPos, FluidState)[TextureAtlasSprite]} is
 * replaced by {@link #sprites} with the identical descriptor, and the interface call
 * {@code IClientFluidTypeExtensions.getTintColor(FluidState, BlockAndTintGetter, BlockPos)I} by {@link #tintColor}
 * with the receiver as its first parameter. Neither moves anything on the stack, so no frame is recomputed.
 *
 * <p>Hot path: this runs once per fluid tesselation, the same cost genuine Forge pays. The count line is gated by
 * a contains-check before an add, and only the first sighting of a fluid logs. Forge's {@code DEFAULT} extension
 * (vanilla fluids, and any Forge fluid whose {@code initClient} never ran) short-circuits to NeoForge's own answer
 * by identity, so vanilla rendering is byte-for-byte what it was. {@code -Dforbric.forgeFluidModels=off} returns
 * NeoForge's answer at both sites.
 *
 * <h2>PORT(1.21.1): what the two asks are made of</h2>
 *
 * <p>26.2's fluid renderer chose a {@code FluidModel} and consulted Forge's extensions for a model plus a
 * no-argument tint; neither type exists on 1.21.1. There the renderer's two data are the sprite array and the
 * packed ARGB colour, and Forge 52 asks for them exactly as described above — verified against
 * {@code forge-runtime.jar} (52.1.16). The sprites are <em>computed</em> here (Forge's own
 * {@code ForgeHooksClient.getFluidSprites}) rather than returned from the caller, because the substitution
 * happens at the call site and its return value is not on the stack; the tint method takes NeoForge's extension
 * as its first parameter for the same reason. {@code -1} needs no special case: NeoForge's renderer unpacks the
 * colour into components directly, and {@code -1} unpacks to white, which is what Forge's default tint means.
 */
public final class KernelForgeFluids {
	public static final String PROPERTY = "forbric.forgeFluidModels";
	private static final Set<Fluid> ASKED = ConcurrentHashMap.newKeySet();
	private static final Set<Fluid> ANSWERED = ConcurrentHashMap.newKeySet();
	private static volatile boolean failureReported;

	private KernelForgeFluids() {
	}

	/**
	 * Site A: what the fluid's own Forge client extensions supply, or NeoForge's cache answer. Forge's extension
	 * type is spelled in full because NeoForge's has the same simple name and both are needed in this file.
	 */
	public static TextureAtlasSprite[] sprites(BlockAndTintGetter level, BlockPos pos, FluidState state) {
		if ("off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) return FluidSpriteCache.getFluidSprites(level, pos, state);
		try {
			net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions extensions =
					net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions.of(state);
			Fluid fluid = state.getType();
			// Counted before the DEFAULT short-circuit, so a vanilla fluid in view proves the funnel is on the render
			// path even when no Forge fluid exists to answer; the contains-check keeps the hot path cheap.
			boolean first = !ASKED.contains(fluid) && ASKED.add(fluid);
			if (extensions == net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions.DEFAULT) {
				if (first) report(fluid);
				return FluidSpriteCache.getFluidSprites(level, pos, state);
			}
			TextureAtlasSprite[] own = net.minecraftforge.client.ForgeHooksClient.getFluidSprites(level, pos, state);
			if (first) {
				if (own != null) ANSWERED.add(fluid);
				report(fluid);
			}
			return own == null ? FluidSpriteCache.getFluidSprites(level, pos, state) : own;
		} catch (Throwable t) {
			if (!failureReported) {
				failureReported = true;
				ForbricLog.warn("[Forbric/Fluids] MinecraftForge fluid extensions threw while choosing sprites — "
						+ "NeoForge's are used", Reflect.unwrap(t));
			}
			return FluidSpriteCache.getFluidSprites(level, pos, state);
		}
	}

	private static void report(Fluid fluid) {
		ForbricLog.info("[Forbric/Fluids] MinecraftForge client extensions consulted for %d fluid(s) so far, %d "
				+ "supplied their own sprites (%s)", ASKED.size(), ANSWERED.size(), fluid);
	}

	/**
	 * Site B: the tint for a fluid — Forge's extension answer, or NeoForge's. The first parameter is the
	 * receiver the substituted interface call was made on, so the call site's stack is untouched.
	 */
	public static int tintColor(IClientFluidTypeExtensions neo, FluidState state, BlockAndTintGetter level, BlockPos pos) {
		if ("off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) return neo.getTintColor(state, level, pos);
		try {
			net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions extensions =
					net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions.of(state);
			if (extensions == net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions.DEFAULT) {
				return neo.getTintColor(state, level, pos);
			}
			return extensions.getTintColor(state, level, pos);
		} catch (Throwable t) {
			return neo.getTintColor(state, level, pos);
		}
	}
}
