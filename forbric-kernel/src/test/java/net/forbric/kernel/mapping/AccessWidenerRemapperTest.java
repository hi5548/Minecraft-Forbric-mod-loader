/*
 * Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0.
 */
package net.forbric.kernel.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.access.AccessWidenerRemapper;
import net.forbric.kernel.access.ClassTweakerTransformer;

/**
 * The access-widener remap: fabric-api writes its wideners in the intermediary namespace, the kernel runs Mojmap,
 * and the {@code class-tweaker} library translates nothing — so a directive left as written widens a class the
 * runtime class visitor never asks about. {@code fabric-registry-sync-v0} widens
 * {@code class_7923 method_47487 ()V}, which is the private {@code BuiltInRegistries.createContents()} the
 * registry-sync redirect calls.
 *
 * <p>A directive may also name an <b>inherited</b> member on the subclass it means to widen — Cobblemon widens
 * {@code LivingEntity}'s final {@code canBreatheUnderwater()} and {@code getDimensions(Pose)} — and the
 * intermediary file carries such a member under its declaring class only (see
 * {@link ForbricMappings#mapMemberName}). That is the second shape this pass has to translate.
 */
class AccessWidenerRemapperTest {
	/**
	 * Cobblemon's {@code cobblemon-common.accesswidener}, verbatim (the two entries that matter here): it widens
	 * two of {@code LivingEntity}'s own final methods. {@code PokemonEntity} overrides both, and the widener is
	 * the only reason that is legal on 1.21.1.
	 */
	private static final String COBBLEMON = "accessWidener\tv2\tintermediary\n"
			+ "transitive-extendable\tmethod\tnet/minecraft/class_1309\tmethod_6094\t()Z\n"
			+ "transitive-extendable\tmethod\tnet/minecraft/class_1309\tmethod_18377"
			+ "\t(Lnet/minecraft/class_4050;)Lnet/minecraft/class_4048;\n";

	private static final String COBBLEMON_REMAPPED_HEADER = "accessWidener\tv2\tofficial\n";

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

