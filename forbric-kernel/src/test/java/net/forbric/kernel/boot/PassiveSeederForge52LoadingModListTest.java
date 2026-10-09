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

package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.forbric.kernel.TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * S3 of the 2026-10-08 merge-convention audit: <b>{@code PassiveSeeder.seedForge52LoadingModList} used to freeze an
 * EMPTY {@code FMLLoader.loadingModList} before Main with no proven backfill</b> — the same class as the
 * {@code LoadingModList} NPE that killed startup, only silent.
 *
 * <p>The write side is the easy half and it succeeds either way, so this probe checks the READ side: the seeded
 * field answers through the same door MinecraftForge's own readers use. On 1.21.1 (Forge 52.1.16, javap'd from the
 * staged carrier) {@code LoadingModList} is a plain class — {@code private static LoadingModList INSTANCE}, written
 * by {@code LoadingModList.of(files, mods, error)} — and {@code FMLLoader.loadingModList} is a static field
 * {@code FMLLoader.getLoadingModList()} returns. There is no {@code LoadingModListImpl} and no {@code ModSorter$State}
 * here, so the 26.2 holder machinery is inert and the field is the whole fix.
 *
 * <p>What every case exercises, by reading real MinecraftForge bytecode rather than a stand-in:
 * <ul>
 *   <li>{@code getMods()} returns the concrete {@code ModInfo}s and a mod resolves <b>itself</b> through
 *       {@code getModFileById(id).versionString()} — the NeoForge twin of this bug is what NPE'd Sodium's mixin;</li>
 *   <li>{@code LoadingModList.get()} answers from the SAME object the field holds, because {@code of} assigns
 *       {@code INSTANCE} — a field written but never reachable through {@code get()} would pass a field-only check
 *       and still leave every reader empty;</li>
 *   <li>{@code getBrokenFiles()} is non-null: Forge's own {@code ModLoader} constructor streams it next to
 *       {@code getErrors()} during Bootstrap, which is exactly the pre-Main read this seed exists for;</li>
 *   <li>a second call does not overwrite the first answer, and an instance with no Forge-family mod carries an
 *       EMPTY list because that is the truth (the three states of {@code ForgeLoadingList}: populated / genuinely
 *       empty / unknown-is-loud).</li>
 * </ul>
 *
 * <p>Everything needing the staged carrier self-skips when it is absent, like the other real-bytecode tests.
 */
class PassiveSeederForge52LoadingModListTest {
	/** Stands in for 1.21.1's {@code FMLLoader}: the seeder only ever touches its STATIC {@code loadingModList}. */
	static final class FakeFmlLoader {
		private static Object loadingModList;

		static void clear() {
			loadingModList = null;
		}
	}

	@TempDir
	Path tmp;

	@AfterEach
	void clearGlobals() {
		System.clearProperty(PassiveSeeder.SEED_SWITCH);
		System.clearProperty("forbric.multiLoaderPreference");
		MultiLoaderArbiter.reset();
		FakeFmlLoader.clear();
	}

	@Test
	void theSeededFieldCarriesTheModsAndReadsBackThroughLoadingModListGet() throws Exception {
		Path mods = Files.createDirectories(tmp.resolve("mods"));
		writeModJar(mods.resolve("wthit.jar"), "wthit", "20.0.0", "WTHIT");
		writeModJar(mods.resolve("lithium.jar"), "lithium", "0.15.4", "Lithium");

		try (URLClassLoader game = forgeLoader()) {
			PassiveSeeder.seedForge52LoadingModList(game, FakeFmlLoader.class, mods);

			Object seeded = FakeFmlLoader.loadingModList;
			assertNotNull(seeded, "the Forge 52 FMLLoader.loadingModList static field must be written");

			// The reader door, not the write door: of() assigns INSTANCE, so get() must hand back this exact list.
			Class<?> lml = Class.forName("net.minecraftforge.fml.loading.LoadingModList", false, game);
			assertSame(seeded, lml.getMethod("get").invoke(null),
					"LoadingModList.get() must answer from the same object the field holds");

			List<?> seededMods = (List<?>) call(seeded, "getMods");
			Set<String> ids = new TreeSet<>();
			for (Object mod : seededMods) ids.add((String) call(mod, "getModId"));
			assertEquals(Set.of("lithium", "wthit"), ids,
					"both Forge-family mods must be in the list this used to seed empty");

			// The seeder's OWN read-back path, driven directly. This is the check the first cut got wrong: getMods()
			// is an instance method, so reading it with a null receiver threw on every boot while the write succeeded.
			assertEquals(2, PassiveSeeder.readBackForge52ModCount(lml, seeded),
					"the seeded list must read back through LoadingModList.get().getMods()");

			// A mod resolving ITSELF — the shape that NPE'd on the NeoForge twin of this bug.
			Object fileInfo = call(seeded, "getModFileById", String.class, "wthit");
			assertNotNull(fileInfo, "getModFileById(id) must find the mod");
			assertEquals("20.0.0", call(fileInfo, "versionString"),
					"versionString() must be the mod's DECLARED version, not a placeholder");

			Class<?> modInfo = Class.forName("net.minecraftforge.fml.loading.moddiscovery.ModInfo", false, game);
			assertTrue(modInfo.isInstance(seededMods.get(0)),
					"elements must be the CONCRETE ModInfo — every reader checkcasts to it");

			// Forge's ModLoader constructor streams both of these during Bootstrap; neither may be null.
			assertNotNull(call(seeded, "getBrokenFiles"), "ModLoader's ctor streams getBrokenFiles() unguarded");
			assertTrue(((List<?>) call(seeded, "getErrors")).isEmpty(), "a clean seed carries no pre-load errors");
		}
	}

