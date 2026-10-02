package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.tools.ToolProvider;

/** Public API spies only: all three kernel classes come from the independently compiled runtime directory. */
final class KernelForgeClientInitFixture implements AutoCloseable {

	private final URLClassLoader loader;
	private final Class<?> probe;
	private final Object minecraft;
	private final Object resources;

	KernelForgeClientInitFixture(Path stubs, Path runtime) throws Exception {
		loader = new URLClassLoader(new URL[] {stubs.toUri().toURL(), runtime.toUri().toURL()},
				ClassLoader.getPlatformClassLoader());
		probe = type("fixture.ClientProbe");
		minecraft = type("net.minecraft.client.Minecraft").getConstructor().newInstance();
		Class<?> packType = type("net.minecraft.server.packs.PackType");
		resources = type("net.minecraft.server.packs.resources.ReloadableResourceManager")
				.getConstructor(packType).newInstance(packType.getField("CLIENT_RESOURCES").get(null));
	}

	void installBridge() throws Exception {
		call(type("net.forbric.kernel.runtime.KernelGameClientReload").getMethod("install", Object.class), null, value("bus"));
	}

	void init() throws Exception {
		call(type("net.forbric.kernel.runtime.KernelForgeClientInit").getMethod("initClientHooks",
				minecraft.getClass(), resources.getClass()), null, minecraft, resources);
	}

	void geometry() throws Exception {
		call(type("net.forbric.kernel.runtime.KernelForgeClientInit").getMethod("initGeometryLoaders"), null);
	}

	void particles() throws Exception {
		Class<?> particles = type("net.minecraft.client.particle.ParticleEngine");
		call(type("net.forbric.kernel.runtime.KernelForgeClientInit").getMethod("onRegisterParticleProviders", particles),
				null, particles.getConstructor().newInstance());
	}

	Object drain() throws Exception {
		return call(type("net.forbric.kernel.runtime.ForgeClientReloadCapture").getMethod("drain"), null);
	}

	void reload() throws Exception {
		call(resources.getClass().getMethod("reloadAll"), resources);
	}

	int applies(Object listener) throws Exception {
		return listener.getClass().getField("applies").getInt(listener);
	}

	int count(String name) throws Exception {
		return probe.getField(name).getInt(null);
	}

	Object value(String name) throws Exception {
		return probe.getField(name).get(null);
	}

	void value(String name, Object value) throws Exception {
		probe.getField(name).set(null, value);
	}

	@SuppressWarnings("unchecked")
	List<Object> listeners() throws Exception {
		return (List<Object>) value("listeners");
	}

	@SuppressWarnings("unchecked")
	List<String> trace() throws Exception {
		return (List<String>) value("trace");
	}

	@SuppressWarnings("unchecked")
	List<String> warnings() throws Exception {
		return (List<String>) value("warnings");
	}

	List<?> realListeners() throws Exception {
		return (List<?>) call(resources.getClass().getMethod("getListeners"), resources);
	}

	boolean scratchClosed() throws Exception {
		Object scratch = value("scratch");
		return scratch != null && scratch.getClass().getField("closed").getBoolean(scratch);
	}

	boolean realClosed() throws Exception {
		return resources.getClass().getField("closed").getBoolean(resources);
	}

	@Override
	public void close() throws Exception {
		loader.close();
	}

	private Class<?> type(String name) throws ClassNotFoundException {
		return Class.forName(name, true, loader);
	}

	private static Object call(Method method, Object receiver, Object... arguments) throws Exception {
		try {
			return method.invoke(receiver, arguments);
		} catch (InvocationTargetException wrapped) {
			Throwable failure = wrapped.getCause();
			if (failure instanceof Exception exception) throw exception;
			if (failure instanceof Error error) throw error;
			throw new AssertionError(failure);
		}
	}

	static Path compileSpies(Path directory) throws Exception {
		Path classes = directory.resolve("classes");
		Files.createDirectories(classes);
		List<String> arguments = new ArrayList<>(List.of("-proc:none", "-classpath", classes.toString(), "-d", classes.toString()));
		for (Map.Entry<String, String> source : sources().entrySet()) {
			Path file = directory.resolve("src").resolve(source.getKey().replace('.', '/') + ".java");
			Files.createDirectories(file.getParent());
			Files.writeString(file, source.getValue());
			arguments.add(file.toString());
		}
		var compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull(compiler, "a JDK is required to compile the isolated public API spies");
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		assertEquals(0, compiler.run(null, output, output, arguments.toArray(String[]::new)),
				() -> output.toString(StandardCharsets.UTF_8));
		return classes;
	}

