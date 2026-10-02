package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

class ForgeBlockTintInjectorTest {
	private static final ForgeBlockTintInjector INJECTOR = new ForgeBlockTintInjector();
	@AfterEach void clear() { System.clearProperty("forbric.forgeClientInit"); }

	@Test void redirectsOnePostWithoutChangingTheStackOrInstructionSequence() throws Exception {
		byte[] before = fixture(1, true);
		byte[] after = INJECTOR.transform(ForgeBlockTintInjector.TARGET, before, null);
		assertNotSame(before, after);
		MethodNode original = site(before), changed = site(after);
		assertEquals(opcodes(original), opcodes(changed));
		assertEquals(0, posts(changed, ForeignType.FML_MOD_LOADER.internal(Ecosystem.NEOFORGE)));
		assertEquals(1, posts(changed, ForgeBlockTintInjector.HOOK));
		new Analyzer<>(new BasicVerifier()).analyze(ForgeBlockTintInjector.TARGET.replace('.', '/'), changed);
		assertSame(after, INJECTOR.transform(ForgeBlockTintInjector.TARGET, after, null));
	}

	@Test void ambiguousMissingOrDifferentEventsStandDown() {
		for (byte[] bytes : List.of(fixture(0, true), fixture(2, true), fixture(1, false))) {
			assertSame(bytes, INJECTOR.transform(ForgeBlockTintInjector.TARGET, bytes, null));
		}
		byte[] other = {1, 2};
		assertSame(other, INJECTOR.transform("example.Other", other, null));
	}

	@Test void clientInitControlRestoresTheOriginalCall() {
		byte[] bytes = fixture(1, true);
		System.setProperty("forbric.forgeClientInit", "off");
		assertSame(bytes, INJECTOR.transform(ForgeBlockTintInjector.TARGET, bytes, null));
	}

	/**
	 * The anchor against the real carrier: 1.21.1's {@code BlockColors.createDefault} names no colour-handler
	 * event, and this method is where NeoForge builds and posts it. The Item event lives in the same class and
	 * must stay untouched.
	 */
	@Test void theActualNeoForgeCarrierHasExactlyThisSeam() throws Exception {
		Path root = TestFixtures.stagedRoot();
		Path jar = root.resolve("neoforge-runtime/neoforge-runtime.jar");
		assumeTrue(Files.isRegularFile(jar), "requires the staged NeoForge carrier");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			var entry = zip.getEntry(ForgeBlockTintInjector.TARGET.replace('.', '/') + ".class");
			assumeTrue(entry != null, "ClientHooks absent from this carrier");
			byte[] before = zip.getInputStream(entry).readAllBytes();
			assertEquals(1, posts(site(before), ForeignType.FML_MOD_LOADER.internal(Ecosystem.NEOFORGE)),
					"onBlockColorsInit must post exactly one event — the seam this injector moves");
			byte[] after = INJECTOR.transform(ForgeBlockTintInjector.TARGET, before, null);
			assertNotSame(before, after);
			assertEquals(opcodes(site(before)), opcodes(site(after)));
			assertEquals(1, posts(site(after), ForgeBlockTintInjector.HOOK));
			assertSame(after, INJECTOR.transform(ForgeBlockTintInjector.TARGET, after, null));

			// The sibling that posts the Item event is not this seam and must come back byte-identical.
			var items = zip.getEntry(ForgeBlockTintInjector.TARGET.replace('.', '/') + ".class");
			byte[] all = zip.getInputStream(items).readAllBytes();
			assertEquals(1, posts(method(all, "onItemColorsInit",
					"(Lnet/minecraft/client/color/item/ItemColors;Lnet/minecraft/client/color/block/BlockColors;)V"),
					ForeignType.FML_MOD_LOADER.internal(Ecosystem.NEOFORGE)));
			assertEquals(0, posts(method(after, "onItemColorsInit",
					"(Lnet/minecraft/client/color/item/ItemColors;Lnet/minecraft/client/color/block/BlockColors;)V"),
					ForgeBlockTintInjector.HOOK));
		}
	}

	@Test void typedFunnelPostsNeoBeforeAskingForgeWithTheEventsBlockColors() throws Exception {
		Path runtime = Path.of("build/classes/java/runtime", ForgeBlockTintInjector.HOOK + ".class");
		assumeTrue(Files.isRegularFile(runtime), "requires runtime source set");
		ClassNode node = parse(Files.readAllBytes(runtime));
		MethodNode method = node.methods.stream().filter(m -> m.name.equals("postBlockTintSources")).findFirst().orElseThrow();
		assertEquals(ForgeBlockTintInjector.POST, method.desc);
		int neo = -1, getter = -1, forge = -1;
		for (int i = 0; i < method.instructions.size(); i++) {
			if (!(method.instructions.get(i) instanceof MethodInsnNode call)) continue;
			if (call.owner.equals(ForeignType.FML_MOD_LOADER.internal(Ecosystem.NEOFORGE)) && call.name.equals("postEvent")) neo = i;
			if (call.owner.equals(ForeignType.BLOCK_TINT_EVENT.internal(Ecosystem.NEOFORGE)) && call.name.equals("getBlockColors")) getter = i;
			if (call.owner.equals(ForeignType.CLIENT_HOOKS.internal(Ecosystem.FORGE)) && call.name.equals("onBlockColorsInit")) forge = i;
		}
		assertTrue(neo >= 0 && getter > neo && forge > getter, "one live instance must flow through both registration APIs");
	}

	private static ClassNode parse(byte[] bytes) { ClassNode n = new ClassNode(); new ClassReader(bytes).accept(n, 0); return n; }
	private static MethodNode method(byte[] bytes, String name, String desc) {
		return parse(bytes).methods.stream()
				.filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow();
	}
	private static MethodNode site(byte[] bytes) { return method(bytes, ForgeBlockTintInjector.SITE, ForgeBlockTintInjector.SITE_DESC); }
	private static List<Integer> opcodes(MethodNode m) {
		return Arrays.stream(m.instructions.toArray()).filter(i -> i.getOpcode() >= 0).map(i -> i.getOpcode()).toList();
	}
	private static long posts(MethodNode method, String owner) {
		return Arrays.stream(method.instructions.toArray()).filter(i -> i instanceof MethodInsnNode call
				&& call.owner.equals(owner) && call.desc.equals(ForgeBlockTintInjector.POST)).count();
	}
	private static byte[] fixture(int posts, boolean expectedEvent) {
		ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		String target = ForgeBlockTintInjector.TARGET.replace('.', '/');
		out.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, target, null, "java/lang/Object", null);
		var method = out.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, ForgeBlockTintInjector.SITE,
				ForgeBlockTintInjector.SITE_DESC, null, null);
		method.visitCode();
		for (int i = 0; i < posts; i++) {
			String event = expectedEvent ? ForeignType.BLOCK_TINT_EVENT.internal(Ecosystem.NEOFORGE) : "example/OtherEvent";
			method.visitTypeInsn(Opcodes.NEW, event); method.visitInsn(Opcodes.DUP); method.visitVarInsn(Opcodes.ALOAD, 0);
			method.visitMethodInsn(Opcodes.INVOKESPECIAL, event, "<init>", ForgeBlockTintInjector.EVENT_DESC, false);
			method.visitMethodInsn(Opcodes.INVOKESTATIC, ForeignType.FML_MOD_LOADER.internal(Ecosystem.NEOFORGE),
					"postEvent", ForgeBlockTintInjector.POST, false);
		}
		method.visitInsn(Opcodes.RETURN); method.visitMaxs(0, 0); method.visitEnd();
		out.visitEnd(); return out.toByteArray();
	}
}
