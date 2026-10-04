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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.forbric.api.DiscoveredMod;

/**
 * Verifies, against the STAGED neoforge-runtime bytecode, that the kernel seeds a {@code LoadingModList} a mod can
 * actually find itself in — the defect being that an empty one answers {@code null} to
 * {@code getModFileById(myId)} and NPEs the caller (Iris, inside {@code Minecraft.<init>}).
 *
 * <p>The three claims that matter are each a separate failure mode:
 * <ul>
 *   <li>{@code getModFileById(id).versionString()} returns the mod's REAL declared version — a placeholder there
 *       is the same bug, only quieter (mods render this string);</li>
 *   <li>{@code getMods()}' elements are assignable to the CONCRETE {@code ModInfo} — every consumer's per-element
 *       access is a {@code checkcast ModInfo}, so a dynamic proxy would blow up at the reader, not here;</li>
 *   <li>a jar the {@link MultiLoaderArbiter} handed to Fabric is absent — a universal jar must contribute under
 *       exactly the one ecosystem it was arbitrated to.</li>
 * </ul>
 *
 * <p>Everything needing the staged jar self-skips when it is absent; the arbitration case is pure kernel code and
 * always runs.
 */
class PassiveSeederLoadingModListTest {
	/**
	 * Through {@code FORBRIC_OLD} when it is set, as every other staged-bytecode test resolves it: this class
	 * hardcoded the relative path, so in a second worktree every case here skipped and the suite reported green.
	 */
	static final Path NEO_RUNTIME = Path.of(System.getenv().getOrDefault("FORBRIC_OLD",
			System.getProperty("user.dir") + "/../forbric-loader"), "run", "neoforge-runtime", "neoforge-runtime.jar")
			.normalize();

	@TempDir
	Path tmp;

	/** Stands in for {@code FMLLoader}: the seeder only ever touches its {@code loadingModList} field. */
	static final class FakeFmlLoader {
		private Object loadingModList;
	}

	/** Stands in for 1.21.1's {@code FMLLoader}, whose {@code loadingModList} is a STATIC field. */
	static final class StaticFakeFmlLoader {
		private static Object loadingModList;
	}

	@AfterEach
	void clearGlobals() {
		System.clearProperty(PassiveSeeder.SEED_SWITCH);
		System.clearProperty("forbric.multiLoaderPreference");
		MultiLoaderArbiter.reset();
	}

	@Test
	void seedsARealModFileInfoAndModInfoForANeoForgeJar() throws Exception {
		ClassLoader game = neoForgeLoader();
		Path mods = Files.createDirectories(tmp.resolve("mods"));
		writeModJar(mods.resolve("kerneltestmod.jar"), "kerneltestmod", "4.12.2", "Kernel Test Mod", false);

		FakeFmlLoader loader = new FakeFmlLoader();
		PassiveSeeder.seedNeoForgeLoadingModList(game, FakeFmlLoader.class, loader, mods);

		Object list = seededList(loader);
		assertNotNull(list, "a LoadingModList must have been seeded");

		Object fileInfo = call(list, "getModFileById", String.class, "kerneltestmod");
		assertNotNull(fileInfo, "the mod must resolve itself through getModFileById");
		assertEquals("4.12.2", call(fileInfo, "versionString"),
				"versionString() must be the mod's DECLARED version, not a placeholder");

		List<?> seeded = (List<?>) call(list, "getMods");
		assertEquals(1, seeded.size(), "getMods() carries the one discovered mod");

		Class<?> modInfo = Class.forName("net.neoforged.fml.loading.moddiscovery.ModInfo", false, game);
		assertTrue(modInfo.isInstance(seeded.get(0)),
				"elements must be the CONCRETE ModInfo — every reader checkcasts to it");

		Class<?> modFileInfo = Class.forName("net.neoforged.fml.loading.moddiscovery.ModFileInfo", false, game);
		assertTrue(modFileInfo.isInstance(fileInfo), "getModFileById checkcasts to the concrete ModFileInfo");
		assertEquals("Kernel Test Mod", call(seeded.get(0), "getDisplayName"));
		assertEquals("kerneltestmod", call(seeded.get(0), "getModId"));
		// getConfig() is dereferenced unguarded by FeatureFlagLoader during Bootstrap on every boot.
		assertNotNull(call(seeded.get(0), "getConfig"), "ModInfo.getConfig() must never be null");
		// The synthetic ModFile is what keeps toString()/getFilePath() from NPE-ing on a mod-loading error path.
		assertNotNull(call(fileInfo, "getFile"), "the ModFileInfo must carry a file");
		assertEquals("kerneltestmod", fileInfo.toString(), "ModFileInfo.toString() is its first mod's id");
	}

