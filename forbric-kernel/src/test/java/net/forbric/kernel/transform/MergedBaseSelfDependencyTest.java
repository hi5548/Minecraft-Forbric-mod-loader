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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

import net.forbric.kernel.TestFixtures;

/**
 * Covers the self-dependency the tag/registry sorter used to record and then walk forever.
 *
 * <p>The fixture is the REAL merged base's {@code net.minecraft.util.DependencySorter}, driven through its own
 * private {@code isCyclic} / {@code addDependencyIfNotCyclic} on a real Guava multimap, with the two tag ids the
 * 2026-10-03 sweep measured: MinecraftForge's {@code forge:mushrooms} declares a dependency on itself (its data
 * pack ships {@code {"id":"#forge:mushrooms","required":false}}) and NeoForge's {@code c:mushrooms} depends on it
 * ({@code {"id":"#forge:mushrooms","required":false}} in {@code data/c/tags/item/mushrooms.json}). The merged base
 * serves BOTH carriers, which is the pack set neither native loader ever had.
 *
 * <p>Calling the two methods directly is what makes this order-free. In the game the recursion depends on the
 * sorter's own key {@code HashMap} being walked with the self-edge already recorded — which is why nine subjects
 * with 604 item tags survive and one with 881 does not — and a test that reproduced it through
 * {@code orderByDependencies} alone would be asserting a bucket order rather than the defect. The end-to-end call
 * is here as well, for the repaired bytes, because "the sort still produces the order its caller needs" is the
 * contract the boot actually depends on.
 */
class MergedBaseSelfDependencyTest {
	private static final String SORTER = "net.minecraft.util.DependencySorter";
	private static final String SORTER_ENTRY = "net.minecraft.util.DependencySorter$Entry";
	/** The tag MinecraftForge declares a dependency on itself, and the NeoForge tag that points at it. */
	private static final String SELF = "forge:mushrooms";
	private static final String DEPENDENT = "c:mushrooms";

	@Test
	void theMergedBaseRecordsASelfDependencyAndThenWalksItForever() throws Exception {
		Path base = mergedBase();
		Sorter sorter = Sorter.of(entry(base, SORTER), base);

		Multimap<String, String> edges = HashMultimap.create();
		sorter.addDependencyIfNotCyclic(edges, SELF, SELF);
		assertEquals(List.of(SELF), List.copyOf(edges.get(SELF)),
				"premise: the guard's closure test has nothing to find yet, so the self-dependency is recorded — "
						+ "this is the hole the repair closes, and if the merge pipeline closed it instead this "
						+ "test and the repair beside it should go");

		assertThrows(StackOverflowError.class, () -> sorter.isCyclic(edges, DEPENDENT, SELF),
				"the walk re-enters itself on the self-edge and never returns: isCyclic carries no visited set, "
						+ "and on the acyclic graph it was written for it never needed one");
	}

	@Test
	void theRepairRefusesTheSelfDependencyAndTheWalkTerminates() throws Exception {
		Path base = mergedBase();
		byte[] merged = entry(base, SORTER);
		byte[] repaired = new ForbricMergedBaseCompatTransformer().transform(SORTER, merged, null);
		assertFalse(Arrays.equals(merged, repaired), "the repair edited nothing");
		Sorter sorter = Sorter.of(repaired, base);

		Multimap<String, String> edges = HashMultimap.create();
		sorter.addDependencyIfNotCyclic(edges, SELF, SELF);
		assertTrue(edges.get(SELF).isEmpty(),
				"a dependency an entry declares on itself IS a cycle, so the guard refuses it — and that is the "
						+ "only insertion that could leave the multimap cyclic");
		assertFalse(sorter.isCyclic(edges, DEPENDENT, SELF), "nothing walks into a self-edge that was never recorded");
	}

	/**
	 * The other half of the same guard: a real dependency of length two is still found. Without this, a repair
	 * that answered "cyclic" too eagerly would pass the test above and quietly stop ordering anything.
	 */
	@Test
	void theRepairStillFindsADependencyOfLengthTwo() throws Exception {
		Path base = mergedBase();
		byte[] repaired = new ForbricMergedBaseCompatTransformer()
				.transform(SORTER, entry(base, SORTER), null);
		Sorter sorter = Sorter.of(repaired, base);

		Multimap<String, String> edges = HashMultimap.create();
		edges.put(SELF, DEPENDENT);
		assertTrue(sorter.isCyclic(edges, DEPENDENT, SELF),
				"adding c:mushrooms → forge:mushrooms closes the cycle forge:mushrooms → c:mushrooms known here");
		edges.clear();
		assertFalse(sorter.isCyclic(edges, DEPENDENT, SELF), "with no edge at all there is no cycle");
	}

	/** The failing operation itself: the measured tag shape sorts, and the dependency comes out before its user. */
	@Test
	void itSortsTheMeasuredTagShape() throws Exception {
		Path base = mergedBase();
		byte[] repaired = new ForbricMergedBaseCompatTransformer()
				.transform(SORTER, entry(base, SORTER), null);
		Sorter sorter = Sorter.of(repaired, base);

		sorter.addEntry(SELF, sorter.entry(List.of(), List.of(SELF)));
		sorter.addEntry(DEPENDENT, sorter.entry(List.of(), List.of(SELF)));

		List<String> order = new ArrayList<>();
		sorter.orderByDependencies((id, entry) -> order.add((String) id));

		assertEquals(List.of(SELF, DEPENDENT), order,
				"TagLoader builds each tag after the tags it includes, and the boot dies here when that walk "
						+ "overflows instead");
	}

