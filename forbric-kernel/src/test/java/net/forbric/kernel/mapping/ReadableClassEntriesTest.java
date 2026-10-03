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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * {@link ReadableClassEntries} must be able to re-zip a jar that carries the SAME entry name twice.
 *
 * <p>Duplicate names are legal in a zip and real in a mod jar: measured on {@code CheaperGapples.jar}, which
 * carries {@code META-INF/mods.toml} and {@code META-INF/neoforge.mods.toml} twice each (plus AppleDouble
 * sidecars with the same shape). A re-pack that writes every entry it kept hit the second record and died with
 * {@code java.util.zip.ZipException: duplicate entry: META-INF/mods.toml} — so a jar the kernel's own remap path
 * has to cleanse could not be cleansed at all.
 *
 * <p>The policy asserted here is "keep the LAST occurrence", and the reason is that it is what the original jar's
 * readers already see: {@code ZipFile.getEntry}/{@code JarFile} walk the central directory in order and a later
 * record overwrites an earlier one, so the last entry of a name is the one a classloader would have loaded. The
 * first assertion below pins that premise on the input jar, so the policy cannot quietly drift away from it.
 */
class ReadableClassEntriesTest {
	private static final String DUP = "META-INF/mods.toml";
	private static final String BROKEN = "example/Broken.class";
	private static final String REAL = "example/Real.class";

	@Test
	void aJarWithADuplicateEntryNameIsCleansedRatherThanRejected(@TempDir Path dir) throws Exception {
		Path jar = dir.resolve("duplicated.jar");
		writeJar(jar);

		// The premise: the LAST record of a name is what this jar's own readers resolve.
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			assertEquals("last", text(zip, DUP), "ZipFile must resolve the last duplicate, which is the whole "
					+ "reason the cleansed copy keeps the last one");
		}

		Path readable = ReadableClassEntries.readable(jar, dir);
		assertNotNull(readable, "a jar needing a cleanse must come back as a copy, not an exception");

		try (ZipFile zip = new ZipFile(readable.toFile())) {
			assertEquals(1, zip.stream().filter(e -> DUP.equals(e.getName())).count(),
					"the copy must carry the duplicate name exactly once");
			assertEquals("last", text(zip, DUP), "the surviving record must be the one the original readers saw");
			assertNull(zip.getEntry(BROKEN), "the unreadable class is what the cleanse was for");
			assertNotNull(zip.getEntry(REAL), "and every readable class still ships");
		}
	}

	private static String text(ZipFile zip, String name) throws Exception {
		ZipEntry entry = zip.getEntry(name);
		try (InputStream in = zip.getInputStream(entry)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * A jar with a duplicated name, an unreadable {@code .class}, and one real class.
	 *
	 * <p>Written by hand rather than through {@link ZipOutputStream}, which refuses the second record for a name it
	 * already holds — the same refusal the kernel hit while cleansing. Real jars get duplicates from tools that do
	 * not refuse (macOS's {@code __MACOSX} sidecars beside their originals, a build that merged two {@code mods.toml}
	 * variants), so the fixture has to come from outside the JDK's writer. Entries are STORED: no compression to
	 * get wrong, and every offset below is then the sum of the pieces the loop already wrote.
	 */
	private static void writeJar(Path jar) throws Exception {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, REAL.substring(0, REAL.length() - 6), null, "java/lang/Object",
				null);
		writer.visitEnd();

		String[] names = {DUP, REAL, BROKEN, DUP};
		byte[][] data = {"first".getBytes(StandardCharsets.UTF_8), writer.toByteArray(), new byte[0],
				"last".getBytes(StandardCharsets.UTF_8)};
		long[] crc = new long[names.length];
		int[] offset = new int[names.length];

		ByteArrayOutputStream file = new ByteArrayOutputStream();
		for (int i = 0; i < names.length; i++) {
			CRC32 sum = new CRC32();
			sum.update(data[i]);
			crc[i] = sum.getValue();
			offset[i] = file.size();
			localHeader(file, names[i], data[i], crc[i]);
		}

		int directory = file.size();
		for (int i = 0; i < names.length; i++) {
			centralHeader(file, names[i], data[i].length, crc[i], offset[i]);
		}
		int directorySize = file.size() - directory;

		le32(file, 0x06054b50L);
		le16(file, 0);
		le16(file, 0);
		le16(file, names.length);
		le16(file, names.length);
		le32(file, directorySize);
		le32(file, directory);
		le16(file, 0);
		Files.write(jar, file.toByteArray());
	}

	private static void localHeader(ByteArrayOutputStream out, String name, byte[] data, long crc) throws Exception {
		byte[] encoded = name.getBytes(StandardCharsets.UTF_8);
		le32(out, 0x04034b50L);
		le16(out, 20);
		le16(out, 0);
		le16(out, 0);
		le16(out, 0);
		le16(out, 0x21);
		le32(out, crc);
		le32(out, data.length);
		le32(out, data.length);
		le16(out, encoded.length);
		le16(out, 0);
		out.write(encoded);
		out.write(data);
	}

	private static void centralHeader(ByteArrayOutputStream out, String name, int size, long crc, int offset)
			throws Exception {
		byte[] encoded = name.getBytes(StandardCharsets.UTF_8);
		le32(out, 0x02014b50L);
		le16(out, 20);
		le16(out, 20);
		le16(out, 0);
		le16(out, 0);
		le16(out, 0);
		le16(out, 0x21);
		le32(out, crc);
		le32(out, size);
		le32(out, size);
		le16(out, encoded.length);
		le16(out, 0);
		le16(out, 0);
		le16(out, 0);
		le16(out, 0);
		le32(out, 0);
		le32(out, offset);
		out.write(encoded);
	}

	private static void le16(ByteArrayOutputStream out, int value) {
		out.write(value & 0xFF);
		out.write((value >>> 8) & 0xFF);
	}

	private static void le32(ByteArrayOutputStream out, long value) {
		for (int i = 0; i < 4; i++) {
			out.write((int) ((value >>> (8 * i)) & 0xFF));
		}
	}
}
