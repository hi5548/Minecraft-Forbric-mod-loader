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
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.json.JsonFormat;

import net.forbric.kernel.util.ForbricLog;

/**
 * PORT(1.21.1): translates a Fabric guest's Mixin refmap from the intermediary namespace to the named one.
 *
 * <p>A loom-built Fabric mod ships {@code <modid>-refmap.json}, and Mixin resolves every name it is given — an
 * {@code @Accessor}'s target member, an {@code @Inject(method = …)} selector, a {@code @Mixin} target — through it
 * before touching the game. The refmap's values are INTERMEDIARY (its keys are the names the mod wrote), which is
 * the runtime namespace under Fabric and is NOT the runtime namespace here.
 *
 * <p>Measured on ferrite-core 7.0.3: with the classes and the annotation strings remapped but the refmap left
 * alone, Mixin applied the mixin and then died parsing {@code @Inject} selectors with
 * <em>"@Inject annotation on cacheStateHead specifies a target class 'net/minecraft/class_4970$class_4971', which
 * is not supported"</em> — the annotation had been translated to the named target and the refmap translated it
 * straight back to intermediary. Its accessor entries are the same shape one level down
 * ({@code "xs": "field_1361:Lit/unimi/dsi/fastutil/doubles/DoubleList;"} — a member whose runtime name is
 * intermediary).
 *
 * <p><b>How a value is translated.</b> A value is a class name, a {@code name:fieldDesc} field reference, or a
 * {@code name(args)ret} method reference. The owner of a bare member reference is the mixin class's own
 * {@code @Mixin} target(s), so the targets are read out of the SAME jar's classes first; the descriptor's classes
 * are translated with the spine in the same pass. Anything that does not resolve is left exactly as written.
 *
 * <p>Runs after the remap, on its output: the classes' own names are already named by then, so a value this layer
 * cannot map is a name it does not own.
 */
public final class MixinRefmaps {
	private MixinRefmaps() {
	}

	/** Rewrites every refmap in {@code jar} in place; a jar without one, or with nothing to change, is untouched. */
	public static void translate(Path jar, ForbricMappings spine) throws IOException {
		Map<String, byte[]> entries = new HashMap<>();
		List<String> order = new ArrayList<>();

		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (Enumeration<? extends ZipEntry> it = zip.entries(); it.hasMoreElements(); ) {
				ZipEntry entry = it.nextElement();
				if (entry.isDirectory()) continue;
				try (InputStream in = zip.getInputStream(entry)) {
					entries.put(entry.getName(), in.readAllBytes());
				}
				order.add(entry.getName());
			}
		}

		Map<String, List<String>> targets = new HashMap<>();
		for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
			if (!entry.getKey().endsWith(".class")) continue;
			ClassNode node = new ClassNode();
			new ClassReader(entry.getValue()).accept(node, ClassReader.SKIP_FRAMES);
			List<String> mixinTargets = mixinTargets(node);
			if (!mixinTargets.isEmpty()) targets.put(node.name, mixinTargets);
		}

		boolean changed = false;
		for (String name : order) {
			if (!name.endsWith(".json")) continue;
			byte[] bytes = entries.get(name);

			Config root;
			try {
				root = JsonFormat.minimalInstance().createParser()
						.parse(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
			} catch (RuntimeException notJson) {
				continue;
			}

			Object mappings = root.get("mappings");
			if (!(mappings instanceof UnmodifiableConfig byMixin)) continue;

			boolean touched = false;
			for (UnmodifiableConfig.Entry mixin : byMixin.entrySet()) {
				if (!(mixin.getValue() instanceof Config refs)) continue;
				List<String> mixinTargets = targets.getOrDefault(mixin.getKey(), List.of());

				for (UnmodifiableConfig.Entry ref : refs.entrySet()) {
					if (!(ref.getValue() instanceof String value)) continue;
					String translated = translateValue(spine, mixinTargets, value);
					if (!translated.equals(value)) {
						refs.set(ref.getKey(), translated);
						touched = true;
					}
				}
			}

			if (touched) {
				entries.put(name, JsonFormat.fancyInstance().createWriter().writeToString(root)
						.getBytes(StandardCharsets.UTF_8));
				changed = true;
			}
		}

		if (!changed) return;

		Path tmp = jar.resolveSibling(jar.getFileName() + ".refmap.tmp");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
			for (String name : order) {
				out.putNextEntry(new ZipEntry(name));
				out.write(entries.get(name));
				out.closeEntry();
			}
		}
		Files.move(tmp, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		ForbricLog.debug("[Forbric/Mapping] translated %s's Mixin refmap to the named namespace", jar.getFileName());
	}

	/** A refmap value: a class name, {@code name:fieldDesc}, or {@code name(args)ret}. */
	private static String translateValue(ForbricMappings spine, List<String> mixinTargets, String value) {
		if (value.isEmpty()) return value;

		int paren = value.indexOf('(');
		if (paren > 0) {
			String name = value.substring(0, paren);
			return lookupMethod(spine, mixinTargets, name) + mapDescriptor(spine, value.substring(paren));
		}
		int colon = value.indexOf(':');
		if (colon > 0) {
			String name = value.substring(0, colon);
			return lookupField(spine, mixinTargets, name) + ":" + mapDescriptor(spine, value.substring(colon + 1));
		}

		String asClass = spine.mapClass(ForbricMappings.INTERMEDIARY, ForbricMappings.NAMED, value);
		if (!asClass.equals(value)) return asClass;

		String asField = lookupField(spine, mixinTargets, value);
		return asField.equals(value) ? lookupMethod(spine, mixinTargets, value) : asField;
	}

	private static String lookupField(ForbricMappings spine, List<String> targets, String name) {
		for (String target : targets) {
			String owner = spine.mapClass(ForbricMappings.NAMED, ForbricMappings.INTERMEDIARY, target);
			String mapped = spine.mapField(ForbricMappings.INTERMEDIARY, ForbricMappings.NAMED, owner, name, null);
			if (!mapped.equals(name)) return mapped;
		}
		return name;
	}

	private static String lookupMethod(ForbricMappings spine, List<String> targets, String name) {
		for (String target : targets) {
			String owner = spine.mapClass(ForbricMappings.NAMED, ForbricMappings.INTERMEDIARY, target);
			String mapped = spine.mapMethod(ForbricMappings.INTERMEDIARY, ForbricMappings.NAMED, owner, name, null);
			if (!mapped.equals(name)) return mapped;
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
			if ("Lorg/spongepowered/asm/mixin/Mixin;".equals(candidate.desc)) return candidate;
		}
		return null;
	}
}
