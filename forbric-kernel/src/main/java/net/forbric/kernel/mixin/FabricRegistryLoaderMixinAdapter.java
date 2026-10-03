/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.util.ForbricLog;

/**
 * Re-anchors fabric-registry-sync's registry-loader callback onto the merged base's overloads, and names the
 * fallback pin ({@link #PIN}) that covers the mixin when it cannot be re-anchored.
 *
 * <h2>The class this names moved between fabric-api generations</h2>
 *
 * <p>{@link #MIXIN} is the class that carries the server-binding wrap and the pending-tags injector. In the
 * generation this adapter was written for (fabric-api 0.154.0) it was {@code registry.sync.RegistryDataLoaderMixin};
 * <b>fabric-api 0.116.17 — the version Forbric actually ships — does not contain that class at all</b>. Its
 * {@code fabric-registry-sync-v0.mixins.json} declares, and its jar contains, {@code RegistryLoaderMixin} instead.
 * Measured on the remapped jar in the run cache ({@code javap -v}):
 *
 * <pre>
 *   @Mixin(RegistryDataLoader)
 *   private static RegistryAccess$Frozen wrapIsServerCall(Object, RegistryAccess, List&lt;RegistryData&gt;, Operation&lt;Frozen&gt;)
 *       @WrapOperation(method = RegistryDataLoader.load(ResourceManager, RegistryAccess, List),
 *                      at = @At(INVOKE, target = RegistryDataLoader.load(LoadingFunction, RegistryAccess, List)))
 *   private static void beforeLoad(Object, RegistryAccess, List&lt;RegistryData&gt;, CallbackInfoReturnable&lt;Frozen&gt;, List&lt;Loader&lt;?&gt;&gt;)
 *       @Inject(method = RegistryDataLoader.load(LoadingFunction, RegistryAccess, List),
 *               at = @At(INVOKE, target = "Ljava/util/List;forEach(...)"), ordinal = 0)   // and ordinal = 1
 *       -- the pending-tags surface: the captured List&lt;Loader&lt;?&gt;&gt; becomes a DynamicRegistryView and
 *          DynamicRegistrySetupCallback fires
 *   private static String prependDirectoryWithNamespace(ResourceKey, Operation&lt;String&gt;)
 *       @WrapOperation(method = {loadContentsFromNetwork(..), loadContentsFromManager(..)},
 *                      at = @At(INVOKE, target = Registries.elementsDirPath(ResourceKey)))
 * </pre>
 *
 * <p>Two consequences, and both were checked rather than assumed. First, the annotations already name the LIVE
 * private {@code load(LoadingFunction, RegistryAccess, List)}: unlike the 26.2-era class, nothing has to be
 * re-anchored here, so on this base {@link #adapt} finds no work to do and returns 0. Second, the 0.116.17 class
 * binds a {@code ThreadLocal} and keeps no asynchronous state — the re-bind wraps and the {@code supplyAsync}
 * handler {@link #adapt} looks for belong to the widened 26.2-era overloads ({@code load(LoaderFactory, List, List,
 * Executor, List)} returning a {@code CompletableFuture}) and simply do not exist.
 *
 * <h2>So the pin is what applies, and it is a fallback, not a decoration</h2>
 *
 * <p>{@code SUPPRESSED_MIXINS} carries {@link #PIN}; {@link ForbricMixinService#suppressedMixinsFor} LIFTS it only
 * while this adapter can actually retain the callback ({@link #retainsOnBase}). On the 1.21.1 base it cannot, so the
 * entry stays in force and the mixin is left out — the cost is stated at the entry and reported by {@link
 * net.forbric.kernel.boot.FabricApiModuleLossAudit}. Naming the class the module really ships is the whole point:
 * while both this adapter and the pin said {@code RegistryDataLoaderMixin}, the pin suppressed nothing and the
 * module's registry-loader mixin applied unadapted and unmeasured.
 */
