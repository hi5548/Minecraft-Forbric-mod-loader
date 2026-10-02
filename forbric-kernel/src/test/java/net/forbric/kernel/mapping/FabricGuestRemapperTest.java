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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the W2 remap stage against the REAL fixtures: a module of the released fabric-api build for 1.21.1,
 * which is compiled against Fabric's intermediary names, remapped with the real intermediary + Mojang mapping
 * data the installer stages.
 *
 * <p>The assertions are the ones the layer exists for, and they are read out of the constant pool rather than
 * counted as text:
 * <ul>
 *   <li>no class name in the guest's own classes is left in intermediary — a survivor does not link; and</li>
 *   <li>no reference INTO the game (a member reference whose owner the guest does not itself define) is left in
 *       intermediary — a survivor is a {@code NoSuchFieldError}/{@code NoSuchMethodError} at first use.</li>
 * </ul>
 * A synthetic fixture cannot prove either: the failure mode is a name the mapping spine does not reach, and it
 * only shows up on the real data.
 */
class FabricGuestRemapperTest {
	/** {@code class_310}: an intermediary class name. */
	private static final Pattern INTERMEDIARY_CLASS = Pattern.compile("class_\\d+");
	/** {@code method_1514} / {@code field_3835}: an intermediary member name. */
	private static final Pattern INTERMEDIARY_MEMBER = Pattern.compile("(?:method|field)_\\d+");

	@Test
	void aRealFabricGuestJarComesBackInTheRuntimeNamespace(@TempDir Path dir) throws Exception {
		FabricGuestMappings mappings = FabricGuestMappings.of(MappingFixtures.intermediary(),
				MappingFixtures.mojmap());
		Path guest = MappingFixtures.guestJar();

		assertTrue(intermediaryClasses(guest) > 0,
				"the fixture is supposed to be intermediary-mapped; without that this test proves nothing");

		List<Path> remapped = FabricGuestRemapper.remapAll(List.of(guest), mappings, dir.resolve("remap"),
				List.of(MappingFixtures.mergedBase()));

		assertEquals(1, remapped.size(), "every guest that goes in comes back out");
		Path out = remapped.get(0);
		assertNotEquals(guest, out, "the guest must be replaced by a remapped jar, not passed through");
		assertTrue(Files.isRegularFile(out), "the remapped jar must exist at " + out);

		assertEquals(0, intermediaryClasses(out),
				"a leftover intermediary class name is a Fabric class that will not link against the merged base");
		assertEquals(List.of(), untranslatedGameReferences(out),
				"every member reference into the game must be renamed; a leftover is a lookup that fails at runtime");
	}

	/**
	 * The cache is content-addressed: the same guest resolves to the same entry, while a guest whose BYTES changed
	 * gets an entry of its own rather than the stale remap.
	 */
	@Test
	void theCacheIsKeyedOnTheGuestsContent(@TempDir Path dir) throws Exception {
		FabricGuestMappings mappings = FabricGuestMappings.of(MappingFixtures.intermediary(),
				MappingFixtures.mojmap());
		Path guest = MappingFixtures.guestJar();
		List<Path> classpath = List.of(MappingFixtures.mergedBase());
		Path cache = dir.resolve("remap");

		Path first = only(FabricGuestRemapper.remapAll(List.of(guest), mappings, cache, classpath));
		Path again = only(FabricGuestRemapper.remapAll(List.of(guest), mappings, cache, classpath));

		assertEquals(first, again, "an unchanged guest must resolve to the same cache entry");
		assertEquals(1, entries(cache), "the second run must reuse the first run's entry, not add another");

		Path changed = dir.resolve(guest.getFileName().toString());
		Files.copy(guest, changed);

		try (FileSystem zip = FileSystems.newFileSystem(changed)) {
			Files.writeString(zip.getPath("/forbric-marker.txt"), "this jar is not the jar that was cached");
		}

		Path changedOut = only(FabricGuestRemapper.remapAll(List.of(changed), mappings, cache,
				List.of(MappingFixtures.mergedBase())));

		assertNotEquals(first, changedOut, "a guest whose bytes changed must not be served the old remap");
		assertEquals(2, entries(cache), "the changed guest is a second cache entry");
	}

	private static Path only(List<Path> jars) {
		assertEquals(1, jars.size());
		return jars.get(0);
	}

	private static long entries(Path cache) throws IOException {
		try (var files = Files.list(cache)) {
			return files.filter(Files::isRegularFile).count();
		}
	}

