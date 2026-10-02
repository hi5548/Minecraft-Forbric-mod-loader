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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
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

		List<String> renames = new ArrayList<>();
		IMappingProvider provider = MixinShadowMembers.withRenames(acceptor -> { }, jar, spine);
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

		assertEquals(List.of(MIXIN_CLASS + "." + FIELD + DESC + " -> collisionShape"), renames,
				"the shadowed field must take the target's runtime name; anything else leaves Mixin binding a name "
						+ "the merged game does not have");

		// And through the ENGINE: the mapping layer is only useful if tiny-remapper applies it to the declaration.
		Path out = dir.resolve("remapped-" + target.hashCode() + ".jar");
		ForgeModRemapper.remapJar(jar, out, provider, List.of(), true);

		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(out.toFile())) {
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
}