public final class FabricRegistryLoaderMixinAdapter {
	public static final String PROPERTY="forbric.fabricRegistryLoader";
	/**
	 * The pin entry for the class THIS branch's fabric-api generation ships (0.116.17 on 1.21.1). It is the entry
	 * the loss audit asks about, so it must name the class that actually applies.
	 */
	public static final String PIN="fabric-registry-sync-v0.mixins.json:RegistryLoaderMixin";
	/** The same pair for the other generation, kept so a 26.2-era module is covered without an edit-and-rebuild. */
	public static final String PIN_26_2="fabric-registry-sync-v0.mixins.json:RegistryDataLoaderMixin";
	/** Every pin entry of this module, in either generation; the lift removes whichever the module ships. */
	public static final List<String> PINS=List.of(PIN,PIN_26_2);
	/** The class names this adapter recognises, one per generation: 0.116.17 ships {@code RegistryLoaderMixin},
	 *  0.154.0+ ships {@code RegistryDataLoaderMixin}. A module declares exactly one of them. */
	private static final Set<String> MIXINS=Set.of(
			"net/fabricmc/fabric/mixin/registry/sync/RegistryLoaderMixin",
			"net/fabricmc/fabric/mixin/registry/sync/RegistryDataLoaderMixin");
	private static final String TARGET="net/minecraft/resources/RegistryDataLoader";
	private static final String FACTORY="L"+TARGET+"$LoaderFactory;";
	private static final String ARGS="Ljava/util/List;Ljava/util/List;Ljava/util/concurrent/Executor;";
	private static final String FUTURE="Ljava/util/concurrent/CompletableFuture;";
	private static final String RM="Lnet/minecraft/server/packs/resources/ResourceManager;";
	private static final String OP="com/llamalad7/mixinextras/injector/wrapoperation/Operation";
	private FabricRegistryLoaderMixinAdapter() { }
	public static boolean enabled(){return !"off".equalsIgnoreCase(System.getProperty(PROPERTY,"on"));}

	/** Whether {@code internalName} is one of the generations' classes for this mixin. */
	static boolean named(String internalName){return MIXINS.contains(internalName);}
	/** The names this adapter knows, for the test that pins the table to what the shipped module declares. */
	static Set<String> knownNames(){return MIXINS;}

	/** The carrier's widened public overload: {@code load(ResourceManager, List, List, Executor, List)CompletableFuture}. */
	private static String publicLive(){return "load("+RM+ARGS+"Ljava/util/List;)"+FUTURE;}
	/** The carrier's widened private overload: {@code load(LoaderFactory, List, List, Executor, Z)CompletableFuture}. */
	private static String privateLive(){return "load("+FACTORY+ARGS+"Z)"+FUTURE;}

	/**
	 * Whether the merged base has the widened overload pair this adapter re-anchors onto, with exactly one call from
	 * the public one to the private one — the adapter's own precondition, expressed once and used twice ({@link
	 * #adapt} to decide whether to rewrite, {@link #retainsOnBase} to decide whether the fallback pin may lift).
	 */
	static boolean widenedShape(ClassNode target){
		if(target==null||target.methods==null)return false;
		String publicLive=publicLive(),privateLive=privateLive();
		MethodNode publicMethod=target.methods.stream().filter(m->(m.name+m.desc).equals(publicLive)).findFirst().orElse(null);
		MethodNode privateMethod=target.methods.stream().filter(m->(m.name+m.desc).equals(privateLive)).findFirst().orElse(null);
		if(publicMethod==null||privateMethod==null)return false;
		long calls=java.util.stream.StreamSupport.stream(publicMethod.instructions.spliterator(),false)
				.filter(i->i instanceof MethodInsnNode c&&c.owner.equals(TARGET)&&(c.name+c.desc).equals(privateLive)).count();
		return calls==1;
	}

	/** Cached answer for {@link #retainsOnBase}; the read walks the merged base's bytes once per process. */
	private static volatile Boolean retainsOnBase;

	/**
	 * Whether {@link #adapt} can retain the mixin's callback on THIS base, asked before Mixin reads the class (a
	 * config rewrite decides the pin). Reads the same merged {@code RegistryDataLoader} the adapter will later be
	 * handed, so the two cannot disagree; a read that fails answers false, which keeps the pin — the documented,
	 * harmless direction.
	 */
	public static boolean retainsOnBase(){
		Boolean known=retainsOnBase;
		if(known!=null)return known;
		boolean present;
		try{
			present=widenedShape(ForbricMixinService.mergedBaseNodeFor(TARGET,0));
		}catch(Throwable unreadable){
			present=false;
		}
		retainsOnBase=present;
		return present;
	}

