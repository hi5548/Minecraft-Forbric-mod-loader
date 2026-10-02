/*
 * Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0.
 */
package net.forbric.kernel.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * {@link MixinNames} resolves an {@code @At(target=…)} through the mod's OWN refmap, and Fabric writes that refmap's
 * KEYS in a different spelling from the annotation's value. The member name in a key can be reached from either
 * spelling, and when it cannot the entry is silently inert: Mixin gets a selector that names nothing in the merged
 * base and the injector is a required loss. These are the two measured shapes on the 1.21.1 fabric bucket.
 */
class MixinNamesTest {
	private static final String MIXIN = "example/ProbeMixin";

	/** `@At(target="Lnet/minecraft/entity/LivingEntity;isSleeping()Z")` against a refmap key spelling the owner dotted. */
	@Test
	void anAtTargetInTheDescriptorSpellingResolvesThroughADottedRefmapKey(@TempDir Path dir) throws Exception {
		Path jar = dir.resolve("probe.jar");
		writeJar(jar,
				"{\"mappings\":{\"" + MIXIN + "\":{"
						+ "\"net/minecraft/entity/LivingEntity.isSleeping()Z\":"
						+ "\"Lnet/minecraft/world/entity/LivingEntity;isSleeping()Z\"}}}",
				"Lnet/minecraft/entity/LivingEntity;isSleeping()Z", "INVOKE");

		MixinNames.translate(jar, spine());

		assertEquals(List.of("Lnet/minecraft/world/entity/LivingEntity;isSleeping()Z"), atTargets(jar),
				"the refmap names method_18428 (= isSleeping) under a dotted key; the annotation spells the same "
						+ "member as a descriptor, and only the bare-name fallback can join the two");
	}

	/**
	 * The same gap with a descriptor-carrying key (`readCustomDataFromNbt(NbtCompound)V`): the member is renamed,
	 * but the owner package moved too, so the entry cannot be read as a member of the game class the annotation names.
	 */
	@Test
	void anAtTargetWithADescriptorResolvesThroughADottedRefmapKey(@TempDir Path dir) throws Exception {
		Path jar = dir.resolve("probe.jar");
		writeJar(jar,
				"{\"mappings\":{\"" + MIXIN + "\":{"
						+ "\"net/minecraft/entity/Entity.readCustomDataFromNbt(Lnet/minecraft/nbt/NbtCompound;)V\":"
						+ "\"Lnet/minecraft/world/entity/Entity;readAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V\"}}}",
				"Lnet/minecraft/entity/Entity;readCustomDataFromNbt(Lnet/minecraft/nbt/NbtCompound;)V", "INVOKE");

		MixinNames.translate(jar, spine());

		assertEquals(List.of("Lnet/minecraft/world/entity/Entity;readAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V"),
				atTargets(jar));
	}

	/**
	 * An {@code @At(value="NEW", target=…)} carrying a bare constructor descriptor names no member at all, so no
	 * member path can translate the intermediary {@code class_*} names inside it and the injection never binds.
	 */
	@Test
	void aBareConstructorDescriptorAtTargetIsTranslatedClassByClass(@TempDir Path dir) throws Exception {
		Path jar = dir.resolve("probe.jar");
		writeJar(jar,
				"{\"mappings\":{\"" + MIXIN + "\":{}}}",
				"(Lnet/minecraft/class_1935;)Lnet/minecraft/class_1799;", "NEW");

		MixinNames.translate(jar, spine());

		assertEquals(List.of("(Lnet/minecraft/world/level/ItemLike;)Lnet/minecraft/world/item/ItemStack;"), atTargets(jar),
				"ItemStack is constructed in the merged doBrew, but the target still says class_1799");
	}

	private static ForbricMappings spine() throws Exception {
		return FabricGuestMappings.of(MappingFixtures.intermediary(), MappingFixtures.mojmap()).mappings();
	}

	/** One {@code @Mixin} with one {@code @Inject}-annotated method whose {@code @At(value, target)} is the probe. */
	private static void writeJar(Path jar, String refmap, String atTarget, String atValue) throws Exception {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MIXIN, null, "java/lang/Object", null);

		AnnotationVisitor mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", true);
		AnnotationVisitor targets = mixin.visitArray("targets");
		targets.visit(null, "net.minecraft.world.entity.LivingEntity");
		targets.visitEnd();
		mixin.visitEnd();

