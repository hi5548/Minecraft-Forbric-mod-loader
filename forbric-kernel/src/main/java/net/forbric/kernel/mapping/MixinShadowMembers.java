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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.tinyremapper.IMappingProvider;

import net.forbric.kernel.util.ForbricLog;

/**
 * PORT(1.21.1): renames a mixin's OWN shadowed declarations — the one form of Fabric name a bytecode remapper
 * cannot reach and tiny-remapper's {@code MixinExtension} deliberately leaves alone.
 *
 * <p>A mixin's {@code @Shadow} field or {@code @Overwrite} method is not a reference into the game: it is a
 * declaration of the guest, which Mixin binds to the target's member by NAME at apply time. tiny-remapper renames
 * classes, members, descriptors and class literals, and its {@code MixinExtension} translates the annotation
 * STRINGS ({@code @Mixin(targets=…)}, {@code @Inject(method=…)}, {@code @At(target=…)}, {@code @Accessor} values);
 * for an unprefixed {@code @Shadow} the extension treats the declared name as identity — because on a genuine
 * Fabric instance that name IS the runtime name, the game running intermediary there.
 *
 * <p>Under the kernel the runtime is named, so an untouched {@code @Shadow field_19360} looks for a field the
 * merged game does not have: Mixin reports the mixin unfit, the kernel records a REQUIRED finding, and a STRICT
 * launch stops. Measured on ferrite-core 7.0.3, whose {@code BlockStateCacheMixin} was suppressed for exactly
 * {@code field_19360}/{@code field_16560}/{@code field_19429} — all three mappable through the same spine the rest
 * of the remap uses (obf {@code b}/{@code a}/{@code j} →
 * {@code collisionShape}/{@code solidRender}/{@code faceSturdy}).
 *
 * <p><b>How the name is resolved.</b> The mixin's {@code @Mixin} annotation names its target(s) in the SOURCE
 * namespace, which is exactly the owner the spine needs, so the mapped name is
 * {@code mapField/mapMethod(intermediary, named, target, name, desc)}. A member the spine cannot map is left
 * exactly as written: renaming it on a guess would be worse than the unfit mixin it already is.
 *
 * <p><b>Why a mapping layer and not a second bytecode pass.</b> {@link #withRenames} hands tiny-remapper one extra
 * entry per shadowed member, so the engine renames the declaration AND every use of it inside the mixin (which
 * shares the owner) in the same application it renames everything else. A separate post-pass would have to
 * re-implement the reference rewrite for the mixin's own {@code GETFIELD}/{@code PUTFIELD} sites and could
 * disagree with what the engine already did.
 *
 * <p>{@code @Shadow(prefix = …)} is skipped: its binding name is {@code prefix + declared name}, which the
 * extension's own prefix handling owns.
 */
public final class MixinShadowMembers {
	private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
	private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
	private static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";

	private MixinShadowMembers() {
	}

	/** One shadowed declaration the spine can resolve, and the runtime name it must take. */
	private record Rename(IMappingProvider.Member member, String runtimeName) {}

	/**
	 * {@code delegate} plus one field mapping per shadowed declaration in {@code guestJar}. Identity — the delegate
	 * itself — when the jar has no mixin that shadows anything the spine can map.
	 */
	public static IMappingProvider withRenames(IMappingProvider delegate, Path guestJar, ForbricMappings spine)
			throws IOException {
		List<Rename> renames = scan(guestJar, spine);
		if (renames.isEmpty()) return delegate;

		ForbricLog.debug("[Forbric/Mapping] %s: %d shadowed mixin member(s) take their target's runtime names",
				guestJar.getFileName(), renames.size());

		return acceptor -> {
			delegate.load(acceptor);
			for (Rename rename : renames) {
				acceptor.acceptField(rename.member(), rename.runtimeName());
			}
		};
	}

	/** Every shadowed field/overwritten method in the jar that the spine can map, with its runtime name. */
	private static List<Rename> scan(Path jar, ForbricMappings spine) throws IOException {
		List<Rename> out = new ArrayList<>();
		Map<String, Boolean> seen = new LinkedHashMap<>();

		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
				ZipEntry entry = entries.nextElement();
				if (!entry.getName().endsWith(".class")) continue;

				byte[] bytes;
				try (InputStream in = zip.getInputStream(entry)) {
					bytes = in.readAllBytes();
				}

				ClassNode node = new ClassNode();
				new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);

				List<String> targets = mixinTargets(node);
				if (targets.isEmpty()) continue;

				for (FieldNode field : node.fields) {
					if (!hasAnnotation(field.visibleAnnotations, field.invisibleAnnotations, SHADOW)
							|| hasPrefix(field.visibleAnnotations, field.invisibleAnnotations)) {
						continue;
					}
					String mapped = mapShadowedField(targets, spine, field.name);
					record(out, seen, node.name, field.name, field.desc, mapped);
				}

