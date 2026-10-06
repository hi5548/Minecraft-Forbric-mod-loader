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

package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.TestFixtures;

/**
 * Covers the 1.21.1 particle-provider read redirect against the REAL staged base and the REAL fabric-api build.
 *
 * <p>A synthetic fixture would be the wrong subject twice over: what can drift is what the merge emits (the one
 * {@code providers} field and its descriptor) and what fabric-api's accessor actually declares (the
 * {@code Int2ObjectMap} face and the {@code @Accessor} annotation), and both are read out of the shipped bytes
 * here. The 26.2 write-side splice this test used to cover is gone — see
 * {@link ForbricMergedBaseCompatTransformer#routeFabricParticleFactoriesThroughTheLiveMap} for why the two-field
 * shape cannot exist on this base.
 */
class MergedBaseParticleProvidersTest {
	private static final String ENGINE = "net/minecraft/client/particle/ParticleEngine";
	private static final String ENGINE_ENTRY = "net/minecraft/client/particle/ParticleEngine.class";
	private static final String ACCESSOR = "net.fabricmc.fabric.mixin.client.particle.ParticleManagerAccessor";
	private static final String ACCESSOR_ENTRY = "net/fabricmc/fabric/mixin/client/particle/ParticleManagerAccessor.class";
	private static final String NAME_KEYED = "Ljava/util/Map;";
	private static final String ID_KEYED = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;";

	/**
	 * The fact the whole re-derivation rests on: ONE field, keyed by {@code ResourceLocation}. If a base ever
	 * carried vanilla's {@code Int2ObjectMap} field beside it, the 26.2 write-side splice would be the right
	 * repair and this redirect would be wrong to apply.
	 */
	@Test
	void theBaseDeclaresExactlyOneResourceLocationKeyedProvidersField() throws Exception {
		ClassNode engine = parse(engine());
		List<FieldNode> providers = engine.fields.stream().filter(field -> "providers".equals(field.name)).toList();
		assertEquals(1, providers.size(),
				"expected 1.21.1's single `providers` field, found " + providers.stream()
						.map(field -> field.name + ':' + field.desc).toList());
		assertEquals(NAME_KEYED, providers.getFirst().desc,
				"the surviving field must be the ResourceLocation-keyed Map every reader of this base uses");
	}

	/** The engine's half: a bridge that reads the private field from inside the class that owns it. */
	@Test
	void theEngineCarriesTheProviderViewBridge() throws Exception {
		byte[] original = engine();
		byte[] repaired = new ForbricMergedBaseCompatTransformer().transform(ENGINE, original, null);
		assertNotSame(original, repaired);

		MethodNode bridge = method(parse(repaired), ForbricMergedBaseCompatTransformer.PROVIDER_VIEW,
				ForbricMergedBaseCompatTransformer.PROVIDER_VIEW_DESC);
		assertTrue((bridge.access & Opcodes.ACC_PUBLIC) != 0 && (bridge.access & Opcodes.ACC_STATIC) != 0,
				"the accessor mixin's default body has to be able to call it from outside the class");

		AbstractInsnNode[] body = bridge.instructions.toArray();
		assertEquals(Opcodes.ALOAD, body[0].getOpcode());
		assertInstanceOf(FieldInsnNode.class, body[1]);
		FieldInsnNode read = (FieldInsnNode) body[1];
		assertEquals(Opcodes.GETFIELD, read.getOpcode());
		assertEquals(ENGINE.replace('.', '/'), read.owner);
		assertEquals("providers", read.name);
		assertEquals(NAME_KEYED, read.desc, "the bridge must read the field that IS written, not a second one");
		assertInstanceOf(MethodInsnNode.class, body[2]);
		MethodInsnNode view = (MethodInsnNode) body[2];
		assertEquals("net/forbric/kernel/runtime/KernelParticleProviders", view.owner);
		assertEquals("intKeyedView", view.name);
		assertEquals("(Ljava/util/Map;)Ljava/lang/Object;", view.desc);
		assertEquals(Opcodes.ARETURN, body[3].getOpcode());
		assertEquals(4, body.length, "nothing else may be in the bridge: the view is built from the live map");
		new Analyzer<>(new BasicVerifier()).analyze(ENGINE.replace('.', '/'), bridge);
	}

