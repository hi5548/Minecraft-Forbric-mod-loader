/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;
import org.objectweb.asm.*;import org.objectweb.asm.tree.*;

/**
 * Keep native fluid predicates and event cancellation authoritative while calling the relocated Create hooks.
 *
 * <p>PORT(1.21.1): 26.2's {@code CommonHooks.onLivingBreathe(LivingEntity, ServerLevel, int, int)} carried the level
 * as its second argument. NeoForge 21.1 declares {@code onLivingBreathe(LivingEntity, int, int)} — no level — so the
 * hook derives it from the entity ({@code LivingEntity.level()}), which is the same object the 26.2 parameter held.
 */
public final class CreateBreathingInjector implements ClassTransformer {
	public static final String TARGET="net.neoforged.neoforge.common.CommonHooks";
	private static final String HOOK="net/forbric/kernel/interop/CreateBreathingScope";
	private static final String METHOD_DESC="(Lnet/minecraft/world/entity/LivingEntity;II)V";
	@Override public AnchorSet anchors(){return AnchorSet.of(new AnchorSet.Anchor(TARGET,AnchorSet.Severity.REQUIRED,"Create diving equipment loses its breathing hooks on the native air-supply path"));}
	@Override public byte[] transform(String name,byte[] bytes,TransformContext context){
		if(!name.equals(TARGET))return bytes;ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);
		MethodNode method=node.methods.stream().filter(m->m.name.equals("onLivingBreathe")&&m.desc.equals(METHOD_DESC)).findFirst().orElse(null);if(method==null)return bytes;
		java.util.List<MethodInsnNode> water=new java.util.ArrayList<>();for(var i:method.instructions)if(i instanceof MethodInsnNode call){if(call.owner.equals(HOOK))return bytes;if(call.owner.equals("net/minecraft/world/effect/MobEffectUtil")&&call.name.equals("hasWaterBreathing")&&call.desc.equals("(Lnet/minecraft/world/entity/LivingEntity;)Z"))water.add(call);}
		if(water.size()!=1)return bytes;
		// lava(entity, level): the level is the entity's own, which is what the 26.2 method parameter held.
		InsnList start=new InsnList();start.add(new VarInsnNode(Opcodes.ALOAD,0));start.add(new VarInsnNode(Opcodes.ALOAD,0));
		start.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"net/minecraft/world/entity/LivingEntity","level","()Lnet/minecraft/world/level/Level;",false));
		start.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"lava","(Ljava/lang/Object;Ljava/lang/Object;)V",false));method.instructions.insert(start);
		MethodInsnNode call=water.getFirst();method.instructions.insertBefore(call,new InsnNode(Opcodes.DUP));InsnList after=new InsnList();after.add(new VarInsnNode(Opcodes.ALOAD,0));
		after.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"net/minecraft/world/entity/LivingEntity","level","()Lnet/minecraft/world/level/Level;",false));
		after.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"water","(Ljava/lang/Object;ZLjava/lang/Object;)Z",false));method.instructions.insert(call,after);
		ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);return writer.toByteArray();
	}
}
