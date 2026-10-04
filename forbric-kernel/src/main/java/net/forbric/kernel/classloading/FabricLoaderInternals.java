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

package net.forbric.kernel.classloading;

import java.util.Set;

/**
 * The classes from Fabric Loader's INTERNAL packages the kernel ships, and the switch that takes them away again.
 *
 * <p>The kernel implements no Fabric Loader machinery. But mods link against a handful of its internals anyway, and on
 * Fabric those links hold: Core Lib's {@code preLaunch} (and every SuperMartijn642 mod's content with it) goes through
 * {@code FabricLoaderImpl}'s entrypoint storage, its mixin plugin reads the legacy
 * {@code net.fabricmc.loader.FabricLoader}, pets-mod calls {@code StringUtil.capitalize}, and owo anchors on
 * {@code Hooks.startServer}. Each is shipped as the narrowest surface those mods use, by exact name, so anything beyond
 * it still fails by name.
 *
 * <p><b>Pinned to the parent, by exact name.</b> There must be one copy, the kernel's: a mod that shaded Fabric
 * Loader's real {@code FabricLoaderImpl} would otherwise win child-first and run Fabric's own static initialiser in
 * the middle of the kernel's boot. Only the names listed are pinned; the rest of {@code net.fabricmc.loader.impl}
 * keeps today's child-first rule, so a mod's shaded copy of some OTHER internal helper keeps working as before.
 *
 * <p><b>Withheld by {@code -Dforbric.fabricImpl=off}.</b> The class loader then answers
 * {@code ClassNotFoundException} for every name in {@link #SWITCHED}, and the kernel stops reading the entrypoint
 * storage back — exactly the behaviour from before these classes existed. {@link #ALWAYS} is not in that set: the
 * game's own entry points call {@code Hooks}, which has its own switch ({@code -Dforbric.fabricHooks=off}) that stops
 * the calls being emitted in the first place, and the pre-0.15 launcher facade is a static the kernel fills at boot
 * and never empties.
 */
public final class FabricLoaderInternals {
	/** {@code -Dforbric.fabricImpl=off}: mods see none of {@link #SWITCHED}, and the storage is not read back. */
	public static final String SWITCH = "forbric.fabricImpl";

	/** Every class (nested ones included) of the shipped internals the switch withholds. */
	static final Set<String> SWITCHED = Set.of(
			"net.fabricmc.loader.FabricLoader",
			"net.fabricmc.loader.impl.FabricLoaderImpl",
			"net.fabricmc.loader.impl.FabricLoaderImpl$InitHelper",
			"net.fabricmc.loader.impl.ModContainerImpl",
			"net.fabricmc.loader.impl.entrypoint.EntrypointStorage",
			"net.fabricmc.loader.impl.entrypoint.EntrypointStorage$Entry",
			"net.fabricmc.loader.impl.entrypoint.EntrypointStorage$NewEntry",
			"net.fabricmc.loader.impl.util.DefaultLanguageAdapter",
			"net.fabricmc.loader.impl.util.StringUtil",
			"net.fabricmc.loader.impl.util.version.StringVersion");

	/** Shipped internals pinned but never withheld: the game's own hook, and the launcher facade the kernel fills. */
	static final Set<String> ALWAYS = Set.of(
			"net.fabricmc.loader.impl.game.minecraft.Hooks",
			// The pre-0.15 launcher facade. The kernel installs its launcher view into this static at boot and never
			// takes it away, so it is pinned like the rest and — unlike {@code Hooks} — never withheld either: the
			// impl-package facade beside it is not the switch's business, and neither is this one. Pinned because a
			// mod that shaded it would otherwise win child-first with a second, empty static: {@code getLauncher()}
			// would then answer null to the very call the kernel had just installed.
			"net.fabricmc.loader.launch.common.FabricLauncher",
			"net.fabricmc.loader.launch.common.FabricLauncherBase");

	private FabricLoaderInternals() {
	}

	/** Whether the switched internals are offered to mods. Read on every call, so a test can flip it. */
	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
	}

	/** Whether {@code className} is one of the shipped internals, which only the parent may define. */
	public static boolean pinned(String className) {
		return SWITCHED.contains(className) || ALWAYS.contains(className);
	}

	/** Whether the class loader must answer {@code ClassNotFoundException} for {@code className}. */
	public static boolean withheld(String className) {
		return SWITCHED.contains(className) && !enabled();
	}
}
