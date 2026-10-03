/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Rewrites Forge's {@code ClassLoaderFactory.defineClass(ClassNode)} so the generated event-handler wrapper is
 * defined by the GAME loader ({@code ForbricClassLoader}) instead of the bus's child {@code ASMClassLoader}.
 *
 * <p>The wrapper's package is the listener's package. Defined into the game loader it shares the listener's runtime
 * package and package-private listeners stay accessible; defined into the child {@code ASMClassLoader} it does not,
 * and every dispatch to MinecraftForge's own package-private listeners dies with {@code IllegalAccessError} before
 * the listener runs (measured: {@code DeferredRegister$EventDispatcher}, so all Forge {@code DeferredRegister} and
 * {@code NewRegistryEvent} handling). See {@link net.forbric.kernel.interop.EventBusWrapperDefiner}.
 *
 * <p>{@link ModLauncherClaimRewriter} is what makes the bus take this path at all — it keeps the kernel from asking
 * the absent ModLauncher for the wrapper. This transformer does not change that decision; it only changes where the
 * bus's own generator puts the class, which is exactly what {@code ModLauncherFactory} would have dictated.
 *
 * <p>The rewritten call keeps the receiver on the stack and passes it through as the bridge's fallback, so on a
 * loader the kernel did not build the wrapper is still defined the way it was before this transform.
 */
public final class EventBusWrapperDefinerInjector implements ClassTransformer {
	private static final String OWNER = "net.minecraftforge.eventbus.ClassLoaderFactory";
	private static final String METHOD = "defineClass";
	private static final String DESCRIPTOR = "(Lorg/objectweb/asm/tree/ClassNode;)Ljava/lang/Class;";
	private static final String ASM_LOADER = "net/minecraftforge/eventbus/ClassLoaderFactory$ASMClassLoader";
	private static final String DEFINE = "define";
	private static final String DEFINE_DESCRIPTOR = "(Ljava/lang/String;[B)Ljava/lang/Class;";
	private static final String BRIDGE = "net/forbric/kernel/interop/EventBusWrapperDefiner";
	private static final String BRIDGE_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/String;[B)Ljava/lang/Class;";

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!OWNER.equals(className)) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		boolean changed = false;
		for (MethodNode method : node.methods) {
			if (!METHOD.equals(method.name) || !DESCRIPTOR.equals(method.desc)) continue;
			for (var instruction : method.instructions) {
				if (!(instruction instanceof MethodInsnNode call)) continue;
				if (call.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
				if (!ASM_LOADER.equals(call.owner) || !DEFINE.equals(call.name)
						|| !DEFINE_DESCRIPTOR.equals(call.desc)) continue;
				// Same argument count and order (receiver, name, bytes) and same return type, so the frame is
				// unchanged: only the target moves, from the child ASMClassLoader to the game loader.
				call.setOpcode(Opcodes.INVOKESTATIC);
				call.owner = BRIDGE;
				call.name = "defineOrFallback";
				call.desc = BRIDGE_DESCRIPTOR;
				call.itf = false;
				changed = true;
				ForbricLog.info("[Forbric/EventBus] %s.%s%s now defines the generated handler wrapper in the game "
						+ "loader, not the bus's child ASMClassLoader — a package-private listener (MinecraftForge's "
						+ "DeferredRegister$EventDispatcher and friends) is otherwise unreachable from the wrapper "
						+ "and every dispatch dies with IllegalAccessError before it runs", className, METHOD, DESCRIPTOR);
			}
		}
		if (!changed) return classBytes;

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.of(new AnchorSet.Anchor(OWNER, AnchorSet.Severity.REQUIRED,
				"the bus would define every generated handler wrapper in its child ASMClassLoader again, so every "
						+ "dispatch to a package-private listener (Forge's DeferredRegister$EventDispatcher, hence "
						+ "NewRegistryEvent and all Forge DeferredRegister content) would throw IllegalAccessError"));
	}

	@Override
	public String name() {
		return "forbric:event-bus-wrapper-definer";
	}
}
