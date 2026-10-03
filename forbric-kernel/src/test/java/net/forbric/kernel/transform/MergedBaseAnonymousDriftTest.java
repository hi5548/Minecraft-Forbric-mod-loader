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

package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.kernel.TestFixtures;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.mixin.MergedBaseAnonymousDrift;

/**
 * Derives the three anonymous-drift buckets from the game jar the merge was built from and the staged merged
 * base, and asserts they EQUAL the pinned constants. The identity of an anonymous class is its non-{@code <init>}
 * method set (name + descriptor) plus its superclass; the constructor descriptor and the {@code val$} captures
 * are javac's plumbing.
 *
 * <p>Both jars come from the checkout's own staging, not a version baked into this file: the constants are a
 * census OF ONE BASE, and a hard-coded path let them stay a 26.2 census while the kernel was built for 1.21.1
 * (the test silently skipped, so every entry was applied to a base it was never measured on).
 *
 * <p>Candidates are found by {@link Shape#stillHolds}: same superclass and every vanilla method still declared.
 * Exact set equality was the first spelling and it is wrong the moment a carrier patch ADDS a method to the
 * class vanilla's body moved into — NeoForge gives {@code ByteBufCodecs$27} two holderset helpers on 1.21.1, and
 * exact equality then reported vanilla's {@code $25} as reshaped although its body is plainly there.
 */
class MergedBaseAnonymousDriftTest {
	private static final Pattern ANONYMOUS = Pattern.compile("^(net/minecraft/|com/mojang/).*\\$\\d+\\.class$");

	record Shape(String superName, Set<String> methods, String ctorDesc, Set<String> captures) {
		/** The same class, possibly with methods a patch ADDED: every vanilla method is still there, same superclass. */
		boolean stillHolds(Shape vanilla) {
			return superName.equals(vanilla.superName) && methods.containsAll(vanilla.methods);
		}
	}

	@Test
	void theThreeBucketsAreExactlyThePinnedConstants() throws Exception {
		Path MERGED = TestFixtures.mergedBase();
		Path vanilla = TestFixtures.namedGameJar();
		assumeTrue(MERGED != null && Files.isRegularFile(MERGED) && Files.isRegularFile(vanilla),
				"staged merged base or the named game jar absent: " + MERGED + " / " + vanilla);
		Map<String, Shape> vanillaShapes = shapes(vanilla), mergedShapes = shapes(MERGED);
		assertTrue(vanillaShapes.size() > 500, "this does not look like a full vanilla jar (" + vanillaShapes.size() + " anonymous classes)");

		Map<String, List<String>> relocated = new TreeMap<>();
		Set<String> reshaped = new TreeSet<>(), captureOnly = new TreeSet<>();
		int missing = 0;
		for (Map.Entry<String, Shape> e : vanillaShapes.entrySet()) {
			String name = e.getKey();
			Shape v = e.getValue();
			Shape m = mergedShapes.get(name);
			if (m == null) { missing++; continue; }
			// NeoForge ADDS methods to anonymous classes it patches (MappedRegistry$2 gains getData/getDataMap,
			// CompoundTag$1 gains readNamedTagType): still the class vanilla compiled there. Only a vanilla method
			// that is GONE from $N says the name now holds a different class.
			if (m.stillHolds(v)) {
				if (!v.ctorDesc().equals(m.ctorDesc()) || !v.captures().equals(m.captures())) captureOnly.add(name);
				continue;
			}
			String outer = name.substring(0, name.lastIndexOf('$'));
			// A patch may ADD methods to the class vanilla's body MOVED INTO (NeoForge gives ByteBufCodecs$27 two
			// holderset helpers on 1.21.1), so the gate is "still holds", not "equal". Equality then picks among
			// those: it is the shape the original census was derived with and, where one survives, the more
			// precise answer. Only when no sibling is exactly vanilla's is the widened set used at all.
			List<String> exact = new ArrayList<>(), widened = new ArrayList<>();
			for (Map.Entry<String, Shape> other : mergedShapes.entrySet()) {
				if (other.getKey().equals(name) || !other.getKey().startsWith(outer + "$")) continue;
				Shape candidate = other.getValue();
				if (!candidate.stillHolds(v)) continue;
				if (candidate.methods().equals(v.methods()) && candidate.superName().equals(v.superName())) exact.add(other.getKey());
				else widened.add(other.getKey());
			}
			List<String> candidates = exact.isEmpty() ? widened : exact;
			java.util.Collections.sort(candidates);
			if (candidates.isEmpty()) reshaped.add(name); else relocated.put(name, candidates);
		}
		assertEquals(0, missing, "vanilla anonymous classes absent from the merged base");
		assertEquals(new TreeMap<>(MergedBaseAnonymousDrift.VANILLA_1_21_1.relocated()), relocated, "RELOCATED: vanilla $N whose body lives at another $M");
		assertEquals(new TreeSet<>(MergedBaseAnonymousDrift.VANILLA_1_21_1.reshaped()), reshaped, "RESHAPED: vanilla $N whose body exists nowhere in the outer class");
		assertEquals(new TreeSet<>(MergedBaseAnonymousDrift.VANILLA_1_21_1.captureOnly()), captureOnly, "CAPTURE_ONLY: same methods, different constructor or captures — never flagged");
	}

