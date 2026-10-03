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

package net.forbric.kernel.util;

import java.nio.charset.StandardCharsets;

/**
 * Looks for ASCII needles in raw class bytes, without parsing or copying them.
 *
 * <h2>Why this exists as a shared thing</h2>
 *
 * <p>Several transformers run for EVERY class the game loads and want the same cheap first question: does this
 * class even mention the name I care about? A class file stores type and method names in its constant pool as
 * modified UTF-8, and every name these transformers look for is plain ASCII, so it appears in the bytes
 * verbatim. A "no" is therefore final, not a guess — the class provably does not name it.
 *
 * <p>Three copies of this had grown, each slightly different, and one of them turned the whole class into a
 * {@code String} first: an allocation the size of the class, for every class, to ask a question that needs none.
 */
public final class ByteScan {

	private ByteScan() {
	}

	/** The bytes of an ASCII needle, for the constants a caller holds. */
	public static byte[] needle(String ascii) {
		return ascii.getBytes(StandardCharsets.US_ASCII);
	}

	/** Whether {@code haystack} contains {@code needle}. */
	public static boolean contains(byte[] haystack, byte[] needle) {
		return containsAny(haystack, new byte[][] {needle});
	}

	/**
	 * Whether {@code bytes} begin with a class file's magic number — the only cheap test that separates a real
	 * class from an entry that merely ends in {@code .class}.
	 *
	 * <p>Why a name is not enough: a jar written on macOS carries an {@code __MACOSX/} tree of AppleDouble
	 * sidecars, one per entry, named {@code ._<original>} — so a jar with {@code ModrinthWrapper.class} also has
	 * {@code __MACOSX/com/modrinth/_6sSDO6Y/._ModrinthWrapper.class}, whose bytes are a resource fork
	 * ({@code 00 05 16 07 …}), not a class. Every jar scanner in this package walks entries by suffix, so each
	 * one handed those bytes to ASM, and {@code new ClassReader(bytes)} answers a file that is not a class with
	 * {@code IllegalArgumentException: null} — no class name, no entry name, nothing to act on. Measured on
	 * {@code CheaperGapples.jar} (uhc-gapples, forge bucket): the whole launch died in
	 * {@code MixinShadowMembers.scan} before a single Fabric guest was remapped. The check is the magic, not the
	 * {@code __MACOSX/} prefix, because the invariant that matters is the one ASM needs.
	 */
	public static boolean isClass(byte[] bytes) {
		return bytes != null && bytes.length >= 4
				&& bytes[0] == (byte) 0xCA && bytes[1] == (byte) 0xFE
				&& bytes[2] == (byte) 0xBA && bytes[3] == (byte) 0xBE;
	}

	/**
	 * Whether {@code haystack} contains any of {@code needles}, in ONE pass over the bytes.
	 *
	 * <p>One pass rather than one per needle: the answer is almost always no, and a scan per needle walks the
	 * whole class once per needle to say so. Testing every needle at each position is the same number of
	 * comparisons over a fraction of the memory traffic.
	 */
	public static boolean containsAny(byte[] haystack, byte[][] needles) {
		if (haystack == null || haystack.length == 0 || needles == null) return false;

		int shortest = Integer.MAX_VALUE;
		for (byte[] needle : needles) {
			if (needle != null && needle.length > 0) shortest = Math.min(shortest, needle.length);
		}
		if (shortest == Integer.MAX_VALUE || haystack.length < shortest) return false;

		for (int at = 0; at + shortest <= haystack.length; at++) {
			for (byte[] needle : needles) {
				if (needle == null || needle.length == 0) continue;
				if (at + needle.length > haystack.length) continue;
				if (matchesAt(haystack, at, needle)) return true;
			}
		}
		return false;
	}

	private static boolean matchesAt(byte[] haystack, int at, byte[] needle) {
		for (int i = 0; i < needle.length; i++) {
			if (haystack[at + i] != needle[i]) return false;
		}
		return true;
	}
}
