/*
 * Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0.
 */
package net.forbric.kernel.access;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The merge namespace is the one the MOST files declare, ties going to the runtime one — not the first file's.
 * First-file-wins made one unrewritten file total: cloth-config's camelCase {@code cloth-config.accessWidener}
 * (v1, still intermediary) sat first in mod order and skipped the other 18 correct {@code official} files, leaving
 * cristellib and BetterRailwaySystem on the original {@code IllegalAccessError}.
 */
class ClassTweakerTransformerNamespaceTest {
	private static byte[] widener(String namespace, String owner, String name, String desc) {
		return ("accessWidener v2 " + namespace + "\naccessible method " + owner + " " + name + " " + desc + "\n")
				.getBytes(StandardCharsets.UTF_8);
	}

	private static final byte[] OFFICIAL = widener("official",
			"net/minecraft/core/registries/BuiltInRegistries", "createContents", "()V");
	private static final byte[] OFFICIAL_TWO = widener("official",
			"net/minecraft/core/registries/BuiltInRegistries", "bootStrap", "()V");
	private static final byte[] INTERMEDIARY = widener("intermediary",
			"net/minecraft/class_7923", "method_47487", "()V");

	@Test
	void theMajorityNamespaceWinsEvenWhenTheFirstFileIsTheMiss() {
		// The stray file is FIRST, exactly as cloth-config was in mod order.
		ClassTweakerTransformer transformer = ClassTweakerTransformer.createFrom(List.of(
				new ClassTweakerTransformer.File("cloth-config.jar", INTERMEDIARY),
				new ClassTweakerTransformer.File("a.jar", OFFICIAL),
				new ClassTweakerTransformer.File("b.jar", OFFICIAL_TWO)), (name, bytes) -> { });

		assertNotNull(transformer);
		assertTrue(transformer.targets().contains("net/minecraft/core/registries/BuiltInRegistries"),
				"the majority namespace is merged: " + transformer.targets());
		assertFalse(transformer.targets().contains("net/minecraft/class_7923"),
				"the one stray file is skipped on its own, not the other way round: " + transformer.targets());
	}

	@Test
	void aTieGoesToTheRuntimeNamespace() {
		ClassTweakerTransformer transformer = ClassTweakerTransformer.createFrom(List.of(
				new ClassTweakerTransformer.File("stray.jar", INTERMEDIARY),
				new ClassTweakerTransformer.File("ok.jar", OFFICIAL)), (name, bytes) -> { });

		assertNotNull(transformer);
		assertTrue(transformer.targets().contains("net/minecraft/core/registries/BuiltInRegistries"),
				"a tie is broken toward the namespace the kernel runs: " + transformer.targets());
		assertFalse(transformer.targets().contains("net/minecraft/class_7923"), transformer.targets().toString());
	}
}