	/**
	 * A camelCase entry name and a v1 header are both real: cloth-config ships {@code cloth-config.accessWidener}
	 * with {@code accessWidener v1 intermediary}. Matching the suffix case-sensitively left it unrewritten, and
	 * being first in mod order it set the merge namespace and disabled the other 18 files.
	 */
	@Test
	void aCamelCaseV1EntryIsRewrittenToo() throws Exception {
		ForbricMappings spine = FabricGuestMappings.of(MappingFixtures.intermediary(), MappingFixtures.mojmap()).mappings();
		Path jar = java.nio.file.Files.createTempFile("cloth-config", ".jar");
		try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(jar))) {
			out.putNextEntry(new java.util.zip.ZipEntry("cloth-config.accessWidener"));
			out.write(("accessWidener\tv1\tintermediary\n"
					+ "accessible\tmethod\tnet/minecraft/class_7923\tmethod_47487\t()V\n")
					.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			out.closeEntry();
		}

		assertEquals(1, AccessWidenerRemapper.remap(jar, spine));

		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
			String text = new String(zip.getInputStream(zip.getEntry("cloth-config.accessWidener")).readAllBytes(),
					java.nio.charset.StandardCharsets.UTF_8);
			assertTrue(text.startsWith("accessWidener\tv1\tofficial"), text);
			assertTrue(text.contains("accessible\tmethod\tnet/minecraft/core/registries/BuiltInRegistries"
					+ "\tcreateContents\t()V"), text);
		}
	}

	/**
	 * {@code method_18377} — {@code getDimensions(Pose)} — is declared on {@code class_1297} ({@code Entity}) in
	 * the intermediary file, but Cobblemon names it on {@code class_1309} ({@code LivingEntity}), the subclass
	 * whose own final override it means to widen. The owner-scoped lookup misses there, so before the fallback the
	 * entry stayed {@code method_18377}: the merged base declares no member by that name, and
	 * {@code LivingEntity.getDimensions} kept ACC_FINAL. {@code canBreatheUnderwater} is declared on class_1309
	 * itself and always translated, which is why the failure surfaced on the inherited one.
	 */
	@Test
	void anInheritedMemberNamedOnTheSubclassIsRewritten() throws Exception {
		ForbricMappings spine = FabricGuestMappings.of(MappingFixtures.intermediary(), MappingFixtures.mojmap()).mappings();
		Path jar = java.nio.file.Files.createTempFile("cobblemon-common", ".jar");
		writeEntry(jar, "cobblemon-common.accesswidener", COBBLEMON);

		assertEquals(1, AccessWidenerRemapper.remap(jar, spine));

		String out = readEntry(jar, "cobblemon-common.accesswidener");
		assertTrue(out.startsWith(COBBLEMON_REMAPPED_HEADER), out);
		assertTrue(out.contains("transitive-extendable\tmethod\tnet/minecraft/world/entity/LivingEntity"
				+ "\tcanBreatheUnderwater\t()Z"), out);
		assertTrue(out.contains("transitive-extendable\tmethod\tnet/minecraft/world/entity/LivingEntity"
				+ "\tgetDimensions\t(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;"),
				out);
	}

	/**
	 * The end the widening exists for, on the real merged bytes: once both directives resolve, the class-tweaker
	 * pass clears ACC_FINAL on {@code LivingEntity.canBreatheUnderwater()Z} and
	 * {@code LivingEntity.getDimensions(Pose)}. That is what the JVM requires before it will define Cobblemon's
	 * {@code PokemonEntity}, whose declarations of both would otherwise be
	 * {@code IncompatibleClassChangeError: ... overrides final method ...}.
	 */
	@Test
	void theMergedBasesFinalMembersLoseTheirFinalFlag() throws Exception {
		ForbricMappings spine = FabricGuestMappings.of(MappingFixtures.intermediary(), MappingFixtures.mojmap()).mappings();
		Path jar = java.nio.file.Files.createTempFile("cobblemon-common", ".jar");
		writeEntry(jar, "cobblemon-common.accesswidener", COBBLEMON);
		AccessWidenerRemapper.remap(jar, spine);

		ClassTweakerTransformer tweaker = ClassTweakerTransformer.createFrom(List.of(
				new ClassTweakerTransformer.File("Cobblemon-fabric", readEntry(jar, "cobblemon-common.accesswidener")
						.getBytes(StandardCharsets.UTF_8))), (name, bytes) -> { });
		assertNotNull(tweaker, "the remapped widener must merge");

		byte[] widened = tweaker.transform("net.minecraft.world.entity.LivingEntity",
				readEntryBytes(MappingFixtures.mergedBase(), "net/minecraft/world/entity/LivingEntity.class"), null);

		assertEquals(0, methodAccess(widened, "canBreatheUnderwater", "()Z") & Opcodes.ACC_FINAL,
				"canBreatheUnderwater must be overridable");
		assertEquals(0, methodAccess(widened, "getDimensions",
				"(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;") & Opcodes.ACC_FINAL,
				"getDimensions(Pose) must be overridable");
	}

	private static void writeEntry(Path jar, String entry, String text) throws Exception {
		try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(jar))) {
			out.putNextEntry(new java.util.zip.ZipEntry(entry));
			out.write(text.getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}
	}

	private static String readEntry(Path jar, String entry) throws Exception {
		return new String(readEntryBytes(jar, entry), StandardCharsets.UTF_8);
	}

	private static byte[] readEntryBytes(Path jar, String entry) throws Exception {
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
			return zip.getInputStream(zip.getEntry(entry)).readAllBytes();
		}
	}

	private static int methodAccess(byte[] bytes, String name, String desc) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		for (MethodNode method : node.methods) {
			if (method.name.equals(name) && method.desc.equals(desc)) return method.access;
		}
		throw new AssertionError("the class declares no " + name + desc);
	}
}
