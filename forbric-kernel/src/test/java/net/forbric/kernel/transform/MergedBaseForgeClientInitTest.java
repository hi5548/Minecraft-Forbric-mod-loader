package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import net.forbric.kernel.TestFixtures;

/**
 * PORT(1.21.1): the merged Minecraft calls the carriers' client hooks against {@code ParticleEngine} (26.2's
 * {@code ParticleResources}), and {@code ModelManager.reload} is vanilla's six-argument reload whose head runs
 * NeoForge's geometry loader. Both repairs are re-derived onto those shapes.
 */
class MergedBaseForgeClientInitTest {
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String MODELS = "net/minecraft/client/resources/model/ModelManager";
    private static final String NEO = "net/neoforged/neoforge/client/ClientHooks";
    private static final String KERNEL = "net/forbric/kernel/runtime/KernelForgeClientInit";
    private static final String INIT = "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/server/packs/resources/ReloadableResourceManager;)V";
    private static final String PARTICLES = "(Lnet/minecraft/client/particle/ParticleEngine;)V";
    private static final String RELOAD = "(Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;"
            + "Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;"
            + "Lnet/minecraft/util/profiling/ProfilerFiller;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)"
            + "Ljava/util/concurrent/CompletableFuture;";

    @AfterEach void restoreSwitch() { System.clearProperty("forbric.forgeClientInit"); }

    @Test void twoRealMinecraftCallsKeepTheirDescriptorsAndInstructionCounts() throws Exception {
        byte[] input = baseline(MINECRAFT);
        ClassNode before = parse(input);
        byte[] output = transform(MINECRAFT, input);
        ClassNode after = parse(output);
        for (String name : List.of("initClientHooks", "onRegisterParticleProviders")) {
            assertEquals(1, calls(before, NEO, name));
            assertEquals(0, calls(after, NEO, name));
            assertEquals(1, calls(after, KERNEL, name));
            MethodInsnNode redirected = call(after, KERNEL, name);
            assertEquals(name.equals("initClientHooks") ? INIT : PARTICLES, redirected.desc);
            assertEquals(Opcodes.INVOKESTATIC, redirected.getOpcode());
            assertFalse(redirected.itf);
        }
        assertEquals(instructionCount(before), instructionCount(after));
        assertSame(output, transform(MINECRAFT, output), "already redirected byte array must be returned unchanged");
        System.setProperty("forbric.forgeClientInit", "off");
        assertSame(input, transform(MINECRAFT, input), "off leaves both original calls intact");
    }

    @Test void missingDuplicateWrongDescriptorAndSplitConstructorAnchorsStandDownAtomically() throws Exception {
        byte[] input = baseline(MINECRAFT);
        for (String damage : List.of("missing", "duplicate", "descriptor", "different-constructor")) {
            ClassNode node = parse(input);
            MethodInsnNode target = call(node, NEO, "initClientHooks");
            MethodNode method = node.methods.stream().filter(m -> m.instructions.indexOf(target) >= 0).findFirst().orElseThrow();
            switch (damage) {
                case "missing" -> method.instructions.remove(target);
                case "duplicate" -> method.instructions.insert(target,
                        new MethodInsnNode(Opcodes.INVOKESTATIC, NEO, target.name, target.desc, false));
                case "descriptor" -> target.desc = "(Ljava/lang/Object;)V";
                case "different-constructor" -> {
                    method.instructions.remove(target);
                    MethodNode extra = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
                    extra.instructions.add(target); node.methods.add(extra);
                }
            }
            byte[] before = bytes(node);
            assertFalse(ForbricMergedBaseCompatTransformer.restoreForgeClientInit(node), damage);
            assertArrayEquals(before, bytes(node), damage + " must not partially redirect particles");
        }
    }

