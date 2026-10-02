package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.api.ModPresence;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** The same discovery -> ModInfo -> owning file path PAL reads during its constructor. */
class KernelModFileInfoVersionTest {
    private static final Path STAGED = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"), "run");
    @TempDir Path temp;

    @AfterEach
    void clearMetadata() {
        ModPresence.publishForgeFamily(List.of());
        ModPresence.publishFabric(List.of());
    }

    @Test
    void bothCarriersDelegateVersionStringToTheFirstModVersion() throws Exception {
        for (String family : List.of("forge", "neoforge")) {
            Path jar = STAGED.resolve(family + "-runtime/" + family + "-runtime.jar");
            assumeTrue(Files.isRegularFile(jar), "staged carrier absent");
            String namespace = family.equals("forge") ? "net/minecraftforge" : "net/neoforged";
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                String entry = namespace + "/fml/loading/moddiscovery/ModFileInfo.class";
                ClassNode node = new ClassNode();
                new ClassReader(zip.getInputStream(zip.getEntry(entry)).readAllBytes()).accept(node, 0);
                var method = node.methods.stream().filter(m -> m.name.equals("versionString")).findFirst().orElseThrow();
                var calls = new java.util.ArrayList<String>();
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call) calls.add(call.name + call.desc);
                }
                assertTrue(calls.contains("getMods()Ljava/util/List;"), calls.toString());
                assertTrue(calls.contains("getVersion()Lorg/apache/maven/artifact/versioning/ArtifactVersion;"), calls.toString());
                assertTrue(calls.contains("toString()Ljava/lang/String;"), calls.toString());
            }
        }
    }

    @Test
    void neoDeclaredVersionWinsOverAnUnrelatedManifestVersion() throws Exception {
        Path jar = fixture("neo.jar", "META-INF/neoforge.mods.toml", "4.2.1-dev", "99.0");
        try (URLClassLoader runtime = runtimeLoader()) {
            String version = version(info(runtime, jar));
            assertEquals("4.2.1-dev", version);
            assertTrue(version.contains("dev"), "PAL's dev-version branch must receive the actual declared version");
        }
    }

    @Test
    void forgeDeclaredVersionIsRetainedWithoutAManifestVersion() throws Exception {
        Path jar = fixture("forge.jar", "META-INF/mods.toml", "3.8.7", null);
        try (URLClassLoader runtime = runtimeLoader()) {
            assertEquals("3.8.7", version(info(runtime, jar)));
        }
    }

    @Test
    void bothTomlDialectsUseTheResolvedManifestPlaceholder() throws Exception {
        try (URLClassLoader runtime = runtimeLoader()) {
            for (String metadata : List.of("META-INF/mods.toml", "META-INF/neoforge.mods.toml")) {
                Path jar = fixture(metadata.contains("neoforge") ? "neo.jar" : "forge.jar",
                        metadata, "${file.jarVersion}", "7.6.5-dev");
                assertEquals("7.6.5-dev", version(info(runtime, jar)), "discovery must resolve " + metadata);
            }
        }
    }

    @Test
    void aPalReleaseVersionSupportsItsActualContainsCall() throws Exception {
        Path jar = fixture("pal.jar", "META-INF/neoforge.mods.toml", "1.2.6+mc.26.2", null);
        try (URLClassLoader runtime = runtimeLoader()) {
            String version = version(info(runtime, jar));
            assertEquals("1.2.6+mc.26.2", version);
            assertFalse(version.contains("dev"), "PAL must stay on its production branch, not be forced into dev mode");
        }
    }

    @Test
    void missingVersionKeepsTheOwnersExistingExplicitUnknownValue() throws Exception {
        Path jar = fixture("unknown.jar", "META-INF/neoforge.mods.toml", null, null);
        try (URLClassLoader runtime = runtimeLoader()) {
            Object owner = info(runtime, jar);
            String version = version(owner);
            assertNotNull(version);
            // KernelModMetadata already defines unknown as 0.0. This fix follows its owner, just as both
            // carriers do, instead of inventing a second fallback or changing all mod version semantics.
            assertEquals("0.0", version);
            assertEquals(owner.getClass().getMethod("getVersion").invoke(owner).toString(), version);
            assertFalse(version.contains("dev"));
        }
    }

    @Test
    void fileVersionUsesItsPublishedOwnerRatherThanLaterPresenceMetadata() throws Exception {
        Path first = fixture("first.jar", "META-INF/neoforge.mods.toml", "2.0.1", null);
        Path later = fixture("later.jar", "META-INF/neoforge.mods.toml", "9.9.9-dev", null);
        try (URLClassLoader runtime = runtimeLoader()) {
            Object owner = info(runtime, first);
            ModPresence.publishForgeFamily(new ForbricModDiscoverer().discoverJar(later));
            assertEquals("2.0.1", version(owner), "versionString must agree with its owner even if global discovery changes");
        }
    }

    private Object info(ClassLoader runtime, Path jar) throws Exception {
        ModPresence.publishForgeFamily(new ForbricModDiscoverer().discoverJar(jar));
        return Class.forName("net.forbric.kernel.runtime.KernelModInfo", true, runtime)
                .getConstructor(String.class, Path.class).newInstance("versionprobe", jar);
    }

    /**
     * The jar-contents seam after the 1.21.1 port.
     *
     * <p>26.2 handed every file a {@code net.neoforged.fml.jarcontents.JarContents} — an empty one for a presence
     * alias — which this test enumerated with a {@code JarResourceVisitor}. Neither 26.2 type exists on 1.21.1: the
     * file's contents are a {@code cpw.mods.jarhandling.SecureJar} behind {@code KernelModFile.getSecureJar()},
     * read through {@code findResource(String...)}. 1.21.1 has no empty {@code SecureJar} factory, so a jar-less
     * presence alias reports null — and it must never dereference null or replay another build's resources.
     *
     * <p>A real file keeps its resources: {@code KernelModFile.secureJarOf} builds its {@code SecureJar} through
     * {@link net.forbric.kernel.boot.ForgeSecureJarStandIn} — the stand-in the seeded Forge/NeoForge
     * {@code ModFile}s already use — because the carrier's own {@code SecureJar.from(Path...)} cannot initialise off
     * ModLauncher (its {@code <clinit>} demands ModLauncher's {@code UnionFileSystemProvider}, which the kernel
     * replaces). {@code findResource("META-INF", "neoforge.mods.toml")} therefore resolves to this file's own
     * entry, which the test reads back.
     */
    @Test void presenceAliasesHaveNoNativeResourcesAndRealFilesKeepTheirOwn() throws Exception {
        Path jar = fixture("real.jar", "META-INF/neoforge.mods.toml", "1.0.0", null);
        try (URLClassLoader runtime = runtimeLoader()) {
            Class<?> fileType = runtime.loadClass("net.forbric.kernel.runtime.KernelModFile");
            for (Path source : new Path[] {null, jar}) {
                Object file = fileType.getConstructor(String.class, Path.class).newInstance("resourceprobe", source);
                Object secureJar = fileType.getMethod("getSecureJar").invoke(file);
                Path found = (Path) fileType.getMethod("findResource", String[].class)
                        .invoke(file, (Object) new String[] {"META-INF", "neoforge.mods.toml"});
                Path filePath = (Path) fileType.getMethod("getFilePath").invoke(file);
                assertNotNull(filePath, "the path is never null, so a native walk of the file list cannot dereference null");
                if (source == null) {
                    // 26.2 answered JarContents.empty(path); 1.21.1 has no such factory, so an alias has nothing.
                    assertNull(secureJar, "a jar-less alias has no SecureJar to hand out (PORT(1.21.1))");
                    assertNull(found, "aliases must not replay another build's resources");
                    assertFalse(filePath.equals(jar), "an alias never answers another build's path");
                    assertEquals("resourceprobe.jar", fileType.getMethod("getFileName").invoke(file),
                            "an alias answers its own placeholder name");
                } else {
                    assertNotNull(secureJar, "a real file exposes its own SecureJar");
                    assertNotNull(found, "a real file resolves its own META-INF/neoforge.mods.toml");
                    assertTrue(Files.isRegularFile(found), found.toString());
                    assertTrue(Files.readString(found).contains("modId=\"versionprobe\""),
                            "the resolved path must be this file's own entry");
                    assertEquals(jar, filePath, "a real file answers its own jar");
                }
            }
        }
    }

    private static String version(Object info) throws Exception {
        Object file = info.getClass().getMethod("getOwningFile").invoke(info);
        return (String) file.getClass().getMethod("versionString").invoke(file);
    }

    private Path fixture(String name, String metadata, String version, String manifestVersion) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (manifestVersion != null) manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, manifestVersion);
        Path path = temp.resolve(name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            output.putNextEntry(new ZipEntry(metadata));
            String toml = "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"test\"\n[[mods]]\nmodId=\"versionprobe\"\n"
                    + (version == null ? "" : "version=\"" + version + "\"\n");
            output.write(toml.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return path;
    }

    private static URLClassLoader runtimeLoader() throws Exception {
        Path compiled = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
        Path carrier = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
        assumeTrue(Files.isDirectory(compiled) && Files.isRegularFile(carrier), "staged runtime classes/carrier absent");
        return new URLClassLoader(new URL[] {compiled.toUri().toURL(), carrier.toUri().toURL()},
                KernelModFileInfoVersionTest.class.getClassLoader());
    }
}
