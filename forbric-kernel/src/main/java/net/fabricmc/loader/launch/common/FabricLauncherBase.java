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

/**
 * The static hand-off mods use to reach the PRE-0.15 {@link FabricLauncher}: {@code FabricLauncherBase.getLauncher()}.
 *
 * <p>This is the old-package twin of {@code net.fabricmc.loader.impl.launch.FabricLauncherBase}. Both are
 * installed with the same kernel launcher view, so a mod that reaches for either package sees one answer. See
 * {@link FabricLauncher} for the caller that needs this one and why only one member is declared.
 */
public final class FabricLauncherBase {
	private static volatile FabricLauncher launcher;

	private FabricLauncherBase() {
	}

	/** The active launcher, or {@code null} before the kernel has installed one. */
	public static FabricLauncher getLauncher() {
		return launcher;
	}

	/** Installs the kernel's launcher view. Called once by the boot orchestrator, beside the impl-package twin. */
	public static void setLauncher(FabricLauncher active) {
		launcher = active;
	}
}
