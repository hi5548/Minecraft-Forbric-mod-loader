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

package net.fabricmc.loader.launch.common;

import java.net.URL;

/**
 * Fabric Loader's PRE-0.15 launcher facade, which loader 0.19.5 still ships for mods compiled against it.
 *
 * <p>{@code net.fabricmc.loader.impl.launch} is where the launcher lived after the move; before that it was this
 * package, and a mod built then keeps naming it however modern the loader is. LuckPerms 5.4.140 is such a mod:
 * {@code me/lucko/luckperms/fabric/FabricClassPathAppender} does
 * {@code net.fabricmc.loader.launch.common.FabricLauncherBase.getLauncher().propose(jar)} to append the jar it
 * unpacked. Real loader 0.19.5 still carries {@code FabricLauncherBase.class} and {@code FabricLauncher.class}
 * under this name, so on Fabric that call links; a kernel that ships only the {@code impl} path leaves it a
 * {@code NoClassDefFoundError}.
 *
 * <p>As with the {@code impl}-package sibling, only what such mods are OBSERVED to call is declared, so an
 * unsupported call fails at link time by name instead of silently doing nothing — this is not an attempt to
 * reproduce the interface. The observed surface here is exactly {@link #propose(URL)}. Sinytra Connector names
 * this package too, but its use is a hybrid-loader surface far larger than one call and is not covered.
 */
public interface FabricLauncher {
	/**
	 * Appends a jar to the running mod classpath.
	 *
	 * @param url the jar to append; the kernel appends through a {@code Path}, so a non-{@code file:} URL fails
	 *            loudly rather than being dropped
	 */
	void propose(URL url);
}
