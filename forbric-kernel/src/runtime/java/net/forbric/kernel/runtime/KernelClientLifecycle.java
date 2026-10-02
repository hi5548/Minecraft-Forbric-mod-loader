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

import net.forbric.kernel.boot.KernelLifecycle;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.ReloadableResourceManager;

/**
 * The client-lifecycle redirect target for generations whose {@code ClientModLoader.begin} takes the three live
 * objects, so the call can stay descriptor-identical.
 *
 * <p>PORT(1.21.1): 26.2's trigger was {@code ClientModLoader.begin()V} and the redirect could point straight at the
 * boot-side {@code KernelLifecycle.onClientModLoading()} — a no-argument call needs no game types. On 1.21.1 both
 * families' {@code begin} is {@code (Minecraft, PackRepository, ReloadableResourceManager)V} and is called from
 * {@code Minecraft.<init>}, so a redirect that keeps the descriptor (the only form that preserves the arguments)
 * has to land on a method with exactly that signature. {@code KernelLifecycle} is boot-side and cannot name the
 * game types at compile time, so the bridge lives here, on the game side, where it can.
 *
 * <p>Both seams the redirect must serve are here because redirecting the CALL also takes {@code begin}'s body out
 * of the path: the repository is handed to {@link KernelLifecycle#onClientResourcePacks} (what
 * {@code ClientPackHookInjector} does from inside {@code begin} on 26.2), and the kernel's client lifecycle is
 * driven through {@link KernelLifecycle#onClientModLoading()}.
 */
public final class KernelClientLifecycle {
	private KernelClientLifecycle() {
	}

	/** The redirect target of {@code ClientModLoader.begin(Minecraft, PackRepository, ReloadableResourceManager)}. */
	public static void onClientModLoadingWithPacks(Minecraft minecraft, PackRepository packRepository,
			ReloadableResourceManager resourceManager) {
		KernelLifecycle.onClientResourcePacks(packRepository);
		KernelLifecycle.onClientModLoading();
	}
}
