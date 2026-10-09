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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
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

import net.forbric.kernel.mixin.MergedBaseUncalledMethods;

/**
 * Pins that ONE {@link GuestClassScan} pass feeds every guest audit: the four audits used to each open every jar
 * and read every class for their own needles, and now each class's bytes reach all four from a single read. Each
 * of the four recorders is observed rising from a single scan over one synthetic jar.
 */
class GuestClassScanTest {
	private static final String FABRIC_NEEDLE = "net/fabricmc/fabric/api/loot/v3/LootTableEvents";
	private static final String ABI_MISSING = "net/neoforged/example/Missing";
	/** A shipped row of uncalled-methods.txt: {@code name + descriptor} is the key the guest scan records. */
	private static final String UNCALLED_KEY =
			"compareSort(Lnet/minecraft/client/KeyMapping$Category;Lnet/minecraft/client/KeyMapping$Category;)I";

	@AfterEach
	void clearSwitches() {
		System.clearProperty(FabricApiModuleLossAudit.SWITCH);
		System.clearProperty(FieldDriftAudit.SWITCH);
		System.clearProperty(AbiLinkAudit.SWITCH);
		FabricApiModuleLossAudit.reset();
		FieldDriftAudit.reset();
		AbiLinkAudit.reset();
		MergedBaseUncalledMethods.forgetGuests();
	}

	@Test
	void oneScanRecordsEveryAuditsFinding(@TempDir Path dir) throws Exception {
		Path jar = jar(dir, "guest.jar");
		List<Path> jars = List.of(jar);

		GuestClassScan.scan(jars, jars);

		assertTrue(FabricApiModuleLossAudit.users().values().stream().anyMatch(s -> s.contains("guest.jar")),
				"one scan must record the fabric-api surface user");
		assertTrue(FieldDriftAudit.hits().containsKey("guest.jar"),
				"one scan must record the drifted-field read");
		assertTrue(AbiLinkAudit.findings().stream().anyMatch(f -> f.jar().equals("guest.jar")),
				"one scan must record the dangling Forge-family reference");
		assertTrue(MergedBaseUncalledMethods.calledByGuest(UNCALLED_KEY),
				"one scan must record the guest call into an uncalled merged-base method");
	}

	@Test
	void aswitchedOffAuditIsNotFed(@TempDir Path dir) throws Exception {
		Path jar = jar(dir, "guest.jar");
		List<Path> jars = List.of(jar);

		System.setProperty(FieldDriftAudit.SWITCH, "off");
		GuestClassScan.scan(jars, jars);

		assertTrue(FieldDriftAudit.hits().isEmpty(), "a switched-off audit must not be handed bytes");
		assertTrue(FabricApiModuleLossAudit.users().values().stream().anyMatch(s -> s.contains("guest.jar")),
				"the other audits still run in the same pass");
	}

	@Test
	void everyAuditOffMeansNoReadAtAll(@TempDir Path dir) throws Exception {
		Path jar = jar(dir, "guest.jar");
		System.setProperty(FabricApiModuleLossAudit.SWITCH, "off");
		System.setProperty(FieldDriftAudit.SWITCH, "off");
		System.setProperty(AbiLinkAudit.SWITCH, "off");
		System.setProperty("forbric.mixinFit.liveness", "off");

		GuestClassScan.scan(List.of(jar), List.of(jar));

		assertTrue(FabricApiModuleLossAudit.users().isEmpty());
		assertFalse(FieldDriftAudit.enabled());
		System.clearProperty("forbric.mixinFit.liveness");
	}

	// --- fixtures -----------------------------------------------------------------------------------------------

	/** One jar whose single class carries all four audits' needles. */
	private static Path jar(Path dir, String name) throws Exception {
		Path path = dir.resolve(name);
		try (OutputStream file = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(file)) {
			zip.putNextEntry(new ZipEntry("test/Guest.class"));
			zip.write(guestClass());
			zip.closeEntry();
		}
		return path;
	}

	/** A class whose constant pool names a fabric-api surface, a drifted field, a dangling Forge class, and an uncalled method. */
	private static byte[] guestClass() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "test/Guest", null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_STATIC, "fabricSurface", "L" + FABRIC_NEEDLE + ";", null, null).visitEnd();
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "()V", null, null);
		mv.visitCode();
		mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/client/KeyMapping", "MAP", "Ljava/util/Map;");
		mv.visitInsn(Opcodes.ACONST_NULL);
		mv.visitInsn(Opcodes.ACONST_NULL);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/KeyMapping", "compareSort",
				"(Lnet/minecraft/client/KeyMapping$Category;Lnet/minecraft/client/KeyMapping$Category;)I", false);
		mv.visitInsn(Opcodes.POP);
		// A CONSTANT_Class owner: the ABI audit judges CONSTANT_Class entries, not type descriptors.
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, ABI_MISSING, "run", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}