	/**
	 * fabric-api's half, and the reason the fix cannot be caller-side only: the {@code @Accessor} has to go. Mixin
	 * resolves it by name AND descriptor, the descriptor it declares is the field this base does not have, and its
	 * failure takes the whole interface mixin — including the two sprite accessors — with it.
	 */
	@Test
	void theAccessorReadsThroughThatBridge() throws Exception {
		byte[] original = accessor();
		byte[] repaired = new ForbricMergedBaseCompatTransformer().transform(ACCESSOR, original, null);
		assertNotSame(original, repaired);
		ClassNode node = parse(repaired);

		MethodNode factories = method(node, "getFactories",
				"()Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;");
		assertTrue((factories.access & Opcodes.ACC_ABSTRACT) == 0,
				"getFactories must become a default method; Mixin cannot bind the field this base does not have");
		assertTrue((factories.access & Opcodes.ACC_SYNTHETIC) != 0,
				"getFactories must also be SYNTHETIC: a real default method makes Mixin read this interface mixin as "
						+ "its INTERFACE variant, whose target must be an interface, and the class ParticleEngine is "
						+ "not one — the whole config is then rejected");
		assertTrue(factories.visibleAnnotations == null || factories.visibleAnnotations.stream()
						.noneMatch(annotation -> "Lorg/spongepowered/asm/mixin/gen/Accessor;".equals(annotation.desc)),
				"the @Accessor annotation must be gone, or Mixin resolves it again and throws");

		AbstractInsnNode[] body = factories.instructions.toArray();
		assertEquals(Opcodes.ALOAD, body[0].getOpcode());
		assertInstanceOf(TypeInsnNode.class, body[1]);
		assertEquals(ENGINE.replace('.', '/'), ((TypeInsnNode) body[1]).desc);
		assertInstanceOf(MethodInsnNode.class, body[2]);
		MethodInsnNode bridge = (MethodInsnNode) body[2];
		assertEquals(ENGINE.replace('.', '/'), bridge.owner);
		assertEquals(ForbricMergedBaseCompatTransformer.PROVIDER_VIEW, bridge.name);
		assertEquals(ForbricMergedBaseCompatTransformer.PROVIDER_VIEW_DESC, bridge.desc);
		assertInstanceOf(TypeInsnNode.class, body[3]);
		assertEquals(Opcodes.CHECKCAST, body[3].getOpcode());
		assertEquals("it/unimi/dsi/fastutil/ints/Int2ObjectMap", ((TypeInsnNode) body[3]).desc,
				"the caller puts ints into it, so the Object the kernel seam returns has to be cast to its own face");
		assertEquals(Opcodes.ARETURN, body[4].getOpcode());
		assertEquals(5, body.length);
		new Analyzer<>(new BasicVerifier()).analyze(ACCESSOR.replace('.', '/'), factories);

		// The two accessors beside it bind on this base (both descriptors are Map), and must stay as shipped.
		for (String name : List.of("getParticleAtlasTexture", "getSpriteAwareFactories")) {
			MethodNode accessor = node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
			assertTrue((accessor.access & Opcodes.ACC_ABSTRACT) != 0, name + " must stay an @Accessor method");
			assertNotNull(accessor.visibleAnnotations);
			assertTrue(accessor.visibleAnnotations.stream()
							.anyMatch(annotation -> "Lorg/spongepowered/asm/mixin/gen/Accessor;".equals(annotation.desc)),
					name + " must keep its @Accessor");
		}
	}

	/**
	 * The probe the repair's own javadoc rests on: ask Mixin's own classifier what it makes of the rewritten bytes,
	 * and of the same bytes with the SYNTHETIC flag cleared.
	 *
	 * <p>It is a PAIR on purpose. Asserting only that the rewritten mixin classifies as ACCESSOR would pass even if
	 * this transformer did nothing at all — the shipped accessor has three {@code @Accessor} methods and is an
	 * accessor mixin already. The load-bearing fact is the difference: clearing one flag is exactly the bug that
	 * rejected the config in the 2026-10-06 Create boot ({@code @Mixin target type mismatch: … ParticleEngine is not
	 * an interface}), so the control reproduces that classification from the same bytes.
	 *
	 * <p>{@code getVariant} is package-private in Mixin and there is no public equivalent; reflection is the only way
	 * to ask the classifier itself rather than a re-implementation of it, and a re-implementation is what a probe
	 * must never be. Two details are load-bearing: {@code getVariant} reads
	 * {@code MixinEnvironment.getCurrentEnvironment()} through {@code ClassInfo}'s constructor, so Mixin's own
	 * bootstrap has to have run; and {@code ClassInfo.fromClassNode} CACHES by class name, so each node is renamed
	 * before it is asked — asking twice under one name would answer from the first node's leaves either way.
	 */
	@Test
	void mixinClassifiesTheRewrittenAccessorAsAnAccessorMixin() throws Exception {
		org.spongepowered.asm.launch.MixinBootstrap.init();
		org.spongepowered.asm.mixin.MixinEnvironment.getDefaultEnvironment();

		byte[] repaired = new ForbricMergedBaseCompatTransformer().transform(ACCESSOR, accessor(), null);
		ClassNode after = parse(repaired);
		after.name = after.name + "$RepairedProbe";
		assertEquals("ACCESSOR", mixinVariant(after),
				"the rewritten mixin must stay the ACCESSOR variant, the one that accepts the class target "
						+ "ParticleEngine; the INTERFACE variant demands an interface target");

		ClassNode control = parse(repaired);
		control.name = control.name + "$ControlProbe";
		method(control, "getFactories", "()Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;").access &= ~Opcodes.ACC_SYNTHETIC;
		assertEquals("INTERFACE", mixinVariant(control),
				"without the flag the same bytes classify as the INTERFACE variant — this is the rejection the flag "
						+ "exists to avoid, so the assertion above is not vacuous");
	}

