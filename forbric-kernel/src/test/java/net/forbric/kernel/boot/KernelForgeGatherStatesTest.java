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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * MinecraftForge's gather states are recorded as completed once the kernel has run them, so the merged
 * {@code Sheets} stops reporting it was "loaded too early" — and nothing is recorded while Forge's loading state
 * is invalid or with the switch off.
 */
@ResourceLock("system-properties")
class KernelForgeGatherStatesTest {
	private static final String MOD_LOADER = "net/minecraftforge/fml/ModLoader";

	@AfterEach
	void reset() {
		System.clearProperty(KernelLifecycle.FORGE_LOADING_STATES);
	}

	@Test
	void everyGatherStateIsRecordedOnceTheKernelHasRunThem() throws Exception {
		ClassLoader cl = fixture(true);
		Object loadRegistries = state(cl, "net.minecraftforge.common.ForgeStatesProvider", "LOAD_REGISTRIES");
		assertFalse(completed(cl).contains(loadRegistries), "the stage the kernel replaced never recorded anything");

		assertEquals(6, KernelLifecycle.publishForgeGatherStates(cl));
		assertTrue(completed(cl).contains(loadRegistries), "Sheets' question now has the answer MinecraftForge would give");
		for (String entry : KernelLifecycle.FORGE_GATHER_STATES) {
			int hash = entry.indexOf('#');
			assertTrue(completed(cl).contains(state(cl, entry.substring(0, hash), entry.substring(hash + 1))), entry);
		}
		assertEquals(0, KernelLifecycle.publishForgeGatherStates(cl), "a second call adds and re-runs nothing");
	}

	@Test
	void anInvalidLoadingStateCompletesNothing() throws Exception {
		ClassLoader cl = fixture(false);
		assertEquals(0, KernelLifecycle.publishForgeGatherStates(cl));
		assertFalse(completed(cl).contains(state(cl, "net.minecraftforge.common.ForgeStatesProvider", "LOAD_REGISTRIES")),
				"a failed load does not complete its states, natively or here");
	}

	@Test
	void theSwitchLeavesTheSetAsItWas() throws Exception {
		System.setProperty(KernelLifecycle.FORGE_LOADING_STATES, "off");
		ClassLoader cl = fixture(true);
		assertEquals(0, KernelLifecycle.publishForgeGatherStates(cl));
		assertFalse(completed(cl).contains(state(cl, "net.minecraftforge.common.ForgeStatesProvider", "LOAD_REGISTRIES")));
	}

	/** The server records them at the end of its registration window, the client after the deferred Forge mods. */
	@Test
	void theyAreRecordedAfterTheLastForgeRegisterEventStreamOnEachSide() throws Exception {
		ClassNode node = new ClassNode();
		try (var input = KernelLifecycle.class.getResourceAsStream("KernelLifecycle.class")) {
			new ClassReader(input).accept(node, 0);
		}
		assertTrue(calledAfter(node, "registerNeoForgeContent", "register", "publishForgeGatherStates"),
				"the server records them after the traditional-Forge RegisterEvent stream");
		assertTrue(calledAfter(node, "constructDeferredForgeMods", "fireRegisterEvents", "publishForgeGatherStates"),
				"the client records them after the deferred Forge mods' RegisterEvent stream");
	}

