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

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.kernel.util.ForbricLog;

/**
 * PORT(1.21.1): MinecraftForge's picture-in-picture renderers have no registry to fill on 1.21.1, so there is
 * nothing to collect and no map to fill.
 *
 * <p>On 26.2 this class existed because the byte merge left the vanilla {@code Class -> PictureInPictureRenderer}
 * map inside {@code GuiRenderer} with no writer, and the only thing that would have written it was MinecraftForge's
 * {@code RegisterPictureInPictureRendererEvent}. <b>None of that exists on 1.21.1.</b> Verified with javap against
 * the staged 1.21.1 merged base, {@code forge-runtime.jar} and {@code neoforge-runtime.jar}: there is no
 * {@code net.minecraft.client.gui.render} package at all (so no {@code GuiRenderer}), no
 * {@code PictureInPictureRenderer} / {@code PictureInPictureRenderState}, no MinecraftForge
 * {@code RegisterPictureInPictureRendererEvent}, and no NeoForge {@code PictureInPictureRendererRegistration}. The
 * whole client GUI on 1.21.1 draws through {@code GuiGraphics}' immediate-mode path, so a guest mod's in-world
 * preview or minimap element registers through the ordinary HUD layer path, which
 * {@link KernelForgeOverlayLayers} already serves.
 *
 * <p>What remains is the one entry point the kernel's own registry of runtime seams names
 * ({@code KernelRuntimeClasses}: {@code build() -> Map}), so a boot-side repair that still calls it gets an empty,
 * non-null map — the same answer 26.2 gave when no mod registered — instead of a {@code NoSuchMethodError} inside
 * the game's own constructor.
 *
 * <p>The 26.2-only members are REMOVED rather than stubbed, because their parameter and return types do not exist
 * on this base and nothing that loads here can call them: {@code poolRegistrations(List) -> List},
 * {@code build(List) -> Map} and {@code close(Map)}. The boot-side pieces that name them
 * ({@code ForbricMergedBaseCompatTransformer.bridgeOrphanedPipRenderers},
 * {@code CreateInjectionAdapters.gui}) are inert for the same reason and must be deleted or re-derived on 1.21.1.
 *
 * <p>{@code -Dforbric.forgePipRenderers=off} is still honoured, so the switch keeps its meaning as the negative
 * control for its own mechanism.
 */
public final class KernelForgePipRenderers {
	static final String PROPERTY = "forbric.forgePipRenderers";

	/** One line per process: this entry point is a no-op on 1.21.1 and that should not be inferred from silence. */
	private static final AtomicBoolean ANNOUNCED = new AtomicBoolean();

	private KernelForgePipRenderers() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/**
	 * The map {@code GuiRenderer.<init>} used to assign on 26.2 — always empty here.
	 *
	 * <p>Never null and never throws: on 26.2 this ran inside the game's own constructor, and that contract is kept
	 * even though nothing on 1.21.1 calls it.
	 */
	public static Map<Object, Object> build() {
		if (!enabled()) {
			ForbricLog.warn("[Forbric/PipRenderers] -D%s=off — the picture-in-picture map stays empty (it has no "
					+ "writer on 1.21.1 either way)", PROPERTY);
			return Map.of();
		}
		if (ANNOUNCED.compareAndSet(false, true)) {
			ForbricLog.info("[Forbric/PipRenderers] nothing to collect on 1.21.1 — the picture-in-picture renderer "
					+ "types this bridge existed for do not exist on this base, so the map is empty by construction");
		}
		return Map.of();
	}
}
