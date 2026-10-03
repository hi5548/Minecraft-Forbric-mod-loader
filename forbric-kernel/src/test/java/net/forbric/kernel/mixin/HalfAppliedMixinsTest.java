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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * The half-applied state a failed mixin leaves in its target, and the repair that stops it becoming a
 * {@code VerifyError} for the whole class.
 *
 * <p>The fixture is the measured balm shape: Mixin has already merged a mixin's handlers into the target when an
 * injector throws, and MixinExtras's sugar rewrite stops half way — the descriptor loses the sugar parameter
 * (which is why the injected call failed to bind) and the body keeps using it. A STATIC method declared
 * {@code (Object)Object} whose first instruction is {@code aload_1} is byte for byte what HotSpot reports as
 * "Type top (current frame, locals[1]) is not assignable to reference type" — the campaign's
 * {@code CropBlock.localvar$zzd000$balm$getGrowthSpeedCaptureLocals} error, raised at LINK time, from an
 * unrelated class's {@code <clinit>}, long after the mixin's own failure was reported and survived.
 */
@org.junit.jupiter.api.parallel.ResourceLock("ModCatalog")
class HalfAppliedMixinsTest {
	private static final String TARGET = "example.Target";
	private static final String OTHER_TARGET = "example.Other";
	private static final String MIXIN = "example.M";
	private static final String OTHER_MIXIN = "example.N";

	private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
	/** What Mixin writes for a merged handler: the injector's prefix, the session uid, then the declared name. */
	private static final String BROKEN = "localvar$abc000$broken";
	private static final String BROKEN_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";
	private static final String KEPT = "handler$abc000$kept";
	private static final String DEAD = "handler$abc000$dead";

	@AfterEach
	void reset() {
		HalfAppliedMixins.reset();
		MixinCompatibility.reset();
		net.forbric.api.CompatibilityFindings.reset();
		MixinConfigOwners.publish(List.of());
		System.clearProperty(HalfAppliedMixins.PROPERTY);
	}

	/**
	 * The defect, and the repair: the JVM rejects the class before it and links the class after.
	 *
	 * <p>{@code use()} calls the handler of the injector that DID attach, so this is also the assertion that the
	 * recovery is per injector: the surviving attachment and its handler are untouched, while the one member the
	 * verifier rejects — the one belonging to the injector that threw — no longer poisons the class.
	 */
	@Test
	void aHalfAppliedMemberIsNeutralisedAndTheAttachedHandlerSurvives() throws Exception {
		byte[] woven = target();
		assertJvmRejects("the fixture must reproduce the defect", woven);

		HalfAppliedMixins.failedToApply(TARGET, MIXIN);
		byte[] repaired = HalfAppliedMixins.repair(TARGET, woven);

		assertJvmLinks("the repaired class must not die the way the unrepaired one does", repaired);
		assertFalse(asmRejects(repaired), "the repaired class must satisfy the verifier the JVM rejected");
		ClassNode node = parse(repaired);
		assertNotNull(method(node, BROKEN, BROKEN_DESC),
				"the member stays: the post-definition audit finds a mixin's injectors by this marker");
		assertTrue(mergedFrom(method(node, BROKEN, BROKEN_DESC), MIXIN),
				"@MixinMerged must survive, or the per-injector loss goes unreported");
		assertStubbed(method(node, BROKEN, BROKEN_DESC));
		assertTrue(calls(method(node, "use", "()V"), KEPT), "the injector that attached still calls its handler");
		assertEqualsFingerprint(woven, repaired, DEAD, "()V",
				"a member the verifier accepts is not this repair's business, attached or not");
	}

	/** A member merged by a mixin that did NOT fail is left alone, whatever its body looks like. */
	@Test
	void anotherMixinsBrokenMemberIsNotTouched() {
		byte[] woven = otherTarget();
		HalfAppliedMixins.failedToApply(OTHER_TARGET, MIXIN);
		assertSame(woven, HalfAppliedMixins.repair(OTHER_TARGET, woven),
				"only the mixin Mixin reported a failure for may be repaired");
	}

