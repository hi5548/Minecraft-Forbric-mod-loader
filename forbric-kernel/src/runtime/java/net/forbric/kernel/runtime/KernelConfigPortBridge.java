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

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.client.gui.screens.Screen;

import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.fml.event.config.ModConfigEvent;

/**
 * Registers a Fabric mod's config with the REAL {@code ConfigTracker}, from a caller that expects an older shape.
 *
 * <p>ForgeConfigAPIPort exists so Fabric mods can use NeoForge's config API, and it ships its own
 * {@code net.neoforged.fml.config.ConfigTracker} to provide it. Under Forbric that package is {@code ALWAYS_GAME},
 * so the carrier's class wins and the port's never loads — and the port's compiled call sites then ask for
 * {@code registerConfig(ModConfig$Type, IConfigSpec, String, String)}, keyed by mod ID, which real NeoForge
 * 26.2.0.88 does not have. It takes a {@code ModContainer}. The result is a {@code NoSuchMethodError} raised not
 * by the port but by whichever mod first registered a config through it, which is how a ShoulderSurfing crash in
 * {@code Minecraft.<init>} came to be a config-API bug.
 *
 * <p>So the ID gets a container. Not a published one: it is identity for the tracker and nothing else. The
 * tracker keys its per-mod lock by {@code container.getModId()} and posts config events through
 * {@code container.acceptEvent}, and that is the whole surface used here.
 *
 * <p><b>The bus is the mod's own, and used to be null.</b> {@code ModContainer.acceptEvent} is final and begins
 * by returning when its bus is null — so a null bus was a legal and completely silent way to throw away every
 * config event the tracker posted. That is the whole point of the port's API: a Fabric mod registers a callback
 * for "my config loaded" or "my config was edited on disk", and none of them ever fired. ShoulderSurfing-Fabric
 * reads its settings from exactly that callback.
 *
 * <p>A PRIVATE bus per mod, not the NeoForge baseline's: the baseline's would fan a Fabric mod's
 * {@code ModConfigEvent.Loading} out to NeoForge's own listeners under a foreign mod ID. Nothing else subscribes
 * to these buses; the kernel puts one listener on each and forwards to the port's own dispatcher.
 *
 * <p>One container per ID, cached: a fresh one per call would give each of a mod's configs a different event sink
 * and a different lock.
 *
 * <p><b>The other half.</b> The port's 21.1.x classes also have a Forge-flavoured registry whose two remaining
 * overloads RETURN a {@code net.minecraftforge.fml.config.ModConfig}, read back out of the NeoForge config through
 * a {@code modConfig} field the carrier does not have. {@link #forgeHandle} is that read, and it manufactures the
 * object over the mod's own MinecraftForge container. It is the one part of this class with a cost — the
 * manufacture files the config in MinecraftForge's own tracker as well — and the method says so rather than
 * hiding it.
 */
public final class KernelConfigPortBridge {

	private KernelConfigPortBridge() {
	}

	private static final Map<String, ModContainer> CONTAINERS = new ConcurrentHashMap<>();

	/** One MinecraftForge container per mod id — the identity a manufactured Forge config is filed under. */
	private static final Map<String, Object> FORGE_CONTAINERS = new ConcurrentHashMap<>();

	/** One MinecraftForge view per NeoForge config: a caller that keeps the handle must keep getting it back. */
	private static final Map<ModConfig, net.minecraftforge.fml.config.ModConfig> FORGE_HANDLES =
			new ConcurrentHashMap<>();

	/** The port's own dispatcher, which turns a NeoForge config event into the Fabric callbacks mods register. */
	private static final String EVENTS_HELPER = "fuzs.forgeconfigapiport.fabric.impl.core.ModConfigEventsHelper";

	/** One line per boot, on the first event that actually lands, rather than one per mod per config. */
	private static final AtomicBoolean ANNOUNCED = new AtomicBoolean();

	/** The same, and for the same reason, on the MinecraftForge half of the bridge. */
	private static final AtomicBoolean ANNOUNCED_FORGE = new AtomicBoolean();

	static ModContainer containerFor(String modId) {
		return CONTAINERS.computeIfAbsent(modId, id -> {
			// markerType and allowPerPhasePost mirror the mod buses the kernel builds elsewhere: ModConfigEvent is
			// an IModBusEvent, and a bus that does not carry that marker is not the bus these events belong on.
			// No start() call — BusBuilder only starts a bus shut down if asked with startShutdown().
			IEventBus bus = BusBuilder.builder()
					.markerType(IModBusEvent.class)
					.allowPerPhasePost()
					.build();
			ModContainer container = (ModContainer) KernelContainers.container(id, bus, null);
			forwardConfigEvents(bus);
			return container;
		});
	}