		var method = writer.visitMethod(Opcodes.ACC_PRIVATE, "handler", "()V", null, null);
		AnnotationVisitor inject = method.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
		AnnotationVisitor methods = inject.visitArray("method");
		methods.visit(null, "Lnet/minecraft/world/entity/LivingEntity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z");
		methods.visitEnd();
		AnnotationVisitor ats = inject.visitArray("at");
		AnnotationVisitor at = ats.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
		at.visit("value", atValue);
		at.visit("target", atTarget);
		at.visitEnd();
		ats.visitEnd();
		inject.visitEnd();
		method.visitCode();
		method.visitInsn(Opcodes.RETURN);
		method.visitMaxs(0, 1);
		method.visitEnd();
		writer.visitEnd();

		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
			out.putNextEntry(new ZipEntry(MIXIN + ".class"));
			out.write(writer.toByteArray());
			out.closeEntry();
			out.putNextEntry(new ZipEntry("probe-refmap.json"));
			out.write(refmap.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			out.closeEntry();
		}
	}

	/** The {@code @At(target)} of the probe method as it now stands in the jar. */
	private static List<String> atTargets(Path jar) throws Exception {
		List<String> out = new ArrayList<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ClassNode node = new ClassNode();
			new ClassReader(zip.getInputStream(zip.getEntry(MIXIN + ".class")).readAllBytes())
					.accept(node, ClassReader.SKIP_FRAMES);
			for (MethodNode method : node.methods) {
				for (AnnotationNode annotation : method.visibleAnnotations == null ? List.<AnnotationNode>of()
						: method.visibleAnnotations) {
					if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) continue;
					collect(annotation, out);
				}
			}
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static void collect(AnnotationNode annotation, List<String> out) {
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
			String key = String.valueOf(annotation.values.get(i));
			Object value = annotation.values.get(i + 1);
			if (value instanceof AnnotationNode nested) {
				collect(nested, out);
			} else if (value instanceof List<?> list) {
				for (Object item : list) {
					if (item instanceof AnnotationNode nested) collect(nested, out);
					else if (item instanceof String text && isAtTarget(annotation, key)) out.add(text);
				}
			} else if (value instanceof String text && isAtTarget(annotation, key)) {
				out.add(text);
			}
		}
	}

	/** Whether this string sits under the {@code target} key of an {@code @At}. */
	private static boolean isAtTarget(AnnotationNode annotation, String key) {
		return "target".equals(key) && annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/At;");
	}

	/**
	 * Two constructors in one refmap: the bare-name fallback is refused by design (it would be a coin toss between
	 * them), so the ONLY way left is the refmap key's own spelling. fabric-lifecycle-events-v1 is exactly this —
	 * `SynchronizeRecipesS2CPacket.<init>(Ljava/util/Collection;)V` and `SynchronizeTagsS2CPacket.<init>(Ljava/util/Map;)V`
	 * are both dotted keys named `<init>`, and both of its `@At(target=…)` selectors are written in the descriptor
	 * spelling, so before this both anchors stayed untranslated and the injectors were required losses on every
	 * subject that reached the audit (7 of 10 in the 10-subject slice).
	 */
	@Test
	void aConstructorAtTargetResolvesThroughItsOwnDottedRefmapKeyWhenTwoConstructorsShareTheName(@TempDir Path dir) throws Exception {
		Path jar = dir.resolve("probe.jar");
		writeJar(jar,
				"{\"mappings\":{\"" + MIXIN + "\":{"
						+ "\"net/minecraft/network/packet/s2c/play/SynchronizeRecipesS2CPacket.<init>(Ljava/util/Collection;)V\":"
						+ "\"Lnet/minecraft/network/protocol/game/ClientboundUpdateRecipesPacket;<init>(Ljava/util/Collection;)V\","
						+ "\"net/minecraft/network/packet/s2c/common/SynchronizeTagsS2CPacket.<init>(Ljava/util/Map;)V\":"
						+ "\"Lnet/minecraft/network/protocol/common/ClientboundUpdateTagsPacket;<init>(Ljava/util/Map;)V\"}}}",
				"Lnet/minecraft/network/packet/s2c/play/SynchronizeRecipesS2CPacket;<init>(Ljava/util/Collection;)V", "INVOKE");

		MixinNames.translate(jar, spine());

		assertEquals(List.of("Lnet/minecraft/network/protocol/game/ClientboundUpdateRecipesPacket;<init>(Ljava/util/Collection;)V"),
				atTargets(jar),
				"the refmap's own key is the dotted spelling of the very member the annotation names; the ambiguous "
						+ "bare name must not be the only path to it");
	}
}
