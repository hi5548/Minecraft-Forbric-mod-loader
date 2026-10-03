/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * The event-bus wrapper must be defined by the game loader, not by ClassLoaderFactory's child ASMClassLoader: the
 * generated class's package is the listener's, and only the same loader gives the same runtime package, so a
 * package-private listener (Forge's own {@code DeferredRegister$EventDispatcher}) stays reachable.
 *
 * <p>Real bytecode, from the staged 1.21.1 carrier: the call site is rewritten, the method still verifies, and a
 * class the transformer does not own is returned byte-for-byte unchanged.
 */
class EventBusWrapperDefinerInjectorTest {
	private static final String ENTRY = "net/minecraftforge/eventbus/ClassLoaderFactory.class";
	private static final String DEFINE_CLASS = "defineClass";
	private static final String DEFINE_CLASS_DESC = "(Lorg/objectweb/asm/tree/ClassNode;)Ljava/lang/Class;";
	private static final String ASM_LOADER = "net/minecraftforge/eventbus/ClassLoaderFactory$ASMClassLoader";
	private static final String BRIDGE = "net/forbric/kernel/interop/EventBusWrapperDefiner";

	private static byte[] carrier() throws Exception {
		Path staged = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"));
		Path carrier = staged.resolve("merged-base/forge-runtime-interop.jar");
		assumeTrue(Files.isRegularFile(carrier), "staged forge-runtime-interop.jar absent");
		try (ZipFile zip = new ZipFile(carrier.toFile())) {
			var entry = zip.getEntry(ENTRY);
			assumeTrue(entry != null, "ClassLoaderFactory moved");
			return zip.getInputStream(entry).readAllBytes();
		}
	}

	private static ClassNode read(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode defineClass(ClassNode node) {
		return node.methods.stream()
				.filter(m -> m.name.equals(DEFINE_CLASS) && m.desc.equals(DEFINE_CLASS_DESC))
				.findFirst().orElseThrow(() -> new AssertionError("no " + DEFINE_CLASS + DEFINE_CLASS_DESC));
	}

	/** How many calls in {@code method} target {@code owner.name}, optionally narrowed by descriptor. */
	private static long callsTo(MethodNode method, String owner, String name, String desc) {
		long count = 0;
		for (var instruction : method.instructions) {
			if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)
					&& (desc == null || call.desc.equals(desc))) count++;
		}
		return count;
	}

	@Test
	void theDefineCallSiteMovesFromTheChildLoaderToTheGameLoader() throws Exception {
		byte[] original = carrier();
		ClassNode before = read(original);
		assertEquals(1, callsTo(defineClass(before), ASM_LOADER, "define", null),
				"premise: the wrapper is defined by the bus's own ASMClassLoader");

		byte[] patched = new EventBusWrapperDefinerInjector().transform(
				"net.minecraftforge.eventbus.ClassLoaderFactory", original, null);
		assertNotSame(original, patched);

		MethodNode method = defineClass(read(patched));
		assertEquals(1, callsTo(method, BRIDGE, "defineOrFallback",
						"(Ljava/lang/Object;Ljava/lang/String;[B)Ljava/lang/Class;"),
				"the ASMClassLoader.define call is redirected to the game-loader bridge");
		assertEquals(0, callsTo(method, ASM_LOADER, "define", null),
				"no call to the child loader's define survives");

		// Same argument count, order and return type, so the frame is unchanged: the method must still verify.
		new Analyzer<>(new BasicVerifier()).analyze(read(patched).name, method);
	}

	@Test
	void aClassItDoesNotOwnComesBackIdentical() {
		byte[] bytes = new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
		byte[] out = new EventBusWrapperDefinerInjector().transform(
				"net.minecraft.world.item.ItemStack", bytes, null);
		assertSame(bytes, out);
	}

	@Test
	void theAnchorNamesTheClassItMustEdit() {
		assertTrue(new EventBusWrapperDefinerInjector().anchors().anchors().stream()
				.anyMatch(a -> a.binaryName().equals("net.minecraftforge.eventbus.ClassLoaderFactory")));
	}
}
