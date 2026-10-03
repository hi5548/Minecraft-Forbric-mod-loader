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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * The one place the remap path decides whether an entry NAMED {@code .class} is a class the engine may read.
 *
 * <p>The remap of a guest jar is a chain of passes that each parse every {@code .class} entry: {@link
 * MixinShadowMembers}'s shadow scan, then tiny-remapper itself, then {@link MixinNames}, {@link
 * InheritedMemberRefs} and {@link InheritedMemberDecls}. A single entry those passes cannot parse — an empty file,
 * a truncated one, a resource misnamed {@code .class} — used to throw out of the FIRST pass that touched it and
 * end the boot for the whole subject, on a mod that was already broken in a way this kernel cannot repair. The
 * passes did not validate their input, so the failure was theirs to survive.
 *
 * <p>Measured 2026-10-03 on CheaperGapples.jar: {@code IllegalArgumentException: null} out of
 * {@code ClassReader.<init>} at {@link MixinShadowMembers}'s scan killed the subject's remap before anything was
 * renamed. Guarding that one read only moved the same entry one step down — tiny-remapper's own
 * {@code error analyzing <entry> from <jar>} is the next death — so the jar the engine is handed is cleansed ONCE,
 * here, and every pass behind sees only readable classes.
 *
 * <p>A dropped entry is named, counted, and once: a mod that silently loses a class is worse than one that says
 * which. The cleansed copy is a temp file the caller deletes; a jar with nothing to drop is returned as itself,
 * so the ordinary path does no extra I/O.
 */
final class ReadableClassEntries {
	private ReadableClassEntries() {
	}

	/** {@code bytes} parsed with {@code flags}, or {@code null} when ASM cannot read it as a class. */
	static ClassNode parse(byte[] bytes, int flags) {
		ClassNode node = new ClassNode();
		try {
			new ClassReader(bytes).accept(node, flags);
			return node;
		} catch (RuntimeException notAClass) {
			return null;
		}
	}

	/** Whether {@code bytes} is a class ASM can read. */
	static boolean readable(byte[] bytes) {
		return parse(bytes, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG) != null;
	}

	/**
	 * {@code jar} as the remap engine may read it: {@code jar} itself when every {@code .class} entry parses, else a
	 * temp copy under {@code workDir} with the entries that do not removed. The caller deletes the copy.
	 */
	static Path readable(Path jar, Path workDir) throws IOException {
		List<String> unreadable = unreadable(jar);
		if (unreadable.isEmpty()) return jar;

		ForbricLog.warn("[Forbric/Mapping] %s: dropping %d unreadable .class entry(ies) before the remap — not a class "
				+ "this kernel can parse, and the engine dies on them; every other class is remapped as usual: %s",
				jar.getFileName(), unreadable.size(), String.join(", ", unreadable));

		Set<String> drop = new HashSet<>(unreadable);
		Path out = Files.createTempFile(workDir, stem(jar) + "-readable", ".jar");
		try (ZipFile zip = new ZipFile(jar.toFile());
				ZipOutputStream sink = new ZipOutputStream(Files.newOutputStream(out))) {
			for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
				ZipEntry entry = entries.nextElement();
				if (drop.contains(entry.getName())) continue;
				sink.putNextEntry(new ZipEntry(entry.getName()));
				try (InputStream in = zip.getInputStream(entry)) {
					in.transferTo(sink);
				}
				sink.closeEntry();
			}
		}
		return out;
	}

	/** The {@code .class} entries of {@code jar} ASM cannot parse, in jar order. */
	private static List<String> unreadable(Path jar) throws IOException {
		List<String> bad = new ArrayList<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
				ZipEntry entry = entries.nextElement();
				if (!entry.getName().endsWith(".class")) continue;
				byte[] bytes;
				try (InputStream in = zip.getInputStream(entry)) {
					bytes = in.readAllBytes();
				}
				if (!readable(bytes)) bad.add(entry.getName());
			}
		}
		return bad;
	}

	private static String stem(Path jar) {
		String name = jar.getFileName().toString();
		return name.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
	}
}
