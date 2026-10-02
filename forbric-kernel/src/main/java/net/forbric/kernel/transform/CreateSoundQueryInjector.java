/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;
import org.objectweb.asm.*;import org.objectweb.asm.tree.*;

/**
 * Restore the original group query inside the native block sound implementation, without replacing sound playback.
 *
 * <p>PORT(1.21.1): 26.2 anchored on NeoForge's {@code IBlockExtension.playStepSound} / {@code playFallSound}, which
 * this generation's carrier does not declare. The native implementations the carrier used to wrap are vanilla's own:
 * the three step paths on {@code Entity} ({@code playStepSound}, {@code playMuffledStepSound},
 * {@code playCombinationStepSounds}) and the fall path on {@code LivingEntity} ({@code playBlockFallSound}). Each
 * calls {@code BlockState.getSoundType(LevelReader, BlockPos, Entity)}, and each of those four calls is redirected
 * to the kernel — all four or nothing, so a half-covered sound path cannot report a scope answer for steps but not
 * for falls. The scope answers the native sound type unless a Create scope is active.
 */
public final class CreateSoundQueryInjector implements ClassTransformer {
	public static final String TARGET = "net.minecraft.world.entity.Entity";
	public static final String FALL_TARGET = "net.minecraft.world.entity.LivingEntity";
	private static final String BLOCK_STATE = "net/minecraft/world/level/block/state/BlockState";
	private static final String SOUND_QUERY = "net/forbric/kernel/runtime/KernelCreateSoundQuery";
	private static final String GET_SOUND_TYPE = "(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/level/block/SoundType;";
	private static final String HOOK_DESC = "(L" + BLOCK_STATE + ";" + GET_SOUND_TYPE.substring(1);

	@Override public AnchorSet anchors() {
		return AnchorSet.of(
				new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED, "Create step sounds are lost in the native block sound methods"),
				new AnchorSet.Anchor(FALL_TARGET, AnchorSet.Severity.REQUIRED, "Create landing sounds are lost in the native block sound implementation"));
	}

	@Override public byte[] transform(String name, byte[] bytes, TransformContext context) {
		int expected = TARGET.equals(name) ? 3 : FALL_TARGET.equals(name) ? 1 : 0;
		if (expected == 0) return bytes;
		ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
		int count = 0;
		for (MethodNode method : node.methods) {
			for (var i : method.instructions.toArray()) {
				if (!(i instanceof MethodInsnNode call)) continue;
				if (call.getOpcode() != Opcodes.INVOKEVIRTUAL || call.itf) continue;
				if (!call.owner.equals(BLOCK_STATE) || !call.name.equals("getSoundType") || !call.desc.equals(GET_SOUND_TYPE)) continue;
				call.setOpcode(Opcodes.INVOKESTATIC);
				call.owner = SOUND_QUERY;
				call.name = "sound";
				call.desc = HOOK_DESC;
				call.itf = false;
				count++;
			}
		}
		if (count != expected) return bytes;
		ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
	}
}
