/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.transform.FabricSoundContractTransformer;
import net.forbric.kernel.util.ForbricLog;
/** Rebind the upstream stream redirect only after the default fallback actually contains Fabric dispatch. */
public final class FabricSoundMixinAdapter {
 private static final String MIXIN="net/fabricmc/fabric/mixin/client/sound/SoundEngineMixin",ENGINE="net/minecraft/client/sounds/SoundEngine";
 /** The 1.21.1 generation of the same mixin: a different class name AND a different call shape. */
 static final String MIXIN_1_21_1="net/fabricmc/fabric/mixin/client/sound/SoundSystemMixin";
 private static final String RESOURCE_LOCATION="Lnet/minecraft/resources/ResourceLocation;";
 /** The 0.116.17 handler's compiled body, so a rewrite is only ever applied to the bytes it was derived from. */
 private static final String BODY_1_21_1="e1920e2fe3c6477179e56cc683c135ce3cf3bec8e6fb21d1668c78c20b6c57dc";
 /** The guest names the FOUR-parameter redirect; the merged body makes an interface call of this shape. */
 private static final String OLD_HANDLER="("+FabricSoundContractTransformer.LIBRARY+RESOURCE_LOCATION+"Z"
   +"L"+FabricSoundContractTransformer.SOUND+";)"+FabricSoundContractTransformer.FUTURE;
 private static final String NEW_TARGET="L"+FabricSoundContractTransformer.SOUND+";getStream"
   +FabricSoundContractTransformer.DESC;
 private static final String NEW_HANDLER="(L"+FabricSoundContractTransformer.SOUND+";"
   +FabricSoundContractTransformer.LIBRARY+FabricSoundContractTransformer.DETAIL+"Z)"
   +FabricSoundContractTransformer.FUTURE;
 private static final String PLAY="(L"+FabricSoundContractTransformer.SOUND+";)V";
 private FabricSoundMixinAdapter(){}
 public static int adapt(ClassNode mixin,Function<String,ClassNode> targets){
  if("off".equalsIgnoreCase(System.getProperty(FabricSoundContractTransformer.PROPERTY,"on")))return 0;
  if(MIXIN_1_21_1.equals(mixin.name))return oneTwentyOne(targets,mixin);
  if(!mixin.name.equals(MIXIN))return 0;
  String sound=FabricSoundContractTransformer.SOUND,library=FabricSoundContractTransformer.LIBRARY,future=FabricSoundContractTransformer.FUTURE;
  String original="("+library+"Lnet/minecraft/resources/Identifier;ZL"+sound+";)"+future;
  MethodNode handler=mixin.methods.stream().filter(m->m.name.equals("getStream")&&m.desc.equals(original)).findFirst().orElse(null);
  if(handler==null||!MixinInstructionFingerprint.hash(handler).equals("b142d6b0548fe93bb213b2d23d239046b46ef38b080e215a012e5d2732ae73ad"))return 0;
  for(var list:Arrays.asList(handler.visibleAnnotations,handler.invisibleAnnotations))if(list!=null&&list.stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;")))return 0;
  ClassNode declaration=targets.apply(sound),engine=targets.apply(ENGINE);if(declaration==null||engine==null)return 0;
  MethodNode fallback=declaration.methods.stream().filter(m->m.name.equals("getStream")&&m.desc.equals(FabricSoundContractTransformer.DESC)).findFirst().orElse(null);if(fallback==null)return 0;
  int dispatch=0;for(var i:fallback.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(FabricSoundContractTransformer.API)&&c.name.equals("getAudioStream"))dispatch++;if(dispatch!=1)return 0;
  MethodNode play=engine.methods.stream().filter(m->m.name.equals("play")&&m.desc.equals("(L"+sound+";)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;")).findFirst().orElse(null);if(play==null)return 0;
  int calls=0;for(var i:play.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(sound)&&c.name.equals("getStream")&&c.desc.equals(FabricSoundContractTransformer.DESC))calls++;if(calls!=1)return 0;
  AnnotationNode redirect=MixinFit.injectorOf(handler);if(redirect==null||MixinFit.atNodes(redirect).size()!=1)return 0;AnnotationNode at=MixinFit.atNodes(redirect).getFirst();
  if(!("Lnet/minecraft/client/sounds/SoundBufferLibrary;getStream(Lnet/minecraft/resources/Identifier;Z)"+future).equals(MixinFit.value(at,"target")))return 0;
  for(int i=0;i<at.values.size();i+=2)if(at.values.get(i).equals("target"))at.values.set(i+1,"L"+sound+";getStream"+FabricSoundContractTransformer.DESC);
  handler.desc="(L"+sound+";"+FabricSoundContractTransformer.DESC.substring(1);handler.signature=null;handler.parameters=null;handler.visibleParameterAnnotations=null;handler.invisibleParameterAnnotations=null;handler.localVariables=null;handler.tryCatchBlocks.clear();handler.instructions.clear();
  handler.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));handler.instructions.add(new VarInsnNode(Opcodes.ALOAD,2));handler.instructions.add(new VarInsnNode(Opcodes.ALOAD,3));handler.instructions.add(new VarInsnNode(Opcodes.ILOAD,4));handler.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,sound,"getStream",FabricSoundContractTransformer.DESC,true));handler.instructions.add(new InsnNode(Opcodes.ARETURN));handler.maxStack=4;handler.maxLocals=5;return 1;
 }
 /**
  * The 0.116.17 generation, {@code SoundSystemMixin}, on the merged base.
  *
  * <p>Its handler was compiled for a redirect inside {@code SoundEngine.play} of the vanilla
  * {@code SoundBufferLibrary.getStream(ResourceLocation,Z)} call, and it answers by calling the sound's own
  * {@code getAudioStream}. The merged {@code play} does not make that call: Forge/NeoForge added
  * {@code SoundInstance.getStream(SoundBufferLibrary, Sound, boolean)} and moved the stream creation behind the
  * interface default, so the anchor is gone and the mixin does not apply at all.
  *
  * <p>The retarget rebinds the redirect onto the call the merged body DOES make and rewrites the handler to the
  * shape of that call, dispatching to {@code FabricSoundInstance.getAudioStream} with the sound's own path — the
  * exact semantics the guest compiled, and the extension point Fabric mods implement. Nothing is added to or
  * dropped from the audio path: for a sound that does not override {@code getAudioStream} the Fabric default is
  * {@code library.getStream(path, loop)}, which is what the merged default body does anyway.
  *
  * <p>Measured, not assumed: the handler's parameter types below are the FRAME's operand types at the interface
  * call (receiver {@code SoundInstance}, then {@code SoundBufferLibrary}, {@code Sound}, {@code boolean}) — the
  * same read that the Indigo retarget needed after 55164ff3 landed a shim that verified nowhere.
  */
 private static int oneTwentyOne(Function<String,ClassNode> targets,ClassNode mixin){
  String sound=FabricSoundContractTransformer.SOUND,tail=FabricSoundContractTransformer.DESC;
  MethodNode handler=mixin.methods.stream().filter(m->m.name.equals("getStream")&&m.desc.equals(OLD_HANDLER)).findFirst().orElse(null);
  if(handler==null||!MixinInstructionFingerprint.hash(handler).equals(BODY_1_21_1))return 0;
  for(var list:Arrays.asList(handler.visibleAnnotations,handler.invisibleAnnotations))if(list!=null&&list.stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;")))return 0;
  AnnotationNode redirect=MixinFit.injectorOf(handler);
  if(redirect==null||!"Lorg/spongepowered/asm/mixin/injection/Redirect;".equals(redirect.desc)||MixinFit.atNodes(redirect).size()!=1)return 0;
  AnnotationNode at=MixinFit.atNodes(redirect).getFirst();
  if(!"INVOKE".equals(MixinFit.value(at,"value"))||MixinFit.value(at,"ordinal")!=null
    ||!("Lnet/minecraft/client/sounds/SoundBufferLibrary;getStream("+RESOURCE_LOCATION+"Z)"+FabricSoundContractTransformer.FUTURE).equals(MixinFit.value(at,"target"))
    ||!List.of("L"+ENGINE+";play"+PLAY).equals(MixinFit.stringList(MixinFit.value(redirect,"method"))))return 0;
  // The guest answers by dispatching to the sound's own stream override; a handler that no longer does is not
  // the one this rewrite was derived from.
  int dispatch=0;for(var i:handler.instructions)
   if(i instanceof MethodInsnNode c&&c.owner.equals(sound)&&c.name.equals("getAudioStream")&&c.desc.equals("("+FabricSoundContractTransformer.LIBRARY+RESOURCE_LOCATION+"Z)"+FabricSoundContractTransformer.FUTURE))dispatch++;
  if(dispatch!=1)return 0;
  ClassNode declaration=targets.apply(sound),engine=targets.apply(ENGINE),detail=targets.apply("net/minecraft/client/resources/sounds/Sound");
  if(declaration==null||engine==null||detail==null)return 0;
  MethodNode fallback=declaration.methods.stream().filter(m->m.name.equals("getStream")&&m.desc.equals(tail)).findFirst().orElse(null);
  if(fallback==null)return 0;
  MethodNode play=engine.methods.stream().filter(m->m.name.equals("play")&&m.desc.equals(PLAY)).findFirst().orElse(null);
  if(play==null)return 0;
  int calls=0;for(var i:play.instructions)
   if(i instanceof MethodInsnNode c&&c.owner.equals(sound)&&c.name.equals("getStream")&&c.desc.equals(tail))calls++;
  if(calls!=1)return 0;
  if(detail.methods.stream().noneMatch(m->m.name.equals("getPath")&&m.desc.equals("()"+RESOURCE_LOCATION)))return 0;
  for(int i=0;i<at.values.size();i+=2)if("target".equals(at.values.get(i)))at.values.set(i+1,NEW_TARGET);
  handler.desc=NEW_HANDLER;handler.signature=null;handler.parameters=null;handler.visibleParameterAnnotations=null;handler.invisibleParameterAnnotations=null;handler.localVariables=null;handler.tryCatchBlocks.clear();handler.instructions.clear();
  var out=handler.instructions;
  out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new TypeInsnNode(Opcodes.CHECKCAST,FabricSoundContractTransformer.API));
  out.add(new VarInsnNode(Opcodes.ALOAD,2));out.add(new VarInsnNode(Opcodes.ALOAD,3));
  out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"net/minecraft/client/resources/sounds/Sound","getPath","()"+RESOURCE_LOCATION,false));
  out.add(new VarInsnNode(Opcodes.ILOAD,4));
  out.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,FabricSoundContractTransformer.API,"getAudioStream","("+FabricSoundContractTransformer.LIBRARY+RESOURCE_LOCATION+"Z)"+FabricSoundContractTransformer.FUTURE,true));
  out.add(new InsnNode(Opcodes.ARETURN));handler.maxStack=4;handler.maxLocals=5;
  ForbricLog.info("[Forbric/Sound] retargeted Fabric's stream redirect onto the merged SoundEngine.play's "
    +"SoundInstance.getStream call (1.21.1 generation); it now dispatches FabricSoundInstance.getAudioStream with "
    +"the sound's own path, exactly as the guest handler did");
  return 1;
 }
}
