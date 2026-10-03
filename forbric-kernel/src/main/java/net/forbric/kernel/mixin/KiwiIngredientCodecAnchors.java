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

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Re-anchors kiwi's two {@code Ingredient} codec handlers onto the call the merged base makes.
 *
 * <p>Kiwi captures the record-builder function each vanilla {@code Ingredient} value codec is built from, so that
 * its own ingredient dispatch can decode {@code item}/{@code tag} ingredients through it:
 *
 * <pre>
 *   private static Function&lt;…&gt; lychee$assignCodec(Function&lt;…&gt; builder) {
 *       IngredientCodecs.ITEM_VALUE_MAP_CODEC = RecordCodecBuilder.mapCodec(builder);
 *       return builder;
 *   }
 * </pre>
 *
 * <p>Its {@code @ModifyArg} names the call {@code Ingredient$ItemValue.<clinit>} made when kiwi was built against
 * this generation — {@code RecordCodecBuilder.create(Function)Codec}, which is what
 * {@code patched-mc-forge-1.21.1}'s {@code Ingredient$ItemValue.<clinit>} still calls ({@code create} →
 * {@code putstatic CODEC}). NeoForge's patch of the same method, which the byte merge kept, calls
 * {@code RecordCodecBuilder.mapCodec(Function)MapCodec} instead and projects it back with
 * {@code MAP_CODEC.codec() → CODEC}. The anchor therefore misses on the merged base, and the loss is not
 * cosmetic: {@code IngredientCodecs.VALUE_MAP_CODEC} is built by a kiwi handler that DOES attach
 * ({@code Ingredient_ValueMixin} {@code @Inject} at {@code Ingredient$Value.<clinit>} RETURN) as
 * {@code IngredientCodecs.xor(ITEM_VALUE_MAP_CODEC, TAG_VALUE_MAP_CODEC)} — i.e. {@code new XorMapCodec(null,
 * null)} — and that codec's {@code decode} dereferences its first half, so any datapack ingredient decoded
 * through kiwi's dispatch fails. Two required losses, in one subject, on the ingredient path every recipe uses.
 *
 * <p>Why {@code mapCodec} is the same call. In datafixerupper 8.0.16 — the version the merged base runs —
 * {@code RecordCodecBuilder.create(f)} IS {@code build(f.apply(instance())).codec()}, instruction for
 * instruction, and {@code mapCodec(f)} is the same expression without the trailing {@code .codec()}. Same
 * argument, same {@code instance()}, same {@code build}; the merged body even performs the {@code .codec()}
 * kiwi's capture is a means to. So the handler is handed the value it was written for.
 *
 * <p>What authorizes the rewrite, per mixin rather than per call site. The kernel's general rule for following a
 * substituted callee ({@link MixinRetarget}'s R6) is limited to {@code @Inject}, for the right reason: a handler
 * bound to the call's arguments or result sees something that may have changed meaning. Here it has not — the
 * argument is carried through unchanged, and that is argued for THIS mixin by the delegation above, which is the
 * shape {@link MergedBaseCalleeSwaps} documents for reviewed rows. It is not a census row: NeoForge did not swap
 * one call in an otherwise identical body, it rewrote the codec construction, so the census premise ("the merged
 * body is the reference body with one call exchanged") does not hold and this adapter does not pretend it does.
 *
 * <p>Abstains unless the merged method proves the rewrite: this class must be one of the two, the handler must be
 * {@code lychee$assignCodec} with the pinned descriptor carrying ONE {@code @ModifyArg} whose {@code @At(INVOKE)}
 * names exactly the {@code create} member above, the merged {@code <clinit>} must make that call ZERO times and the
 * {@code mapCodec} member EXACTLY once. A base where Forge's shape won, a newer kiwi that already anchors
 * {@code mapCodec}, or a reshaped method leaves the annotation as written — and when the class is kiwi's but the
 * proof is what failed, it says so at WARN rather than declining silently.
 *
 * <p>{@code -Dforbric.kiwiIngredientCodec=off} leaves both annotations exactly as kiwi compiled them.
 */
public final class KiwiIngredientCodecAnchors {
	/** {@code -Dforbric.kiwiIngredientCodec=off} leaves kiwi's {@code create} anchors alone. */
	public static final String PROPERTY = "forbric.kiwiIngredientCodec";

	private static final String BASE = "snownee/kiwi/mixin/codec/";
	private static final String TARGET = "net/minecraft/world/item/crafting/Ingredient$";
	/** The two codec mixins, each with the {@code Ingredient} value class whose {@code <clinit>} it captures. */
	private static final Map<String, String> MIXINS = Map.of(
			BASE + "Ingredient_ItemValueMixin", TARGET + "ItemValue",
			BASE + "Ingredient_TagValueMixin", TARGET + "TagValue");
	private static final String HANDLER = "lychee$assignCodec";
	private static final String HANDLER_DESC = "(Ljava/util/function/Function;)Ljava/util/function/Function;";
	private static final String CLINIT = "<clinit>";
	private static final String CLINIT_DESC = "()V";
	/** The call kiwi anchors on, as it was compiled: the MinecraftForge shape of both methods. */
	private static final String CREATE = "Lcom/mojang/serialization/codecs/RecordCodecBuilder;create"
			+ "(Ljava/util/function/Function;)Lcom/mojang/serialization/Codec;";
	/** The call the merged base makes instead, to the same argument, as the delegation's first half. */
	private static final String MAP_CODEC = "Lcom/mojang/serialization/codecs/RecordCodecBuilder;mapCodec"
			+ "(Ljava/util/function/Function;)Lcom/mojang/serialization/MapCodec;";

	private KiwiIngredientCodecAnchors() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/**
	 * Re-anchors {@code mixin}'s one {@code @ModifyArg} when {@code mergedBase} proves the merged method makes the
	 * {@code mapCodec} call once and the {@code create} call never; returns how many annotations were rewritten.
	 */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> mergedBase) {
		if (!enabled()) return 0;
		String target = MIXINS.get(mixin.name);
		if (target == null) return 0;

		MethodNode handler = method(mixin, HANDLER, HANDLER_DESC);
		AnnotationNode injector = handler == null ? null : MixinFit.injectorOf(handler);
		if (injector == null) return 0;
		List<AnnotationNode> points = MixinFit.atNodes(injector);
		if (points.size() != 1) return 0;
		AnnotationNode at = points.getFirst();
		if (!"INVOKE".equals(MixinFit.asString(MixinFit.value(at, "value")))
				|| !CREATE.equals(MixinFit.asString(MixinFit.value(at, "target")))) return 0;

		ClassNode merged = mergedBase.apply(target);
		MethodNode clinit = merged == null ? null : method(merged, CLINIT, CLINIT_DESC);
		if (clinit == null) {
			ForbricLog.warn("[Forbric/Kiwi] could not read %s.<clinit> from the merged base, so %s's ingredient "
					+ "codec capture stays on the call the base no longer makes — kiwi's item/tag ingredient codecs "
					+ "are built from nulls and any datapack ingredient decoded through them fails",
					target.replace('/', '.'), mixin.name.substring(mixin.name.lastIndexOf('/') + 1));
			return 0;
		}
		int create = occurrences(clinit, CREATE);
		int mapCodec = occurrences(clinit, MAP_CODEC);
		if (create != 0 || mapCodec != 1) {
			ForbricLog.warn("[Forbric/Kiwi] %s.<clinit> does not carry the shape this re-anchor is proven for "
					+ "(%d create, %d mapCodec call(s)), so %s keeps the selector it compiled", target.replace('/', '.'),
					create, mapCodec, mixin.name.substring(mixin.name.lastIndexOf('/') + 1));
			return 0;
		}

		set(at, "target", MAP_CODEC);
		ForbricLog.info("[Forbric/Kiwi] %s's ingredient codec capture now follows %s.<clinit> onto "
				+ "RecordCodecBuilder.mapCodec — NeoForge's shape of that method, and the same builder function "
				+ "create() would have taken", mixin.name.substring(mixin.name.lastIndexOf('/') + 1),
				target.replace('/', '.'));
		return 1;
	}

	private static int occurrences(MethodNode method, String member) {
		int count = 0;
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof MethodInsnNode call
					&& member.equals("L" + call.owner + ";" + call.name + call.desc)) count++;
		}
		return count;
	}

	private static MethodNode method(ClassNode node, String name, String descriptor) {
		if (node == null || node.methods == null) return null;
		for (MethodNode method : node.methods) {
			if (method.name.equals(name) && method.desc.equals(descriptor)) return method;
		}
		return null;
	}

	private static void set(AnnotationNode annotation, String key, Object value) {
		if (annotation.values == null) annotation.values = new java.util.ArrayList<>();
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
			if (key.equals(annotation.values.get(i))) {
				annotation.values.set(i + 1, value);
				return;
			}
		}
		annotation.values.add(key);
		annotation.values.add(value);
	}
}
