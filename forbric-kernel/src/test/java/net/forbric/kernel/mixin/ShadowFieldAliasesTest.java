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

package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

/**
 * The merge-renamed {@code @Shadow} field: the rule that writes an {@code aliases} entry when exactly one target
 * field of the same descriptor is left, and the SAME rule read back by {@link MixinFit}.
 *
 * <p>Measured on polymer-core 0.9.19 against the merged 1.21.1 base: {@code PacketCodecsRegistryMixin} shadows
 * {@code val$registryKey:ResourceKey}; {@code ByteBufCodecs$25} (where {@code MixinAnonymousRetarget} moved the
 * target) declares exactly one such field, {@code val$p_319942_}, and its synthetic capture is what Mixin's
 * alias check accepts. The fixtures here are the same shape, built with ASM so the rule is pinned without the
 * modding jars.
 */
class ShadowFieldAliasesTest {
	private static final String TARGET = "test/Target";
	private static final String RESOURCE_KEY = "Lnet/minecraft/resources/ResourceKey;";
	private static final String FUNCTION = "Ljava/util/function/Function;";

	/** A mixin that shadows {@code val$old:ResourceKey} on {@link #TARGET}. */
	private static byte[] mixin() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "test/TheMixin", null, "java/lang/Object", null);
		AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor targets = mixin.visitArray("targets");
		targets.visit(null, TARGET);
		targets.visitEnd();
		mixin.visitEnd();
		FieldVisitor shadow = cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "val$old", RESOURCE_KEY, null, null);
		shadow.visitAnnotation("Lorg/spongepowered/asm/mixin/Shadow;", true).visitEnd();
		shadow.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A target whose declared fields are given as {@code name:desc} pairs; capture fields are synthetic. */
	private static byte[] target(String... fields) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
		for (String field : fields) {
			int colon = field.indexOf(':');
			cw.visitField(Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, field.substring(0, colon), field.substring(colon + 1),
					null, null).visitEnd();
		}
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static Function<String, byte[]> resolver(byte[] target) {
		return name -> (TARGET + ".class").equals(name) ? target : null;
	}

	private static AnnotationNode shadow(ClassNode mixin) {
		for (FieldNode field : mixin.fields) {
			if (field.visibleAnnotations == null) continue;
			for (AnnotationNode annotation : field.visibleAnnotations) {
				if (annotation.desc.endsWith("/Shadow;")) return annotation;
			}
		}
		throw new AssertionError("the fixture has no @Shadow field");
	}

	@Test
	void aRenamedCaptureFieldTakesTheOneFieldOfItsDescriptor() {
		Function<String, byte[]> resolver = resolver(target("val$new:" + RESOURCE_KEY, "other:" + FUNCTION));
		byte[] bytes = mixin();

		// The verdict resolves the shadow through the same rule the rewrite will write: the name is gone, one
		// ResourceKey field is left.
		MixinFit.Result before = MixinFit.evaluate(bytes, resolver);
		assertEquals(MixinFit.Verdict.FIT, before.verdict(), before.unresolved().toString());

		ClassNode node = MixinFit.parse(bytes);
		assertEquals(1, ShadowFieldAliases.apply(node, resolver));
		AnnotationNode annotation = shadow(node);
		assertNotNull(MixinFit.value(annotation, "aliases"), "the rewrite must add an aliases entry");
		assertEquals("[val$new]", MixinFit.value(annotation, "aliases").toString());
	}

	@Test
	void twoFieldsOfTheDescriptorAreNotGuessed() {
		Function<String, byte[]> resolver = resolver(target("val$a:" + RESOURCE_KEY, "val$b:" + RESOURCE_KEY));

		assertEquals(MixinFit.Verdict.UNFIT, MixinFit.evaluate(mixin(), resolver).verdict(),
				"a coin flip must stay a miss");
		assertEquals(0, ShadowFieldAliases.apply(MixinFit.parse(mixin()), resolver));
	}

	@Test
	void aNameStillInTheTargetGetsNoAlias() {
		Function<String, byte[]> resolver = resolver(target("val$old:" + RESOURCE_KEY));

		assertEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(mixin(), resolver).verdict());
		assertEquals(0, ShadowFieldAliases.apply(MixinFit.parse(mixin()), resolver));
	}

	@Test
	void aNameWithADifferentDescriptorIsMixinSOwnErrorNotARename() {
		// val$old is there, typed Function; a ResourceKey capture was added beside it. An alias cannot repair a
		// descriptor clash (Mixin reports it by name), and guessing the ResourceKey field would hide it.
		Function<String, byte[]> resolver = resolver(target("val$old:" + FUNCTION, "val$new:" + RESOURCE_KEY));

		MixinFit.Result result = MixinFit.evaluate(mixin(), resolver);
		assertTrue(result.unresolved().stream().anyMatch(a -> a.contains("val$old")), result.unresolved().toString());
		assertNull(MixinFit.value(shadow(MixinFit.parse(mixin())), "aliases"));
	}

	@Test
	void theSwitchStandsDownOnBothSides() {
		System.setProperty(ShadowFieldAliases.PROPERTY, "off");
		try {
			Function<String, byte[]> resolver = resolver(target("val$new:" + RESOURCE_KEY));
			assertEquals(0, ShadowFieldAliases.apply(MixinFit.parse(mixin()), resolver));
			assertEquals(MixinFit.Verdict.UNFIT, MixinFit.evaluate(mixin(), resolver).verdict(),
					"no alias is written, so the verdict must not grant one either");
		} finally {
			System.clearProperty(ShadowFieldAliases.PROPERTY);
		}
	}
}
