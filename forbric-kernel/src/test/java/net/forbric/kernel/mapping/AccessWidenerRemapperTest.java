/*
 * Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0.
 */
package net.forbric.kernel.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.forbric.kernel.access.AccessWidenerRemapper;

/**
 * The access-widener remap: fabric-api writes its wideners in the intermediary namespace, the kernel runs Mojmap,
 * and the {@code class-tweaker} library translates nothing — so a directive left as written widens a class the
 * runtime class visitor never asks about. {@code fabric-registry-sync-v0} widens
 * {@code class_7923 method_47487 ()V}, which is the private {@code BuiltInRegistries.createContents()} the
 * registry-sync redirect calls.
 */
class AccessWidenerRemapperTest {
	@Test
	void rewritesTheHeaderClassesMembersAndDescriptors() {
		String text = "accessWidener\tv2\tintermediary\n"
				+ "accessible\tmethod\tnet/minecraft/class_7923\tmethod_47487\t()V\n"
				+ "accessible\tfield\tnet/minecraft/class_2370\tfield_36463\tZ\n"
				+ "accessible\tclass\tnet/minecraft/class_7655$class_9158\n"
				+ "inject-interface\tnet/minecraft/class_7923\tnet/example/Iface\n"
				+ "transitive-accessible method net/minecraft/class_7655 method_2 (Lnet/minecraft/class_1799;)V # a comment\n";

		String out = AccessWidenerRemapper.remapText(text, "official",
				name -> name.equals("net/minecraft/class_7923")
						? "net/minecraft/core/registries/BuiltInRegistries"
						: name.replace("net/minecraft/class_7655", "net/minecraft/example/Thing"),
				(owner, name, desc, method) -> name.equals("method_47487") ? "createContents" : name);

		assertTrue(out.startsWith("accessWidener\tv2\tofficial"), out);
		assertTrue(out.contains("accessible\tmethod\tnet/minecraft/core/registries/BuiltInRegistries"
				+ "\tcreateContents\t()V"), out);
		assertTrue(out.contains("accessible\tfield\tnet/minecraft/class_2370\tfield_36463\tZ"), out);
		assertTrue(out.contains("inject-interface\tnet/minecraft/core/registries/BuiltInRegistries\tnet/example/Iface"), out);
		assertTrue(out.contains("transitive-accessible\tmethod\tnet/minecraft/example/Thing\tmethod_2"
				+ "\t(Lnet/minecraft/class_1799;)V\t# a comment"), out);
	}

	/** Against the real 1.21.1 mappings: the registry-sync entry becomes the member the merged base declares. */
	@Test
	void theRegistrySyncEntryBecomesTheRuntimeMember() throws Exception {
		ForbricMappings spine = FabricGuestMappings.of(MappingFixtures.intermediary(), MappingFixtures.mojmap()).mappings();
		String text = "accessWidener\tv2\tintermediary\n"
				+ "accessible\tmethod\tnet/minecraft/class_7923\tmethod_47487\t()V\n";

		String out = AccessWidenerRemapper.remapText(text, AccessWidenerRemapper.RUNTIME_NAMESPACE,
				name -> spine.mapClass(ForbricMappings.INTERMEDIARY, ForbricMappings.NAMED, name),
				(owner, name, desc, method) -> method
						? spine.mapMethod(ForbricMappings.INTERMEDIARY, ForbricMappings.NAMED, owner, name, desc)
						: spine.mapField(ForbricMappings.INTERMEDIARY, ForbricMappings.NAMED, owner, name, desc));

		assertEquals("accessWidener\tv2\tofficial\n"
				+ "accessible\tmethod\tnet/minecraft/core/registries/BuiltInRegistries\tcreateContents\t()V\n", out);
	}
}