	@Test void versionArgumentsAreReadByTheNativeNeoForgeParser() throws Exception {
		ClassLoader game = neoForgeLoader();
		// PORT(1.21.1): 26.2's net.neoforged.fml.loading.ProgramArgs does not exist on 1.21.1. FML's own
		// launch-handler argument container is targets.ArgumentList, with the same --fml.mcVersion spelling and the
		// same from(String...)/remove(String) pair. It is package-private, so the lookups are made accessible first.
		Class<?> parser = Class.forName("net.neoforged.fml.loading.targets.ArgumentList", true, game);
		Method from = parser.getMethod("from", String[].class);
		Method remove = parser.getMethod("remove", String.class);
		from.setAccessible(true);
		remove.setAccessible(true);
		for (String version : List.of("1.21.1", "1.21.4")) {
			Object args = from.invoke(null, (Object) PassiveSeeder.neoForgeVersionArguments(version));
			assertEquals(version, remove.invoke(args, "fml.mcVersion"));
		}
		Object absent = from.invoke(null, (Object) PassiveSeeder.neoForgeVersionArguments(null));
		assertNull(remove.invoke(absent, "fml.mcVersion"), "no detected game version must leave fml.mcVersion unset");
	}

	/**
	 * A mod reading a file out of its own jar through the seeded list (LambDynamicLights copying its default
	 * config on the first launch) must find it. It found nothing while the seeded file's contents were empty.
	 */
	@Test
	void aModReadsItsOwnFilesThroughTheSeededList() throws Exception {
		ClassLoader game = neoForgeLoader();
		Path mods = Files.createDirectories(tmp.resolve("mods"));
		Path jar = mods.resolve("[Lambd的动态光源] kerneltestmod-1+26.2.jar");
		writeModJar(jar, "kerneltestmod", "1", "Kernel Test Mod", true);
		try (var zip = java.nio.file.FileSystems.newFileSystem(jar)) {
			Files.writeString(zip.getPath("kerneltestmod.toml"), "enabled = true\n");
		}
		FakeFmlLoader loader = new FakeFmlLoader();
		PassiveSeeder.seedNeoForgeLoadingModList(game, FakeFmlLoader.class, loader, mods);
		Object file = call(call(seededList(loader), "getModFileById", String.class, "kerneltestmod"), "getFile");
		// PORT(1.21.1): the file's jar is a cpw.mods.jarhandling.SecureJar behind getSecureJar(), read through
		// ModFile.findResource(String...) — 26.2's getContents()/readFile()/containsFile() do not exist here.
		Object secureJar = call(file, "getSecureJar");
		assertEquals(jar.toAbsolutePath(), call(secureJar, "getPrimaryPath"));
		assertEquals("enabled = true\n", Files.readString(resource(file, "kerneltestmod.toml")));
		assertFalse(Files.exists(resource(file, "missing.toml")),
				"a file the jar does not carry must not resolve to a real path");
	}

	/** {@code ModFile.findResource(String...)} — the 1.21.1 way a mod reads a file out of its own jar. */
	private static Path resource(Object modFile, String name) throws Exception {
		return (Path) modFile.getClass().getMethod("findResource", String[].class)
				.invoke(modFile, (Object) new String[] {name});
	}

