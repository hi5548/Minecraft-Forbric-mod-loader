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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * A small content-addressed cache for the (expensive) derived artifacts Forbric builds at boot — the Mojmap
 * game jar and each remapped+wrapped Forge mod jar. Cache file names embed a SHA-256 key over the inputs
 * (MC version + mappings hash + source jar hash), so an unchanged input is a hit and is never rebuilt.
 */
public final class ForbricCache {
	private final Path dir;

	public ForbricCache(Path dir) throws IOException {
		Files.createDirectories(dir);
		this.dir = dir;
	}

	public Path dir() {
		return dir;
	}

	/** The cache file for the given base name and key, e.g. {@code resolve("mymod", key, ".jar")}. */
	public Path resolve(String baseName, String key, String suffix) {
		return dir.resolve(baseName + "-" + key.substring(0, Math.min(16, key.length())) + suffix);
	}

	/**
	 * Whether {@code file} is a usable cache entry: a regular file AND a jar that actually has entries.
	 *
	 * <p>{@code Files.isRegularFile} alone is not enough, and the gap is not theoretical. {@code remapJar} writes to
	 * the final path (see {@link ForgeModRemapper#remapJar}), so a remap interrupted by a kill — a killed run, a
	 * machine under load, a window closed early — leaves a stub there, and a stub is a regular file. Measured on
	 * 2026-10-03: {@code fabric-object-builder-api-v1-0.116.17-…jar} at <b>22 bytes</b> in a warm shared cache, an
	 * empty zip (EOCD only). Every later boot on that cache read it as a hit, put the stub on the owned classpath,
	 * and died in Mixin's own {@code Config.create} with
	 * "The specified resource 'fabric-object-builder-v1.mixins.json' was invalid or could not be read" — a fatal
	 * {@code MixinInitialisationError} that names a mixin config, so it reads as a config problem or a regression in
	 * whichever kernel was current, never as a cache problem. The empty zip is also why it survived: a zero-entry
	 * jar is VALID, so the signing-metadata pass rewrites it happily and the file keeps its place.
	 *
	 * <p>Rejecting the entry sends the caller down its remap path, which rewrites the file from scratch — so a
	 * poisoned cache now heals itself instead of poisoning every run until someone deletes it by hand.
	 */
	public static boolean isCached(Path file) {
		if (!Files.isRegularFile(file)) return false;

		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file.toFile())) {
			return zip.size() > 0;
		} catch (IOException unreadable) {
			return false;
		}
	}

	/** Hex SHA-256 over the concatenation of the given files' contents and string tokens (order matters). */
	public static String key(Object... parts) throws IOException {
		MessageDigest digest = sha256();

		for (Object part : parts) {
			if (part instanceof Path) {
				hashFile(digest, (Path) part);
			} else {
				digest.update(String.valueOf(part).getBytes(StandardCharsets.UTF_8));
			}
			digest.update((byte) 0); // separator so (a,b) != (ab)
		}

		return toHex(digest.digest());
	}

	public static String sha256(Path file) throws IOException {
		MessageDigest digest = sha256();
		hashFile(digest, file);
		return toHex(digest.digest());
	}

	private static void hashFile(MessageDigest digest, Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			byte[] buffer = new byte[1 << 16];
			int read;
			while ((read = in.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
			}
		}
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	private static String toHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			sb.append(Character.forDigit((b >> 4) & 0xF, 16));
			sb.append(Character.forDigit(b & 0xF, 16));
		}
		return sb.toString();
	}
}
