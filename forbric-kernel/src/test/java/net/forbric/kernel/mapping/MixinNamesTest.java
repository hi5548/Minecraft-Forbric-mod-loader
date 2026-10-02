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

	/** A class target with its descriptor wrapping removed, so the two accepted spellings compare equal. */
	private static String bare(String selector) {
		return selector.startsWith("L") && selector.endsWith(";")
				? selector.substring(1, selector.length() - 1) : selector;
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

	/**
	 * An `@At(value="NEW", target="L<class>;")`: the refmap keys the class BARE and answers bare too
	 * (`net/minecraft/village/TradeOffer -> net/minecraft/world/item/trading/MerchantOffer`, verbatim from
	 * fabric-object-builder-api-v1), while the annotation wraps it as a descriptor. Neither the exact lookup nor
	 * any member path can join those, so the construction site was never found and the injector was a required
	 * loss on every subject that reached the audit (8 of 10 in the 10-subject slice).
	 */
	@Test
	void aNewTargetClassResolvesThroughItsBareRefmapKey(@TempDir Path dir) throws Exception {
		Path jar = dir.resolve("probe.jar");
		writeJar(jar,
				"{\"mappings\":{\"" + MIXIN + "\":{"
						+ "\"net/minecraft/village/TradeOffer\":\"net/minecraft/world/item/trading/MerchantOffer\"}}}",
				"Lnet/minecraft/village/TradeOffer;", "NEW");

		MixinNames.translate(jar, spine());

		// The CONTRACT is which class the anchor names. Mixin accepts a class target either bare or wrapped, and
		// which spelling comes back depends on which of the refmap's two tables answered (the per-mixin one, or the
		// flattened namespace one the real module also ships) — asserting one of them would pin an implementation
		// detail rather than the resolution. The real object-builder module comes back bare; both name MerchantOffer.
		assertEquals(List.of("net/minecraft/world/item/trading/MerchantOffer"),
				atTargets(jar).stream().map(MixinNamesTest::bare).toList(),
				"an @At(NEW) target written with the Yarn class name must name the class the merged base constructs");
	}
	/**
	 * The MEMBER half of the same asymmetry the {@code @At(NEW)} case above normalises, and the one that made a cold
	 * cache fail wholesale. fabric-lifecycle-events-v1 keys this member by its BARE name
	 * ({@code "reloadResources"}) while the annotation writes the same bare name, so the exact lookup now RESOLVES
	 * where it used to fall through — and the reply was being "reshaped" to the annotation's unwrapped spelling by
	 * stripping the descriptor's {@code L} and its return type's {@code ;}, giving
	 * {@code net/minecraft/server/MinecraftServer;reloadResources(...)…CompletableFuture}. Mixin parses that as an
	 * owner of {@code net/minecraft/server/MinecraftServer;reloadResources} and refuses the mixin with
	 * {@code invalid target descriptor: Invalid owner}.
	 *
	 * <p>Reshaping is for CLASSES (a bare class name and a wrapped one are both spellings of one class). A member
	 * selector has no such second spelling: the descriptor is what every reader accepts, and it is what the stage
	 * before the reshape emitted. Measured across a real closure: 119 of 625 injection selectors came back stripped,
	 * every module was affected, and the fabric subjects went from {@code cr=2} to {@code cr=88-90}.
	 */
	@Test
	void aBareMemberKeyKeepsItsDescriptorAndIsNotStripped(@TempDir Path dir) throws Exception {
		Path jar = dir.resolve("probe.jar");
		writeJarWithMethod(jar,
				"{\"mappings\":{\"" + MIXIN + "\":{\"reloadResources\":"
						+ "\"Lnet/minecraft/server/MinecraftServer;reloadResources(Ljava/util/Collection;)Ljava/util/concurrent/CompletableFuture;\"}}}",
				"reloadResources", "INVOKE");

		MixinNames.translate(jar, spine());

		assertEquals(List.of("Lnet/minecraft/server/MinecraftServer;reloadResources(Ljava/util/Collection;)"
						+ "Ljava/util/concurrent/CompletableFuture;"), methodSelectors(jar),
				"a resolved member selector must come back as a descriptor; stripping the owner's L and the return "
						+ "type's ; makes Mixin read the member as part of the owner");
	}

	/** One {@code @Mixin} with one {@code @Inject} whose {@code method} selector is the probe. */
	private static void writeJarWithMethod(Path jar, String refmap, String methodSelector, String atValue) throws Exception {
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
		methods.visit(null, methodSelector);
		methods.visitEnd();
		AnnotationVisitor ats = inject.visitArray("at");
		AnnotationVisitor at = ats.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
		at.visit("value", atValue);
		at.visit("target", "Lnet/minecraft/world/entity/LivingEntity;hurt()V");
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

	/** The injector's {@code method} selectors as they now stand in the jar. */
	private static List<String> methodSelectors(Path jar) throws Exception {
		List<String> out = new ArrayList<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ClassNode node = new ClassNode();
			new ClassReader(zip.getInputStream(zip.getEntry(MIXIN + ".class")).readAllBytes())
					.accept(node, ClassReader.SKIP_FRAMES);
			for (MethodNode method : node.methods) {
				for (AnnotationNode annotation : method.visibleAnnotations == null ? List.<AnnotationNode>of()
						: method.visibleAnnotations) {
					if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) continue;
					for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
						if (!"method".equals(String.valueOf(annotation.values.get(i)))) continue;
						for (Object item : (List<?>) annotation.values.get(i + 1)) out.add(String.valueOf(item));
					}
				}
			}
		}
		return out;
	}

}
