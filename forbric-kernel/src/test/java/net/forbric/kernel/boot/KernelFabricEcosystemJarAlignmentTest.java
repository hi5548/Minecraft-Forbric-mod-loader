/*
 * Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0.
 */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The contract the access-widener fix was missing: a reader that opens a mod's jar for RESOURCES must open the same
 * jar the class loader defines its CLASSES from. The loader gets the remapped list, so the pre-remap original must
 * never be the answer — reading it hands {@code ClassTweakerTransformer} an intermediary-namespace file, it widens
 * nothing, and a now-applied mixin throws {@code IllegalAccessError} on the first member it reaches
 * ({@code BuiltInRegistries.createContents()}).
 */
class KernelFabricEcosystemJarAlignmentTest {
	@AfterEach
	void reset() {
		KernelFabricEcosystem.useRemappedJars(List.of(), List.of());
	}

	@Test
	void theReaderOpensTheRemappedJarTheLoaderUses() {
		Path original = Path.of("/mods/registry-sync.jar");
		Path remapped = Path.of("/game/.forbric-kernel/remap/registry-sync-abc123.jar");
		KernelFabricEcosystem.useRemappedJars(List.of(original), List.of(remapped));

		assertEquals(remapped, KernelFabricEcosystem.jarToRead(original));
		assertEquals(remapped, KernelFabricEcosystem.jarToRead(Path.of("/mods/../mods/registry-sync.jar")),
				"the alignment is by normalized absolute path, as the container and the remap spell it differently");
	}

	@Test
	void anUnremappedJarIsReadInPlace() {
		KernelFabricEcosystem.useRemappedJars(List.of(Path.of("/mods/a.jar")), List.of(Path.of("/cache/a.jar")));

		assertEquals(Path.of("/mods/b.jar"), KernelFabricEcosystem.jarToRead(Path.of("/mods/b.jar")),
				"a jar the remap did not cover is still read where it is");
	}
}
