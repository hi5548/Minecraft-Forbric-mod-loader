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

package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import net.forbric.kernel.classloading.LoaderProbePolicy;
import net.forbric.kernel.transform.FmlContextLoaderRewriter;

/**
 * PORT(1.21.1): FML's {@code TransformingClassLoader} view is a documented no-op on this generation.
 *
 * <p>On 26.2 {@code KernelFmlTransformerView.contextLoader} fabricated FML's ClassProcessor graph around the kernel's
 * live Mixin weaver, so a NeoForge mod (LibJF's ASM layer) that casts the context loader to
 * {@code net.neoforged.fml.classloading.transformation.TransformingClassLoader} could walk to the weaver. NeoForge
 * 21.1 has no such class and no such graph — this carrier is ModLauncher 11-based — so the runtime answers the REAL
 * loader, the guest's cast then fails, and it runs without its class patches, exactly as it did before the shim.
 *
 * <p>What remains load-bearing is that the seam is unchanged: {@code FmlContextLoaderRewriter} still injects the
 * call between {@code Thread.getContextClassLoader} and the cast, so porting a real 21.1 view later is a body change,
 * not a rewiring. These tests pin that injection and that the current body is the identity.
 */
class FmlTransformerViewTest {
	private static final String PROBE = "com.example.libjf.MixinPlugin";
	private static final String TRANSFORMING_LOADER = "net/neoforged/fml/classloading/transformation/TransformingClassLoader";
	private static final String VIEW = "net/forbric/kernel/runtime/KernelFmlTransformerView";

	private final IMixinTransformer kernelWeaver = weaver();

	@BeforeEach
	@AfterEach
	void clean() {
		MixinWeaverSlot.reset();
		System.clearProperty(MixinWeaverSlot.SWITCH);
	}