	/**
	 * Forgets the cached answer and, when {@code retains} is non-null, stands in for the merged-base read — for
	 * tests, the same shape {@link net.forbric.kernel.boot.KernelRegistryDirectories#resetForTests} uses. A test
	 * without the staged merged base cannot answer the read, so it must be able to state the answer it is testing.
	 */
	static void resetForTests(Boolean retains){
		retainsOnBase=retains;
	}

	public static int adapt(ClassNode mixin,Function<String,ClassNode> targets){
		if(!enabled()||!named(mixin.name)||mixin.methods.stream().anyMatch(m->m.name.equals("wrapIsServerCall$forbricOriginal")))return 0;
		ClassNode target=targets.apply(TARGET);
		if(!widenedShape(target))return 0;
		String publicLive=publicLive(),privateLive=privateLive();
		MethodNode original=mixin.methods.stream().filter(m->m.name.equals("wrapIsServerCall")&&m.desc.equals("(Ljava/lang/Object;"+ARGS+"L"+OP+";)"+FUTURE)).findFirst().orElse(null);
		MethodNode supply=mixin.methods.stream().filter(m->m.name.equals("supplyAsync")).findFirst().orElse(null);
		if(original==null||supply==null||MixinFit.injectorOf(original)==null||MixinFit.injectorOf(supply)==null)return 0;
		AnnotationNode annotation=MixinFit.injectorOf(original);
		set(annotation,"method",List.of(publicLive));
		for(AnnotationNode at:MixinFit.atNodes(annotation))set(at,"target","L"+TARGET+";"+privateLive);
		set(MixinFit.injectorOf(supply),"method",List.of(privateLive));
		original.visibleAnnotations.remove(annotation);original.name="wrapIsServerCall$forbricOriginal";
		MethodNode wrapper=new MethodNode(Opcodes.ASM9,Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,"wrapIsServerCall",
				"(Ljava/lang/Object;"+ARGS+"ZL"+OP+";)"+FUTURE,null,null);
		wrapper.visibleAnnotations=new ArrayList<>(List.of(annotation));
		// Keep @Coerce on LoaderFactory, which the upstream handler deliberately types as Object.
		wrapper.invisibleParameterAnnotations=original.invisibleParameterAnnotations==null?null:Arrays.copyOf(original.invisibleParameterAnnotations,6);
		InsnList code=wrapper.instructions;
		for(int i=0;i<4;i++)code.add(new VarInsnNode(Opcodes.ALOAD,i));
		code.add(new VarInsnNode(Opcodes.ALOAD,5));code.add(new InsnNode(Opcodes.ICONST_0));code.add(new LdcInsnNode("0,1,2,3"));
		code.add(new InsnNode(Opcodes.ICONST_5));code.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));
		code.add(new InsnNode(Opcodes.DUP));code.add(new InsnNode(Opcodes.ICONST_4));code.add(new VarInsnNode(Opcodes.ILOAD,4));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/Boolean","valueOf","(Z)Ljava/lang/Boolean;",false));code.add(new InsnNode(Opcodes.AASTORE));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"net/forbric/kernel/runtime/KernelWrapOperations","reordered","(L"+OP+";ZLjava/lang/String;[Ljava/lang/Object;)L"+OP+";",false));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,mixin.name,original.name,original.desc,false));code.add(new InsnNode(Opcodes.ARETURN));
		wrapper.maxLocals=6;wrapper.maxStack=11;mixin.methods.add(wrapper);
		ForbricLog.info("[Forbric/RegistrySync] restored the native Fabric registry loader callback and its async "
				+ "ScopedValue propagation on the carrier overloads, preserving pending tags and the leniency flag");
		return 2;
	}
	private static void set(AnnotationNode annotation,String key,Object value){
		for(int i=0;i<annotation.values.size();i+=2)if(key.equals(annotation.values.get(i))){annotation.values.set(i+1,value);return;}
		annotation.values.add(key);annotation.values.add(value);
	}
}
