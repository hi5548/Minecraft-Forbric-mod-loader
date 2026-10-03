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

package net.forbric.kernel.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.forbric.kernel.fabric.KernelMappingResolver;

/**
 * The mapping-backed half of {@code KernelMappingResolver}, against the REAL 1.21.1 mapping data — the same two
 * files the installer stages. The names asserted here are the fixture's own; they were read out of the two files
 * joined on the obfuscated column ({@code fgo}), so a regression in the join (a mis-ordered namespace, a
 * descriptor not translated) fails on data that exists rather than on a synthetic pair that would match anyway.
 *
 * <p>The identity contract of the no-data resolver lives in {@code fabric/KernelMappingResolverTest}; this file
 * is about what changes once there IS data: which namespace the kernel reports, and that every lookup really
 * translates.
 */
class KernelMappingResolverMappingsTest {
	/** {@code net.minecraft.client.Minecraft} — intermediary {@code net/minecraft/class_310}, obfuscated {@code fgo}. */
	private static final String MINECRAFT = "net/minecraft/client/Minecraft";
	private static final String MINECRAFT_INTERMEDIARY = "net/minecraft/class_310";
	private static final String MINECRAFT_OFFICIAL = "fgo";

	private static KernelMappingResolver resolver() {
		return new KernelMappingResolver(FabricGuestMappings.of(MappingFixtures.intermediary(),
				MappingFixtures.mojmap()));
	}

	@Test
	void reportsTheRuntimeNamespaceTheRemapStageTargets() {
		KernelMappingResolver resolver = resolver();

		assertEquals(ForbricMappings.NAMED, resolver.getCurrentRuntimeNamespace(),
				"the runtime namespace must be the one the guests are renamed INTO, not another spelling of it");
		assertEquals(List.of(ForbricMappings.NAMED, ForbricMappings.INTERMEDIARY, ForbricMappings.OFFICIAL),
				List.copyOf(resolver.getNamespaces()));
		assertTrue(resolver.getNamespaces().contains(resolver.getCurrentRuntimeNamespace()),
				"a runtime namespace missing from getNamespaces() is a resolver that disowns its own answers");
	}

	@Test
	void translatesClassesFieldsAndMethodsBetweenTheNamespaces() {
		KernelMappingResolver resolver = resolver();

		// Class, both directions, plus the obfuscated column the two sources were joined on.
		assertEquals(MINECRAFT, resolver.mapClassName(ForbricMappings.INTERMEDIARY, MINECRAFT_INTERMEDIARY));
		assertEquals(MINECRAFT, resolver.mapClassName(ForbricMappings.OFFICIAL, MINECRAFT_OFFICIAL));
		assertEquals(MINECRAFT_INTERMEDIARY, resolver.unmapClassName(ForbricMappings.INTERMEDIARY, MINECRAFT));
		assertEquals(MINECRAFT_OFFICIAL, resolver.unmapClassName(ForbricMappings.OFFICIAL, MINECRAFT));

		// Members. The descriptor is in the QUERY's namespace — Java types in intermediary, which is why the
		// class-typed one is here beside the primitive: a resolver that only ever matched source descriptors
		// would pass the primitive and silently return the intermediary name for the other.
		assertEquals("run", resolver.mapMethodName(ForbricMappings.INTERMEDIARY, MINECRAFT_INTERMEDIARY,
				"method_1514", "()V"));
		assertEquals("wireframe", resolver.mapFieldName(ForbricMappings.INTERMEDIARY, MINECRAFT_INTERMEDIARY,
				"field_32144", "Z"));
		assertEquals("instance", resolver.mapFieldName(ForbricMappings.INTERMEDIARY, MINECRAFT_INTERMEDIARY,
				"field_1700", "L" + MINECRAFT_INTERMEDIARY + ";"));
	}

	/**
	 * The same lookups in the notation the Fabric API documents — dotted, the way {@code Class.getName()} spells a
	 * class. A resolver that only understands internal names answers a dotted query with its own input, which is
	 * how a mod asking for a class inside GENERATED code names the intermediary one instead.
	 */
	@Test
	void translatesTheDottedSpellingTheFabricApiUses() {
		KernelMappingResolver resolver = resolver();

		assertEquals("net.minecraft.client.Minecraft",
				resolver.mapClassName(ForbricMappings.INTERMEDIARY, "net.minecraft.class_310"));
		assertEquals("net.minecraft.class_310",
				resolver.unmapClassName(ForbricMappings.INTERMEDIARY, "net.minecraft.client.Minecraft"));
		assertEquals("run", resolver.mapMethodName(ForbricMappings.INTERMEDIARY, "net.minecraft.class_310",
				"method_1514", "()V"));
	}

	@Test
	void aNamespaceOrNameItCannotTranslateComesBackUnchanged() {
		KernelMappingResolver resolver = resolver();

		// The runtime namespace itself, a name no mapping knows, and a namespace the spine does not carry.
		assertEquals(MINECRAFT, resolver.mapClassName(ForbricMappings.NAMED, MINECRAFT));
		assertEquals("com/example/NotMapped", resolver.mapClassName(ForbricMappings.INTERMEDIARY,
				"com/example/NotMapped"));
		assertEquals(MINECRAFT_INTERMEDIARY, resolver.mapClassName("yarn", MINECRAFT_INTERMEDIARY));
		assertEquals("notAMappedMethod", resolver.mapMethodName(ForbricMappings.INTERMEDIARY,
				MINECRAFT_INTERMEDIARY, "notAMappedMethod", "()V"));

		// And the members of a class the spine does not know at all.
		assertEquals("method_1", resolver.mapMethodName(ForbricMappings.INTERMEDIARY, "com/example/NotMapped",
				"method_1", "()V"));
	}

	/**
	 * The no-data resolver is the identity — the 26.2 shape — and it must stay that way however much mapping
	 * data the process has seen: every guest is loaded unchanged when nothing is staged, so a translation here
	 * would name classes that do not exist.
	 */
	@Test
	void withoutStagedDataEveryLookupIsStillTheIdentity() {
		KernelMappingResolver resolver = new KernelMappingResolver(null);

		assertFalse(resolver.getNamespaces().contains(ForbricMappings.INTERMEDIARY),
				"the identity resolver knows no other namespace and must not claim one");
		assertEquals(MINECRAFT_INTERMEDIARY,
				resolver.mapClassName(ForbricMappings.INTERMEDIARY, MINECRAFT_INTERMEDIARY));
	}
}
