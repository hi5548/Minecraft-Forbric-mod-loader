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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.util.ForbricLog;

/**
 * Neutralises the members a mixin that FAILED to apply left half-merged in its target, so a single failing
 * injector cannot turn the whole class into a {@code VerifyError}.
 *
 * <p>Mixin merges a mixin's methods into the target in an EARLIER pass than the one that injects, and it reports a
 * failing mixin through {@link KernelMixinErrorHandler} rather than undoing it. A mixin that finished its merges
 * and then threw therefore leaves its members in the class, whatever state the throw caught them in. The measured
 * case is balm's {@code FabricCropBlockMixin}: MixinExtras rewrites a sugar handler's descriptor (dropping the
 * {@code LocalRef} parameter) and rewrites its body to obtain that reference only after the injection succeeds —
 * so when the injection throws {@code InvalidInjectionException} ("Found 0 candidate variables but exactly 1 is
 * required"), the merged handler keeps the stripped descriptor and the body that still reads the removed
 * parameter. Mixin writes the class with {@code COMPUTE_FRAMES}, so the class file is internally consistent;
 * {@code CropBlock.localvar$zzd000$balm$getGrowthSpeedCaptureLocals} declares one parameter and starts
 * {@code aload_1}, and the JVM rejects the class at verification, from {@code Blocks.<clinit>}, long after the
 * mixin's own failure was reported and survived.
 *
 * <p>Why neutralise instead of removing. The post-definition audit ({@link FinalMixinApplications}) finds a
 * mixin's injectors by the {@code @MixinMerged} marker on the members it merged, so a repair that DELETED those
 * members would take the per-injector loss report with it — a class that loads but reports nothing is not the
 * trade this defect needs. The member keeps its name, descriptor, access and annotations, and only its body is
 * replaced by a minimal type-correct throw: every member nothing in the class calls behaves exactly as before,
 * and a member the surviving class does call now fails where it is called instead of poisoning the whole class.
 *
 * <p>What is checked is intrinsic, not a shape match: a merged member is neutralised when ASM's
 * {@link BasicVerifier} rejects its body. That catches any body/descriptor inconsistency a half-finished rewrite
 * can leave — it is the same "locals[1] is not a reference" the JVM reported — without needing a class hierarchy,
 * which the kernel cannot resolve mid-transform. What it does NOT catch is documented rather than hidden: a
 * leftover whose types are all well-formed but whose class relationships are wrong (an invoke against a hierarchy
 * the merged base no longer has) needs a hierarchy-aware verifier, and none was reachable here.
 *
 * <p>Only classes Mixin actually reported a failure for are touched, and only members carrying that mixin's own
 * {@code @MixinMerged} marker, so a class whose configs all applied pays one map lookup.
 *
 * <p>{@code -Dforbric.halfAppliedMixins=off} stands the repair down and restores the previous behaviour exactly —
 * the half-applied member stays, and the class meets the verifier as it did before.
 */
public final class HalfAppliedMixins {
	/** {@code -Dforbric.halfAppliedMixins=off} leaves a failed mixin's half-applied members in the class. */
	public static final String PROPERTY = "forbric.halfAppliedMixins";

	/** Mixin's marker on every member it merged into a target: {@code @MixinMerged(mixin=…, …)}. */
	private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";

	/**
	 * The mixins that failed to apply to the class the CURRENT transform is producing, keyed by the class's binary
	 * name. A transform and the repair that follows it run on one thread and Mixin never transforms two classes at
	 * once, but the repair happens after Mixin's lock is released, so a thread-local keeps another class's failure
	 * from reaching this class's bytes.
	 */
	private static final ThreadLocal<Map<String, Set<String>>> FAILED =
			ThreadLocal.withInitial(HashMap::new);

	private HalfAppliedMixins() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** Called from the error handler Mixin reports an apply failure to; {@code targetClass} is Mixin's own name. */
	static void failedToApply(String targetClass, String mixinClass) {
		if (!enabled() || targetClass == null || mixinClass == null) return;
		FAILED.get().computeIfAbsent(binaryName(targetClass), key -> new LinkedHashSet<>())
				.add(binaryName(mixinClass));
	}

