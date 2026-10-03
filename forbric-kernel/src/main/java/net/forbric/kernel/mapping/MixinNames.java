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
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.json.JsonFormat;

import net.forbric.kernel.util.ForbricLog;

/**
 * PORT(1.21.1): every name a guest's Mixin reads that is NOT a bytecode reference — its annotation
 * selector strings and its refmap — translated from the intermediary namespace to the named one.
 *
 * <p>tiny-remapper renames classes, members, descriptors and class literals; its {@code MixinExtension}
 * translates several annotation strings. What survives both is the pair measured on the corpus:
 *
 * <ul>
 *   <li><b>Owner-qualified selectors.</b> {@code @ModifyArg(method = "Lnet/minecraft/class_1297;playSound(…)…")}:
 *       the extension leaves the owner as written (sound-physics-remastered), so Mixin parses a selector naming a
 *       class the merged base does not have — <em>"@ModifyArg annotation on playSound specifies a target class
 *       'net/minecraft/class_1297', which is not supported"</em> — and the mixin is left out.</li>
 *   <li><b>Refmap values.</b> Mixin resolves every name through the mod's refmap FIRST, and a Fabric refmap's
 *       values are intermediary. Measured on ferrite-core 7.0.3: with the annotation already translated to the
 *       named target, the refmap translated it straight back, and the mixin died the same way.</li>
 * </ul>
 *
 * <p>Both live in the same two files, so one pass rewrites both and the jar is written once.
 *
 * <p><b>Selector grammar.</b> Mixin's member reference is {@code [owner;]name[(args)ret]} for a method and
 * {@code [owner;]name[:fieldDesc]} for a field, with an optional {@code L} before the owner. The owner is
 * normalized to intermediary for the lookup and re-emitted in the named namespace; the descriptor's classes are
 * translated with the spine in the same step; a bare name falls back to the mixin's own targets as the owner.
 * Anything that does not resolve is left exactly as written — a rewrite on a guess is worse than the selector the
 * mod wrote.
 *
 * <p>Runs after the remap, on its output: the classes' own names are named by then, so an unresolvable name is one
 * this layer does not own.
 */
public final class MixinNames {
	private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

	/** Annotation → the string keys that carry member references (or classes, for {@code @Mixin}). */
	private static final Map<String, Set<String>> SELECTOR_KEYS = Map.of(
			"Lorg/spongepowered/asm/mixin/Mixin;", Set.of("targets"),
			"Lorg/spongepowered/asm/mixin/injection/Inject;", Set.of("method"),
			"Lorg/spongepowered/asm/mixin/injection/Redirect;", Set.of("method"),
			"Lorg/spongepowered/asm/mixin/injection/ModifyArg;", Set.of("method"),
			"Lorg/spongepowered/asm/mixin/injection/ModifyArgs;", Set.of("method"),
			"Lorg/spongepowered/asm/mixin/injection/ModifyConstant;", Set.of("method"),
			"Lorg/spongepowered/asm/mixin/injection/ModifyVariable;", Set.of("method"),
			"Lorg/spongepowered/asm/mixin/injection/At;", Set.of("target", "value"),
			"Lorg/spongepowered/asm/mixin/gen/Accessor;", Set.of("value"),
			"Lorg/spongepowered/asm/mixin/gen/Invoker;", Set.of("value"));

	private MixinNames() {
	}

	/** Rewrites the selectors and the refmap in {@code jar} in place; a jar with neither is untouched. */
	public static void translate(Path jar, ForbricMappings spine) throws IOException {
		Map<String, byte[]> entries = new LinkedHashMap<>();

		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (Enumeration<? extends ZipEntry> it = zip.entries(); it.hasMoreElements(); ) {
				ZipEntry entry = it.nextElement();
				if (entry.isDirectory()) continue;
				try (InputStream in = zip.getInputStream(entry)) {
					entries.put(entry.getName(), in.readAllBytes());
				}
			}
		}