	@Test
	void offSwitchRestoresTheEmptyList() throws Exception {
		ClassLoader game = neoForgeLoader();
		Path mods = Files.createDirectories(tmp.resolve("mods"));
		writeModJar(mods.resolve("kerneltestmod.jar"), "kerneltestmod", "4.12.2", "Kernel Test Mod", false);

		System.setProperty(PassiveSeeder.SEED_SWITCH, "off");
		FakeFmlLoader loader = new FakeFmlLoader();
		PassiveSeeder.seedNeoForgeLoadingModList(game, FakeFmlLoader.class, loader, mods);

		Object list = seededList(loader);
		assertNotNull(list, "the off switch still seeds a structurally valid list, just an empty one");
		assertTrue(((List<?>) call(list, "getMods")).isEmpty(), "off => zero mods");
		assertNull(call(list, "getModFileById", String.class, "kerneltestmod"), "off => nothing resolves");
	}

	@Test
	void zeroForgeFamilyModsKeepsTheEmptyList() throws Exception {
		ClassLoader game = neoForgeLoader();
		Path mods = Files.createDirectories(tmp.resolve("mods"));

		FakeFmlLoader loader = new FakeFmlLoader();
		PassiveSeeder.seedNeoForgeLoadingModList(game, FakeFmlLoader.class, loader, mods);

		Object list = seededList(loader);
		assertNotNull(list, "a zero-mod boot must behave exactly as before: an empty but present list");
		assertTrue(((List<?>) call(list, "getMods")).isEmpty());
	}

	/**
	 * The 1.21.1 shape: FMLLoader's {@code loadingModList} is a STATIC field and there is no per-launch instance, so
	 * {@code seedNeoForge21Loader} calls the seeder with a {@code null} {@code loaderInstance}.
	 *
	 * <p>The contract the 1.21.1 client launch depends on: the STATIC field is written, and
	 * {@code LoadingModList.get()} — what Sodium's {@code ResourcePackLoaderMixin} calls from inside
	 * {@code ResourcePackLoader.<clinit>}, via {@code getModFileById("sodium").getFile()} — answers for a real mod
	 * instead of returning null (which kills that class initializer and the client with it).
	 */
	@Test
	void the1_21_1StaticShapeSeedsTheSameList() throws Exception {
		ClassLoader game = neoForgeLoader();
		Path mods = Files.createDirectories(tmp.resolve("mods"));
		writeModJar(mods.resolve("kerneltestmod.jar"), "kerneltestmod", "4.12.2", "Kernel Test Mod", false);

		PassiveSeeder.seedNeoForgeLoadingModList(game, StaticFakeFmlLoader.class, null, mods, List.of());

		Field field = StaticFakeFmlLoader.class.getDeclaredField("loadingModList");
		field.setAccessible(true);
		Object seeded = field.get(null);
		assertNotNull(seeded, "a null loaderInstance must write the 1.21.1 STATIC loadingModList field");
		assertEquals("4.12.2", call(call(seeded, "getModFileById", String.class, "kerneltestmod"), "versionString"));

		Class<?> lml = Class.forName("net.neoforged.fml.loading.LoadingModList", false, game);
		Object viaGet = lml.getMethod("get").invoke(null);
		assertNotNull(viaGet, "LoadingModList.get() must answer — it is what the mixin calls at class-init");
		assertNotNull(call(viaGet, "getModFileById", String.class, "kerneltestmod"),
				"a mod must resolve ITSELF through the seeded list; an empty list is what NPE'd Sodium's mixin");
	}

	@Test
	void aJarArbitratedToFabricIsNotInTheNeoForgeList() throws Exception {
		Path mods = Files.createDirectories(tmp.resolve("mods"));
		writeModJar(mods.resolve("universal.jar"), "universalmod", "1.2.3", "Universal Mod", true);

		// Control: with the documented default preference the universal jar is claimed by NeoForge and IS listed.
		MultiLoaderArbiter.reset();
		List<DiscoveredMod> claimed = PassiveSeeder.arbitratedForgeFamilyMods(mods);
		assertEquals(1, claimed.size(), "a universal jar contributes exactly once by default");
		assertEquals("universalmod", claimed.get(0).getId());

		// Hand the same jar to Fabric: its Forge-family manifest must then contribute NOTHING here.
		System.setProperty("forbric.multiLoaderPreference", "fabric");
		MultiLoaderArbiter.reset();
		assertTrue(PassiveSeeder.arbitratedForgeFamilyMods(mods).isEmpty(),
				"a jar the arbiter gave to FABRIC must not appear in the NeoForge list");
	}

	// --- helpers ---

