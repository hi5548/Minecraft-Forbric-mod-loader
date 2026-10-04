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

package net.fabricmc.loader.launch.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.classloading.LoaderProbePolicy.Family;
import net.forbric.kernel.fabric.KernelFabricLauncher;

/**
 * Pins the pre-0.15 launcher facade ({@code net.fabricmc.loader.launch.common}): loader 0.19.5 still ships it, and
 * a mod built before the package moved reaches it. The kernel shipped only the {@code impl} path, so LuckPerms
 * 5.4.140's {@code FabricClassPathAppender} died with
 * {@code NoClassDefFoundError: net/fabricmc/loader/launch/common/FabricLauncherBase} — a named dependency of a
 * sampled subject, so it booked the whole subject.
 *
 * <p>The first test is the caller's call shape, driven reflectively so it is RED while the facade is missing
 * (the {@code forName} is the failure) and GREEN once it exists. The second is the real jar: it asserts the exact
 * members LuckPerms names FROM the fixture, not from a hand-built stand-in, and skips when it is not staged.
 */
class LegacyFabricLauncherFacadeTest {
	private static final String BASE = "net.fabricmc.loader.launch.common.FabricLauncherBase";
	private static final String ITF = "net.fabricmc.loader.launch.common.FabricLauncher";
	private static final String PROBE = "forbrictest/legacy/Appended";
	private static final String PROBE_BINARY = "forbrictest.legacy.Appended";
	private static final Path LUCKPERMS = Path.of(System.getProperty("user.dir"), "run", "client-kernel", "mods",
			"LuckPerms-Fabric-5.4.140.jar").normalize();

	@Test
	void luckPermsAppendsAJarThroughTheLegacyFacade(@TempDir Path dir) throws Exception {
		Path added = jar(dir.resolve("appended.jar"), probe());
		Object implBefore = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher();
		Object legacyBefore = Class.forName(BASE).getMethod("getLauncher").invoke(null);
		try (ForbricClassLoader loader = new ForbricClassLoader(new URL[0], getClass().getClassLoader())) {
			KernelFabricLauncher.install(loader, EnvType.SERVER);

			// FabricClassPathAppender's shape: FabricLauncherBase.getLauncher().propose(jar).
			Object launcher = Class.forName(BASE).getMethod("getLauncher").invoke(null);
			assertNotNull(launcher, "the kernel must install the legacy launcher beside the impl-package twin");
			Class<?> itf = Class.forName(ITF);
			assertTrue(itf.isInstance(launcher), "the installed launcher must implement the legacy interface");
			itf.getMethod("propose", URL.class).invoke(launcher, added.toUri().toURL());

			Class<?> linked = Class.forName(PROBE_BINARY, true, loader);
			assertEquals(7, linked.getMethod("server").invoke(linked.getConstructor().newInstance()),
					"the appended jar must serve its classes through the loader");
			assertEquals(Family.FABRIC, loader.familyOfResource(PROBE_BINARY),
					"the legacy append must record the jar as Fabric's, as the impl-package append does — "
							+ "otherwise a client-only member in it survives the environment strip on a server");
		} finally {
			net.fabricmc.loader.impl.launch.FabricLauncherBase.setLauncher(
					(net.fabricmc.loader.impl.launch.FabricLauncher) implBefore);
			Class.forName(BASE).getMethod("setLauncher", Class.forName(ITF)).invoke(null, legacyBefore);
		}
	}

	@Test
	void everyMemberLuckPermsNamesFromTheLegacyPackageIsDeclared() throws Exception {
		assumeTrue(Files.isRegularFile(LUCKPERMS), "LuckPerms not staged — skipping the real-byte check");

		Set<String> owners = new TreeSet<>();
		Set<String> members = new TreeSet<>();
		try (ZipFile zip = new ZipFile(LUCKPERMS.toFile())) {
			for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
				ZipEntry entry = e.nextElement();
				if (!entry.getName().endsWith(".class")) continue;
				ClassNode node = new ClassNode();
				try (var in = zip.getInputStream(entry)) {
					new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				}
				for (MethodNode m : node.methods) {
					for (AbstractInsnNode i : m.instructions.toArray()) {
						if (i instanceof MethodInsnNode call && call.owner.startsWith("net/fabricmc/loader/launch/common/")) {
							owners.add(call.owner);
							members.add(call.owner + "." + call.name + call.desc);
						} else if (i instanceof FieldInsnNode field
								&& field.owner.startsWith("net/fabricmc/loader/launch/common/")) {
							owners.add(field.owner);
							members.add(field.owner + "." + field.name + ":" + field.desc);
						}
					}
				}
			}
		}

		assertEquals(Set.of("net/fabricmc/loader/launch/common/FabricLauncherBase",
						"net/fabricmc/loader/launch/common/FabricLauncher"), owners,
				"LuckPerms' legacy-package surface moved — re-derive the shim before trusting it");
		assertEquals(Set.of(
						"net/fabricmc/loader/launch/common/FabricLauncherBase.getLauncher()Lnet/fabricmc/loader/launch/common/FabricLauncher;",
						"net/fabricmc/loader/launch/common/FabricLauncher.propose(Ljava/net/URL;)V"),
				members, "the two members the fixture names");

		// And the shim declares them with exactly those descriptors, so the fixture links rather than nearly links.
		assertEquals(Class.forName(ITF), Class.forName(BASE).getMethod("getLauncher").getReturnType());
		assertEquals(void.class, Class.forName(ITF).getMethod("propose", URL.class).getReturnType());
	}

	// ------------------------------------------------------------------------------------------------ synthesis

	/** A class with one server method, so loading it proves the appended jar is really on the classpath. */
	private static byte[] probe() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, PROBE, null, "java/lang/Object", null);
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
		MethodVisitor server = cw.visitMethod(Opcodes.ACC_PUBLIC, "server", "()I", null, null);
		server.visitCode();
		server.visitIntInsn(Opcodes.BIPUSH, 7);
		server.visitInsn(Opcodes.IRETURN);
		server.visitMaxs(0, 0);
		server.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static Path jar(Path file, byte[]... classes) throws Exception {
		try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
			for (byte[] bytes : classes) {
				zip.putNextEntry(new ZipEntry(new ClassReader(bytes).getClassName() + ".class"));
				zip.write(bytes);
				zip.closeEntry();
			}
		}
		return file;
	}
}
