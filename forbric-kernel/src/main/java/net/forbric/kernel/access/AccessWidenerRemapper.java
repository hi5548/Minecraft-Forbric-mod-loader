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

package net.forbric.kernel.access;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import net.forbric.kernel.mapping.ForbricMappings;
import net.forbric.kernel.util.ForbricLog;

/**
 * Rewrites a Fabric mod's {@code .accesswidener}/{@code .classtweaker} from the namespace it was written in
 * (intermediary) to the one this kernel runs (Mojmap) — names AND the header.
 *
 * <p>Without this the whole {@link ClassTweakerTransformer} pass is inert. Its {@code targets} are the names as
 * written ({@code net/minecraft/class_7923}), the class visitor is asked about the runtime class
 * ({@code net/minecraft/core/registries/BuiltInRegistries}), and the library translates nothing — it has no
 * mappings. So every fabric-api widening silently missed, and the first member that a mixin actually reached
 * threw: {@code IllegalAccessError: net.minecraft.server.Bootstrap tried to access private method
 * 'void net.minecraft.core.registries.BuiltInRegistries.createContents()'} — fabric-registry-sync-v0 widens
 * exactly that member, and it stayed private because the entry named {@code class_7923 method_47487}.
 *
 * <p>A directive may also name a member on the <b>subclass</b> whose own override it means to widen, while the
 * intermediary file carries that member under its declaring superclass — the shape {@link
 * ForbricMappings#mapMemberName} exists for. Cobblemon's widener un-finals {@code LivingEntity}'s
 * {@code canBreatheUnderwater()} and {@code getDimensions(Pose)} so its {@code PokemonEntity} may override them;
 * {@code canBreatheUnderwater} ({@code method_6094}) is declared on {@code class_1309} itself, but
 * {@code getDimensions} ({@code method_18377}) is declared on {@code class_1297} (Entity). An owner-scoped
 * lookup missed the second, the directive fell through untranslated, no member of the merged base answered to
 * {@code method_18377}, the flag stayed ACC_FINAL, and the guest died at class definition:
 * {@code IncompatibleClassChangeError: PokemonEntity overrides final method
 * net.minecraft.world.entity.LivingEntity.getDimensions(Lnet/minecraft/world/entity/Pose;)...}.
 */
public final class AccessWidenerRemapper {
	private AccessWidenerRemapper() {
	}

	/** Maps one internal class name from the file's namespace to the runtime one. */
	@FunctionalInterface
	public interface Classes {
		String apply(String internalName);
	}

	/** Maps one member name; {@code method} says which table to consult. */
	@FunctionalInterface
	public interface Members {
		String apply(String owner, String name, String desc, boolean method);
	}

	/** The namespace token a rewritten file carries; the kernel's runtime namespace is Mojmap. */
	public static final String RUNTIME_NAMESPACE = "official";

