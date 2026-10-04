/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.transform.FabricItemContractTransformer;
/** Keep native reset decisions; a same-item Fabric override can explicitly keep the current mining action. */
public final class FabricMiningMixinAdapter {
 /** Two generations of the same class: 26.2 ships MultiPlayerGameModeMixin, 0.116.17 ClientPlayerInteractionManagerMixin. */
 private static final String MIXIN_26_2="net/fabricmc/fabric/mixin/item/client/MultiPlayerGameModeMixin",
   MIXIN_1_21_1="net/fabricmc/fabric/mixin/item/client/ClientPlayerInteractionManagerMixin",
   TARGET="net/minecraft/client/multiplayer/MultiPlayerGameMode",STACK="net/minecraft/world/item/ItemStack";
 /** The handler body each generation's compiler produced, so a rewrite is only ever applied to the bytes it was
  * derived from. Renaming a class is not enough: the two generations also compile different instruction streams. */
 private static final String BODY_26_2="fc6337bc35275d9c053f2ef2d3a4dfcdfbe245555bf6691174bdd227221e9b82",
   BODY_1_21_1="d9cd559d04bd292052b1d79879143feba6d8c069776d16e1825d97b53be85724";
 private FabricMiningMixinAdapter(){}
 public static int adapt(ClassNode mixin,Function<String,ClassNode> targets){
  boolean current=MIXIN_1_21_1.equals(mixin.name);
  if((!current&&!MIXIN_26_2.equals(mixin.name))||"off".equalsIgnoreCase(System.getProperty(FabricItemContractTransformer.PROPERTY,"on")))return 0;
  MethodNode handler=mixin.methods.stream().filter(m->m.name.equals("fabricItemContinueBlockBreakingInject")&&m.desc.equals("(L"+STACK+";L"+STACK+";)Z")).findFirst().orElse(null);
  if(handler==null||!MixinInstructionFingerprint.hash(handler).equals(current?BODY_1_21_1:BODY_26_2))return 0;
  for(var list:Arrays.asList(handler.visibleAnnotations,handler.invisibleAnnotations))if(list!=null&&list.stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;")))return 0;
  ClassNode target=targets.apply(TARGET);if(target==null)return 0;
  MethodNode host=target.methods.stream().filter(m->m.name.equals("sameDestroyTarget")&&m.desc.equals("(Lnet/minecraft/core/BlockPos;)Z")).findFirst().orElse(null);if(host==null)return 0;
  int nativeCalls=0,oldCalls=0;for(var instruction:host.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals(STACK)){if(call.name.equals("shouldCauseBlockBreakReset")&&call.desc.equals("(L"+STACK+";)Z"))nativeCalls++;if(call.name.equals("isSameItemSameComponents"))oldCalls++;}
  if(nativeCalls!=1||oldCalls!=0)return 0;
  AnnotationNode redirect=MixinFit.injectorOf(handler);if(redirect==null||!redirect.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;"))return 0;
  List<AnnotationNode> points=MixinFit.atNodes(redirect);if(points.size()!=1)return 0;
  AnnotationNode at=points.getFirst();if(!("L"+STACK+";isSameItemSameComponents(L"+STACK+";L"+STACK+";)Z").equals(MixinFit.value(at,"target")))return 0;
  for(int i=0;i<at.values.size();i+=2)if(at.values.get(i).equals("target"))at.values.set(i+1,"L"+STACK+";shouldCauseBlockBreakReset(L"+STACK+";)Z");
  handler.instructions.clear();handler.tryCatchBlocks.clear();handler.localVariables=null;
  if(current)currentGeneration(handler);else provenComposition(handler);
  return 1;
 }
 /** The 0.116.17 handler, expressed on the merged base: {@code isSameItemSameComponents(a, b)} becomes
  * {@code !a.shouldCauseBlockBreakReset(b)} — the identity the merged {@code sameDestroyTarget} itself relies on
  * (`!b.shouldCauseBlockBreakReset(a)` against vanilla's `isSameItemSameComponents`, see {@code IItemExtension}) —
  * and Fabric's own override, "a same-item stack that allows continuing block breaking keeps the action", is
  * preserved exactly as the guest wrote it: it re-checks the pair and calls
  * {@code Item.allowContinuingBlockBreaking} (the {@code FabricItem} seam the module's own {@code ItemMixin}
  * implants). No Fabric behaviour is added or dropped; only the member the merge replaced is re-expressed. */
 private static void currentGeneration(MethodNode handler){
  var out=handler.instructions;
  LabelNode same=new LabelNode(),different=new LabelNode();
  out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new VarInsnNode(Opcodes.ALOAD,2));
  out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,STACK,"shouldCauseBlockBreakReset","(L"+STACK+";)Z",false));
  out.add(new JumpInsnNode(Opcodes.IFEQ,same));
  out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,STACK,"getItem","()Lnet/minecraft/world/item/Item;",false));
  out.add(new VarInsnNode(Opcodes.ALOAD,2));out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,STACK,"getItem","()Lnet/minecraft/world/item/Item;",false));
  out.add(new JumpInsnNode(Opcodes.IF_ACMPNE,different));
  out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,STACK,"getItem","()Lnet/minecraft/world/item/Item;",false));
  out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new FieldInsnNode(Opcodes.GETFIELD,"net/fabricmc/fabric/mixin/item/client/ClientPlayerInteractionManagerMixin","minecraft","Lnet/minecraft/client/Minecraft;"));
  out.add(new FieldInsnNode(Opcodes.GETFIELD,"net/minecraft/client/Minecraft","player","Lnet/minecraft/client/player/LocalPlayer;"));
  out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new VarInsnNode(Opcodes.ALOAD,2));
  out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"net/minecraft/world/item/Item","allowContinuingBlockBreaking","(Lnet/minecraft/world/entity/player/Player;L"+STACK+";L"+STACK+";)Z",false));
  out.add(new JumpInsnNode(Opcodes.IFEQ,different));
  out.add(same);out.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));out.add(new InsnNode(Opcodes.ICONST_1));out.add(new InsnNode(Opcodes.IRETURN));
  out.add(different);out.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));out.add(new InsnNode(Opcodes.ICONST_0));out.add(new InsnNode(Opcodes.IRETURN));
  handler.maxStack=4;handler.maxLocals=3;
 }
 /** The 26.2-generation handler, byte-for-byte the emission this adapter already shipped for that generation. */
 private static void provenComposition(MethodNode handler){
  var out=handler.instructions;
  LabelNode keep=new LabelNode(),reset=new LabelNode();
  out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new VarInsnNode(Opcodes.ALOAD,2));out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,STACK,"shouldCauseBlockBreakReset","(L"+STACK+";)Z",false));out.add(new JumpInsnNode(Opcodes.IFEQ,keep));
  out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,STACK,"getItem","()Lnet/minecraft/world/item/Item;",false));out.add(new VarInsnNode(Opcodes.ALOAD,2));out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,STACK,"getItem","()Lnet/minecraft/world/item/Item;",false));out.add(new JumpInsnNode(Opcodes.IF_ACMPNE,reset));
  out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,STACK,"getItem","()Lnet/minecraft/world/item/Item;",false));out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new FieldInsnNode(Opcodes.GETFIELD,MIXIN_26_2,"minecraft","Lnet/minecraft/client/Minecraft;"));out.add(new FieldInsnNode(Opcodes.GETFIELD,"net/minecraft/client/Minecraft","player","Lnet/minecraft/client/player/LocalPlayer;"));out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new VarInsnNode(Opcodes.ALOAD,2));
  out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"net/minecraft/world/item/Item","allowContinuingBlockBreaking","(Lnet/minecraft/world/entity/player/Player;L"+STACK+";L"+STACK+";)Z",false));out.add(new JumpInsnNode(Opcodes.IFNE,keep));
  out.add(reset);out.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));out.add(new InsnNode(Opcodes.ICONST_1));out.add(new InsnNode(Opcodes.IRETURN));out.add(keep);out.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));out.add(new InsnNode(Opcodes.ICONST_0));out.add(new InsnNode(Opcodes.IRETURN));handler.maxStack=4;handler.maxLocals=3;
 }
}
