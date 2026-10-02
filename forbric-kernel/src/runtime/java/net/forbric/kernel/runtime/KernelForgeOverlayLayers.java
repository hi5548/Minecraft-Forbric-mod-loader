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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

import net.forbric.api.EventBridges;
import net.forbric.api.GameEventBridge;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.neoforged.neoforge.client.gui.GuiLayerManager;

/**
 * Gives MinecraftForge's HUD overlay layers somewhere to draw.
 *
 * <p>The merged base carries ZERO references to {@code ForgeLayeredDraw} — NeoForge's {@code GuiLayerManager}
 * won that byte merge outright, so MinecraftForge's entire overlay system is inert: nothing builds the layer
 * tree, nothing posts {@code AddGuiOverlayLayersEvent}, and a mod that adds a HUD overlay registers it into an
 * object the game never renders. Waila's overlay is the one that showed it; every mod using that API had it.
 *
 * <p>On 26.2 the real shape came from MinecraftForge's own tree builder, {@code ForgeLayeredDraw.init}. The
 * vanilla LEAVES were then neutered instead of never existing: {@link #neuterVanillaLeaves} does that from the
 * head of {@code resolveLayers}, before the registration event is posted and before the bake; see
 * {@code ForgeOverlayNeuterInjector} for why that instant is the only one that works. The root is then re-seeded
 * with the names the builder placed in sub-stacks, so root-relative anchors keep resolving exactly as they did.
 *
 * <p><b>PORT(1.21.1): there is no {@code ForgeLayeredDraw.init} to call.</b> On 1.21.1 MinecraftForge builds that
 * tree INSIDE {@code Gui.<init>} (disassembled: {@code new ForgeLayeredDraw(VANILLA_ROOT, COMBINE_LIST)} plus the
 * PRE_SLEEP/POST_SLEEP sub-stacks, then {@code resolveLayers()}), and the merged {@code Gui} is NeoForge's — it
 * names {@code ForgeLayeredDraw} nowhere, so that construction never happens here. The kernel therefore builds
 * the tree it can build: the vanilla root with MinecraftForge's OWN vanilla layer NAMES seeded as no-op leaves
 * (the pre-26.2 shape). Root-relative registrations resolve and nothing vanilla draws twice, but there are no
 * vanilla SUB-stacks, so the four-argument stack-targeted overloads
 * ({@code addBelow(HOTBAR_AND_DECOS, id, SPECTATOR_HOTBAR, layer)}, Xaero's) still cannot resolve and their layer
 * is dropped with MinecraftForge's own "Target stack ... was not present anywhere" warning. That is the accepted
 * gap on 1.21.1: narrowed from "every overlay draws nothing" to "stack-targeted overlays draw nothing"; closing it
 * needs a boot-side seam that rebuilds Forge's real tree, not a change here.
 *
 * <p><b>The accepted gap, stated rather than hidden:</b> the whole MinecraftForge stack is added as ONE NeoForge
 * layer, appended last, so it draws ABOVE the entire vanilla HUD. A mod that asked to sit BELOW a vanilla element
 * still draws above it. Overlays are the layers mods add on top, so this is right for almost all of them, and
 * "drawn slightly too high in the stack" is a different kind of problem from "not drawn at all".
 *
 * <p>{@code -Dforbric.forgeOverlayLayers=off} restores the old behaviour, which is that nothing draws.
 */
public final class KernelForgeOverlayLayers {
	static final String PROPERTY = "forbric.forgeOverlayLayers";

	/** The name the whole MinecraftForge stack is registered under in NeoForge's manager. */
	static final ResourceLocation LAYER =
			ResourceLocation.fromNamespaceAndPath("forbric", "minecraftforge_overlays");

	private static final LayeredDraw.Layer NOTHING = (graphics, delta) -> { };

