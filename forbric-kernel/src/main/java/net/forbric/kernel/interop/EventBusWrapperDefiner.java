/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import java.lang.reflect.Method;

import net.forbric.kernel.classloading.ForbricClassLoader;

/**
 * Defines Forge's generated event-handler wrapper in the GAME loader, not in the bus's own child {@code ASMClassLoader}.
 *
 * <p>{@code ClassLoaderFactory} generates one wrapper class per listener method and defines it into a STATIC
 * {@code ASMClassLoader}, whose parent is the game loader but which is a DIFFERENT runtime package. A listener
 * whose class is package-private in its carrier package — MinecraftForge's own
 * {@code net.minecraftforge.registries.DeferredRegister$EventDispatcher} is one — then cannot be accessed from the
 * generated wrapper and the JVM rejects the dispatch with {@code IllegalAccessError} before the listener runs, so
 * every Forge {@code DeferredRegister} (and {@code NewRegistryEvent}, which builds Forge's custom registries)
 * silently registers nothing.
 *
 * <p>Native Forge never hits this: {@code ModLauncherFactory} has ModLauncher define the wrapper in the game loader,
 * the same loader (and therefore runtime package) as the listener. {@code ModLauncherClaimRewriter} took that path
 * away because the kernel runs no ModLauncher; the transformer
 * {@code net.forbric.kernel.transform.EventBusWrapperDefinerInjector} rewrites the {@code defineClass} call site to
 * land here instead, putting the wrapper back where the launcher would have put it.
 *
 * <p>When no loader is attached — a unit test, a bare Forge {@code ClassLoaderFactory} — the wrapper is defined
 * exactly as before, in the fallback {@code ASMClassLoader}, so the pre-fix behaviour survives.
 */
public final class EventBusWrapperDefiner {
	private static volatile ForbricClassLoader gameLoader;

	private EventBusWrapperDefiner() { }

	/** Called by KernelBoot the moment the sovereign loader exists, before any mod class loads. */
	public static void attach(ForbricClassLoader loader) {
		gameLoader = loader;
	}

	/**
	 * Defines one generated wrapper. {@code fallback} is {@code ClassLoaderFactory}'s own {@code ASMClassLoader},
	 * left on the stack by the rewritten call site so its package-private {@code define} stays reachable.
	 */
	public static Class<?> defineOrFallback(Object fallback, String binaryName, byte[] bytes) {
		ForbricClassLoader loader = gameLoader;
		if (loader != null) {
			try {
				return loader.defineRuntimeClass(binaryName, bytes);
			} catch (Throwable inGameLoader) {
				// A duplicate definition or a broken package must not lose the handler: fall back below.
			}
		}
		return defineIntoFallback(fallback, binaryName, bytes);
	}

	/** The pre-fix path: {@code ASMClassLoader.define(String, byte[])}, package-private, so reflectively. */
	private static Class<?> defineIntoFallback(Object fallback, String binaryName, byte[] bytes) {
		try {
			Method define = fallback.getClass().getDeclaredMethod("define", String.class, byte[].class);
			define.setAccessible(true);
			return (Class<?>) define.invoke(fallback, binaryName, bytes);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("cannot define event-bus wrapper " + binaryName, e);
		}
	}
}
