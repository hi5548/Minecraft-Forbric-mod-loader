/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.util;

import java.lang.reflect.Method;

/**
 * The game's identifier type and the accessor that reads it off a {@code ResourceKey}, under both generations'
 * names.
 *
 * <p>The kernel's reflective code was written against the newer generation, where the type is
 * {@code net.minecraft.resources.Identifier} and a key's id is read with {@code identifier()}. The 1.21.1 merged
 * base this kernel actually boots carries neither: the type is {@code net.minecraft.resources.ResourceLocation}
 * (verified with {@code javap} — {@code net.minecraft.resources.Identifier} does not exist) and the accessor is
 * {@code location()} ({@code ResourceKey} declares {@code public ResourceLocation location()} and no
 * {@code identifier()}). A lookup hardcoded to one generation's spelling does not fail loudly on the other: it
 * throws, the caller catches, and the feature behind it goes quiet. The player log's
 * {@code NoSuchMethodException: net.minecraft.resources.ResourceKey.identifier()} is exactly this, and the
 * registry-sync id staging it guards then never runs — the same failure shape as the two-generation class-name and
 * spelling repairs elsewhere in the kernel.
 *
 * <p>Every such lookup asks here instead, so a member that exists under both names keeps both paths and one that
 * only exists under the older name still resolves.
 */
public final class IdentifierNames {
	private IdentifierNames() {
	}

	/** The simple accessor that answers a key's id: {@code location()} on 1.21.1, {@code identifier()} after. */
	public static Method idGetter(Class<?> keyType) throws NoSuchMethodException {
		try {
			return keyType.getMethod("location");
		} catch (NoSuchMethodException older) {
			return keyType.getMethod("identifier");
		}
	}

	/** The identifier class itself: {@code ResourceLocation} on 1.21.1, {@code Identifier} after. */
	public static Class<?> identifierClass(ClassLoader loader) throws ClassNotFoundException {
		try {
			return Class.forName("net.minecraft.resources.ResourceLocation", false, loader);
		} catch (ClassNotFoundException older) {
			return Class.forName("net.minecraft.resources.Identifier", false, loader);
		}
	}
}