	/**
	 * Forwards this container's config events to the porting layer's dispatcher.
	 *
	 * <p>The port ships its own {@code ConfigTracker} to call these three methods; under Forbric the carrier's
	 * class wins that name and the port's never loads, so nothing called them. Reflection, because the porting
	 * layer is a MOD — it may not be installed, and the game side must not link against it.
	 *
	 * <p>Failure is per-event and logged once: a mod whose config callback throws must not take the config load
	 * down with it.
	 */
	private static void forwardConfigEvents(IEventBus bus) {
		Class<?> helper;
		try {
			helper = Class.forName(EVENTS_HELPER, false, KernelConfigPortBridge.class.getClassLoader());
		} catch (Throwable absent) {
			// The porting layer is not installed. Nothing to forward to, and nothing is wrong.
			return;
		}

		forward(bus, helper, ModConfigEvent.Loading.class, "onLoading");
		forward(bus, helper, ModConfigEvent.Reloading.class, "onReloading");
		forward(bus, helper, ModConfigEvent.Unloading.class, "onUnloading");
	}

	private static <E extends ModConfigEvent> void forward(IEventBus bus, Class<?> helper, Class<E> event,
			String method) {
		Method sink;
		try {
			sink = helper.getMethod(method, ModConfig.class);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/ConfigPort] %s.%s is not there: %s", EVENTS_HELPER, method, String.valueOf(t));
			return;
		}

