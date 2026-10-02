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

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraftforge.common.ForgeHooks;
import net.neoforged.neoforge.event.EventHooks;

/** Composes the carriers' own generators, event dispatch and visibility merging. */
public final class KernelForgeCreativeTabs {
	private KernelForgeCreativeTabs() { }

	/**
	 * Same descriptor as the injector calls. The carriers added the tab's key as a second argument on 1.21.1; it is
	 * resolved from the registry the same way the merged base resolves it before its own NeoForge call, so a tab that
	 * reaches here always has one (the merged base already refuses a tab outside the registry above this point).
	 */
	public static void buildContents(CreativeModeTab tab, CreativeModeTab.DisplayItemsGenerator generator,
			CreativeModeTab.ItemDisplayParameters parameters, CreativeModeTab.Output output) {
		// PORT(1.21.1): 26.2's onCreativeModeTabBuildContents took four arguments; 1.21.1's EventHooks and ForgeHooks
		// both take five, inserting this ResourceKey<CreativeModeTab> after the tab. Neither carrier dereferences it
		// (javap -c: both events only store it), so a null from a tab the registry does not know is safe to pass.
		ResourceKey<CreativeModeTab> key = BuiltInRegistries.CREATIVE_MODE_TAB.getResourceKey(tab).orElse(null);
		if ("off".equalsIgnoreCase(System.getProperty("forbric.forgeCreativeTabs", "on"))) {
			EventHooks.onCreativeModeTabBuildContents(tab, key, generator, parameters, output);
			return;
		}
		// Neo emits parent and search entries separately. Forge's native merge lambda combines matching stacks
		// into PARENT_AND_SEARCH_TABS, then its event can amend the result. Keep the Neo generator inside it:
		// its existing empty-stack tolerance still runs before anything reaches Forge's collector.
		ForgeHooks.onCreativeModeTabBuildContents(tab, key,
				(p, o) -> EventHooks.onCreativeModeTabBuildContents(tab, key, generator, p, o), parameters, output);
	}
}
