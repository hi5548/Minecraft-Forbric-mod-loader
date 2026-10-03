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

package net.forbric.kernel.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

import net.fabricmc.tinyremapper.IMappingProvider;

/**
 * {@link MixinShadowMembers} against the REAL mapping data: a mixin that shadows a member the way ferrite-core's
 * {@code BlockStateCacheMixin} does — an intermediary NAME on a field whose annotation names its target in
 * {@code targets} — must come back with the target's runtime name.
 *
 * <p>This is the pair no bytecode remapper reaches: a {@code @Shadow} member is a declaration of the guest, not a
 * reference into the game, and Mixin binds it by NAME at apply time. The names are the measured ones (obf {@code b}
 * → {@code collisionShape}, field {@code field_19360}).
 */
class MixinShadowMembersTest {
	private static final String MIXIN_CLASS = "example/FerriteMixin";
	private static final String TARGET = "net/minecraft/class_4970$class_4971$class_3752";
	private static final String FIELD = "field_19360";
	private static final String DESC = "Lnet/minecraft/class_265;";

	@Test
	void aShadowedFieldTakesTheTargetsRuntimeName(@TempDir Path dir) throws Exception {
		// The intermediary spelling of the target.
		assertRenamed(dir, TARGET);
	}

	/**
	 * The spelling the real mod uses: a Mojmap {@code @Mixin(targets=…)} (a hard-target string names the class the
	 * game actually runs) next to an intermediary shadow. Both halves must resolve, which is what
	 * {@link MixinShadowMembers} normalizes the target for.
	 */
	@Test
	void aShadowOnAMojmapSpelledTargetStillResolves(@TempDir Path dir) throws Exception {
		assertRenamed(dir, "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase$Cache");
	}

	private void assertRenamed(Path dir, String target) throws Exception {
		ForbricMappings spine = FabricGuestMappings.of(MappingFixtures.intermediary(), MappingFixtures.mojmap())
				.mappings();
		Path jar = dir.resolve("guest-" + target.hashCode() + ".jar");
		writeMixinJar(jar, target);

		IMappingProvider provider = MixinShadowMembers.withRenames(acceptor -> { }, jar, spine);
		List<String> renames = collect(provider);

		assertEquals(List.of(MIXIN_CLASS + "." + FIELD + DESC + " -> collisionShape"), renames,
				"the shadowed field must take the target's runtime name; anything else leaves Mixin binding a name "
						+ "the merged game does not have");

		// And through the ENGINE: the mapping layer is only useful if tiny-remapper applies it to the declaration.
		Path out = dir.resolve("remapped-" + target.hashCode() + ".jar");
		ForgeModRemapper.remapJar(jar, out, provider, List.of(), true);

		try (ZipFile zip = new ZipFile(out.toFile())) {
			byte[] bytes = zip.getInputStream(zip.getEntry(MIXIN_CLASS + ".class")).readAllBytes();
			org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
			new org.objectweb.asm.ClassReader(bytes).accept(node, org.objectweb.asm.ClassReader.SKIP_FRAMES);

			assertEquals(List.of("collisionShape"), node.fields.stream().map(field -> field.name).toList(),
					"the engine must rename the shadowed declaration itself, not only its uses");
		}
	}

	/** A mixin class exactly shaped like the measured one: {@code @Mixin(targets=…)} plus one {@code @Shadow} field. */
	private static void writeMixinJar(Path jar, String target) throws Exception {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MIXIN_CLASS, null, "java/lang/Object", null);

		AnnotationVisitor mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", true);
		AnnotationVisitor targets = mixin.visitArray("targets");
		targets.visit(null, target);
		targets.visitEnd();
		mixin.visitEnd();

		FieldVisitor field = writer.visitField(Opcodes.ACC_PROTECTED, FIELD, DESC, null, null);
		field.visitAnnotation("Lorg/spongepowered/asm/mixin/Shadow;", true).visitEnd();
		field.visitEnd();
		writer.visitEnd();

		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
			out.putNextEntry(new ZipEntry(MIXIN_CLASS + ".class"));
			out.write(writer.toByteArray());
			out.closeEntry();
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// An entry NAMED .class that ASM cannot read
	// ---------------------------------------------------------------------------------------------------------------