		AtomicBoolean warned = new AtomicBoolean();
		bus.addListener(EventPriority.NORMAL, false, event, e -> {
			try {
				sink.invoke(null, e.getConfig());
				if (ANNOUNCED.compareAndSet(false, true)) {
					ForbricLog.info("[Forbric/ConfigPort] a Fabric mod's config events now reach the porting "
							+ "layer's dispatcher — they used to be posted at a container with no bus and dropped "
							+ "in silence, so every \"my config loaded\" and \"my config changed on disk\" callback "
							+ "a mod registered through that API never ran");
				}
			} catch (Throwable t) {
				if (warned.compareAndSet(false, true)) {
					ForbricLog.warn("[Forbric/ConfigPort] could not deliver " + method + " to the config porting "
							+ "layer; a Fabric mod's config callback will not run", Reflect.unwrap(t));
				}
			}
		});
	}

	/**
	 * The mod-ID-keyed 3-arg registration the porting layer compiled against.
	 *
	 * <p>Registered with the carrier, then opened as the port opens it — every type but SERVER, right here
	 * ({@link KernelConfigLoad#openAtRegistration}): a Fabric mod reads its config in the same {@code onInitialize}
	 * that registers it. The kernel's early pass skips what is already loaded, so nothing is opened twice (a second
	 * open warns and installs a second file watcher, and every later edit fires the reload twice).
	 */
	public static ModConfig registerConfig(ConfigTracker tracker, ModConfig.Type type, IConfigSpec spec,
			String modId) {
		ModConfig config = tracker.registerConfig(type, spec, containerFor(modId));
		KernelConfigLoad.openAtRegistration(config);
		return config;
	}

	/** The 4-arg form, with the mod's own file name. */
	public static ModConfig registerConfig(ConfigTracker tracker, ModConfig.Type type, IConfigSpec spec,
			String modId, String fileName) {
		ModConfig config = tracker.registerConfig(type, spec, containerFor(modId), fileName);
		KernelConfigLoad.openAtRegistration(config);
		return config;
	}

	/**
	 * The MinecraftForge {@code ModConfig} the port's Forge-flavoured {@code register} overloads hand back.
	 *
	 * <p>The port keeps the pair in one field: its own {@code net.neoforged.fml.config.ModConfig} carries a
	 * {@code public final net.minecraftforge.fml.config.ModConfig modConfig}, filled by the port's own
	 * {@code ConfigTracker} when the config is registered. Under Forbric the carrier takes both names, and neither
	 * of its classes has that field — so the two registrars that return the Forge half (the {@code String, Type,
	 * IConfigSpec} and {@code …, String} forms of {@code ForgeConfigRegistryImpl}) read a field that does not
	 * exist, and a rewritten call site only moves the failure from a {@code NoSuchMethodError} on the method to a
	 * {@code NoSuchFieldError} on the read behind it. This is what fills that read.
	 *
	 * <p>The object is manufactured, not faked: it is MinecraftForge's own class, over the mod's own MinecraftForge
	 * {@code ModContainer} ({@link KernelForgeContainers}, the identity-only container the traditional-Forge lane
	 * builds — a Fabric subject has no published Forge container, exactly as it has no published NeoForge one and
	 * {@link #containerFor} manufactures that one) and the MinecraftForge spec the port's adapter wraps.
	 *
	 * <p><b>The cost, recorded rather than hidden.</b> MinecraftForge's {@code ModConfig} constructor files the
	 * object in MinecraftForge's own {@code ConfigTracker}, so the kernel's Forge config pass opens the same file
	 * with Forge's reader: one config file, two live readers and two file watchers, and one edit on disk fires
	 * both. They read the same file, so they cannot disagree about a value, and this is the price of a config that
	 * is genuinely both — the port registers it with NeoForge's tracker (which is what carries the values the mod
	 * reads) and asks for the MinecraftForge handle at the same time. Allocating around the constructor to stay out
	 * of the Forge tracker was rejected: it leaves {@code getHandler()} null and {@code getConfigData()} forever
	 * so, which is a handle that lies instead of one that costs.
	 */
	public static net.minecraftforge.fml.config.ModConfig forgeHandle(ModConfig config) {
		return FORGE_HANDLES.computeIfAbsent(config, neo -> {
			net.minecraftforge.fml.config.ModConfig handle = new net.minecraftforge.fml.config.ModConfig(
					net.minecraftforge.fml.config.ModConfig.Type.valueOf(neo.getType().name()),
					forgeSpec(neo.getSpec()),
					(net.minecraftforge.fml.ModContainer) forgeContainer(neo.getModId()),
					neo.getFileName());
			if (ANNOUNCED_FORGE.compareAndSet(false, true)) {
				ForbricLog.info("[Forbric/ConfigPort] a Fabric mod took the MinecraftForge view of its config (%s) "
						+ "— it is a real MinecraftForge ModConfig over the mod's own Forge container and filed in "
						+ "MinecraftForge's tracker, so that file now has both ecosystems' readers and watchers on "
						+ "it; they read the same file and cannot disagree", neo.getModId());
			}
			return handle;
		});
	}

	/**
	 * The MinecraftForge spec the port wrapped in its adapter — the adapter is a record whose one component is
	 * exactly that spec.
	 *
	 * <p>Reflective because the porting layer is a MOD and the game side may not link against it. Loud on drift:
	 * a version of the port that stops exposing the spec cannot be half-answered, because the object this feeds
	 * is handed to the mod.
	 */
	private static net.minecraftforge.fml.config.IConfigSpec<?> forgeSpec(IConfigSpec spec) {
		try {
			return (net.minecraftforge.fml.config.IConfigSpec<?>) spec.getClass().getMethod("spec").invoke(spec);
		} catch (Throwable drift) {
			throw new IllegalStateException("the config porting layer wraps a MinecraftForge spec in "
					+ spec.getClass().getName() + " and this bridge reads it back through that class's no-argument "
					+ "spec() accessor, which is gone — the MinecraftForge view of the config cannot be built: "
					+ Reflect.unwrap(drift));
		}
	}

	/** The mod's MinecraftForge container, manufactured once per id — identity for the tracker, nothing else. */
	private static Object forgeContainer(String modId) {
		return FORGE_CONTAINERS.computeIfAbsent(modId, id -> {
			try {
				return KernelForgeContainers.create(id).container();
			} catch (Throwable t) {
				throw new IllegalStateException("could not build MinecraftForge's own ModContainer for '" + id
						+ "', which the MinecraftForge view of its config is filed under: " + Reflect.unwrap(t));
			}
		});
	}

	/**
	 * The mod-ID-keyed config screen the porting layer's consumers compiled against.
	 *
	 * <p>Same skew, one class over and one step further out: the port ships its own
	 * {@code ConfigurationScreen} whose constructor takes a mod ID where real NeoForge's takes a
	 * {@code ModContainer}, and mods written for the port name that constructor THEMSELVES. ShoulderSurfing hands
	 * {@code ConfigurationScreen::new} to the port's screen-factory registry, so the mismatch is not even a call —
	 * it is a method handle in an {@code invokedynamic}, resolved when the lambda's call site links, which is why
	 * it surfaced as a {@code NoSuchMethodError} from a line that constructs nothing.
	 *
	 * <p>Returns {@code Screen} rather than {@code ConfigurationScreen} so it matches the instantiated type of
	 * that lambda exactly; the factory's functional interface produces a {@code Screen}.
	 */
	public static Screen configurationScreen(String modId, Screen parent) {
		return new net.neoforged.neoforge.client.gui.ConfigurationScreen(containerFor(modId), parent);
	}
}