	/** Mixin's own {@code MixinInfo.getVariant(ClassNode)}: the classifier whose answer decides the boot. */
	private static String mixinVariant(ClassNode node) throws Exception {
		Class<?> mixinInfo = Class.forName("org.spongepowered.asm.mixin.transformer.MixinInfo");
		java.lang.reflect.Method getVariant = mixinInfo.getDeclaredMethod("getVariant", ClassNode.class);
		getVariant.setAccessible(true);
		return getVariant.invoke(null, node).toString();
	}

	@Test
	void aSecondPassLeavesBothHalvesAlone() throws Exception {
		ForbricMergedBaseCompatTransformer once = new ForbricMergedBaseCompatTransformer();
		byte[] engine = once.transform(ENGINE, engine(), null);
		assertSame(engine, once.transform(ENGINE, engine, null),
				"a second pass over the engine must stand down, or every reload stacks another bridge");

		ForbricMergedBaseCompatTransformer twice = new ForbricMergedBaseCompatTransformer();
		byte[] accessor = twice.transform(ACCESSOR, accessor(), null);
		assertSame(accessor, twice.transform(ACCESSOR, accessor, null),
				"a second pass over the accessor must stand down — its body is no longer abstract");
	}

	@Test
	void anotherClassIsUntouched() throws Exception {
		assumeTrue(TestFixtures.mergedBase() != null, "staged merged base absent");
		byte[] other = read(TestFixtures.mergedBase(),
				"net/minecraft/client/particle/ParticleProvider.class");
		assumeTrue(other != null, "ParticleProvider absent from this base");
		assertSame(other, new ForbricMergedBaseCompatTransformer()
				.transform("net.minecraft.client.particle.ParticleProvider", other, null));
	}

	private static byte[] engine() throws IOException {
		Path base = TestFixtures.mergedBase();
		assumeTrue(base != null && Files.isRegularFile(base), "staged merged base absent");
		byte[] bytes = read(base, ENGINE_ENTRY);
		assumeTrue(bytes != null, "ParticleEngine absent from this base");
		return bytes;
	}

	/**
	 * The accessor as the guest jar ships it: inside fabric-api's nested {@code fabric-particles-v1} module, which
	 * is the unit the kernel remaps and loads. Read through both zips — the module is not a file on disk.
	 */
	private static byte[] accessor() throws IOException {
		String configured = System.getProperty("forbric.fabricApi");
		assumeTrue(configured != null && Files.isRegularFile(Path.of(configured)), "fabric-api fixture absent");
		try (ZipFile api = new ZipFile(configured)) {
			ZipEntry module = api.stream()
					.filter(entry -> entry.getName().startsWith("META-INF/jars/fabric-particles-v1-"))
					.findFirst().orElse(null);
			assumeTrue(module != null, "fabric-api carries no fabric-particles-v1 module");
			try (ZipInputStream nested = new ZipInputStream(api.getInputStream(module))) {
				for (ZipEntry entry; (entry = nested.getNextEntry()) != null; ) {
					if (ACCESSOR_ENTRY.equals(entry.getName())) return nested.readAllBytes();
				}
			}
		}
		throw new AssertionError("the fixture's fabric-particles-v1 does not carry " + ACCESSOR);
	}

	private static byte[] read(Path jar, String entry) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			return found == null ? null : zip.getInputStream(found).readAllBytes();
		}
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		MethodNode method = node.methods.stream()
				.filter(candidate -> candidate.name.equals(name) && candidate.desc.equals(desc))
				.findFirst().orElse(null);
		assertNotNull(method, node.name + " has no " + name + desc);
		return method;
	}
}