	/**
	 * CheaperGapples.jar carries a {@code .class} entry ASM cannot parse (empty/truncated), and the pass used to
	 * hand it straight to {@code ClassReader}, killing the whole subject's remap before a single shadow was renamed.
	 * The readable class beside it must still be processed, and the bad one must be named.
	 */
	@Test
	void anUnreadableClassEntryIsSkippedInsteadOfKillingTheRemap(@TempDir Path dir) throws Exception {
		ForbricMappings spine = spine();
		Path jar = dir.resolve("guest-broken.jar");
		writeMixinJar(jar, TARGET);
		appendEntry(jar, "example/Broken.class", new byte[0]);

		PrintStream err = System.err;
		ByteArrayOutputStream log = new ByteArrayOutputStream();
		List<String> renames;
		try {
			System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
			renames = collect(MixinShadowMembers.withRenames(acceptor -> { }, jar, spine));
		} finally {
			System.setErr(err);
		}

		assertEquals(List.of(MIXIN_CLASS + "." + FIELD + DESC + " -> collisionShape"), renames,
				"the readable class must still be processed");
		assertTrue(log.toString(StandardCharsets.UTF_8).contains("example/Broken.class"),
				"the skipped entry must be named, not silently ignored: " + log);
	}

	/**
	 * And the guard has to be the SAME one for the engine: skipping in the shadow scan alone only moves the death one
	 * step down, into tiny-remapper's own {@code error analyzing <entry> from <jar>}. The jar the engine is handed is
	 * cleansed first, so every pass behind sees only readable classes.
	 */
	@Test
	void theEngineOnlySeesReadableClasses(@TempDir Path dir) throws Exception {
		ForbricMappings spine = spine();
		Path clean = dir.resolve("guest-clean.jar");
		writeMixinJar(clean, TARGET);
		assertEquals(clean, ReadableClassEntries.readable(clean, dir), "a clean jar is not copied");

		Path broken = dir.resolve("guest-broken.jar");
		writeMixinJar(broken, TARGET);
		appendEntry(broken, "example/Broken.class", new byte[0]);

		Path readable = ReadableClassEntries.readable(broken, dir);
		assertNotEquals(broken, readable, "an unreadable entry must produce a cleansed copy");
		Path out = dir.resolve("remapped.jar");
		IMappingProvider provider = ForgeModRemapper.provider(spine, ForbricMappings.INTERMEDIARY,
				ForbricMappings.NAMED);
		ForgeModRemapper.remapJar(readable, out, MixinShadowMembers.withRenames(provider, readable, spine),
				List.of(), true); // the engine must not throw

		try (ZipFile zip = new ZipFile(out.toFile())) {
			assertNull(zip.getEntry("example/Broken.class"), "the unreadable entry is dropped");
			assertNotNull(zip.getEntry(MIXIN_CLASS + ".class"), "every readable class still ships");
		}
		Files.deleteIfExists(readable);
	}

	private static ForbricMappings spine() {
		return FabricGuestMappings.of(MappingFixtures.intermediary(), MappingFixtures.mojmap()).mappings();
	}

	/** The field renames a provider announces, as {@code owner.name+desc -> runtimeName}. */
	private static List<String> collect(IMappingProvider provider) {
		List<String> renames = new ArrayList<>();
		provider.load(new IMappingProvider.MappingAcceptor() {
			@Override public void acceptClass(String srcName, String dstName) { }

			@Override public void acceptMethod(IMappingProvider.Member member, String newName) { }

			@Override public void acceptMethodArg(IMappingProvider.Member member, int index, String newName) { }

			@Override public void acceptMethodVar(IMappingProvider.Member member, int index, int startOpIdx,
					int asmIndex, String newName) { }

			@Override public void acceptField(IMappingProvider.Member member, String newName) {
				renames.add(member.owner + "." + member.name + member.desc + " -> " + newName);
			}
		});
		return renames;
	}

	/** Adds an entry to an existing jar, so a real malformed one can sit beside a real readable class. */
	private static void appendEntry(Path jar, String name, byte[] bytes) throws Exception {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
				try (java.io.InputStream in = zip.getInputStream(entry)) {
					entries.put(entry.getName(), in.readAllBytes());
				}
			}
		}
		entries.put(name, bytes);
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				out.putNextEntry(new ZipEntry(entry.getKey()));
				out.write(entry.getValue());
				out.closeEntry();
			}
		}
	}
}