	/** A class no mixin failed on is not parsed, let alone rewritten. */
	@Test
	void aClassNoMixinFailedOnComesBackUntouched() {
		byte[] woven = target();
		assertSame(woven, HalfAppliedMixins.repair(TARGET, woven));
	}

	/** The kill switch restores the previous behaviour exactly: the poison stays in the class. */
	@Test
	void withTheRepairOffThePoisonStays() {
		System.setProperty(HalfAppliedMixins.PROPERTY, "off");
		byte[] woven = target();
		HalfAppliedMixins.failedToApply(TARGET, MIXIN);
		assertSame(woven, HalfAppliedMixins.repair(TARGET, woven));
	}

	/** The failure Mixin reports is what tells the repair which class and mixin failed, and it is still recorded. */
	@Test
	void theErrorHandlerIsWhatTellsTheRepairWhichClassAndMixinFailed() {
		byte[] woven = target();
		new KernelMixinErrorHandler().onApplyError(TARGET, new IllegalStateException("injection failed"),
				info("example.mixins.json", MIXIN), IMixinErrorHandler.ErrorAction.WARN);

		assertFalse(asmRejects(HalfAppliedMixins.repair(TARGET, woven)),
				"the handler's report is the only thing that knows which class needs the repair");
		assertTrue(net.forbric.api.CompatibilityFindings.all().stream()
						.anyMatch(finding -> finding.id().equals("mixin:example.mixins.json:" + MIXIN)),
				"the mixin's own loss is still recorded once per mixin");
	}