	/**
	 * The two censuses stay separate, and the base picks. The 26.2 census is not a longer version of the 1.21.1
	 * one — the same keys carry different homes — so a base that answers for 26.2 must get 26.2's answer: on 26.2
	 * vanilla's {@code ByteBufCodecs$22} has five candidate homes, and that is why {@link
	 * net.forbric.kernel.mixin.MixinAnonymousRetarget} declines it there rather than moving by a coin flip. A
	 * future reader who "simplifies" this to one census inverts that silently; this test is the tripwire.
	 */
	@Test
	void theBaseChoosesItsOwnCensusAndThePinnedTwentySixTwoCensusIsNotLost() {
		MergedBaseAnonymousDrift.Census on26 = MergedBaseAnonymousDrift.forBase(name -> true);
		MergedBaseAnonymousDrift.Census on1211 = MergedBaseAnonymousDrift.forBase(name -> !name.endsWith("$33"));
		assertSame(MergedBaseAnonymousDrift.VANILLA_26_2, on26);
		assertSame(MergedBaseAnonymousDrift.VANILLA_1_21_1, on1211);

		String target = "net/minecraft/network/codec/ByteBufCodecs$22";
		assertEquals(5, on26.relocated().get(target).size(), "26.2: five candidate homes, so the retarget declines");
		assertEquals(List.of("net/minecraft/network/codec/ByteBufCodecs$24"), on1211.relocated().get(target),
				"1.21.1: one home, so the retarget moves");
		assertTrue(on26.drifted(target) && on1211.drifted(target));
	}

	private static Map<String, Shape> shapes(Path jar) throws IOException {
		Map<String, Shape> out = new HashMap<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : zip.stream().toList()) {
				if (!ANONYMOUS.matcher(entry.getName()).matches()) continue;
				ClassNode node;
				try (InputStream in = zip.getInputStream(entry)) {
					node = new ClassNode();
					new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				}
				Set<String> methods = new TreeSet<>();
				String ctor = "";
				for (MethodNode m : node.methods) {
					if ("<init>".equals(m.name)) ctor = m.desc; else if (!"<clinit>".equals(m.name)) methods.add(m.name + m.desc);
				}
				Set<String> captures = new TreeSet<>();
				for (FieldNode f : node.fields) if (f.name.startsWith("val$") || f.name.startsWith("this$")) captures.add(f.name + ":" + f.desc);
				out.put(entry.getName().substring(0, entry.getName().length() - ".class".length()),
						new Shape(node.superName, methods, ctor, captures));
			}
		}
		return out;
	}
}