	@Test
	void theRewriterStillInjectsTheSeamForANeoForgeModThatWalksTheWeaver() {
		byte[] rewritten = rewritten(plugin());
		ClassNode node = parse(rewritten);
		int injected = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(VIEW) && call.name.equals("contextLoader")) {
					assertEquals("(Ljava/lang/ClassLoader;)Ljava/lang/ClassLoader;", call.desc);
					injected++;
				}
			}
		}
		assertEquals(1, injected, "exactly one contextLoader call must sit between the loader and its cast");
		assertTrue(containsCast(node), "the cast stays: a failing view must fail exactly where it always did");
	}

	@Test
	void theViewAnswersTheRealLoaderOnThisGeneration() throws Exception {
		Path compiled = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
		assumeTrue(Files.isDirectory(compiled), "runtime classes not compiled yet");
		try (java.net.URLClassLoader loader = new java.net.URLClassLoader(new java.net.URL[] {compiled.toUri().toURL()})) {
			Class<?> view = Class.forName(VIEW.replace('/', '.'), true, loader);
			ClassLoader real = loader;
			Object answer = view.getMethod("contextLoader", ClassLoader.class).invoke(null, real);
			assertSame(real, answer, "1.21.1 has no ClassProcessor graph, so the guest gets its own loader back");
		}
	}

	@Test
	void anAlreadyRewrittenPluginIsIdempotent() {
		byte[] once = rewritten(plugin());
		assertSame(once, rewritten(once), "a second pass finds its own call and stands down");
	}

	@Test
	void switchedOffNothingIsRewritten() {
		System.setProperty(MixinWeaverSlot.SWITCH, "off");
		byte[] original = plugin();
		assertSame(original, rewritten(original));
	}

	@Test
	void aNonNeoForgeGuestsClassIsNotTouched() {
		byte[] original = plugin();
		byte[] out = new FmlContextLoaderRewriter(name -> LoaderProbePolicy.Family.FORGE).transform(PROBE, original, null);
		assertSame(original, out);
	}

	@Test
	void withoutTheMarkerMethodNothingIsRewritten() {
		byte[] noWalk = pluginWithoutTheWalkedField();
		assertSame(noWalk, rewritten(noWalk), "the field-name marker is what proves the cast is a walk to the weaver");
	}

	@Test
	void theSlotAnswersWithTheFallbackUntilSomethingIsWatched() {
		IMixinTransformer fallback = weaver();
		assertSame(fallback, MixinWeaverSlot.currentOr(fallback));
		MixinWeaverSlot.watch(() -> "not a transformer");
		assertSame(fallback, MixinWeaverSlot.currentOr(fallback));
		IMixinTransformer wrapper = weaver();
		MixinWeaverSlot.watch(() -> wrapper);
		assertSame(wrapper, MixinWeaverSlot.currentOr(fallback));
	}

	/** A second boot in one process weaves with its own Mixin, not through the first boot's view of the old one. */
	@Test
	void installingAWeaverForgetsTheSlotOfTheLastOne() {
		MixinWeaverSlot.install(weaver());
		IMixinTransformer oldWrapper = weaver();
		MixinWeaverSlot.watch(() -> oldWrapper);

		IMixinTransformer next = weaver();
		MixinWeaverSlot.install(next);
		assertSame(next, MixinWeaverSlot.currentOr(next));
	}

	/**
	 * The kernel's class pipeline weaves through the slot, not through the transformer it captured at bootstrap:
	 * the captured one is exactly the field nobody would read after a wrapper replaced it.
	 */
	@Test
	void theKernelWeavesWithWhatTheSlotHolds() throws Exception {
		Path compiled = Path.of("build", "classes", "java", "main", "net", "forbric", "kernel", "mixin",
				"KernelMixinBootstrap.class");
		assumeTrue(Files.isRegularFile(compiled), "main classes not compiled yet");
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		List<String> weaving = new ArrayList<>();
		for (MethodNode method : node.methods) {
			boolean weaves = false;
			boolean throughSlot = false;
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof MethodInsnNode call)) continue;
				if (call.name.equals("transformClassBytes")) weaves = true;
				if (call.owner.equals("net/forbric/kernel/mixin/MixinWeaverSlot") && call.name.equals("currentOr")) {
					throughSlot = true;
				}
			}
			if (weaves) {
				weaving.add(method.name);
				assertTrue(throughSlot, method.name + " weaves with a transformer it did not read from MixinWeaverSlot");
			}
		}
		assertTrue(!weaving.isEmpty(), "found no weaving call to check");
	}

	private static byte[] rewritten(byte[] plugin) {
		return new FmlContextLoaderRewriter(name -> LoaderProbePolicy.Family.NEOFORGE).transform(PROBE, plugin, null);
	}

	private static boolean containsCast(ClassNode node) {
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof org.objectweb.asm.tree.TypeInsnNode cast
						&& cast.getOpcode() == Opcodes.CHECKCAST && TRANSFORMING_LOADER.equals(cast.desc)) return true;
			}
		}
		return false;
	}

	/**
	 * {@code "classTransformer"; return (TransformingClassLoader) Thread.currentThread().getContextClassLoader();} —
	 * the field name is what marks the method as one that walks to the weaver, which the rewriter requires.
	 */
	private static byte[] plugin() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, PROBE.replace('.', '/'), null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "onLoad", "()Ljava/lang/Object;", null, null);
		mv.visitCode();
		mv.visitLdcInsn("classTransformer");
		mv.visitInsn(Opcodes.POP);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getContextClassLoader", "()Ljava/lang/ClassLoader;", false);
		mv.visitTypeInsn(Opcodes.CHECKCAST, TRANSFORMING_LOADER);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** The same cast, but with no {@code "classTransformer"} marker in the method. */
	private static byte[] pluginWithoutTheWalkedField() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, PROBE.replace('.', '/'), null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "onLoad", "()Ljava/lang/Object;", null, null);
		mv.visitCode();
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getContextClassLoader", "()Ljava/lang/ClassLoader;", false);
		mv.visitTypeInsn(Opcodes.CHECKCAST, TRANSFORMING_LOADER);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static IMixinTransformer weaver() {
		return (IMixinTransformer) java.lang.reflect.Proxy.newProxyInstance(IMixinTransformer.class.getClassLoader(),
				new Class<?>[] {IMixinTransformer.class}, (proxy, method, args) -> switch (method.getName()) {
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == args[0];
					case "toString" -> "weaver@" + Integer.toHexString(System.identityHashCode(proxy));
					default -> null;
				});
	}
}