	/** Idempotence: the transform is handed finished bytes on a second pass (and by the drift census). */
	@Test
	void aSecondPassLeavesTheRepairedClassAlone() throws Exception {
		Path base = mergedBase();
		byte[] merged = entry(base, SORTER);
		byte[] once = new ForbricMergedBaseCompatTransformer().transform(SORTER, merged, null);
		byte[] twice = new ForbricMergedBaseCompatTransformer().transform(SORTER, once, null);
		assertArrayEquals(once, twice, "a second pass finds its own guard and stands down");
	}

	@Test
	void itLeavesClassesOutsideTheSorterAlone() {
		byte[] foreign = { 0x00 };
		assertSame(foreign, new ForbricMergedBaseCompatTransformer()
				.transform("net.minecraft.util.SomethingElse", foreign, null));
	}

	private static Path mergedBase() {
		Path base = TestFixtures.mergedBase();
		assumeTrue(base != null && Files.isRegularFile(base), "the merged base is not staged: " + base);
		return base;
	}

	private static byte[] entry(Path jar, String binaryName) throws Exception {
		String path = binaryName.replace('.', '/') + ".class";
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry found = zip.getEntry(path);
			assertNotNull(found, path + " is absent from the merged base " + jar.getFileName());
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}

	/**
	 * The real class, defined by a loader whose parent holds the libraries the game supplies (Guava for the
	 * multimap the signatures name), so the bytes under test are the bytes the game would link against.
	 */
	private static final class Sorter {
		private final Object instance;
		private final Method isCyclic;
		private final Method addDependencyIfNotCyclic;
		private final Method addEntry;
		private final Method orderByDependencies;
		private final Class<?> entryType;

		private Sorter(Class<?> type) throws Exception {
			this.instance = type.getConstructor().newInstance();
			this.isCyclic = accessible(type, "isCyclic", Multimap.class, Object.class, Object.class);
			this.addDependencyIfNotCyclic = accessible(type, "addDependencyIfNotCyclic", Multimap.class,
					Object.class, Object.class);
			this.entryType = Class.forName(SORTER_ENTRY, false, type.getClassLoader());
			// V is bounded by DependencySorter$Entry, so that — not Object — is the second parameter's erasure.
			this.addEntry = type.getMethod("addEntry", Object.class, entryType);
			this.orderByDependencies = type.getMethod("orderByDependencies", BiConsumer.class);
		}

		static Sorter of(byte[] bytes, Path jar) throws Exception {
			URL[] urls = { jar.toUri().toURL() };
			ClassLoader loader = new URLClassLoader(urls, MergedBaseSelfDependencyTest.class.getClassLoader()) {
				@Override
				protected Class<?> findClass(String name) throws ClassNotFoundException {
					if (name.equals(SORTER)) return defineClass(name, bytes, 0, bytes.length);
					return super.findClass(name);
				}
			};
			return new Sorter(Class.forName(SORTER, true, loader));
		}

		private static Method accessible(Class<?> type, String name, Class<?>... parameters) throws Exception {
			Method method = type.getDeclaredMethod(name, parameters);
			method.setAccessible(true);
			return method;
		}

		boolean isCyclic(Multimap<String, String> edges, String from, String to) throws Exception {
			return (Boolean) call(isCyclic, edges, from, to);
		}

		void addDependencyIfNotCyclic(Multimap<String, String> edges, String from, String to) throws Exception {
			call(addDependencyIfNotCyclic, edges, from, to);
		}

		/** A reflective call, with the failure the test is about (the StackOverflowError) rethrown as itself. */
		private static Object call(Method method, Object... arguments) throws Exception {
			try {
				return method.invoke(null, arguments);
			} catch (java.lang.reflect.InvocationTargetException thrown) {
				Throwable cause = thrown.getCause();
				if (cause instanceof Error error) throw error;
				if (cause instanceof RuntimeException unchecked) throw unchecked;
				throw thrown;
			}
		}

		void addEntry(String id, Object entry) throws Exception {
			addEntry.invoke(instance, id, entry);
		}

		void orderByDependencies(BiConsumer<String, Object> consumer) throws Exception {
			orderByDependencies.invoke(instance, consumer);
		}

		/** One {@code DependencySorter$Entry}: the two visitors the sorter asks a key for its dependencies. */
		@SuppressWarnings("unchecked")
		Object entry(List<String> required, List<String> optional) {
			InvocationHandler handler = (proxy, method, args) -> {
				switch (method.getName()) {
					case "visitRequiredDependencies":
						required.forEach(dependency -> ((Consumer<String>) args[0]).accept(dependency));
						return null;
					case "visitOptionalDependencies":
						optional.forEach(dependency -> ((Consumer<String>) args[0]).accept(dependency));
						return null;
					case "toString":
						return "entry";
					case "hashCode":
						return System.identityHashCode(proxy);
					case "equals":
						return proxy == args[0];
					default:
						return null;
				}
			};
			return Proxy.newProxyInstance(entryType.getClassLoader(), new Class<?>[] { entryType }, handler);
		}
	}
}