	/**
	 * The real carrier still keeps the set, reads it the way the fixture does, and builds these six as GATHER states.
	 * If a carrier bump moved any of that, the kernel would be recording states the carrier no longer means.
	 */
	@Test
	void theCarrierKeepsTheSetAndTheseAreItsGatherStates() throws Exception {
		Path jar = carrier();
		assumeTrue(Files.isRegularFile(jar), jar + " absent");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ClassNode loader = read(zip, MOD_LOADER);
			// PORT(1.21.1): Forge 52's ModLoader is an instance: a per-loader `completedStates` Set<IModLoadingState>,
			// reached through ModLoader.get(), and hasCompletedState(String) looks a state up by name then contains().
			FieldNode set = loader.fields.stream().filter(f -> f.name.equals("completedStates")).findFirst().orElseThrow();
			assertEquals("Ljava/util/Set;", set.desc);
			assertTrue((set.access & Opcodes.ACC_STATIC) == 0, "the completed set is per-loader now, not static");
			assertTrue(loader.methods.stream().anyMatch(m -> m.name.equals("get")
					&& (m.access & Opcodes.ACC_STATIC) != 0), "the loader is reached through ModLoader.get()");
			MethodNode has = loader.methods.stream().filter(m -> m.name.equals("hasCompletedState")).findFirst().orElseThrow();
			assertTrue(reads(has, "completedStates") && calls(has, "contains"), "hasCompletedState is a set lookup");

			Map<String, String> phases = new HashMap<>();
			phases.putAll(phasesOf(read(zip, "net/minecraftforge/fml/core/ModStateProvider")));
			phases.putAll(phasesOf(read(zip, "net/minecraftforge/common/ForgeStatesProvider")));
			for (String entry : KernelLifecycle.FORGE_GATHER_STATES) {
				String field = entry.substring(entry.indexOf('#') + 1);
				assertEquals("GATHER", phases.get(field), entry + " is not a GATHER state on this carrier");
			}
			// The freeze field is FREEZE (its ModLoadingState name is FREEZE_DATA), and it is COMPLETE, not claimed.
			assertEquals("COMPLETE", phases.get("FREEZE"), "the freeze is not claimed, and is not a gather state");
		}
	}

	/**
	 * The tripwire for a new reader of Forge's completed-state set.
	 *
	 * <p>PORT(1.21.1): in 26.2 the merged base's {@code Sheets.<clinit>} asked
	 * {@code ModLoader.hasCompletedState(LOAD_REGISTRIES)} to decide whether it had loaded too early. The 1.21.1
	 * merge took NeoForge's guard instead ({@code CommonModLoader.areRegistriesLoaded()}), so nothing in the
	 * staged merged base or either runtime reads Forge's set (verified by scanning for callers, not by name).
	 * The kernel still records the gather states — a Forge mod may ask, and a Forge-won merge would read them —
	 * and this pins that no NEW reader appeared silently.
	 */
	@Test
	void noStagedClassReadsForgeCompletedStates() throws Exception {
		Path run = run();
		// The merged base carries the Minecraft version in its name; TestFixtures resolves whichever was staged.
		List<Path> jars = List.of(net.forbric.kernel.TestFixtures.mergedBase(), carrier(),
				run.resolve("neoforge-runtime/neoforge-runtime.jar"));
		for (Path jar : jars) assumeTrue(jar != null && Files.isRegularFile(jar), jar + " absent");
		TreeSet<String> readers = new TreeSet<>();
		byte[] needle = "hasCompletedState".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		for (Path jar : jars) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
					if (!entry.getName().endsWith(".class")) continue;
					byte[] bytes = zip.getInputStream(entry).readAllBytes();
					if (!net.forbric.kernel.util.ByteScan.contains(bytes, needle)) continue;
					ClassNode node = new ClassNode();
					new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
					for (MethodNode method : node.methods) {
						for (AbstractInsnNode insn : method.instructions) {
							if (insn instanceof MethodInsnNode call && call.owner.equals(MOD_LOADER)
									&& call.name.equals("hasCompletedState")) readers.add(node.name);
						}
					}
				}
			}
		}
		assertEquals(new TreeSet<>(), readers,
				"something new reads ModLoader.hasCompletedState — decide whether it needs the gathered states");
	}

	private static boolean calledAfter(ClassNode node, String method, String first, String then) {
		MethodNode m = node.methods.stream().filter(x -> x.name.equals(method)).findFirst().orElseThrow();
		int seen = -1, at = -1;
		for (int i = 0; i < m.instructions.size(); i++) {
			if (!(m.instructions.get(i) instanceof MethodInsnNode call)) continue;
			if (call.name.equals(first)) seen = i;
			if (call.name.equals(then)) at = i;
		}
		return seen >= 0 && at > seen;
	}

	/**
	 * Which phase each field of a states class is built with: the ModLoadingPhase or gather/complete call before
	 * its put. PORT(1.21.1): on Forge 52 the states are instance fields built in the constructor with PUTFIELD, not
	 * static fields in {@code <clinit>} with PUTSTATIC, so both method bodies and both put opcodes are read.
	 */
	private static Map<String, String> phasesOf(ClassNode states) {
		Map<String, String> out = new HashMap<>();
		for (MethodNode init : states.methods) {
			if (!init.name.equals("<clinit>") && !init.name.equals("<init>")) continue;
			String phase = null;
			for (AbstractInsnNode insn : init.instructions) {
				if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
						&& field.owner.equals("net/minecraftforge/fml/ModLoadingPhase")) phase = field.name;
				if (insn instanceof MethodInsnNode call && call.owner.equals(states.name) && call.getOpcode() == Opcodes.INVOKESTATIC) {
					if (call.name.equals("gather")) phase = "GATHER";
					if (call.name.equals("complete")) phase = "COMPLETE";
				}
				if (insn instanceof FieldInsnNode field && (field.getOpcode() == Opcodes.PUTSTATIC || field.getOpcode() == Opcodes.PUTFIELD)
						&& field.owner.equals(states.name)) {
					out.put(field.name, phase);
					phase = null;
				}
			}
		}
		return out;
	}

	private static boolean reads(MethodNode m, String field) {
		for (AbstractInsnNode insn : m.instructions) if (insn instanceof FieldInsnNode f && f.name.equals(field)) return true;
		return false;
	}

	private static boolean calls(MethodNode m, String name) {
		for (AbstractInsnNode insn : m.instructions) if (insn instanceof MethodInsnNode c && c.name.equals(name)) return true;
		return false;
	}

	private static ClassNode read(ZipFile zip, String internal) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(zip.getInputStream(zip.getEntry(internal + ".class")).readAllBytes()).accept(node, 0);
		return node;
	}

	private static Path run() {
		return Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run").normalize();
	}

	private static Path carrier() {
		return run().resolve("merged-base/forge-runtime-interop.jar");
	}

	private static Object state(ClassLoader cl, String holder, String field) throws Exception {
		return Class.forName(holder, true, cl).getField(field).get(null);
	}

	private static Set<?> completed(ClassLoader cl) throws Exception {
		Object loader = cl.loadClass(MOD_LOADER.replace('/', '.')).getMethod("get").invoke(null);
		Field field = loader.getClass().getDeclaredField("completedStates");
		field.setAccessible(true);
		return (Set<?>) field.get(loader);
	}

	/**
	 * The carrier's shape in miniature: a static {@code get()} returning the one loader, the per-loader
	 * {@code completedStates} set, the validity flag, and the two state holders. Mirrors Forge 52 (1.21.1), where
	 * ModLoader is an instance; the 26.2 fixture's static COMPLETED_STATES / static hasCompletedState(Object).
	 */
	private static ClassLoader fixture(boolean valid) {
		Map<String, byte[]> classes = new HashMap<>();
		ClassWriter loader = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		loader.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, MOD_LOADER, null, "java/lang/Object", null);
		loader.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "INSTANCE", "L" + MOD_LOADER + ";", null, null).visitEnd();
		loader.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "completedStates", "Ljava/util/Set;", null, null).visitEnd();
		loader.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "loadingStateValid", "Z", null, null).visitEnd();
		MethodVisitor clinit = loader.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		clinit.visitCode();
		clinit.visitTypeInsn(Opcodes.NEW, MOD_LOADER);
		clinit.visitInsn(Opcodes.DUP);
		clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, MOD_LOADER, "<init>", "()V", false);
		clinit.visitFieldInsn(Opcodes.PUTSTATIC, MOD_LOADER, "INSTANCE", "L" + MOD_LOADER + ";");
		clinit.visitInsn(valid ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
		clinit.visitFieldInsn(Opcodes.PUTSTATIC, MOD_LOADER, "loadingStateValid", "Z");
		clinit.visitInsn(Opcodes.RETURN);
		clinit.visitMaxs(0, 0);
		clinit.visitEnd();
		MethodVisitor init = loader.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitTypeInsn(Opcodes.NEW, "java/util/HashSet");
		init.visitInsn(Opcodes.DUP);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashSet", "<init>", "()V", false);
		init.visitFieldInsn(Opcodes.PUTFIELD, MOD_LOADER, "completedStates", "Ljava/util/Set;");
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
		MethodVisitor get = loader.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "get", "()L" + MOD_LOADER + ";", null, null);
		get.visitCode();
		get.visitFieldInsn(Opcodes.GETSTATIC, MOD_LOADER, "INSTANCE", "L" + MOD_LOADER + ";");
		get.visitInsn(Opcodes.ARETURN);
		get.visitMaxs(0, 0);
		get.visitEnd();
		MethodVisitor validity = loader.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "isLoadingStateValid", "()Z", null, null);
		validity.visitCode();
		validity.visitFieldInsn(Opcodes.GETSTATIC, MOD_LOADER, "loadingStateValid", "Z");
		validity.visitInsn(Opcodes.IRETURN);
		validity.visitMaxs(0, 0);
		validity.visitEnd();
		loader.visitEnd();
		classes.put(MOD_LOADER.replace('/', '.'), loader.toByteArray());

		Map<String, List<String>> holders = new HashMap<>();
		for (String entry : KernelLifecycle.FORGE_GATHER_STATES) {
			int hash = entry.indexOf('#');
			holders.computeIfAbsent(entry.substring(0, hash), k -> new ArrayList<>()).add(entry.substring(hash + 1));
		}
		holders.forEach((holder, fields) -> classes.put(holder, holderClass(holder.replace('.', '/'), fields)));
		return new ClassLoader(KernelForgeGatherStatesTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException {
				byte[] bytes = classes.get(name);
				if (bytes == null) throw new ClassNotFoundException(name);
				return defineClass(name, bytes, 0, bytes.length);
			}
		};
	}

	private static byte[] holderClass(String internal, List<String> fields) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
		MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		clinit.visitCode();
		for (String field : fields) {
			writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, field, "Ljava/lang/Object;", null, null).visitEnd();
			clinit.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
			clinit.visitInsn(Opcodes.DUP);
			clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
			clinit.visitFieldInsn(Opcodes.PUTSTATIC, internal, field, "Ljava/lang/Object;");
		}
		clinit.visitInsn(Opcodes.RETURN);
		clinit.visitMaxs(0, 0);
		clinit.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}
}
