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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraftforge.common.MinecraftForge;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/**
 * Collects traditional MinecraftForge's client reload listeners and re-registers them with NeoForge.
 *
 * <p>The restored Forge client hook posts its own registration event first. This bridge consumes that capture
 * while NeoForge posts its registration event. With the client-init repair disabled it retains its original
 * fallback: posting Forge's event against a scratch manager itself.
 *
 * <h2>The scratch manager</h2>
 *
 * <p>MinecraftForge's event has no accessor for what was registered to it — the listeners go straight into the
 * {@code ReloadableResourceManager} it was constructed with. So one is constructed purely as a capture buffer,
 * the Forge event is posted against it, and whatever landed inside is read back out and re-registered on
 * NeoForge's event. A captured empty list still means the event has already been posted. The manager is never
 * used to reload anything.
 *
 * <p>PORT(1.21.1): 26.2's NeoForge sorted its client listeners in a graph keyed by a resource id, so this bridge
 * gave every listener a synthetic one. 1.21.1's {@code RegisterClientReloadListenersEvent} takes no id —
 * {@code registerReloadListener(listener)} only — so nothing is named here.
 */
public final class KernelGameClientReload {
	private KernelGameClientReload() {
	}

	/**
	 * @param modBus the NeoForge MOD bus, handed over untyped from the boot side. This event is a mod-bus event,
	 *               not a game-bus one, which is why it is a separate pass from the other four bridges.
	 */
	public static void install(Object modBus) {
		Consumer<RegisterClientReloadListenersEvent> bridge = event -> {
			List<PreparableReloadListener> captured = ForgeClientReloadCapture.drain();
			if (captured != null) {
				// ForgeHooksClient already posted its self-destructing event. Reposting it would return an
				// empty list while its handler count still read "one", losing all real reload listeners.
				register(event, captured);
				return;
			}
			try {
				// The capture buffer. ReloadableResourceManager keeps its listeners in a private list with no
				// accessor, so a scratch subclass records what Forge's event registers into it.
				try (RecordingResourceManager recorder = new RecordingResourceManager()) {
					MinecraftForge.EVENT_BUS.post(
							new net.minecraftforge.client.event.RegisterClientReloadListenersEvent(recorder));
					register(event, recorder.recorded());
				}
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/EventMux] could not bridge Forge client reload listeners",
						Reflect.unwrap(t));
			}
		};

		// Four-argument overload with LOWEST, as everywhere in this package.
		((IEventBus) modBus).addListener(EventPriority.LOWEST, false, RegisterClientReloadListenersEvent.class, bridge);
	}

	private static void register(RegisterClientReloadListenersEvent event, List<PreparableReloadListener> listeners) {
		int n = 0;
		for (PreparableReloadListener listener : listeners) {
			event.registerReloadListener(listener);
			n++;
		}
		if (n > 0) {
			ForbricLog.info("[Forbric/EventMux] bridged %d Forge client reload listener(s) into NeoForge's sorted graph", n);
		}
	}

	/** A scratch manager that records what is registered to it; see the class javadoc. */
	private static final class RecordingResourceManager extends ReloadableResourceManager {
		private final List<PreparableReloadListener> recorded = new ArrayList<>();

		private RecordingResourceManager() {
			super(PackType.CLIENT_RESOURCES);
		}

		@Override
		public void registerReloadListener(PreparableReloadListener listener) {
			recorded.add(listener);
		}

		private List<PreparableReloadListener> recorded() {
			return recorded;
		}
	}
}
