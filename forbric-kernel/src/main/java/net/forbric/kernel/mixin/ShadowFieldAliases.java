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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Gives a {@code @Shadow} field an {@code aliases} entry when the merge RENAMED the field it names: the declared
 * name is gone from the target, and exactly ONE field of the same descriptor took its place.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>{@link net.forbric.kernel.mapping.MixinShadowMembers} renames a mixin's own shadowed declarations through the
 * mapping spine (intermediary → named), which is how a {@code @Shadow field_19360} becomes
 * {@code @Shadow collisionShape}. A name the spine has never heard of is left exactly as written — and a
 * <em>synthetic</em> name is the case that matters here: {@code val$registryKey} is javac's capture of a lambda
 * local, so it is in neither namespace, while the byte-merge that renumbered the anonymous class ALSO renamed the
 * captures it kept. Measured on polymer-core 0.9.19 against the merged 1.21.1 base:
 * {@code PacketCodecsRegistryMixin} shadows {@code val$registryKey:ResourceKey}, and
 * {@code ByteBufCodecs$25} — where {@code MixinAnonymousRetarget} moved its target — declares exactly one
 * {@code ResourceKey} field, {@code val$p_319942_}. The spine cannot reach either name, so the declaration stayed
 * {@code val$registryKey}, Mixin reported the shadow unlocatable, and {@code MixinFit} read PARTIAL.
 *
 * <h2>The rule, expressed once</h2>
 *
 * <p>Mixin binds a shadow field by NAME first and then by each {@code aliases} entry, and it accepts a
 * non-private aliased field only when it is {@code ACC_SYNTHETIC} ({@code MixinPreProcessorStandard.attachFields})
 * — which a javac capture is. So the rename is expressible as an alias, and the descriptor decides the rest: with
 * the declared name absent from the whole target hierarchy, a single field of the SAME descriptor is the field the
 * mixin meant, two or more is a coin flip (the mixin's own or another class's capture), and none means the member
 * is genuinely gone (a real miss Mixin must keep reporting). {@link #aliasFor} is that rule and only that rule;
 * {@link #apply} is the rewrite that writes it into the annotation, and {@link MixinFit} reads the SAME method to
 * resolve the {@code @Shadow} anchor — so the bytes Mixin receives and the verdict about them cannot disagree.
 *
 * <p>Only the annotation changes: no instruction, frame or local is touched, and no field is added or renamed.
 *
 * <p>{@code -Dforbric.shadowFieldAliases=off} restores the previous behaviour exactly (no alias is written AND the
 * verdict stops granting one, which is the same switch on both sides for the same reason).
 */
public final class ShadowFieldAliases {
	public static final String PROPERTY = "forbric.shadowFieldAliases";
	private static final String SHADOW_DESC = "Lorg/spongepowered/asm/mixin/Shadow;";
	private static final String ALIASES = "aliases";

	private ShadowFieldAliases() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** Whether Mixin will bind {@code field} on some target through an alias this rule is about to write. */
	public static boolean binds(List<String> targets, FieldNode field, Function<String, byte[]> resolver) {
		return enabled() && !hasPrefix(field) && aliasFor(targets, field, resolver) != null;
	}

	/**
	 * The name {@code field} must alias to, or {@code null} when the rule declines.
	 *
	 * <p>Asked of EVERY target the mixin will be applied to, and the answer must be the same on each: Mixin applies
	 * one annotation set to all of them, so two targets that name different fields would make the alias right for
	 * one and wrong for the other. A target this kernel cannot read is skipped (the same "assume it fits" the rest
	 * of {@link MixinFit} uses) — it cannot agree or disagree.
	 */
	static String aliasFor(List<String> targets, FieldNode field, Function<String, byte[]> resolver) {
		String alias = null;
		for (String targetName : targets) {
			byte[] bytes = resolver.apply(targetName + ".class");
			if (bytes == null) continue;
			String candidate = candidate(field.name, field.desc, MixinFit.parse(bytes), resolver);
			if (candidate == null) continue;
			if (alias != null && !alias.equals(candidate)) return null;
			alias = candidate;
		}
		return alias;
	}

	/**
	 * The one field of {@code target}'s hierarchy whose descriptor matches and whose name is not {@code declared},
	 * when the declared name is absent everywhere; {@code null} when the name is present (a descriptor clash is
	 * Mixin's own error and an alias cannot fix it) or when the descriptor does not single one field out.
	 */
	private static String candidate(String declared, String desc, ClassNode target, Function<String, byte[]> resolver) {
		String found = null;
		for (ClassNode node : MixinFit.hierarchy(target, resolver)) {
			if (node.fields == null) continue;
			for (FieldNode field : node.fields) {
				if (field.name.equals(declared)) return null;
				if (!field.desc.equals(desc)) continue;
				if (found != null && !found.equals(field.name)) return null;
				found = field.name;
			}
		}
		return found;
	}

	/**
	 * The rewrite: adds the rule's alias to every {@code @Shadow} field that names one. Call AFTER
	 * {@link MixinAnonymousRetarget}, so {@code targets} already names the class the body landed on. Returns how many
	 * fields took an alias.
	 */
	public static int apply(ClassNode mixin, Function<String, byte[]> resolver) {
		if (!enabled() || mixin == null || mixin.fields == null || mixin.fields.isEmpty()) return 0;

		List<String> targets = MixinFit.mixinTargets(mixin);
		if (targets.isEmpty()) return 0;

		int added = 0;
		for (FieldNode field : mixin.fields) {
			AnnotationNode shadow = shadowOf(field);
			if (shadow == null || hasPrefix(shadow) || MixinFit.value(shadow, ALIASES) != null) continue;
			String alias = aliasFor(targets, field, resolver);
			if (alias == null) continue;

			if (shadow.values == null) shadow.values = new ArrayList<>(2);
			shadow.values.add(ALIASES);
			shadow.values.add(new ArrayList<>(List.of(alias)));
			added++;
			ForbricLog.info("[Forbric/Mixin] %s: @Shadow field %s no longer exists in its target; aliased to %s, the "
					+ "one field of the same descriptor the merged base declares",
					mixin.name.replace('/', '.'), field.name, alias);
		}
		return added;
	}

	private static AnnotationNode shadowOf(FieldNode field) {
		AnnotationNode shadow = annotation(field.visibleAnnotations);
		return shadow != null ? shadow : annotation(field.invisibleAnnotations);
	}

	private static AnnotationNode annotation(List<AnnotationNode> annotations) {
		if (annotations == null) return null;
		for (AnnotationNode candidate : annotations) {
			if (SHADOW_DESC.equals(candidate.desc)) return candidate;
		}
		return null;
	}

	/** Whether the {@code @Shadow} names its binding as {@code prefix + declared name}; the extension owns that. */
	private static boolean hasPrefix(FieldNode field) {
		AnnotationNode shadow = shadowOf(field);
		return shadow != null && hasPrefix(shadow);
	}

	private static boolean hasPrefix(AnnotationNode shadow) {
		return MixinFit.value(shadow, "prefix") != null;
	}
}
