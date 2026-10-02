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

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;

import net.forbric.kernel.util.ForbricLog;

/**
 * Names a client reload listener that nothing else can name, instead of letting the unnamed listener die.
 *
 * <p>PORT(1.21.1): this class is inert here. It existed for 26.2's sorted client-listener graph, whose event
 * asked a vanilla name table for a listener's key and THREW — "A non-vanilla reload listener … was added via
 * mixin before the AddClientReloadListenerEvent! Mod-added listeners must go through that event." — when the
 * table returned nothing. 1.21.1 has neither that table nor that assertion: its client registration event
 * carries no name lookup at all, and {@code registerReloadListener(listener)} takes no id, so no listener needs
 * a name and nothing on this side calls this class. It is kept because {@code KernelRuntimeClasses} requires the
 * class to load and the boot-side transformer names it at its old anchor; that transformer's anchor and
 * descriptor must be re-derived for 1.21.1 before any boot-side repair calls this.
 *
 * <h2>What the name is for, and why synthesising one is not a workaround</h2>
 *
 * <p>A name is a sort key and a registry key, nothing more: the sorted-listener graph holds id-to-listener and
 * listener-to-id maps and a dependency graph over them. A listener with a synthesised unique name is therefore a
 * listener that is registered, sorted and RUN — which is the whole difference between this and catching the
 * exception. Derived from the class, so it is stable across runs and unique by construction; a second listener
 * of the same class would collide, and that is reported.
 */
public final class KernelClientReloadNames {

	private static final String NAMESPACE = "forbric";
	private static final Set<String> REPORTED = Collections.newSetFromMap(new ConcurrentHashMap<>());

	private KernelClientReloadNames() {
	}

	/**
	 * A name for a listener nothing else can name.
	 *
	 * <p>PORT(1.21.1): the vanilla table lookup that used to come first is gone — 1.21.1 has no such table, so
	 * every class takes the synthesised path and this method is the whole lookup. See the class note.
	 */
	public static ResourceLocation nameFor(Class<? extends PreparableReloadListener> type) {
		String path = sanitise(type.getName());
		ResourceLocation synthesised = ResourceLocation.fromNamespaceAndPath(NAMESPACE, path);
		if (REPORTED.add(type.getName())) {
			ForbricLog.info("[Forbric/ClientReload] %s was added to the resource manager by a mixin, which is how a "
					+ "Fabric mod has always done it and which the sorted-listener event used to refuse to name — "
					+ "it used to take the client down inside Minecraft.<init>. It is registered and sorted as %s, "
					+ "so it still runs", type.getName(), synthesised);
		}
		return synthesised;
	}

	/**
	 * A class name as a {@code ResourceLocation} path.
	 *
	 * <p>{@code ResourceLocation} accepts only {@code [a-z0-9_.-/]} in a path, and a class name has neither case
	 * nor {@code $} in that set. The mapping is lossy in principle and unique in practice for the thing it names
	 * — two classes that differ only in case or in {@code $} placement would collide, which is why the caller
	 * reports each distinct class it synthesises for.
	 */
	private static String sanitise(String className) {
		StringBuilder out = new StringBuilder(className.length());
		for (int i = 0; i < className.length(); i++) {
			char c = Character.toLowerCase(className.charAt(i));
			out.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-'
					? c : '_');
		}
		return out.toString().toLowerCase(Locale.ROOT);
	}
}