				for (MethodNode method : node.methods) {
					boolean shadow = hasAnnotation(method.visibleAnnotations, method.invisibleAnnotations, SHADOW);
					boolean overwrite = hasAnnotation(method.visibleAnnotations, method.invisibleAnnotations, OVERWRITE);
					if (shadow && hasPrefix(method.visibleAnnotations, method.invisibleAnnotations)) continue;
					if (!shadow && !overwrite) continue;

					String mapped = mapShadowedMethod(targets, spine, method.name);
					record(out, seen, node.name, method.name, method.desc, mapped);
				}
			}
		}

		return out;
	}

	private static void record(List<Rename> out, Map<String, Boolean> seen, String owner, String name, String desc,
			String mapped) {
		if (mapped == null) return;
		if (seen.putIfAbsent(owner + "#" + name + desc, Boolean.TRUE) != null) return;
		out.add(new Rename(new IMappingProvider.Member(owner, name, desc), mapped));
	}

	/**
	 * The runtime name of a shadowed field, or null when no target declares one the spine maps.
	 *
	 * <p>Matched by NAME, not name+descriptor: the descriptor the spine would need is the field's in its source
	 * namespace, and a mixin's {@code @Shadow} descriptor is written against whatever namespace the mod compiled its
	 * types in. Mixin itself binds a shadow by name, so the name is the binding.
	 *
	 * <p>The target is normalized to intermediary first, because the two halves of a shadow are written in
	 * DIFFERENT namespaces by the same mod: measured on ferrite-core 7.0.3,
	 * {@code @Mixin(targets = "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase$Cache")} is
	 * Mojmap — a hard-target string has to name the class the game actually runs, and this mod ships for several
	 * loaders — while the shadow it declares is {@code field_19360}, intermediary. Looking the field up with the
	 * target as written finds no class and quietly renames nothing, which is how the ferrite mixin stayed unfit.
	 */
	private static String mapShadowedField(List<String> targets, ForbricMappings spine, String name) {
		for (String target : targets) {
			String owner = spine.mapClass(ForbricMappings.NAMED, ForbricMappings.INTERMEDIARY, target);
			String mapped = spine.mapField(ForbricMappings.INTERMEDIARY, ForbricMappings.NAMED, owner, name, null);
			if (!mapped.equals(name)) return mapped;
		}
		// No target owns it: the member belongs to an ancestor of the target, or to a class the tree carries only
		// under its declaring name. Intermediary names a member once globally, so the name alone still resolves —
		// measured on the fabric-api modules (W7Harness): "@Shadow method PotionBrewing$Builder.method_59706" and
		// "@Shadow method FireBlock.method_10190" came back with their member names still intermediary while their
		// descriptors were already named, which is the same shape as the LocalPlayer.playSound case the refmap pass
		// was fixed for. Returning null when the name does not move keeps this method's contract: null means "this
		// spine cannot map it", and the declaration is left exactly as the mod wrote it.
		String byName = spine.mapMemberName(name);
		return byName.equals(name) ? null : byName;
	}

	/** The runtime name of a shadowed or overwritten method, resolved the same way for the same reasons. */
	private static String mapShadowedMethod(List<String> targets, ForbricMappings spine, String name) {
		for (String target : targets) {
			String owner = spine.mapClass(ForbricMappings.NAMED, ForbricMappings.INTERMEDIARY, target);
			String mapped = spine.mapMethod(ForbricMappings.INTERMEDIARY, ForbricMappings.NAMED, owner, name, null);
			if (!mapped.equals(name)) return mapped;
		}
		// No target owns it: the member belongs to an ancestor of the target, or to a class the tree carries only
		// under its declaring name. Intermediary names a member once globally, so the name alone still resolves —
		// measured on the fabric-api modules (W7Harness): "@Shadow method PotionBrewing$Builder.method_59706" and
		// "@Shadow method FireBlock.method_10190" came back with their member names still intermediary while their
		// descriptors were already named, which is the same shape as the LocalPlayer.playSound case the refmap pass
		// was fixed for. Returning null when the name does not move keeps this method's contract: null means "this
		// spine cannot map it", and the declaration is left exactly as the mod wrote it.
		String byName = spine.mapMemberName(name);
		return byName.equals(name) ? null : byName;
	}

	/** The mixin's targets, internal names in the SOURCE namespace: the {@code targets} strings and {@code value} classes. */
	private static List<String> mixinTargets(ClassNode node) {
		AnnotationNode annotation = annotation(node.visibleAnnotations, MIXIN);
		if (annotation == null) annotation = annotation(node.invisibleAnnotations, MIXIN);
		if (annotation == null || annotation.values == null) return List.of();

		List<String> targets = new ArrayList<>();
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
			String key = String.valueOf(annotation.values.get(i));
			if (!(annotation.values.get(i + 1) instanceof List<?> list)) continue;

			if ("targets".equals(key)) {
				for (Object item : list) {
					if (item instanceof String text) targets.add(text.replace('.', '/'));
				}
			} else if ("value".equals(key)) {
				for (Object item : list) {
					if (item instanceof Type type) targets.add(type.getInternalName());
				}
			}
		}
		return targets;
	}

	private static AnnotationNode annotation(List<AnnotationNode> annotations, String descriptor) {
		if (annotations == null) return null;
		for (AnnotationNode candidate : annotations) {
			if (descriptor.equals(candidate.desc)) return candidate;
		}
		return null;
	}

	private static boolean hasAnnotation(List<AnnotationNode> visible, List<AnnotationNode> invisible, String descriptor) {
		return annotation(visible, descriptor) != null || annotation(invisible, descriptor) != null;
	}

	/** Whether the {@code @Shadow} carries a {@code prefix}: its binding name is {@code prefix + declared name}. */
	private static boolean hasPrefix(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
		AnnotationNode shadow = annotation(visible, SHADOW);
		if (shadow == null) shadow = annotation(invisible, SHADOW);
		if (shadow == null || shadow.values == null) return false;

		for (int i = 0; i + 1 < shadow.values.size(); i += 2) {
			if ("prefix".equals(String.valueOf(shadow.values.get(i)))) return true;
		}
		return false;
	}
}