	/** How many intermediary class names the jar's own classes still carry; nested jars are not classes. */
	private static int intermediaryClasses(Path jar) throws IOException {
		int count = 0;

		for (ClassConstants constants : constants(jar).values()) {
			for (String utf8 : constants.utf8.values()) {
				if (INTERMEDIARY_CLASS.matcher(utf8).matches()) count++;
			}
		}

		return count;
	}

	/**
	 * Every member reference whose owner the jar does NOT itself define and whose member name is still
	 * intermediary — i.e. every reference into the game the stage failed to rename. Members the guest declares
	 * itself (a mixin's {@code @Shadow} field) are that name by design and are not references into the game.
	 */
	private static List<String> untranslatedGameReferences(Path jar) throws IOException {
		Set<String> defined = new HashSet<>();
		Map<String, ClassConstants> classes = constants(jar);

		for (String name : classes.keySet()) {
			if (name.endsWith(".class")) defined.add(name.substring(0, name.length() - ".class".length()));
		}

		List<String> leftovers = new ArrayList<>();

		for (ClassConstants constants : classes.values()) {
			for (int[] ref : constants.memberRefs) {
				Integer ownerIndex = constants.classes.get(ref[0]);
				int[] nameAndType = constants.nameAndTypes.get(ref[1]);
				if (ownerIndex == null || nameAndType == null) continue;

				String owner = constants.utf8.get(ownerIndex);
				String member = constants.utf8.get(nameAndType[0]);
				if (owner == null || member == null || defined.contains(owner)) continue;

				if (INTERMEDIARY_MEMBER.matcher(member).matches()) leftovers.add(owner + "." + member);
			}
		}

		Collections.sort(leftovers);
		return leftovers;
	}

	/** The constant-pool entries of every class in the jar, keyed by entry name. */
	private static Map<String, ClassConstants> constants(Path jar) throws IOException {
		Map<String, ClassConstants> perClass = new HashMap<>();

		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
				ZipEntry entry = entries.nextElement();
				if (!entry.getName().endsWith(".class")) continue;

				byte[] bytes;
				try (InputStream in = zip.getInputStream(entry)) {
					bytes = in.readAllBytes();
				}

				perClass.put(entry.getName(), ClassConstants.read(bytes));
			}
		}

		return perClass;
	}

	/**
	 * The parts of a class file's constant pool that name members: the UTF8 strings, the {@code CONSTANT_Class}
	 * and {@code CONSTANT_NameAndType} entries, and the field/method references that join them.
	 */
	private static final class ClassConstants {
		final Map<Integer, String> utf8 = new HashMap<>();
		final Map<Integer, Integer> classes = new HashMap<>();
		final Map<Integer, int[]> nameAndTypes = new HashMap<>();
		final List<int[]> memberRefs = new ArrayList<>();

		static ClassConstants read(byte[] classFile) throws IOException {
			ClassConstants out = new ClassConstants();

			try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile))) {
				in.readInt(); // magic
				in.readUnsignedShort(); // minor
				in.readUnsignedShort(); // major
				int count = in.readUnsignedShort();

				for (int i = 1; i < count; i++) {
					int tag = in.readUnsignedByte();

					switch (tag) {
						case 1 -> out.utf8.put(i, in.readUTF()); // Utf8 — readUTF IS the Constant_Utf8 layout
						case 3, 4 -> in.skipBytes(4); // Integer, Float
						case 5, 6 -> { in.skipBytes(8); i++; } // Long, Double take two slots
						case 7, 8, 16, 19, 20 -> { // Class, String, MethodType, Module, Package
							int index = in.readUnsignedShort();
							if (tag == 7) out.classes.put(i, index);
						}
						case 15 -> in.skipBytes(3); // MethodHandle
						case 9, 10, 11 -> out.memberRefs.add(new int[] {in.readUnsignedShort(),
								in.readUnsignedShort()}); // Fieldref, Methodref, InterfaceMethodref
						case 12 -> out.nameAndTypes.put(i, new int[] {in.readUnsignedShort(),
								in.readUnsignedShort()});
						case 17, 18 -> in.skipBytes(4); // Dynamic, InvokeDynamic
						default -> throw new IOException("unknown constant pool tag " + tag + " in a "
								+ classFile.length + "-byte class file");
					}
				}
			}

			return out;
		}
	}
}