		// The mixin's targets, by class name, as a fallback owner for selectors that name no owner of their own.
		Map<String, List<String>> targets = new HashMap<>();
		for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
			if (!entry.getKey().endsWith(".class")) continue;
			ClassNode node = new ClassNode();
			new ClassReader(entry.getValue()).accept(node, ClassReader.SKIP_FRAMES);
			List<String> mixinTargets = mixinTargets(node);
			if (!mixinTargets.isEmpty()) targets.put(node.name, mixinTargets);
		}

		boolean changed = false;
		for (Map.Entry<String, byte[]> entry : new ArrayList<>(entries.entrySet())) {
			String name = entry.getKey();
			if (name.endsWith(".class")) {
				byte[] rewritten = translateSelectors(entry.getValue(), spine, targets);
				if (rewritten != null) {
					entries.put(name, rewritten);
					changed = true;
				}
			} else if (name.endsWith(".json")) {
				byte[] rewritten = translateRefmap(entry.getValue(), spine, targets);
				if (rewritten != null) {
					entries.put(name, rewritten);
					changed = true;
				}
			}
		}

		if (!changed) return;

		Path tmp = jar.resolveSibling(jar.getFileName() + ".names.tmp");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				out.putNextEntry(new ZipEntry(entry.getKey()));
				out.write(entry.getValue());
				out.closeEntry();
			}
		}
		Files.move(tmp, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		ForbricLog.debug("[Forbric/Mapping] translated %s's Mixin selector strings and refmap to the named namespace",
				jar.getFileName());
	}

	// --- annotation selector strings -------------------------------------------------------------------------

	/** The class with its selector strings translated, or null when nothing changed. */
	private static byte[] translateSelectors(byte[] bytes, ForbricMappings spine, Map<String, List<String>> targets) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
		List<String> mixinTargets = targets.getOrDefault(node.name, List.of());
		boolean[] touched = {false};

		walk(node.visibleAnnotations, mixinTargets, spine, touched);
		walk(node.invisibleAnnotations, mixinTargets, spine, touched);
		for (FieldNode field : node.fields) {
			walk(field.visibleAnnotations, mixinTargets, spine, touched);
			walk(field.invisibleAnnotations, mixinTargets, spine, touched);
		}
		for (MethodNode method : node.methods) {
			walk(method.visibleAnnotations, mixinTargets, spine, touched);
			walk(method.invisibleAnnotations, mixinTargets, spine, touched);
		}
		if (!touched[0]) return null;

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static void walk(List<AnnotationNode> annotations, List<String> mixinTargets, ForbricMappings spine,
			boolean[] touched) {
		if (annotations == null) return;
		for (AnnotationNode annotation : annotations) {
			Set<String> keys = SELECTOR_KEYS.get(annotation.desc);
			if (annotation.values == null) continue;

			for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
				String key = String.valueOf(annotation.values.get(i));
				Object value = annotation.values.get(i + 1);

				// Nested annotations and arrays always get walked: @Inject(at = @At(…)) is the common shape.
				if (value instanceof AnnotationNode nested) {
					walk(List.of(nested), mixinTargets, spine, touched);
					continue;
				}
				if (value instanceof List<?> items) {
					List<Object> translatedList = new ArrayList<>(items.size());
					boolean listTouched = false;
					for (Object item : items) {
						if (item instanceof AnnotationNode nested) {
							walk(List.of(nested), mixinTargets, spine, touched);
							translatedList.add(nested);
						} else if (keys != null && keys.contains(key) && item instanceof String text) {
							String translated = translateSelector(spine, mixinTargets, text);
							listTouched |= !translated.equals(text);
							translatedList.add(translated);
						} else {
							translatedList.add(item);
						}
					}
					if (listTouched) {
						annotation.values.set(i + 1, translatedList);
						touched[0] = true;
					}
					continue;
				}
				if (keys == null || !keys.contains(key) || !(value instanceof String text)) continue;

				String translated = translateSelector(spine, mixinTargets, text);
				if (!translated.equals(text)) {
					annotation.values.set(i + 1, translated);
					touched[0] = true;
				}
			}
		}
	}

	/**
	 * One selector string, translated. Anything that does not parse as a member reference (an {@code @At} constant
	 * such as {@code HEAD}, a slice name) or that the spine cannot map comes back unchanged.
	 */
	private static String translateSelector(ForbricMappings spine, List<String> mixinTargets, String value) {
		if (value.isEmpty()) return value;

		// A bare value that carries a package path but no member syntax is a CLASS name, not a member: Fabric's
		// refmaps carry those for anonymous inner classes, where the entry reads
		// "<named inner class>": "net/minecraft/class_8197$class_5305$1". Reading it as a member name resolved
		// nothing and the entry kept naming an intermediary class — measured as the LAST two of 94 leftovers on
		// fabric-biome-api-v1. A member name never contains '/': it is either a plain name or owner-qualified with
		// ';', so this cannot capture one.
		if (!value.contains("(") && !value.contains(";") && !value.contains(":") && value.indexOf('/') >= 0) {
			String mapped = mapClassName(spine, value);
			return mapped.equals(value) ? value : mapped;
		}

		String owner = "";
		String rest = value;
		int semi = value.indexOf(';');
		int paren = value.indexOf('(');
		int firstColon = value.indexOf(':');
		// A ';' opens the owner ONLY when it comes before the member name. In a field selector the ';' belongs to
		// the descriptor (`field_1655:Lnet/minecraft/class_3675$class_306;`), and reading it as the owner separator
		// left the name empty and the whole entry untranslated — measured as Cobblemon's 60 leftovers, 32 of them
		// this shape, with the class it names long since remapped.
		if (semi >= 0 && (paren < 0 || semi < paren) && (firstColon < 0 || semi < firstColon)) {
			owner = value.substring(0, semi);
			rest = value.substring(semi + 1);
		}

		String name = rest;
		String desc = "";
		int open = rest.indexOf('(');
		int colon = rest.indexOf(':');
		if (open > 0) {
			name = rest.substring(0, open);
			desc = rest.substring(open);
		} else if (colon > 0) {
			name = rest.substring(0, colon);
			desc = rest.substring(colon);
		}

		// A bare word with no owner, descriptor or colon is a constant (HEAD, RETURN, …) or a name with no
		// structure; only the ownerless-but-unambiguously-a-member forms are worth a lookup.
		if (owner.isEmpty() && desc.isEmpty() && !isIntermediaryName(name) && !name.isEmpty()
				&& Character.isUpperCase(name.charAt(0))) {
			return value;
		}

		String internal = owner.isEmpty() ? "" : owner.startsWith("L") ? owner.substring(1) : owner;
		String intermediaryOwner = internal;
		if (!internal.isEmpty()) {
			String mapped = spine.mapClass(ForbricMappings.NAMED, ForbricMappings.INTERMEDIARY, internal);
			if (!mapped.equals(internal)) intermediaryOwner = mapped;
		}
		List<String> owners = internal.isEmpty() ? mixinTargets : List.of(intermediaryOwner);

		String mappedName = name;
		String namedOwner = internal;
		for (String candidate : owners) {
			String normalized = spine.mapClass(ForbricMappings.NAMED, ForbricMappings.INTERMEDIARY, candidate);
			String method = spine.mapMethod(ForbricMappings.INTERMEDIARY, ForbricMappings.NAMED, normalized, name, null);
			String field = spine.mapField(ForbricMappings.INTERMEDIARY, ForbricMappings.NAMED, normalized, name, null);
			String resolved = !method.equals(name) ? method : field;
			if (!resolved.equals(name)) {
				mappedName = resolved;
				if (internal.isEmpty()) namedOwner = spine.mapClass(ForbricMappings.INTERMEDIARY, ForbricMappings.NAMED, normalized);
				break;
			}
		}
		if (mappedName.equals(name)) {
			// The tree carries no member by this name under this owner — an override the obfuscated jar inherits
			// under its superclass's own name (see ForbricMappings#mapMemberName). The name alone is still
			// unambiguous, and the owner is the class the mixin really targets.
			mappedName = spine.mapMemberName(name);
		}

		// The owner is translated on its own, NOT as a side effect of resolving the member. A constructor or a
		// <clinit> is renamed by no mapping at all, so an entry naming one resolves nothing and — before this —
		// came back untouched, owner included. Measured on fabric-biome-api-v1 (W7Harness, same day): the remapped
		// class was clean while its refmap still held `"<clinit>": "Lnet/minecraft/class_2169;<clinit>()V"` and
		// eleven more intermediary class names, and Mixin reads the refmap before it reads the game, so the mixin
		// stopped applying with "failed to apply ... (InvalidMixinException)". The member name and the descriptor
		// keep their own rules; only the early return was wrong.
		if (!internal.isEmpty()) {
			namedOwner = spine.mapClass(ForbricMappings.INTERMEDIARY, ForbricMappings.NAMED, internal);
		}

		String mappedDesc = desc.isEmpty() ? "" : mapDescriptor(spine, desc);
		if (mappedName.equals(name) && mappedDesc.equals(desc) && namedOwner.equals(internal)) return value;

		String prefix = internal.isEmpty() ? "" : (owner.startsWith("L") ? "L" + namedOwner + ";" : namedOwner + ";");
		return prefix + mappedName + mappedDesc;
	}

	private static boolean isIntermediaryName(String name) {
		return name.startsWith("method_") || name.startsWith("field_") || name.startsWith("class_");
	}

	// --- the refmap ------------------------------------------------------------------------------------------

	/** The refmap with its values translated, or null when nothing changed. */
	private static byte[] translateRefmap(byte[] bytes, ForbricMappings spine, Map<String, List<String>> targets) {
		Config root;
		try {
			root = JsonFormat.minimalInstance().createParser()
					.parse(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
		} catch (RuntimeException notJson) {
			return null;
		}

		// BOTH sections, and the second one is the easy one to miss. A Fabric refmap carries "mappings" — the
		// per-mixin member table — and "data", whose "named:intermediary" table is the flattened lookup Mixin
		// itself uses. This pass once rewrote only "mappings", and the measured shape of that mistake is
		// distinctive: every symbol survived in exactly HALF its occurrences. Measured on fabric-biome-api-v1
		// (W7Harness's evidence): class_2169 10 -> 5, method_46680 2 -> 1 after the owner fix, with the "data" half
		// still naming untranslated intermediary classes — which is what Mixin reads, so the mixin still stopped
		// applying.
		boolean touched = translateRefmapSection(root.get("mappings"), spine, targets);

		Object data = root.get("data");
		if (data instanceof UnmodifiableConfig sections) {
			for (UnmodifiableConfig.Entry section : new ArrayList<>(sections.entrySet())) {
				if (section.getValue() instanceof Config) {
					touched |= translateRefmapSection(section.getValue(), spine, targets);
				}
			}
		}
		if (!touched) return null;

		return JsonFormat.fancyInstance().createWriter().writeToString(root).getBytes(StandardCharsets.UTF_8);
	}

	/** One refmap section (mixIn -> member -> selector), with its values translated. True when anything changed. */
	private static boolean translateRefmapSection(Object section, ForbricMappings spine,
			Map<String, List<String>> targets) {
		if (!(section instanceof UnmodifiableConfig byMixin)) return false;

		boolean touched = false;
		// Both levels are read into a snapshot before anything is written. night-config's entrySet() is a LIVE view,
		// and a set() that takes a path key can add children to the node being iterated: measured, a set() inside the
		// inner loop killed the boot on every Fabric guest whose refmap had a translatable value as
		// "java.util.ConcurrentModificationException" out of MixinNames -> FabricGuestRemapper.remapAll.
		List<UnmodifiableConfig.Entry> mixins = new ArrayList<>(byMixin.entrySet());

		for (UnmodifiableConfig.Entry mixin : mixins) {
			if (!(mixin.getValue() instanceof Config refs)) continue;
			List<String> mixinTargets = targets.getOrDefault(mixin.getKey(), List.of());

			List<Map.Entry<String, String>> changes = new ArrayList<>();
			for (UnmodifiableConfig.Entry ref : refs.entrySet()) {
				if (!(ref.getValue() instanceof String value)) continue;
				String translated = translateSelector(spine, mixinTargets, value);
				if (!translated.equals(value)) changes.add(Map.entry(ref.getKey(), translated));
			}

			for (Map.Entry<String, String> change : changes) {
				// set(List.of(key), ...), NOT set(key, ...): night-config's String overload parses its argument as a
				// PATH, splitting on '.', and a refmap key is often dotted (a class-qualified selector). It then
				// tries to descend through an existing String leaf and throws
				// "IncompatibleIntermediaryLevelException: Cannot add an element to an intermediary value of type:
				// class java.lang.String" — measured, it killed the boot during remap for every Fabric guest whose
				// refmap had a dotted key, which is why those subjects were reported not-discovered. One path element
				// is the literal key.
				refs.set(List.of(change.getKey()), change.getValue());
				touched = true;
			}
		}
		return touched;
	}


	/**
	 * A class name, including the anonymous-inner suffix no mapping carries: the longest mapped prefix is mapped and
	 * the rest is kept, so {@code net/minecraft/class_8197$class_5305$1} becomes
	 * {@code net/minecraft/world/biome/source/MultiNoiseBiomeSourceParameterList$Preset$1}. Anonymous classes have no
	 * mapping entry of their own — they are named by position — so the whole-name lookup cannot answer for them.
	 */
	private static String mapClassName(ForbricMappings spine, String name) {
		String mapped = spine.mapClass(ForbricMappings.INTERMEDIARY, ForbricMappings.NAMED, name);
		if (!mapped.equals(name)) return mapped;

		for (int at = name.lastIndexOf('$'); at > 0; at = name.lastIndexOf('$', at - 1)) {
			String prefix = name.substring(0, at);
			String mappedPrefix = spine.mapClass(ForbricMappings.INTERMEDIARY, ForbricMappings.NAMED, prefix);
			if (!mappedPrefix.equals(prefix)) return mappedPrefix + name.substring(at);
		}
		return name;
	}

	/** Maps every class name inside a descriptor, leaving primitives and arities alone. */
	private static String mapDescriptor(ForbricMappings spine, String descriptor) {
		StringBuilder out = new StringBuilder(descriptor.length());
		int i = 0;
		while (i < descriptor.length()) {
			if (descriptor.charAt(i) != 'L') {
				out.append(descriptor.charAt(i++));
				continue;
			}

			int end = descriptor.indexOf(';', i);
			if (end < 0) {
				out.append(descriptor.substring(i));
				break;
			}
			out.append('L')
					.append(spine.mapClass(ForbricMappings.INTERMEDIARY, ForbricMappings.NAMED,
							descriptor.substring(i + 1, end)))
					.append(';');
			i = end + 1;
		}
		return out.toString();
	}

	/** The mixin's targets as internal names: the {@code targets} strings and {@code value} classes. */
	private static List<String> mixinTargets(ClassNode node) {
		AnnotationNode annotation = annotation(node.visibleAnnotations);
		if (annotation == null) annotation = annotation(node.invisibleAnnotations);
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

	private static AnnotationNode annotation(List<AnnotationNode> annotations) {
		if (annotations == null) return null;
		for (AnnotationNode candidate : annotations) {
			if (MIXIN.equals(candidate.desc)) return candidate;
		}
		return null;
	}
}
