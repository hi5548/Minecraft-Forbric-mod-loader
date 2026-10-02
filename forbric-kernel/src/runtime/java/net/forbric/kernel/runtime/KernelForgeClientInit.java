package net.forbric.kernel.runtime;

import java.util.ArrayList;
import java.util.List;

import net.forbric.kernel.util.ForbricLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.model.geometry.GeometryLoaderManager;
import net.neoforged.neoforge.client.ClientHooks;

/** Restores Forge's own client hooks at the same lifecycle sites that already serve NeoForge. */
public final class KernelForgeClientInit {
	private KernelForgeClientInit() {}

	private static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty("forbric.forgeClientInit", "on"));
	}

	public static void initClientHooks(Minecraft minecraft, ReloadableResourceManager resources) {
		if (!enabled()) {
			ClientHooks.initClientHooks(minecraft, resources);
			return;
		}
		// The scratch owns no packs. Its listeners must enter NeoForge's graph while that graph is built:
		// adding them directly to resources would be undone by NeoForge's updateListenersFrom afterwards.
		// PORT(1.21.1): ReloadableResourceManager keeps its listeners in a private list with no accessor, so there is
		// no way to read them back; registration is observed by overriding the public non-final
		// registerReloadListener on a scratch subclass. The scratch owns no packs, so its close() only closes an
		// empty MultiPackResourceManager.
		List<PreparableReloadListener> listeners;
		try (RecordingResourceManager scratch = new RecordingResourceManager()) {
			ForgeHooksClient.initClientHooks(minecraft, scratch);
			listeners = List.copyOf(scratch.recorded());
		}
		minecraft.options.load(true);
		boolean drained = ForgeClientReloadCapture.withCaptured(listeners,
				() -> ClientHooks.initClientHooks(minecraft, resources));
		if (!drained && !listeners.isEmpty()) {
			if ("off".equalsIgnoreCase(System.getProperty("forbric.unifiedEvents", "on"))) {
				ForbricLog.warn("[Forbric/ForgeClient] client reload bridge is disabled; %d captured listener(s) were not installed", listeners.size());
			} else {
				throw new IllegalStateException("Forge client reload capture was not consumed by NeoForge's registration event");
			}
		}
		ForbricLog.info("[Forbric/ForgeClient] applied MinecraftForge client init: %d captured reload listener(s), %d key mapping(s)",
				listeners.size(), minecraft.options.keyMappings.length);
	}

	public static void onRegisterParticleProviders(ParticleEngine particles) {
		if (enabled()) ForgeHooksClient.onRegisterParticleProviders(particles);
		ClientHooks.onRegisterParticleProviders(particles);
	}

	/** Forge's original ModelManager calls this on every reload, before starting any model-loading future. */
	public static void initGeometryLoaders() {
		if (enabled()) GeometryLoaderManager.init();
	}

	/**
	 * A scratch manager that records what is registered to it.
	 *
	 * <p>1.21.1's {@code ReloadableResourceManager} offers no way to read its listeners back: they live in a
	 * private list with no accessor. The only observation point is the public non-final
	 * {@code registerReloadListener}, so this subclass overrides it to keep what Forge registers.
	 */
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
