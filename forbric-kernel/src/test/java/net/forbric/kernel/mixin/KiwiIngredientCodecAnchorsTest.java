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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Kiwi's ingredient codec capture: re-anchored only when the merged base proves the call it moved to.
 *
 * <p>Kiwi's handler is handed the {@code Function} argument; the merged base's {@code mapCodec} takes that same
 * argument and (in datafixerupper 8.0.16) computes exactly what {@code create} computed before its trailing
 * {@code .codec()}. The adapter is what carries that per-mixin argument, so the tests are about the guard around
 * it: the rewrite happens for the proven shape and for nothing else.
 */
class KiwiIngredientCodecAnchorsTest {
	private static final String MIXIN = "snownee/kiwi/mixin/codec/Ingredient_ItemValueMixin";
	private static final String TARGET = "net/minecraft/world/item/crafting/Ingredient$ItemValue";
	private static final String CREATE = "Lcom/mojang/serialization/codecs/RecordCodecBuilder;create"
			+ "(Ljava/util/function/Function;)Lcom/mojang/serialization/Codec;";
	private static final String MAP_CODEC = "Lcom/mojang/serialization/codecs/RecordCodecBuilder;mapCodec"
			+ "(Ljava/util/function/Function;)Lcom/mojang/serialization/MapCodec;";

	@AfterEach
	void reset() {
		System.clearProperty(KiwiIngredientCodecAnchors.PROPERTY);
	}

	/** The merged shape: mapCodec once, create never — kiwi's capture moves and the second pass has nothing to do. */
	@Test
	void theCaptureFollowsTheCallTheMergedBaseMakes() {
		ClassNode mixin = kiwiMixin();
		assertEquals(1, KiwiIngredientCodecAnchors.adapt(mixin, base(MAP_CODEC)),
				"NeoForge's shape of Ingredient$ItemValue.<clinit> is what the merged base runs");
		assertEquals(MAP_CODEC, target(mixin));
		assertEquals(0, KiwiIngredientCodecAnchors.adapt(mixin, base(MAP_CODEC)), "idempotent");
	}

	/** Forge's shape: the guest's own selector is already right, so nothing is rewritten. */
	@Test
	void aBaseThatStillMakesTheOriginalCallIsLeftAlone() {
		ClassNode mixin = kiwiMixin();
		assertEquals(0, KiwiIngredientCodecAnchors.adapt(mixin, base(CREATE)));
		assertEquals(CREATE, target(mixin));
	}

	/** No unique replacement: both calls, or two of them, is not a shape this rewrite is proven for. */
	@Test
	void aBaseWithoutOneUnambiguousReplacementIsLeftAlone() {
		ClassNode both = kiwiMixin();
		assertEquals(0, KiwiIngredientCodecAnchors.adapt(both, base(CREATE, MAP_CODEC)));
		assertEquals(CREATE, target(both));

		ClassNode twice = kiwiMixin();
		assertEquals(0, KiwiIngredientCodecAnchors.adapt(twice, base(MAP_CODEC, MAP_CODEC)));
		assertEquals(CREATE, target(twice));
	}

	/** A merged base that cannot be read is a decline with a warning, never a guess. */
	@Test
	void anUnreadableBaseIsLeftAlone() {
		ClassNode mixin = kiwiMixin();
		assertEquals(0, KiwiIngredientCodecAnchors.adapt(mixin, name -> null));
		assertEquals(CREATE, target(mixin));
	}

	/** The kill switch restores the compiled selector exactly. */
	@Test
	void withTheAdapterOffTheSelectorStaysAsKiwiWroteIt() {
		System.setProperty(KiwiIngredientCodecAnchors.PROPERTY, "off");
		ClassNode mixin = kiwiMixin();
		assertEquals(0, KiwiIngredientCodecAnchors.adapt(mixin, base(MAP_CODEC)));
		assertEquals(CREATE, target(mixin));
	}

	/** Any other mixin is answered without reading the base at all. */
	@Test
	void anotherMixinsAnchorIsNotEvenLookedAt() {
		ClassNode mixin = kiwiMixin();
		mixin.name = "net/blay09/mods/balm/mixin/FabricCropBlockMixin";
		assertEquals(0, KiwiIngredientCodecAnchors.adapt(mixin, name -> {
			throw new AssertionError("the base must not be read for a mixin this adapter does not own: " + name);
		}));
		assertEquals(CREATE, target(mixin));
	}

	/** Kiwi's mixin as it is compiled: one handler, one @ModifyArg naming create. */
	private static ClassNode kiwiMixin() {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.access = Opcodes.ACC_PUBLIC;
		mixin.name = MIXIN;
		mixin.superName = "java/lang/Object";
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "lychee$assignCodec",
				"(Ljava/util/function/Function;)Ljava/util/function/Function;", null, null);
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "remap", Boolean.FALSE, "target", CREATE));
		AnnotationNode injector = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/ModifyArg;");
		injector.values = new ArrayList<>(List.of(
				"method", new ArrayList<>(List.of("L" + TARGET + ";" + "<clinit>" + "()V")), "at", at));
		handler.visibleAnnotations = new ArrayList<>(List.of(injector));
		mixin.methods.add(handler);
		return mixin;
	}

	/** {@code Ingredient$ItemValue} as the given base has it: a {@code <clinit>} making those calls in order. */
	private static Function<String, ClassNode> base(String... calls) {
		ClassNode target = new ClassNode();
		target.version = Opcodes.V21;
		target.access = Opcodes.ACC_PUBLIC;
		target.name = TARGET;
		target.superName = "java/lang/Object";
		MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		for (String call : calls) {
			MixinFit.Member member = MixinFit.parseMember(call);
			clinit.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, member.owner(), member.name(),
					member.desc(), false));
		}
		clinit.instructions.add(new InsnNode(Opcodes.RETURN));
		target.methods.add(clinit);
		return name -> {
			assertEquals(TARGET, name, "the adapter resolves the class it declared");
			return target;
		};
	}

	private static String target(ClassNode mixin) {
		MethodNode handler = mixin.methods.getFirst();
		AnnotationNode injector = MixinFit.injectorOf(handler);
		assertNotNull(injector, "the handler keeps its injector annotation");
		return MixinFit.asString(MixinFit.value(MixinFit.atNodes(injector).getFirst(), "target"));
	}
}