	@Test
	void aSecondCallDoesNotOverwriteTheFirstAnswer() throws Exception {
		Path first = Files.createDirectories(tmp.resolve("first"));
		writeModJar(first.resolve("wthit.jar"), "wthit", "20.0.0", "WTHIT");

		try (URLClassLoader game = forgeLoader()) {
			PassiveSeeder.seedForge52LoadingModList(game, FakeFmlLoader.class, first);
			Object seeded = FakeFmlLoader.loadingModList;
			assertNotNull(seeded);

			Path second = Files.createDirectories(tmp.resolve("second"));
			writeModJar(second.resolve("lithium.jar"), "lithium", "0.15.4", "Lithium");
			PassiveSeeder.seedForge52LoadingModList(game, FakeFmlLoader.class, second);

			assertSame(seeded, FakeFmlLoader.loadingModList, "a later call must not replace the first answer");
			assertNotResolvable(seeded, "lithium");
			assertNotNull(call(seeded, "getModFileById", String.class, "wthit"),
					"the first answer must survive a later call");
		}
	}

	@Test
	void anInstanceWithNoForgeFamilyModsCarriesAnHonestlyEmptyList() throws Exception {
		Path mods = Files.createDirectories(tmp.resolve("mods"));

		try (URLClassLoader game = forgeLoader()) {
			PassiveSeeder.seedForge52LoadingModList(game, FakeFmlLoader.class, mods);

			Object seeded = FakeFmlLoader.loadingModList;
			assertNotNull(seeded, "zero mods is still an ANSWER — a structurally valid empty list");
			assertTrue(((List<?>) call(seeded, "getMods")).isEmpty(), "zero mods => empty");
			assertNull(call(seeded, "getModFileById", String.class, "wthit"), "nothing resolves");
			assertNotNull(call(seeded, "getBrokenFiles"), "even the empty list must carry brokenFiles, not null");
		}
	}

	@Test
	void theOffSwitchRestoresTheEmptyList() throws Exception {
		Path mods = Files.createDirectories(tmp.resolve("mods"));
		writeModJar(mods.resolve("wthit.jar"), "wthit", "20.0.0", "WTHIT");

		System.setProperty(PassiveSeeder.SEED_SWITCH, "off");
		try (URLClassLoader game = forgeLoader()) {
			PassiveSeeder.seedForge52LoadingModList(game, FakeFmlLoader.class, mods);
			Object seeded = FakeFmlLoader.loadingModList;
			assertNotNull(seeded, "the off switch still seeds a structurally valid list, just an empty one");
			assertTrue(((List<?>) call(seeded, "getMods")).isEmpty(), "off => zero mods");
			assertNull(call(seeded, "getModFileById", String.class, "wthit"), "off => nothing resolves");
		}
	}

	// --- helpers --------------------------------------------------------------------------------------------

	private static void assertNotResolvable(Object list, String id) throws Exception {
		assertNull(call(list, "getModFileById", String.class, id), id + " must not be in the first answer");
	}

	private static Object call(Object target, String method) throws Exception {
		Method m = target.getClass().getMethod(method);
		m.setAccessible(true);
		return m.invoke(target);
	}

	private static Object call(Object target, String method, Class<?> argType, Object arg) throws Exception {
		Method m = target.getClass().getMethod(method, argType);
		m.setAccessible(true);
		return m.invoke(target, arg);
	}

	/**
	 * The MinecraftForge carrier plus the two libraries Minecraft gives it (Guava, which its list links against, and
	 * Commons Lang, which its version parser does) and the logging stand-ins, over this suite's own classes so there
	 * is one night-config/ASM — as at runtime.
	 */
	private URLClassLoader forgeLoader() throws Exception {
		Path forgeRuntime = TestFixtures.stagedJar("forge-runtime", "forge-runtime");
		TestFixtures.require(forgeRuntime != null && Files.isRegularFile(forgeRuntime),
				"staged forge-runtime.jar absent — skipping the Forge 52 LoadingModList probe");
		Path guava = newestLibrary("com/google/guava/guava");
		Path lang = newestLibrary("org/apache/commons/commons-lang3");
		assumeTrue(guava != null && lang != null, "no Guava / Commons Lang in the local Minecraft library tree");
		Path stubs = PassiveSeederLoadingModListTest.loggingStubs(tmp.resolve("stubs"));
		return new URLClassLoader(new URL[] {stubs.toUri().toURL(), forgeRuntime.toUri().toURL(), guava.toUri().toURL(),
				lang.toUri().toURL()}, PassiveSeederForge52LoadingModListTest.class.getClassLoader());
	}

	private static Path newestLibrary(String under) throws IOException {
		Path root = TestFixtures.minecraftDir().resolve("libraries").resolve(under);
		if (!Files.isDirectory(root)) return null;
		try (var stream = Files.walk(root)) {
			return stream.filter(f -> f.toString().endsWith(".jar") && !f.toString().contains("sources"))
					.sorted(java.util.Comparator.comparing(f -> f.getFileName().toString())).reduce((a, b) -> b).orElse(null);
		}
	}

	private static void writeModJar(Path jar, String modId, String version, String displayName) throws IOException {
		String toml = "modLoader=\"javafml\"\n"
				+ "loaderVersion=\"[1,)\"\n"
				+ "license=\"Apache-2.0\"\n"
				+ "\n"
				+ "[[mods]]\n"
				+ "modId=\"" + modId + "\"\n"
				+ "version=\"" + version + "\"\n"
				+ "displayName=\"" + displayName + "\"\n";
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			zip.putNextEntry(new ZipEntry("META-INF/mods.toml"));
			OutputStream out = zip;
			out.write(toml.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
	}
}