	private static Map<String, String> sources() {
		return Map.ofEntries(
			Map.entry("fixture.ClientProbe", """
				package fixture;
				import java.util.*;
				import java.util.concurrent.*;
				import java.util.function.Consumer;
				import net.minecraft.server.packs.resources.*;
				import net.neoforged.bus.api.*;
				import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
				public final class ClientProbe {
				  public static final List<String> trace = new ArrayList<>(), warnings = new ArrayList<>();
				  public static final List<PreparableReloadListener> listeners = new ArrayList<>(List.of(new Listener()));
				  public static final SpyBus bus = new SpyBus();
				  public static int forgeCalls, forgePosts, neoCalls, optionLoads, geometryCalls;
				  public static RuntimeException forgeFailure, neoFailureBefore, neoFailureAfter, graphFailure;
				  public static ReloadableResourceManager scratch;
				  public static String priority;
				  public static boolean receiveCanceled;
				  public static Class<?> eventType;
				  public static final class Listener implements PreparableReloadListener {
				    public int applies;
				    public CompletableFuture<Void> reload(SharedState state, Executor prepare, PreparationBarrier barrier, Executor apply) {
				      return CompletableFuture.runAsync(() -> { applies++; trace.add("listener:apply"); }, apply);
				    }
				  }
				  public static final class SpyBus implements IEventBus {
				    private Consumer<RegisterClientReloadListenersEvent> listener;
				    @SuppressWarnings("unchecked")
				    public <T extends Event> void addListener(EventPriority p, boolean canceled, Class<T> eventClass, Consumer<T> callback) {
				      priority = p.name(); receiveCanceled = canceled; eventType = eventClass;
				      listener = event -> callback.accept((T) event);
				    }
				    public void dispatch(RegisterClientReloadListenersEvent event) { if (listener != null) listener.accept(event); }
				  }
				}
				"""),
			Map.entry("net.minecraft.client.KeyMapping", "package net.minecraft.client; public class KeyMapping {}"),
			Map.entry("net.minecraft.client.Options", """
				package net.minecraft.client;
				public class Options {
				  public KeyMapping[] keyMappings = new KeyMapping[] {new KeyMapping()};
				  public void load(boolean keysOnly) { fixture.ClientProbe.optionLoads++; fixture.ClientProbe.trace.add("options:" + keysOnly); }
				}
				"""),
			Map.entry("net.minecraft.client.Minecraft", """
				package net.minecraft.client;
				public class Minecraft { public final Options options = new Options(); }
				"""),
			Map.entry("net.minecraft.client.particle.ParticleEngine", "package net.minecraft.client.particle; public class ParticleEngine {}"),
			Map.entry("net.minecraft.server.packs.PackType", "package net.minecraft.server.packs; public enum PackType { CLIENT_RESOURCES }"),
			Map.entry("net.minecraft.server.packs.resources.PreparableReloadListener", """
				package net.minecraft.server.packs.resources;
				import java.util.concurrent.*;
				public interface PreparableReloadListener {
				  CompletableFuture<Void> reload(SharedState state, Executor preparation, PreparationBarrier barrier, Executor apply);
				  final class SharedState {}
				  interface PreparationBarrier {}
				}
				"""),
			Map.entry("net.minecraft.server.packs.resources.ReloadableResourceManager", """
				package net.minecraft.server.packs.resources;
				import java.util.*;
				import net.minecraft.server.packs.PackType;
				public class ReloadableResourceManager implements AutoCloseable {
				  private List<PreparableReloadListener> listeners = new ArrayList<>();
				  public boolean closed;
				  public ReloadableResourceManager(PackType type) {}
				  public List<PreparableReloadListener> getListeners() { return listeners; }
				  public void registerReloadListener(PreparableReloadListener listener) { listeners.add(listener); }
				  public void reloadAll() { for (var listener : listeners) listener.reload(null, Runnable::run, null, Runnable::run).join(); }
				  public void close() { closed = true; fixture.ClientProbe.trace.add("scratch:close"); }
				}
				"""),
			Map.entry("net.minecraftforge.eventbus.api.Event", "package net.minecraftforge.eventbus.api; public class Event {}"),
			Map.entry("net.minecraftforge.eventbus.api.IEventBus", """
				package net.minecraftforge.eventbus.api;
				public interface IEventBus { boolean post(Event event); }
				"""),
			Map.entry("net.minecraftforge.common.MinecraftForge", """
				package net.minecraftforge.common;
				import fixture.ClientProbe;
				import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
				public final class MinecraftForge {
				  private static boolean dispatched;
				  // Spy stand-in for Forge's game bus: its one mod handler publishes the reload listeners the event carries.
				  public static final net.minecraftforge.eventbus.api.IEventBus EVENT_BUS = event -> {
				    ClientProbe.forgePosts++; ClientProbe.trace.add("forge:post");
				    RegisterClientReloadListenersEvent registration = (RegisterClientReloadListenersEvent) event;
				    ClientProbe.scratch = registration.resources;
				    if (!dispatched) {
				      dispatched = true;
				      for (var listener : ClientProbe.listeners) registration.registerReloadListener(listener);
				    }
				    return false;
				  };
				  private MinecraftForge() {}
				}
				"""),
			Map.entry("net.minecraftforge.client.event.RegisterClientReloadListenersEvent", """
				package net.minecraftforge.client.event;
				import net.minecraft.server.packs.resources.*;
				public class RegisterClientReloadListenersEvent extends net.minecraftforge.eventbus.api.Event {
				  public final ReloadableResourceManager resources;
				  public RegisterClientReloadListenersEvent(ReloadableResourceManager resources) { this.resources = resources; }
				  public void registerReloadListener(PreparableReloadListener listener) { resources.registerReloadListener(listener); }
				}
				"""),
			Map.entry("net.minecraftforge.client.ForgeHooksClient", """
				package net.minecraftforge.client;
				import fixture.ClientProbe;
				import net.minecraft.client.Minecraft;
				import net.minecraft.client.particle.ParticleEngine;
				import net.minecraft.server.packs.resources.ReloadableResourceManager;
				import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
				import net.minecraftforge.common.MinecraftForge;
				public final class ForgeHooksClient {
				  private static boolean initialized;
				  public static void initClientHooks(Minecraft mc, ReloadableResourceManager manager) {
				    ClientProbe.forgeCalls++; ClientProbe.trace.add("forge:init"); ClientProbe.scratch = manager;
				    if (initialized) throw new IllegalStateException("Client hooks initialized more than once");
				    initialized = true;
				    if (ClientProbe.forgeFailure != null) throw ClientProbe.forgeFailure;
				    MinecraftForge.EVENT_BUS.post(new RegisterClientReloadListenersEvent(manager));
				  }
				  public static void onRegisterParticleProviders(ParticleEngine particles) { ClientProbe.trace.add("forge:particles"); }
				}
				"""),
			Map.entry("net.minecraftforge.client.model.geometry.GeometryLoaderManager", """
				package net.minecraftforge.client.model.geometry;
				public final class GeometryLoaderManager {
				  public static void init() { fixture.ClientProbe.geometryCalls++; fixture.ClientProbe.trace.add("geometry"); }
				}
				"""),
			Map.entry("net.neoforged.bus.api.Event", "package net.neoforged.bus.api; public class Event {}"),
			Map.entry("net.neoforged.bus.api.EventPriority", "package net.neoforged.bus.api; public enum EventPriority { HIGHEST, HIGH, NORMAL, LOW, LOWEST }"),
			Map.entry("net.neoforged.bus.api.IEventBus", """
				package net.neoforged.bus.api;
				import java.util.function.Consumer;
				public interface IEventBus {
				  <T extends Event> void addListener(EventPriority priority, boolean receiveCanceled, Class<T> eventType, Consumer<T> consumer);
				}
				"""),
			Map.entry("net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent", """
				package net.neoforged.neoforge.client.event;
				import fixture.ClientProbe;
				import net.minecraft.server.packs.resources.*;
				public class RegisterClientReloadListenersEvent extends net.neoforged.bus.api.Event {
				  private final ReloadableResourceManager resourceManager;
				  public RegisterClientReloadListenersEvent(ReloadableResourceManager resourceManager) { this.resourceManager = resourceManager; }
				  public void registerReloadListener(PreparableReloadListener listener) {
				    if (ClientProbe.graphFailure != null) throw ClientProbe.graphFailure;
				    resourceManager.registerReloadListener(listener);
				    ClientProbe.trace.add("graph:add");
				  }
				}
				"""),
			Map.entry("net.neoforged.neoforge.client.ClientHooks", """
				package net.neoforged.neoforge.client;
				import fixture.ClientProbe;
				import net.minecraft.client.Minecraft;
				import net.minecraft.client.particle.ParticleEngine;
				import net.minecraft.server.packs.resources.ReloadableResourceManager;
				import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
				public final class ClientHooks {
				  public static void initClientHooks(Minecraft mc, ReloadableResourceManager manager) {
				    ClientProbe.neoCalls++; ClientProbe.trace.add("neo:init");
				    if (ClientProbe.neoFailureBefore != null) throw ClientProbe.neoFailureBefore;
				    ClientProbe.bus.dispatch(new RegisterClientReloadListenersEvent(manager));
				    if (ClientProbe.neoFailureAfter != null) throw ClientProbe.neoFailureAfter;
				  }
				  public static void onRegisterParticleProviders(ParticleEngine particles) { ClientProbe.trace.add("neo:particles"); }
				}
				"""),
			Map.entry("net.forbric.kernel.util.ForbricLog", """
				package net.forbric.kernel.util;
				public final class ForbricLog {
				  public static void info(String message, Object... arguments) {}
				  public static void warn(String message, Object... arguments) { fixture.ClientProbe.warnings.add(String.format(message, arguments)); }
				  public static void warn(String message, Throwable failure) { fixture.ClientProbe.warnings.add(message); }
				}
				"""),
			Map.entry("net.forbric.kernel.util.Reflect", """
				package net.forbric.kernel.util;
				public final class Reflect { public static Throwable unwrap(Throwable failure) { return failure; } }
				""")
		);
	}
}