	/**
	 * The loss is still REPORTED: the post-definition audit finds the mixin's injector through the marker the
	 * repair kept, so the injector that threw degrades into a recorded per-injector loss instead of a dead class.
	 */
	@Test
	void theNeutralisedMemberStillCarriesTheRecordedPerInjectorLoss() throws Exception {
		byte[] woven = target();
		HalfAppliedMixins.failedToApply(TARGET, MIXIN);
		byte[] repaired = HalfAppliedMixins.repair(TARGET, woven);

		String config = "example.mixins.json";
		MixinConfigOwners.publish(List.of(
				new MixinConfigOwners.Owned(config, "example", net.forbric.api.Ecosystem.FABRIC)));
		MixinCompatibility.rememberOriginalConfig(config, ("{\"required\":true,\"package\":\"example\","
				+ "\"mixins\":[\"M\"],\"injectors\":{\"defaultRequire\":1}}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		FinalMixinApplications.remember(mixin(config));

		FinalMixinApplications.observe(TARGET, repaired,
				(mixin, name, desc) -> List.of(new FinalMixinApplications.Renamed(BROKEN, BROKEN_DESC)));

		var losses = net.forbric.api.CompatibilityFindings.all().stream()
				.filter(finding -> finding.id().startsWith("mixin-injector:"))
				.filter(finding -> finding.confidence() == net.forbric.api.CompatibilityFinding.Confidence.CONFIRMED)
				.toList();
		assertFalse(losses.isEmpty(), "the injector that threw must be a recorded loss, not silence");
		assertTrue(losses.getFirst().id().endsWith("@example.Target"), losses.getFirst().id());
	}

	/** The mixin {@code example.M}, declared the way a config declares it, for the audit. */
	private static ClassNode mixin(String config) {
		ClassNode node = new ClassNode();
		node.name = MIXIN.replace('.', '/');
		node.visibleAnnotations = List.of(annotation("Lorg/spongepowered/asm/mixin/Mixin;", "targets", List.of(TARGET)));
		MethodNode injector = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "broken", BROKEN_DESC, null, null);
		injector.visibleAnnotations = new ArrayList<>(List.of(annotation(
				"Lorg/spongepowered/asm/mixin/injection/Inject;", "require", 1)));
		node.methods.add(injector);
		return node;
	}

	private static AnnotationNode annotation(String descriptor, String key, Object value) {
		AnnotationNode annotation = new AnnotationNode(descriptor);
		annotation.values = new ArrayList<>(List.of(key, value));
		return annotation;
	}

	/** The pipeline runs the repair on Mixin's own output, before the passes that read the woven class. */
	@Test
	void thePipelineRepairsMixinsOutput() throws Exception {
		Path compiled = Path.of("build", "classes", "java", "main", "net", "forbric", "kernel", "mixin",
				"KernelMixinBootstrap.class");
		assumeTrue(Files.isRegularFile(compiled), "main classes not compiled yet");
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		boolean weaving = false;
		for (MethodNode method : node.methods) {
			List<MethodInsnNode> calls = new ArrayList<>();
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call) calls.add(call);
			}
			int weave = indexOf(calls, null, "transformClassBytes");
			if (weave < 0) continue;
			weaving = true;
			int repair = indexOf(calls, "net/forbric/kernel/mixin/HalfAppliedMixins", "repair");
			assertTrue(repair > weave, method.name + " must repair what Mixin wrote, before anything reads it");
		}
		assertTrue(weaving, "found no weaving call to check");
	}

	private static int indexOf(List<MethodInsnNode> calls, String owner, String name) {
		for (int i = 0; i < calls.size(); i++) {
			MethodInsnNode call = calls.get(i);
			if ((owner == null || owner.equals(call.owner)) && name.equals(call.name)) return i;
		}
		return -1;
	}

	/** {@code example.Target}: the constructor, a call site to the mixin's attached handler, both its handlers, and one half-applied member of {@link #MIXIN}. */
	private static byte[] target() {
		return weave(TARGET, MIXIN, true);
	}

	/** {@code example.Other}: the same half-applied shape, merged by a mixin that did not fail. */
	private static byte[] otherTarget() {
		return weave(OTHER_TARGET, OTHER_MIXIN, false);
	}

	private static byte[] weave(String className, String mixin, boolean withAttached) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V17;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = className.replace('.', '/');
		node.superName = "java/lang/Object";
		MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
		constructor.instructions.add(new InsnNode(Opcodes.RETURN));
		node.methods.add(constructor);
		if (withAttached) {
			MethodNode use = new MethodNode(Opcodes.ACC_PUBLIC, "use", "()V", null, null);
			use.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, node.name, KEPT, "()V", false));
			use.instructions.add(new InsnNode(Opcodes.RETURN));
			node.methods.add(use);
			node.methods.add(plain(KEPT, "()V", mixin));
			node.methods.add(plain(DEAD, "()V", mixin));
		}
		node.methods.add(broken(BROKEN, mixin));
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** A handler whose body matches its descriptor. */
	private static MethodNode plain(String name, String descriptor, String mixin) {
		MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, name, descriptor, null, null);
		method.instructions.add(new InsnNode(Opcodes.RETURN));
		mark(method, mixin);
		return method;
	}

	/**
	 * The half-applied handler: the sugar parameter is gone from the descriptor and still read from the body, so
	 * {@code aload_1} reads a slot this signature does not have. Written with {@code COMPUTE_FRAMES}, like Mixin's
	 * own writer, which does not repair it either — the frame is consistent with the descriptor, the body is not.
	 */
	private static MethodNode broken(String name, String mixin) {
		MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, name, BROKEN_DESC, null, null);
		method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		method.instructions.add(new InsnNode(Opcodes.ARETURN));
		mark(method, mixin);
		return method;
	}

	private static void mark(MethodNode method, String mixin) {
		AnnotationNode merged = new AnnotationNode(MERGED);
		merged.values = new ArrayList<>(List.of("mixin", mixin, "priority", 1000, "sessionId", "test"));
		method.visibleAnnotations = new ArrayList<>(List.of(merged));
	}

	/** The JVM's own answer: {@code defineClass} does not verify, so the failure needs a link. */
	private static void assertJvmRejects(String message, byte[] bytes) throws Exception {
		Bytes loader = new Bytes();
		loader.define(TARGET, bytes);
		try {
			Class.forName(TARGET, true, loader);
			fail(message + ": the JVM accepted the fixture, so it no longer reproduces the defect");
		} catch (VerifyError expected) {
			assertTrue(expected.getMessage().contains("Bad local variable type"), expected.getMessage());
		}
	}

	private static void assertJvmLinks(String message, byte[] bytes) throws Exception {
		Bytes loader = new Bytes();
		loader.define(TARGET, bytes);
		try {
			Class.forName(TARGET, true, loader);
		} catch (VerifyError rejected) {
			fail(message + ": " + rejected.getMessage());
		}
	}

	private static final class Bytes extends ClassLoader {
		Bytes() {
			super(HalfAppliedMixinsTest.class.getClassLoader());
		}

		Class<?> define(String name, byte[] bytes) {
			return defineClass(name, bytes, 0, bytes.length);
		}
	}

	/** ASM's read of the same bodies — the same verdict without standing a JVM definition up. */
	private static boolean asmRejects(byte[] bytes) {
		ClassNode node = parse(bytes);
		for (MethodNode method : node.methods) {
			try {
				new org.objectweb.asm.tree.analysis.Analyzer<>(
						new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(node.name, method);
			} catch (Exception | Error malformed) {
				return true;
			}
		}
		return false;
	}

	private static void assertStubbed(MethodNode method) {
		boolean constructs = false;
		boolean throwsUnsupported = false;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
					&& "java/lang/UnsupportedOperationException".equals(type.desc)) constructs = true;
			if (insn instanceof InsnNode throwInsn && throwInsn.getOpcode() == Opcodes.ATHROW) throwsUnsupported = true;
		}
		assertTrue(constructs && throwsUnsupported,
				"a member nothing may call must fail where it is called, not in the verifier");
	}

	private static void assertEqualsFingerprint(byte[] before, byte[] after, String name, String descriptor, String message) {
		MethodNode original = method(parse(before), name, descriptor);
		MethodNode repaired = method(parse(after), name, descriptor);
		assertNotNull(original, message + " (missing before)");
		assertNotNull(repaired, message + " (missing after)");
		assertTrue(MixinInstructionFingerprint.hash(original).equals(MixinInstructionFingerprint.hash(repaired)), message);
	}

	private static boolean mergedFrom(MethodNode method, String mixin) {
		if (method.visibleAnnotations == null) return false;
		for (AnnotationNode annotation : method.visibleAnnotations) {
			if (!MERGED.equals(annotation.desc)) continue;
			for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
				if ("mixin".equals(annotation.values.get(i)) && mixin.equals(annotation.values.get(i + 1))) return true;
			}
		}
		return false;
	}

	private static boolean calls(MethodNode method, String name) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.name.equals(name)) return true;
		}
		return false;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String descriptor) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst().orElse(null);
	}

	private static IMixinInfo info(String configName, String className) {
		IMixinConfig config = (IMixinConfig) Proxy.newProxyInstance(HalfAppliedMixinsTest.class.getClassLoader(),
				new Class<?>[] { IMixinConfig.class }, (proxy, method, args) -> switch (method.getName()) {
					case "getName" -> configName;
					case "isRequired" -> true;
					case "toString" -> configName;
					default -> throw new UnsupportedOperationException(method.getName());
				});
		return (IMixinInfo) Proxy.newProxyInstance(HalfAppliedMixinsTest.class.getClassLoader(),
				new Class<?>[] { IMixinInfo.class }, (proxy, method, args) -> switch (method.getName()) {
					case "getConfig" -> config;
					case "getClassName" -> className;
					case "getName" -> className.substring(className.lastIndexOf('.') + 1);
					case "toString" -> className;
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}
}
