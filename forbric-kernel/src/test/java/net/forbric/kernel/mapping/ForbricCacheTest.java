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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForbricCacheTest {
	@Test
	void keyIsStableAndCollisionResistant() throws Exception {
		assertEquals(ForbricCache.key("a", "b"), ForbricCache.key("a", "b"));
		assertNotEquals(ForbricCache.key("a", "b"), ForbricCache.key("ab"));
		assertNotEquals(ForbricCache.key("a", "b"), ForbricCache.key("b", "a"));
	}

	@Test
	void keyDependsOnFileContents(@TempDir Path dir) throws Exception {
		Path a = Files.writeString(dir.resolve("a.bin"), "hello");
		Path b = Files.writeString(dir.resolve("b.bin"), "world");

		assertNotEquals(ForbricCache.key("1.21.11", a), ForbricCache.key("1.21.11", b));
		assertEquals(ForbricCache.sha256(a), ForbricCache.sha256(a));
	}

	@Test
	void resolveAndIsCached(@TempDir Path dir) throws Exception {
		ForbricCache cache = new ForbricCache(dir.resolve("cache"));
		String key = ForbricCache.key("mymod", "1.0");
		Path out = cache.resolve("mymod", key, ".jar");

		assertTrue(out.startsWith(cache.dir()));
		assertFalse(ForbricCache.isCached(out), "nothing is there yet");

		// A stub that is a regular file but not a usable jar — a remap killed mid-write leaves one — must NOT read
		// as a hit: isCached validates the zip, because such a stub on the classpath died in Mixin's Config.create.
		Files.writeString(out, "x");
		assertFalse(ForbricCache.isCached(out), "a non-jar stub must not read as a cache hit");

		writeOneEntryJar(out);
		assertTrue(ForbricCache.isCached(out));
	}

	private static void writeOneEntryJar(Path file) throws Exception {
		try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
			out.putNextEntry(new java.util.zip.ZipEntry("marker"));
			out.write(new byte[] { 1 });
			out.closeEntry();
		}
	}
}