    @Test void geometryInitializationRunsAtEveryRealReloadEntryBeforeAnyAsyncWork() throws Exception {
        byte[] input = baseline(MODELS);
        byte[] output = transform(MODELS, input);
        ClassNode node = parse(output);
        MethodNode reload = method(node, "reload", RELOAD);
        AbstractInsnNode first = code(reload.instructions.getFirst());
        assertInstanceOf(MethodInsnNode.class, first);
        assertEquals(KERNEL, ((MethodInsnNode) first).owner);
        assertEquals("initGeometryLoaders", ((MethodInsnNode) first).name);
        assertEquals("()V", ((MethodInsnNode) first).desc);
        assertEquals(1, calls(node, KERNEL, "initGeometryLoaders"));
        assertEquals(0, calls(node, KERNEL, "initClientHooks"), "geometry must not be attached to the one-time client hook");
        assertDoesNotThrow(() -> new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, reload));
        assertSame(output, transform(MODELS, output));
        System.setProperty("forbric.forgeClientInit", "off");
        assertSame(input, transform(MODELS, input));
    }

    @Test void anExistingForgeGeometryCallAndAnUnrecognizedReloadBodyAreUntouched() throws Exception {
        byte[] input = baseline(MODELS);
        ClassNode existing = parse(input);
        method(existing, "reload", RELOAD).instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "net/minecraftforge/client/model/geometry/GeometryLoaderManager", "init", "()V", false));
        byte[] before = bytes(existing);
        assertFalse(ForbricMergedBaseCompatTransformer.restoreForgeGeometryReload(existing));
        assertArrayEquals(before, bytes(existing));

        // A reload body that does not run NeoForge's geometry loader is not this seam either.
        ClassNode unanchored = parse(input);
        MethodNode reload = method(unanchored, "reload", RELOAD);
        for (AbstractInsnNode insn : reload.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals("net/neoforged/neoforge/client/model/geometry/GeometryLoaderManager")
                    && call.name.equals("init")) reload.instructions.remove(insn);
        }
        before = bytes(unanchored);
        assertFalse(ForbricMergedBaseCompatTransformer.restoreForgeGeometryReload(unanchored));
        assertArrayEquals(before, bytes(unanchored));
    }

    @Test void runtimeCallsLinkAgainstBothActualCarriersAndMergedOptions() throws Exception {
        for (String family : List.of("forge", "neoforge")) {
            String owner = family.equals("forge") ? "net/minecraftforge/client/ForgeHooksClient" : NEO;
            ClassNode node = parse(read(TestFixtures.stagedRoot().resolve(family + "-runtime/" + family + "-runtime.jar"), owner));
            for (String name : List.of("initClientHooks", "onRegisterParticleProviders")) {
                MethodNode hook = method(node, name, name.equals("initClientHooks") ? INIT : PARTICLES);
                assertEquals(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        hook.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
            }
        }
        ClassNode options = parse(game("net/minecraft/client/Options"));
        assertTrue((method(options, "load", "(Z)V").access & Opcodes.ACC_PUBLIC) != 0);

        // MinecraftForge's own patched ModelManager inits ITS geometry loader; the merged base's inits NeoForge's,
        // which is why the kernel adds Forge's on top (the repair above).
        Path forgePatched = TestFixtures.stagedJar("forge-patched", "patched-mc-forge-");
        assumeTrue(forgePatched != null && Files.isRegularFile(forgePatched), "staged forge-patched base absent");
        ClassNode forgeModels = parse(read(forgePatched, MODELS));
        assertTrue(calls(forgeModels, "net/minecraftforge/client/model/geometry/GeometryLoaderManager", "init") >= 1,
                "MinecraftForge's patched reload runs its own geometry loader");
    }

    private static byte[] baseline(String owner) throws Exception {
        System.setProperty("forbric.forgeClientInit", "off");
        byte[] result = transform(owner, game(owner));
        System.clearProperty("forbric.forgeClientInit");
        return result;
    }
    private static byte[] transform(String owner, byte[] input) {
        return new ForbricMergedBaseCompatTransformer().transform(owner.replace('/', '.'), input, null);
    }
    private static byte[] game(String owner) throws Exception {
        Path base = TestFixtures.mergedBase();
        assumeTrue(base != null && Files.isRegularFile(base), "staged merged base absent");
        return read(base, owner);
    }
    private static byte[] read(Path jar, String owner) throws Exception {
        assumeTrue(Files.isRegularFile(jar), "staged jar absent: " + jar);
        try (ZipFile zip = new ZipFile(jar.toFile())) {return zip.getInputStream(zip.getEntry(owner + ".class")).readAllBytes();}
    }
    private static ClassNode parse(byte[] bytes) {ClassNode node = new ClassNode();new ClassReader(bytes).accept(node, 0);return node;}
    private static byte[] bytes(ClassNode node) {ClassWriter writer = new ClassWriter(0);node.accept(writer);return writer.toByteArray();}
    private static AbstractInsnNode code(AbstractInsnNode node) {while (node != null && node.getOpcode() < 0) node = node.getNext();return node;}
    private static MethodNode method(ClassNode node, String name, String desc) {return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow();}
    private static long instructionCount(ClassNode node) {return node.methods.stream().flatMap(m -> java.util.Arrays.stream(m.instructions.toArray())).filter(i -> i.getOpcode() >= 0).count();}
    private static long calls(ClassNode node, String owner, String name) {return node.methods.stream().flatMap(m -> java.util.Arrays.stream(m.instructions.toArray())).filter(i -> i instanceof MethodInsnNode c && owner.equals(c.owner) && name.equals(c.name)).count();}
    private static MethodInsnNode call(ClassNode node, String owner, String name) {return node.methods.stream().flatMap(m -> java.util.Arrays.stream(m.instructions.toArray())).filter(i -> i instanceof MethodInsnNode c && owner.equals(c.owner) && name.equals(c.name)).map(i -> (MethodInsnNode)i).findFirst().orElseThrow();}
}
