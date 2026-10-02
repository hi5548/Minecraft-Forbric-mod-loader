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

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.registries.DataMapLoader;
import net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent;

import net.forbric.kernel.util.ForbricLog;

/**
 * Watches NeoForge's OWN data-map reload path and counts what it does, so {@link KernelNeoWorldgen}'s
 * about-to-start fallback can stand down when the genuine path already applied. No static game state: this class
 * can be initialised and its watch installed on a recording bus outside the game.
 *
 * <p>{@code PORT(1.21.1)}: 26.2 registered the loader through {@code AddServerReloadListenersEvent} keyed by
 * {@code NeoForgeReloadListeners.DATA_MAPS_KEY}, and the summary fired on the {@code TagsUpdatedEvent.ServerDataLoad}
 * subclass. 1.21.1 has neither: {@code NeoForgeEventHandler.onResourceReload(AddReloadListenerEvent)} adds a
 * {@link DataMapLoader} to the listener list directly (javap: {@code new DataMapLoader(event.getRegistryAccess())},
 * {@code addListener}), and {@code TagsUpdatedEvent} is one class whose {@code UpdateCause} carries the
 * server-vs-client distinction. The three observations are otherwise identical.
 */
public final class KernelNeoDataMapWatch {
	private KernelNeoDataMapWatch() {
	}

	private static final java.util.concurrent.atomic.AtomicInteger GENERATION = new java.util.concurrent.atomic.AtomicInteger();
	private static final java.util.concurrent.atomic.AtomicInteger APPLIED_THIS_GENERATION = new java.util.concurrent.atomic.AtomicInteger();
	private static final java.util.concurrent.atomic.AtomicInteger APPLIED_TOTAL = new java.util.concurrent.atomic.AtomicInteger();

	/**
	 * Watches NeoForge's OWN data-map path, which the merged base carries whole: the carrier's
	 * {@code NeoForgeEventHandler.onResourceReload} registers a {@code DataMapLoader} on
	 * {@code AddReloadListenerEvent} (the merged {@code ReloadableServerResources} posts it and injects the live
	 * condition context), and the loader's own reload calls {@code apply()}, posting
	 * {@code DataMapsUpdatedEvent.SERVER_RELOAD} per registry — on the initial load and on every {@code /reload}.
	 * Three LOWEST-priority listeners count what that path does per reload generation and say so, and
	 * {@link #beforeServerStart} stands its fallback down when the genuine path already applied.
	 *
	 * <p>LOWEST, through the four-argument overload: the shorter ones promote to NORMAL, which would run the
	 * count line before NeoForge's own registration has added anything.
	 */
	public static void installDataMapWatch(Object neoBus) {
		IEventBus bus = (IEventBus) neoBus;
		bus.addListener(EventPriority.LOWEST, false, AddReloadListenerEvent.class, event -> {
			int generation = GENERATION.incrementAndGet();
			APPLIED_THIS_GENERATION.set(0);
			boolean registered = event.getListeners().stream().anyMatch(DataMapLoader.class::isInstance);
			if (!registered) {
				ForbricLog.warn("[Forbric/Worldgen] reload #%d: NeoForge's DataMapLoader is NOT registered — "
						+ "NeoForgeEventHandler.onResourceReload did not run on this bus; the kernel's about-to-start "
						+ "fallback will load the data maps", generation);
			}
		});
		bus.addListener(EventPriority.LOWEST, false, DataMapsUpdatedEvent.class, event -> {
			if (event.getCause() == DataMapsUpdatedEvent.UpdateCause.SERVER_RELOAD) {
				APPLIED_THIS_GENERATION.incrementAndGet();
				APPLIED_TOTAL.incrementAndGet();
			}
		});
		bus.addListener(EventPriority.LOWEST, false, TagsUpdatedEvent.class, event -> {
			if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) return;
			int applied = APPLIED_THIS_GENERATION.get();
			if (applied > 0) {
				ForbricLog.info("[Forbric/Worldgen] reload #%d: NeoForge's own reload path applied data maps for %d "
						+ "registr%s", GENERATION.get(), applied, applied == 1 ? "y" : "ies");
			} else {
				ForbricLog.warn("[Forbric/Worldgen] reload #%d: NeoForge's own reload path applied data maps for 0 "
						+ "registries — the kernel's about-to-start fallback will load them (with the live condition "
						+ "context), and /reload will not rebuild them", GENERATION.get());
			}
		});
	}

	/** Registries NeoForge's own path applied data maps for, across every reload so far. */
	public static int appliedTotal() {
		return APPLIED_TOTAL.get();
	}

}
