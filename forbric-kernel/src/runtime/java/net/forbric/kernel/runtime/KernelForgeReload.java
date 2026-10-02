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

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraftforge.event.ForgeEventFactory;
import net.neoforged.neoforge.event.EventHooks;

/**
 * The server twin of {@link KernelGameClientReload}: posts MinecraftForge's {@code AddReloadListenerEvent} from
 * the merged server reload, through the carrier's own {@code ForgeEventFactory}.
 *
 * <p>The merged {@code ReloadableServerResources.loadResources} calls only NeoForge's
 * {@code EventHooks.onResourceReload} (on 26.2 a constant-pool scan found ZERO references to Forge's event; on
 * 1.21.1 it finds Forge's call in an unreachable duplicate lambda — see the PORT note below). So every
 * traditional-Forge mod that registers a JSON data loader the documented way — from
 * {@code AddReloadListenerEvent} — had its listener registered on a bus nobody posted to, and its custom data
 * folder loaded nothing, silently. The merged constructor's single call site is redirected here with the same
 * descriptor; this method calls NeoForge's hook first (so its list, context injection and ordering are exactly
 * what they were), then hands that list to the carrier's real {@code ForgeEventFactory.onResourceReload}, which
 * fires the Forge bus and appends what Forge listeners added. Forge's own order is the same: its listeners come
 * after the game's. The registry lookup handed to Forge is the same object NeoForge's path uses
 * ({@code getRegistryLookup()} returns the {@code lookupWithUpdatedTags} the resources were built with).
 *
 * <p>{@code -Dforbric.forgeReloadListeners=off} returns NeoForge's list unchanged — the redirect itself is inert
 * — and a throwing Forge listener costs this reload every Forge listener (the carrier fires them in one post),
 * which is logged rather than allowed to abort the reload.
 *
 * <h2>PORT(1.21.1): the hook's own shape moved, the hole did not</h2>
 *
 * <p>21.1's hooks are three-argument Forge and two-argument NeoForge: {@code EventHooks.onResourceReload}
 * takes {@code (ReloadableServerResources, RegistryAccess)} and keeps the listener map inside NeoForge rather
 * than receiving it from the caller, and {@code ForgeEventFactory.onResourceReload} takes
 * {@code (ReloadableServerResources, HolderLookup.Provider, RegistryAccess)}. Both verified with {@code javap}
 * against the staged {@code neoforge-runtime.jar} (21.1.252) and {@code forge-runtime.jar} (52.1.16); the
 * 26.2-only {@code ListenerKey} parameter is gone with NeoForge's map parameter.
 *
 * <p>The hole is still there, and the evidence is one {@code javap -c} away: the merged
 * {@code ReloadableServerResources.loadResources} carries TWO near-identical lambdas, one calling
 * {@code ForgeEventFactory.onResourceReload} and one calling {@code EventHooks.onResourceReload} — and the
 * single {@code invokedynamic} in {@code loadResources} binds the NeoForge one by its capture list
 * ({@code FeatureFlagSet, Commands$CommandSelection, int, ResourceManager, Executor, Executor}). Forge's
 * lambda survives in the constant pool and is unreachable, so Forge's event is still never posted.
 *
 * <p>The redirect this class stands behind therefore still needs its 1.21.1 anchor re-derived:
 * {@code EventHooks.onResourceReload:(Lnet/minecraft/server/ReloadableServerResources;Lnet/minecraft/core/RegistryAccess;)Ljava/util/List;}
 * in {@code lambda$loadResources$5}, where the redirect pushes two arguments instead of three. That is the
 * transform layer's ({@code ForbricMergedBaseCompatTransformer}), not this file's.
 */
public final class KernelForgeReload {
	public static final String PROPERTY = "forbric.forgeReloadListeners";

	private KernelForgeReload() {
	}

	/** Same descriptor as the NeoForge hook it stands in front of, so the merged call site's stack is untouched. */
	public static List<PreparableReloadListener> onResourceReload(ReloadableServerResources resources,
			RegistryAccess registries) {
		List<PreparableReloadListener> neo = EventHooks.onResourceReload(resources, registries);
		if ("off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) {
			ForbricLog.debug("[Forbric/EventMux] -D%s=off — MinecraftForge's AddReloadListenerEvent is not posted "
					+ "for this reload", PROPERTY);
			return neo;
		}
		try {
			// The carrier's own code: constructs Forge's event, fires it, and returns the listeners it collected.
			// PORT(1.21.1): 26.2's hook took NeoForge's list as its third argument and returned NeoForge's
			// listeners followed by Forge's; 21.1's takes the RegistryAccess instead and returns ONLY Forge's
			// listeners, so the two lists are concatenated here, in the same order (the game's, then Forge's).
			List<PreparableReloadListener> forge = ForgeEventFactory.onResourceReload(resources,
					resources.getRegistryLookup(), registries);
			List<PreparableReloadListener> all = new ArrayList<>(neo.size() + forge.size());
			all.addAll(neo);
			all.addAll(forge);
			int added = all.size() - neo.size();
			if (added > 0) {
				ForbricLog.info("[Forbric/EventMux] bridged %d MinecraftForge server reload listener(s) into this "
						+ "reload — the merged base posts only NeoForge's AddServerReloadListenersEvent, so a "
						+ "traditional-Forge mod's JSON data loaders were never registered", added);
			} else {
				ForbricLog.debug("[Forbric/EventMux] MinecraftForge's AddReloadListenerEvent was posted; no Forge "
						+ "listener added anything to this reload");
			}
			return all;
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/EventMux] could not post MinecraftForge's AddReloadListenerEvent — every "
					+ "Forge reload listener is absent from this reload", Reflect.unwrap(t));
			return neo;
		}
	}
}
