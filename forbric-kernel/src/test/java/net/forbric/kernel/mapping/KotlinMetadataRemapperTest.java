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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

/**
 * The Kotlin-metadata half of the guest remap, on the real Cobblemon class that failed the boot.
 *
 * <p>Cobblemon's Fabric entrypoint died at {@code SpeciesAdditions.<clinit>} with
 * {@code ClassNotFoundException: net.minecraft.class_2960} out of {@code kotlin-reflect}'s
 * {@code KDeclarationContainerImpl.parseType}. kotlin-reflect reaches that name through
 * {@code JvmMethodSignature.getDesc()}, which is an <b>index</b> into the metadata string table ({@code d2}) —
 * not the constant pool, and not the protobuf — and a bytecode remapper never touches an annotation. The class
 * comes from the real jar the reference rig loads; the spine is the real 1.21.1 mapping data the test task
 * already stages.
 *
 * <p>Assertions read the {@code d2} table itself, never a byte scan of the class: the raw jar's constant pool
 * legitimately still spells everything intermediary (the bytecode remap is a different pass), so a whole-file
 * search would test the wrong thing.
 */
class KotlinMetadataRemapperTest {
	private static final String SPECIES_ADDITIONS = "com/cobblemon/mod/common/pokemon/SpeciesAdditions.class";
	private static final String COBBLEMON_JAVA_CLASS = "com/cobblemon/mod/common/DoubleJump.class";
	private static final String METADATA_DESC = "Lkotlin/Metadata;";
	private static final String D2 = "d2";
	private static final String INTERMEDIARY_LOCATION = "Lnet/minecraft/class_2960;";
	private static final String NAMED_LOCATION = "Lnet/minecraft/resources/ResourceLocation;";

	@Test
	void theRealKotlinClassGetsItsMetadataNamespaceRewritten() throws Exception {
		ForbricMappings spine = spine();
		byte[] original = guestEntry(SPECIES_ADDITIONS);

		List<String> before = stringTable(original);
		assertTrue(before.contains(INTERMEDIARY_LOCATION),
				"the jar is compiled against intermediary, so the metadata table spells ResourceLocation that way");
		assertTrue(before.contains("()Lnet/minecraft/class_2960;"),
				"the getter's descriptor is what kotlin-reflect hands to parseType — an index into this table");

		byte[] fixed = KotlinMetadataRemapper.translateClass(original, spine);
		assertNotNull(fixed, "a Kotlin class with game types in d2 is what this pass exists for");
		List<String> after = stringTable(fixed);
		assertFalse(after.contains(INTERMEDIARY_LOCATION),
				"kotlin-reflect resolves types out of d2, so the intermediary name must be gone");
		assertTrue(after.contains(NAMED_LOCATION),
				"and replaced by the name the merged base actually declares");
		assertTrue(after.contains("getId") && after.contains("reload"),
				"member names sit in the same table and are not class names — they must survive untouched");
	}

	@Test
	void aClassWithoutKotlinMetadataIsLeftAlone() throws Exception {
		byte[] java = guestEntry(COBBLEMON_JAVA_CLASS);
		assertTrue(stringTable(java).isEmpty(), "a plain Java class has no d2 table at all");
		assertNull(KotlinMetadataRemapper.translateClass(java, spine()),
				"a class with nothing to rewrite must answer null, not a rewritten copy");
	}

	/** The jar-level entry point: the same pass over a real jar rewrites the entry and leaves the rest alone. */
	@Test
	void theJarPassRewritesTheEntryAndLeavesOthers() throws Exception {
		ForbricMappings spine = spine();
		Path jar = Files.createTempDirectory("kotlin-metadata").resolve("guest.jar");
		byte[] kotlin = guestEntry(SPECIES_ADDITIONS);
		byte[] plain = guestEntry(COBBLEMON_JAVA_CLASS);
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
			out.putNextEntry(new ZipEntry(SPECIES_ADDITIONS));
			out.write(kotlin);
			out.closeEntry();
			out.putNextEntry(new ZipEntry(COBBLEMON_JAVA_CLASS));
			out.write(plain);
			out.closeEntry();
		}

		assertEquals(1, KotlinMetadataRemapper.translate(jar, spine), "exactly the Kotlin class changes");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			assertFalse(stringTable(entry(zip, SPECIES_ADDITIONS)).contains(INTERMEDIARY_LOCATION));
			assertFalse(stringTable(entry(zip, COBBLEMON_JAVA_CLASS)).contains(INTERMEDIARY_LOCATION));
			assertEquals(plain.length, zip.getEntry(COBBLEMON_JAVA_CLASS).getSize(),
					"the class that needed nothing comes back byte for byte");
		}
		assertEquals(0, KotlinMetadataRemapper.translate(jar, spine), "and a second pass finds nothing left to do");
	}

	private static byte[] entry(ZipFile zip, String name) throws IOException {
		return zip.getInputStream(zip.getEntry(name)).readAllBytes();
	}

	/** The {@code @Metadata} string table of a class, or an empty list when it has no Kotlin metadata. */
	private static List<String> stringTable(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		List<AnnotationNode> annotations = new ArrayList<>();
		if (node.visibleAnnotations != null) annotations.addAll(node.visibleAnnotations);
		if (node.invisibleAnnotations != null) annotations.addAll(node.invisibleAnnotations);
		for (AnnotationNode annotation : annotations) {
			if (!METADATA_DESC.equals(annotation.desc) || annotation.values == null) continue;
			for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
				if (!D2.equals(annotation.values.get(i))) continue;
				List<String> out = new ArrayList<>();
				for (Object value : (List<?>)annotation.values.get(i + 1)) out.add((String)value);
				return out;
			}
		}
		return List.of();
	}

	private static ForbricMappings spine() {
		return FabricGuestMappings.of(MappingFixtures.intermediary(), MappingFixtures.mojmap()).mappings();
	}

	/** The real Cobblemon jar's entry, served from the reference rig's copy when one is staged. */
	private static byte[] guestEntry(String name) throws IOException {
		Path jar = guestJar();
		assumeTrue(jar != null, "no staged Cobblemon jar");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(name);
			assumeTrue(entry != null, name + " absent from " + jar.getFileName());
			return zip.getInputStream(entry).readAllBytes();
		}
	}

	private static Path guestJar() {
		String pointed = System.getProperty("forbric.guestJar");
		if (pointed != null && !pointed.isBlank()) return Path.of(pointed);
		for (Path candidate : List.of(
				Path.of("/tmp/w7-fabric-ref/mods/Cobblemon-fabric-1.8.1+1.21.1.jar"),
				Path.of("/tmp/w7-stage/guest/Cobblemon-fabric-1.8.1+1.21.1.jar"))) {
			if (Files.isRegularFile(candidate)) return candidate;
		}
		return null;
	}
}