	/**
	 * Replaces the body of every member {@code bytes} carries from a mixin that failed to apply to this class and
	 * that the verifier rejects. {@code bytes} may be null (Mixin's class-GENERATION request), and the SAME array
	 * comes back whenever there is nothing to repair — a class no mixin failed on must not be re-parsed or rewritten.
	 */
	public static byte[] repair(String className, byte[] bytes) {
		Map<String, Set<String>> recorded = FAILED.get();
		if (recorded.isEmpty()) return bytes;
		Set<String> failed = recorded.remove(binaryName(className));
		if (failed == null || failed.isEmpty() || bytes == null || !enabled()) return bytes;

		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		Map<String, List<String>> neutralised = new LinkedHashMap<>();
		for (MethodNode method : node.methods) {
			String mixin = mergedFrom(method);
			if (mixin == null || !failed.contains(mixin) || verifies(node.name, method)) continue;
			stub(method);
			neutralised.computeIfAbsent(mixin, key -> new ArrayList<>()).add(method.name + method.desc);
		}
		if (neutralised.isEmpty()) return bytes;

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		for (Map.Entry<String, List<String>> entry : neutralised.entrySet()) {
			ForbricLog.warn("[Forbric/Mixin] %s: %s failed to apply and left %d member(s) the verifier rejects; "
					+ "they are neutralised so the class loads — %s. The injectors that did attach are untouched, "
					+ "and the post-definition audit still reports the rest as per-injector losses",
					binaryName(className), entry.getKey(), entry.getValue().size(), entry.getValue());
		}
		return writer.toByteArray();
	}

	/** Forgets the failures recorded on this thread — for tests, so one case cannot repair the next one's class. */
	static void reset() {
		FAILED.remove();
	}

	/** {@code @MixinMerged}'s {@code mixin} value, normalized to a binary name; null when the member was not merged. */
	private static String mergedFrom(MethodNode method) {
		for (List<AnnotationNode> annotations : List.of(nonNull(method.visibleAnnotations), nonNull(method.invisibleAnnotations))) {
			for (AnnotationNode annotation : annotations) {
				if (!MERGED.equals(annotation.desc) || annotation.values == null) continue;
				for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
					if ("mixin".equals(annotation.values.get(i)) && annotation.values.get(i + 1) instanceof String mixin) {
						return binaryName(mixin);
					}
				}
			}
		}
		return null;
	}

	/** Whether the JVM's own first check — the body against its descriptor — accepts the method. */
	private static boolean verifies(String owner, MethodNode method) {
		try {
			new Analyzer<>(new BasicVerifier()).analyze(owner, method);
			return true;
		} catch (AnalyzerException | RuntimeException malformed) {
			// A body ASM cannot even walk is one the JVM cannot verify either; both mean the same thing here.
			return false;
		}
	}

	/**
	 * Gives the method a body that satisfies its descriptor by construction: a throw, with no branch, no local and
	 * no try/catch, so no stack map frame is needed and the rest of the class keeps the frames it already has.
	 */
	private static void stub(MethodNode method) {
		method.instructions.clear();
		method.tryCatchBlocks = null;
		method.localVariables = null;
		method.visibleLocalVariableAnnotations = null;
		method.invisibleLocalVariableAnnotations = null;
		InsnList code = method.instructions;
		code.add(new TypeInsnNode(Opcodes.NEW, "java/lang/UnsupportedOperationException"));
		code.add(new InsnNode(Opcodes.DUP));
		code.add(new LdcInsnNode("forbric: a mixin that failed to apply left this member half-applied"));
		code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>",
				"(Ljava/lang/String;)V", false));
		code.add(new InsnNode(Opcodes.ATHROW));
	}

	private static List<AnnotationNode> nonNull(List<AnnotationNode> annotations) {
		return annotations == null ? List.of() : annotations;
	}

	private static String binaryName(String name) {
		return name.replace('/', '.');
	}
}