	private KernelForgeOverlayLayers() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/**
	 * Called at the end of {@code GuiLayerManager.initModdedLayers}, where NeoForge has just posted its own
	 * registration event — the one moment where every mod is loaded and the HUD has not yet drawn a frame.
	 *
	 * @param layerManager the live {@code GuiLayerManager}, pushed as {@code this} by the injector
	 */
	public static void install(Object layerManager) {
		if (!enabled()) {
			ForbricLog.warn("[Forbric/HudBridge] -D%s=off — a MinecraftForge mod's HUD overlay layers will not draw",
					PROPERTY);
			// Falls THROUGH to verify rather than returning: returning here produced neither an installed nor a
			// MISSING line, so the switch could not be used as a negative control for its own mechanism.
			EventBridges.verify(GameEventBridge.Pass.CLIENT_HUD);
			return;
		}
		synchronized (KernelForgeOverlayLayers.class) {
			if (treeRegistered) {
				EventBridges.verify(GameEventBridge.Pass.CLIENT_HUD);
				return;
			}
			treeRegistered = true;
		}

		String refusal = null;
		ForgeLayeredDraw tree = null;
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc == null || mc.gui == null) {
				refusal = "the client has no HUD yet";
			} else {
				// PORT(1.21.1): 26.2 called MinecraftForge's own builder, {@code ForgeLayeredDraw.init(hud, mc)}.
				// 1.21.1 has no such method — Forge builds the tree inside {@code Gui.<init>} (VANILLA_ROOT +
				// COMBINE_LIST plus the pre/post-sleep sub-stacks, then resolveLayers), and the merged Gui is
				// NeoForge's, which never names ForgeLayeredDraw. So the tree is built here from the pieces that
				// ARE on 1.21.1: the vanilla root with this class's own vanilla layer NAMES seeded as no-op
				// leaves. resolveLayers is head-hooked by ForgeOverlayNeuterInjector, so neuterVanillaLeaves runs
				// BEFORE the registration event and BEFORE the bake, exactly as it did inside init on 26.2.
				tree = new ForgeLayeredDraw(ForgeLayeredDraw.VANILLA_ROOT, ForgeLayeredDraw.COMBINE_LIST);
				for (ResourceLocation name : vanillaLayerNames()) tree.add(name, NOTHING);
				tree.resolveLayers();
				refusal = NEUTERED.refusal();
			}
		} catch (Throwable t) {
			refusal = "building MinecraftForge's overlay tree threw (" + Reflect.unwrap(t) + ")";
		}

		// The EVENT half is reported separately from the DRAWING half, because they can differ. Once the build has
		// run through resolveLayers the event really was posted and the listeners really did run; saying
		// otherwise makes DeadEventAudit mark xaerominimap/xaeroworldmap DEGRADED with "which this merged game
		// never posts", which would be false.
		if (NEUTERED.ran()) EventBridges.installed(GameEventBridge.GUI_OVERLAY_LAYERS);

		if (refusal != null) {
			// Deliberately NOT the success sentence, and deliberately no GuiLayerManager.add: registering a tree
			// whose vanilla leaves are live draws the entire vanilla HUD a second time, which is worse than the
			// bug. This WARN is then the only thing carrying the bad news, so it is mandatory.
			ForbricLog.warn("[Forbric/HudBridge] refusing to register MinecraftForge's overlay stack (%s) — every "
					+ "MinecraftForge HUD overlay will draw nothing%s", refusal, NEUTERED.strandedSuffix());
			EventBridges.verify(GameEventBridge.Pass.CLIENT_HUD);
			return;
		}

		try {
			// PORT(1.21.1): 26.2 handed the manager a static method reference into ForgeLayeredDraw's singleton.
			// On 1.21.1 the tree built above is itself a LayeredDraw, and LayeredDraw.render(GuiGraphics,
			// DeltaTracker) is exactly the Layer NeoForge's manager takes. The manager keeps this reference, so
			// the tree stays alive as long as the HUD does.
			((GuiLayerManager) layerManager).add(LAYER, tree::render);
			ForbricLog.info("[Forbric/HudBridge] MinecraftForge's overlay stack is on NeoForge's layer manager — %s",
					NEUTERED.describe());
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/HudBridge] could not give MinecraftForge's overlay layers a place to draw — "
					+ "a mod's HUD overlay will render nothing", Reflect.unwrap(t));
		}
		EventBridges.verify(GameEventBridge.Pass.CLIENT_HUD);
	}

	/**
	 * Replaces every vanilla LEAF in MinecraftForge's tree with a no-op, at the head of {@code resolveLayers}.
	 *
	 * <p>Called from bytecode ({@code ForgeOverlayNeuterInjector}), which is why the parameter is
	 * {@code Object}. That call site is before the registration event is posted (bci 12) and before
	 * {@code resolveNested} bakes (bci 17), so there is NO snapshot to take and no identity to track: nothing
	 * else has touched these maps yet, and the bake reads {@code namedLayers.get(name)} after us.
	 *
	 * <p>Map VALUES are replaced and keys are never removed, and {@code order} is never touched.
	 * {@code resolveNested} does {@code bakedLayers.add((LayeredDraw.Layer) namedLayers.get(name))} UNCHECKED, so an
	 * {@code order} entry whose key no longer exists bakes a null and NPEs on the first HUD frame.
	 *
	 * <p>The root is then re-seeded with every vanilla name {@code init} left in a sub-stack, because
	 * {@code addBelow(ResourceLocation, ResourceLocation, LayeredDraw.Layer)} resolves its anchor against the ROOT
	 * ({@code locateStack(VANILLA_ROOT)}) and drops the layer when the name is absent. Without this, giving the
	 * tree its real shape would silently break every root-relative registration that works today. Seeding a name
	 * that is also a sub-stack is harmless: {@code locateStack} does not read {@code namedLayers}, so a stack
	 * lookup still finds the real sub-stack.
	 */
	public static void neuterVanillaLeaves(Object root) {
		if (!enabled()) return;
		try {
			if (!(root instanceof ForgeLayeredDraw tree)) return;
			// A mod may build its own ForgeLayeredDraw; only the vanilla root is ours to rewrite.
			if (!ForgeLayeredDraw.VANILLA_ROOT.equals(tree.getName())) return;
			NEUTERED.saw(tree);

			List<ResourceLocation> vanilla = vanillaLayerNames();
			Set<ResourceLocation> vanillaSet = new LinkedHashSet<>(vanilla);
			Set<ResourceLocation> seen = new LinkedHashSet<>();
			List<ResourceLocation> unknown = new ArrayList<>();
			int replaced = 0;
			int stacks = 0;

			java.util.Deque<ForgeLayeredDraw> queue = new java.util.ArrayDeque<>();
			queue.add(tree);
			while (!queue.isEmpty()) {
				ForgeLayeredDraw node = queue.poll();
				stacks++;
				Map<ResourceLocation, Object> named = mapField(node, "namedLayers");
				if (named != null) {
					for (Map.Entry<ResourceLocation, Object> entry : named.entrySet()) {
						seen.add(entry.getKey());
						if (vanillaSet.contains(entry.getKey())) {
							entry.setValue(NOTHING);
							replaced++;
						} else {
							unknown.add(entry.getKey());
						}
					}
				}
				Map<ResourceLocation, Object> subs = mapField(node, "subLayerStacks");
				if (subs != null) {
					for (Map.Entry<ResourceLocation, Object> entry : subs.entrySet()) {
						seen.add(entry.getKey());
						if (entry.getValue() instanceof Map.Entry<?, ?> pair
								&& pair.getKey() instanceof ForgeLayeredDraw child) {
							queue.add(child);
						}
					}
				}
			}

			// A leaf that is NOT one of MinecraftForge's vanilla names is one this pass did not silence, and it
			// would draw on top of the vanilla HUD a second time. Refuse rather than ship a doubled crosshair.
			if (!unknown.isEmpty()) {
				NEUTERED.refuse("MinecraftForge's tree holds " + unknown.size() + " leaf/leaves this pass does not "
						+ "recognise and therefore did not silence " + unknown);
				return;
			}

			// Against the ROOT's own two maps, NOT the whole-tree walk. The names that need re-seeding are
			// exactly the ones 26.2's init() placed in a SUB-stack: addBelow(ResourceLocation, ResourceLocation, LayeredDraw.Layer)
			// resolves its anchor against the root, so a nested name is invisible to it and the layer is
			// dropped with "Expected layer ... was not found in stack". Skipping everything `seen` holds
			// re-seeded 1 name instead of 22 and left that regression latent — measured.
			Set<ResourceLocation> atRoot = new LinkedHashSet<>();
			Map<ResourceLocation, Object> rootNamed = mapField(tree, "namedLayers");
			if (rootNamed != null) atRoot.addAll(rootNamed.keySet());
			Map<ResourceLocation, Object> rootSubs = mapField(tree, "subLayerStacks");
			if (rootSubs != null) atRoot.addAll(rootSubs.keySet());

			List<ResourceLocation> reseeded = new ArrayList<>();
			for (ResourceLocation id : vanilla) {
				if (atRoot.contains(id)) continue;
				tree.add(id, NOTHING);
				reseeded.add(id);
			}

			// Names MinecraftForge's own builder never placed anywhere (today: minecraft:debug).
			List<ResourceLocation> notPlaced = new ArrayList<>(vanillaSet);
			notPlaced.removeAll(seen);

			NEUTERED.succeeded(tree, replaced, stacks, reseeded, notPlaced);
		} catch (Throwable t) {
			NEUTERED.refuse("neutering MinecraftForge's vanilla leaves threw (" + Reflect.unwrap(t) + ")");
		}
	}

	/** One tree's private {@code Map} field, or null when it cannot be read. */
	@SuppressWarnings("unchecked")
	private static Map<ResourceLocation, Object> mapField(ForgeLayeredDraw node, String name) throws Exception {
		Field field = ForgeLayeredDraw.class.getDeclaredField(name);
		field.setAccessible(true);
		Object value = field.get(node);
		return value instanceof Map<?, ?> ? (Map<ResourceLocation, Object>) value : null;
	}

	/**
	 * What the neuter did, read by {@link #install} as a POSITIVE gate.
	 *
	 * <p>Positive on purpose. A flag set only on failure is fail-OPEN: a neuter that never runs at all — the
	 * anchor drifted, the phase changed, the class never reached the pipeline — would leave it clear, and the
	 * kernel would register the REAL vanilla tree and print full health. That is the one way this repair is
	 * worse than the bug it replaces.
	 */
	private static final class Neutered {
		/** The tree we were handed, kept on EVERY path so the refusal sentence can still name what it strands. */
		private volatile ForgeLayeredDraw tree;
		private volatile ForgeLayeredDraw root;
		private volatile String refusal;
		private volatile int replaced;
		private volatile int stacks;
		private volatile List<ResourceLocation> reseeded = List.of();
		private volatile List<ResourceLocation> notPlaced = List.of();

		void saw(ForgeLayeredDraw tree) {
			this.tree = tree;
		}

		void succeeded(ForgeLayeredDraw tree, int replaced, int stacks, List<ResourceLocation> reseeded,
				List<ResourceLocation> notPlaced) {
			this.tree = tree;
			this.root = tree;
			this.replaced = replaced;
			this.stacks = stacks;
			this.reseeded = List.copyOf(reseeded);
			this.notPlaced = List.copyOf(notPlaced);
			this.refusal = null;
		}

		void refuse(String why) {
			this.refusal = why;
			this.root = null;
		}

		/** Whether {@code resolveLayers} was entered at all — which is what says the event was posted. */
		boolean ran() {
			return root != null || refusal != null;
		}

		String refusal() {
			if (refusal != null) return refusal;
			if (root == null) return "the kernel's neuter never ran, so the tree still draws the whole vanilla HUD";
			if (replaced < 1) return "the neuter silenced no vanilla leaf, which cannot be right for a built tree";
			return null;
		}

		/**
		 * The mod layers that will not draw, named.
		 *
		 * <p>Only answerable on the refusal path, and only there: this runs after {@code init} completed, so the
		 * registration event has been posted and the mods' own layers are in the tree. At neuter time — which is
		 * before the post — they do not exist yet. When the tree cannot be walked the sentence simply omits
		 * them rather than inventing a number.
		 */
		String strandedSuffix() {
			try {
				if (tree == null) return "";
				List<ResourceLocation> stranded = new ArrayList<>();
				java.util.Deque<ForgeLayeredDraw> queue = new java.util.ArrayDeque<>();
				queue.add(tree);
				Set<ResourceLocation> vanilla = new LinkedHashSet<>(vanillaLayerNames());
				while (!queue.isEmpty()) {
					ForgeLayeredDraw node = queue.poll();
					Map<ResourceLocation, Object> named = mapField(node, "namedLayers");
					if (named != null) {
						for (ResourceLocation id : named.keySet()) if (!vanilla.contains(id)) stranded.add(id);
					}
					Map<ResourceLocation, Object> subs = mapField(node, "subLayerStacks");
					if (subs != null) {
						for (Object value : subs.values()) {
							if (value instanceof Map.Entry<?, ?> pair
									&& pair.getKey() instanceof ForgeLayeredDraw child) {
								queue.add(child);
							}
						}
					}
				}
				return stranded.isEmpty() ? "" : " — " + stranded.size() + " mod layer(s) stranded " + stranded;
			} catch (Throwable unreadable) {
				return "";
			}
		}

		/**
		 * What landed, BY NAME.
		 *
		 * <p>A count cannot carry this. The old counter did {@code namedLayers.size() - 26} on the root, and
		 * under the real tree shape the root holds two leaves while the mods' layers land in SUB-stacks — so a
		 * fully working repair printed "0 layer(s) added by mods", which is indistinguishable from the failure
		 * it replaced. The ids and the stack they landed in are the only honest report.
		 *
		 * <p>Counts that do appear come from the walk that did the work, never hardcoded: two independent hand
		 * counts of what {@code init} places disagreed (23 entries over 22 distinct names, with
		 * {@code minecraft:subtitle} placed twice).
		 */
		String describe() {
			String landed = modLayers();
			return "silenced " + replaced + " vanilla leaf/leaves across " + stacks + " stack(s), re-seeded "
					+ reseeded.size() + " name(s) at the root so root-relative anchors still resolve"
					+ (notPlaced.isEmpty() ? "" : ", not placed by MinecraftForge's own builder " + notPlaced)
					+ (landed.isEmpty() ? " — and NO mod layer is in the tree, so nothing new will draw" : landed);
		}

		/** Every non-vanilla leaf, with the stack it landed in. Empty when the tree cannot be walked. */
		private String modLayers() {
			try {
				if (tree == null) return "";
				Set<ResourceLocation> vanilla = new LinkedHashSet<>(vanillaLayerNames());
				List<String> landed = new ArrayList<>();
				java.util.Deque<ForgeLayeredDraw> queue = new java.util.ArrayDeque<>();
				queue.add(tree);
				while (!queue.isEmpty()) {
					ForgeLayeredDraw node = queue.poll();
					Map<ResourceLocation, Object> named = mapField(node, "namedLayers");
					if (named != null) {
						for (ResourceLocation id : named.keySet()) {
							if (!vanilla.contains(id)) landed.add(id + " in " + node.getName());
						}
					}
					Map<ResourceLocation, Object> subs = mapField(node, "subLayerStacks");
					if (subs != null) {
						for (Object value : subs.values()) {
							if (value instanceof Map.Entry<?, ?> pair
									&& pair.getKey() instanceof ForgeLayeredDraw child) {
								queue.add(child);
							}
						}
					}
				}
				return landed.isEmpty() ? "" : ", and " + landed.size() + " mod layer(s) landed " + landed;
			} catch (Throwable unreadable) {
				return "";
			}
		}
	}

	private static final Neutered NEUTERED = new Neutered();
	private static boolean treeRegistered;

	/**
	 * MinecraftForge's own names for the vanilla HUD positions, read off its class rather than listed here.
	 *
	 * <p>Listing them would be a second copy to keep in step, and the failure of a stale copy is silent: a mod
	 * positioning against the name this list forgot gets MinecraftForge's "layer not present" warning and lands
	 * wherever it was added.
	 */
	static List<ResourceLocation> vanillaLayerNames() {
		List<ResourceLocation> names = new ArrayList<>();
		for (Field field : ForgeLayeredDraw.class.getFields()) {
			if (field.getType() != ResourceLocation.class) continue;
			try {
				// The root's own name is the tree, not a position inside it.
				if (field.get(null) instanceof ResourceLocation id && !id.equals(ForgeLayeredDraw.VANILLA_ROOT)) {
					names.add(id);
				}
			} catch (IllegalAccessException unreadable) {
				ForbricLog.debug("[Forbric/HudBridge] could not read %s: %s", field.getName(), unreadable);
			}
		}
		return names;
	}

}