	static Object seededList(FakeFmlLoader loader) throws Exception {
		Field field = FakeFmlLoader.class.getDeclaredField("loadingModList");
		field.setAccessible(true);
		return field.get(loader);
	}

	static Object call(Object target, String method) throws Exception {
		return target.getClass().getMethod(method).invoke(target);
	}

	static Object call(Object target, String method, Class<?> argType, Object arg) throws Exception {
		return target.getClass().getMethod(method, argType).invoke(target, arg);
	}

	/**
	 * A classloader over the staged neoforge-runtime jar plus the two logging types it links against but does not
	 * ship ({@code com.mojang.logging.LogUtils} / {@code org.slf4j}), which come from the Minecraft library tree at
	 * runtime and are not on the unit-test classpath. Only the static initializers need them, so empty stand-ins
	 * are enough; the test self-skips when the staged jar is absent.
	 */
	private ClassLoader neoForgeLoader() throws Exception {
		assumeTrue(Files.isRegularFile(NEO_RUNTIME),
				"staged neoforge-runtime.jar absent — skipping real-bytecode LoadingModList seeding check");
		Path stubs = loggingStubs(tmp.resolve("stubs"));
		return new URLClassLoader(new URL[] {stubs.toUri().toURL(), NEO_RUNTIME.toUri().toURL()},
				PassiveSeederLoadingModListTest.class.getClassLoader());
	}

	/** Writes the logging stand-ins {@link #neoForgeLoader} describes into {@code dir}, and returns it. */
	static Path loggingStubs(Path dir) throws IOException {
		Path stubs = Files.createDirectories(dir);
		writeClass(stubs, "org/slf4j/Logger", emptyInterface("org/slf4j/Logger"));
		writeClass(stubs, "org/slf4j/Marker", emptyInterface("org/slf4j/Marker"));
		writeClass(stubs, "org/slf4j/LoggerFactory", loggerFactory());
		writeClass(stubs, "com/mojang/logging/LogUtils", logUtils());
		return stubs;
	}

	static void writeClass(Path root, String internalName, byte[] bytes) throws IOException {
		Path out = root.resolve(internalName + ".class");
		Files.createDirectories(out.getParent());
		Files.write(out, bytes);
	}

	private static byte[] emptyInterface(String internalName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, internalName,
				null, "java/lang/Object", null);
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** {@code class LogUtils { public static Marker FATAL_MARKER; public static Logger getLogger(){ return null; } }} */
	private static byte[] logUtils() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/mojang/logging/LogUtils", null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "FATAL_MARKER", "Lorg/slf4j/Marker;", null, null)
				.visitEnd();
		nullLogger(cw, "getLogger", "()Lorg/slf4j/Logger;");
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** {@code class LoggerFactory { public static Logger getLogger(Class|String){ return null; } }} */
	private static byte[] loggerFactory() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "org/slf4j/LoggerFactory", null, "java/lang/Object", null);
		nullLogger(cw, "getLogger", "(Ljava/lang/Class;)Lorg/slf4j/Logger;");
		nullLogger(cw, "getLogger", "(Ljava/lang/String;)Lorg/slf4j/Logger;");
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static void nullLogger(ClassWriter cw, String name, String desc) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.ACONST_NULL);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
	}

	/** A minimal mod jar: a NeoForge {@code mods.toml}, optionally plus a {@code fabric.mod.json} (universal jar). */
	private static void writeModJar(Path jar, String modId, String version, String displayName, boolean alsoFabric)
			throws IOException {
		String toml = "modLoader=\"javafml\"\n"
				+ "loaderVersion=\"[1,)\"\n"
				+ "license=\"Apache-2.0\"\n"
				+ "\n"
				+ "[[mods]]\n"
				+ "modId=\"" + modId + "\"\n"
				+ "version=\"" + version + "\"\n"
				+ "displayName=\"" + displayName + "\"\n";
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			put(zip, "META-INF/neoforge.mods.toml", toml);
			if (alsoFabric) {
				put(zip, "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\""
						+ version + "\",\"name\":\"" + displayName + "\"}");
			}
		}
	}

	static void put(ZipOutputStream zip, String name, String content) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		OutputStream out = zip;
		out.write(content.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}
}
