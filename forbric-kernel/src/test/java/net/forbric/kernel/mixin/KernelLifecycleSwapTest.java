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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.transform.LifecycleHookInjector;

/**
 * A call THIS KERNEL replaced is not a merge loss, and the anchor-retarget has to be able to see that.
 *
 * <p>{@code LifecycleHookInjector} rewrites the genuine loader trigger in {@code Main.main} onto the kernel's own
 * hook, because the kernel owns the lifecycle. A guest anchored on the original call then reads as
 * {@code PARTIAL — 1/2 anchors resolve, missing: @At(INVOKE) ServerModLoader.load in Main.main}, which is the
 * verdict Sinytra Connector's {@code boot.ServerMainMixin#earlyInit} carried on every subject — a loss the kernel
 * caused and the census reported as the merge's. Publishing the swap lets the anchor move onto the hook, the same
 * program point, so the handler still runs where the loader's trigger now is.
 *
 * <p>Both directions are asserted: with a published row the anchor moves and the mixin re-evaluates FIT, and it
 * moves for a guest of ANY ecosystem, because the swap belongs to the kernel rather than to a family.
 */
class KernelLifecycleSwapTest {
	private static final String MAIN = "net/minecraft/server/Main";
	private static final String MAIN_METHOD = "main([Ljava/lang/String;)V";
	private static final String TRIGGER_OWNER = "net/neoforged/neoforge/server/loading/ServerModLoader";
	private static final String TRIGGER = "L" + TRIGGER_OWNER + ";load()V";
	private static final String HOOK_OWNER = "net/forbric/kernel/boot/KernelLifecycle";
	private static final String HOOK = "L" + HOOK_OWNER + ";onServerModLoadingNoArg()V";
	private static final String MIXIN = "test/ServerMainMixin";

	@AfterEach
	void reset() {
		MixinStubRebind.forget();
		MixinRetarget.reset();
		MergedBaseCalleeSwaps.forgetKernelSubstitutions();
	}

	@Test
	void anAnchorOnAKernelRedirectedCallMovesOntoTheHook() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.NEOFORGE);
		byte[] mixin = guest(TRIGGER);
		assertEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(mixin, resolver(call(TRIGGER))).verdict(),
				"premise: the anchor resolves while the trigger is still in the method");

		byte[] redirected = call(HOOK);
		MixinFit.Result before = MixinFit.evaluate(mixin, resolver(redirected));
		assertEquals(MixinFit.Verdict.PARTIAL, before.verdict(), "the kernel's own redirect is what breaks it");
		assertTrue(before.unresolved().toString().contains("ServerModLoader.load in Main.main"), before.unresolved().toString());
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin), resolver(redirected)).isEmpty(),
				"premise: without a published row the retarget has nothing to move along");

		MergedBaseCalleeSwaps.kernelSubstituted(row());

		MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(mixin), resolver(redirected));
		// The move, plus the guard the retarget adds for any rewritten target: a LinkageError from the moved handler
		// skips it instead of failing the method it now runs in.
		assertEquals(2, plan.rewrites().size(), plan.describe());
		assertEquals(TRIGGER, plan.rewrites().get(0).from());
		assertEquals(HOOK, plan.rewrites().get(0).to());
		assertTrue(plan.rewrites().get(1).to().endsWith("$forbricguard"), plan.describe());
		assertEquals(List.of(), MixinFit.evaluate(MixinRetarget.rewritten(mixin, plan), resolver(redirected)).unresolved(),
				"the moved handler binds at the hook, which is where the trigger now is");
	}

	/**
	 * A kernel-made swap is not family-scoped: the kernel replaced the call in the BASE, so every guest anchored on
	 * it — whatever family compiled it — was anchored on a call that is now gone. Merely plausible for a Fabric mod
	 * here: Sinytra Connector is Fabric-ecosystem and anchors on NeoForge's loader class on purpose, and family
	 * filtering left its handler behind on a real boot.
	 */
	@Test
	void aGuestOfAnyEcosystemMoves() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		MergedBaseCalleeSwaps.kernelSubstituted(row());
		MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(guest(TRIGGER)), resolver(call(HOOK)));
		assertEquals(2, plan.rewrites().size(), plan.describe());
		assertEquals(HOOK, plan.rewrites().get(0).to());
	}

	/** The row the pass publishes for the redirect it makes, built by the pass's own constructor. */
	private static MergedBaseCalleeSwaps.Substitution row() {
		return LifecycleHookInjector.substitutionRow(MAIN, MAIN_METHOD, TRIGGER_OWNER, "load", "()V",
				Ecosystem.NEOFORGE, HOOK_OWNER, "onServerModLoadingNoArg", "()V");
	}

	private static Function<String, byte[]> resolver(byte[] main) {
		return name -> (MAIN + ".class").equals(name) ? main : null;
	}

	/** {@code Main.main} as it stands after the redirect the test is about. */
	private static byte[] call(String target) {
		int space = target.indexOf(';');
		String owner = target.substring(1, space);
		String rest = target.substring(space + 1);
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, MAIN, null, "java/lang/Object", null);
		MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
		m.visitCode();
		if (target.endsWith("()V") && target.contains("onServerModLoadingNoArg")) {
			m.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "onServerModLoadingNoArg", "()V", false);
		} else {
			m.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "load", "()V", false);
		}
		m.visitInsn(Opcodes.RETURN);
		m.visitMaxs(0, 0);
		m.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A guest mixin targeting {@code Main} whose only handler injects before an INVOKE of {@code atTarget}. */
	private static byte[] guest(String atTarget) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, MIXIN, null, "java/lang/Object", null);
		AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		mixin.visit("value", org.objectweb.asm.Type.getObjectType(MAIN));
		mixin.visitEnd();

		MethodVisitor handler = cw.visitMethod(Opcodes.ACC_PRIVATE, "earlyInit",
				"(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
		AnnotationVisitor inject = handler.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
		AnnotationVisitor methods = inject.visitArray("method");
		methods.visit(null, "main");
		methods.visitEnd();
		AnnotationVisitor ats = inject.visitArray("at");
		AnnotationVisitor at = ats.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
		at.visit("value", "INVOKE");
		at.visit("target", atTarget);
		at.visitEnd();
		ats.visitEnd();
		inject.visitEnd();
		handler.visitCode();
		handler.visitInsn(Opcodes.RETURN);
		handler.visitMaxs(0, 2);
		handler.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}