	/**
	 * Rewrites {@code jar} in place: every {@code .accesswidener}/{@code .classtweaker} entry's names become the
	 * runtime ones and its header carries {@link #RUNTIME_NAMESPACE}. Any other entry is copied untouched.
	 *
	 * @return the number of entries rewritten
	 */
	public static int remap(Path jar, ForbricMappings spine) throws IOException {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
			for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
				if (entry.isDirectory()) continue;
				entries.put(entry.getName(), in.readAllBytes());
			}
		}

		Classes classes = name -> spine.mapClass(ForbricMappings.INTERMEDIARY, ForbricMappings.NAMED, name);
		Members members = (owner, name, desc, method) -> {
			String named = method
					? spine.mapMethod(ForbricMappings.INTERMEDIARY, ForbricMappings.NAMED, owner, name, desc)
					: spine.mapField(ForbricMappings.INTERMEDIARY, ForbricMappings.NAMED, owner, name, desc);
			if (!named.equals(name)) return named;

			// The tree carries an inherited member under its declaring class only: an override keeps the
			// superclass's own intermediary name and gets no row of its own, so an owner-scoped lookup misses
			// whenever a widener names the member on the subclass it means to widen (see
			// ForbricMappings#mapMemberName). Cobblemon's is the measured shape — `transitive-extendable method
			// net/minecraft/class_1309 method_18377 (Lnet/minecraft/class_4050;)Lnet/minecraft/class_4048;` names
			// LivingEntity, whose final `getDimensions(Pose)` the intermediary file declares as method_18377 on
			// class_1297 (Entity). Left as written the directive named no member of the merged base, so
			// LivingEntity.getDimensions kept ACC_FINAL and Cobblemon's PokemonEntity — which overrides it — died
			// at definition: `IncompatibleClassChangeError: PokemonEntity overrides final method`. The name alone
			// resolves it, exactly as MixinNames resolves a refmap key written against the subclass.
			return spine.mapMemberName(name);
		};

		int rewritten = 0;
		for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
			String name = entry.getKey();
			if (!isWidener(name)) continue;
			String text = new String(entry.getValue(), StandardCharsets.UTF_8);
			String remapped = remapText(text, RUNTIME_NAMESPACE, classes, members);
			if (remapped.equals(text)) continue;
			entry.setValue(remapped.getBytes(StandardCharsets.UTF_8));
			rewritten++;
		}
		if (rewritten == 0) return 0;

		Path temp = jar.resolveSibling(jar.getFileName() + ".aw");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temp))) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				out.putNextEntry(new ZipEntry(entry.getKey()));
				out.write(entry.getValue());
				out.closeEntry();
			}
		}
		Files.move(temp, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		ForbricLog.info("[Forbric/Mapping] rewrote %d access widener(s) in %s to the %s namespace",
				rewritten, jar.getFileName(), RUNTIME_NAMESPACE);
		return rewritten;
	}

	private static boolean isWidener(String entryName) {
		// Case-insensitive: the entry name is the mod's own and neither Fabric's spec nor the loader forces lower
		// case. cloth-config ships `cloth-config.accessWidener` (camelCase, v1) — a case-sensitive match left it
		// unrewritten, and being first in mod order it then set the merge namespace and disabled the other 18 files.
		String lower = entryName.toLowerCase(java.util.Locale.ROOT);
		return lower.endsWith(".accesswidener") || lower.endsWith(".classtweaker");
	}

	/**
	 * The rewritten text: the header's namespace becomes {@code targetNamespace}, and a {@code class}, {@code field}
	 * or {@code method} directive (optionally {@code transitive-} prefixed) has its owner, member name and descriptor
	 * mapped. {@code inject-interface} maps its class names; anything else (enum extension) is left alone. Lines are
	 * re-emitted tab-separated with their comment, which is the format the reader accepts.
	 */
	public static String remapText(String text, String targetNamespace, Classes classes, Members members) {
		StringBuilder out = new StringBuilder(text.length() + 64);
		for (String raw : text.split("\n", -1)) {
			int hash = raw.indexOf('#');
			String body = hash < 0 ? raw : raw.substring(0, hash);
			String comment = hash < 0 ? "" : raw.substring(hash);
			String trimmed = body.trim();
			if (trimmed.isEmpty()) {
				out.append(raw.isEmpty() ? "" : raw + "\n");
				continue;
			}

			String[] tokens = trimmed.split("\\s+");
			if ("accessWidener".equals(tokens[0])) {
				if (tokens.length >= 3) tokens[2] = targetNamespace;
			} else if ("inject-interface".equals(tokens[0]) && tokens.length >= 3) {
				int at = "named".equals(tokens[1]) ? 2 : 1;
				if (tokens.length >= at + 2) {
					tokens[at] = classes.apply(tokens[at]);
					tokens[at + 1] = classes.apply(tokens[at + 1]);
				}
			} else if (!"extend-enum".equals(tokens[0]) && !"add-enum-field".equals(tokens[0])) {
				// The grammar is `<modifier> class|field|method …`, the modifier optionally `transitive-` prefixed.
				String kind = tokens.length > 1 ? tokens[1] : "";
				if ("class".equals(kind) && tokens.length >= 3) {
					tokens[2] = classes.apply(tokens[2]);
				} else if (("field".equals(kind) || "method".equals(kind)) && tokens.length >= 5) {
					String owner = tokens[2];
					tokens[2] = classes.apply(owner);
					tokens[3] = members.apply(owner, tokens[3], tokens[4], "method".equals(kind));
					tokens[4] = mapDescriptor(tokens[4], classes);
				}
			}
			out.append(String.join("\t", tokens)).append(comment.isEmpty() ? "" : "\t" + comment).append('\n');
		}
		return out.toString();
	}

	/** Maps every class name inside a descriptor, leaving primitives and arities alone. */
	private static String mapDescriptor(String descriptor, Classes classes) {
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
			out.append('L').append(classes.apply(descriptor.substring(i + 1, end))).append(';');
			i = end + 1;
		}
		return out.toString();
	}
}
