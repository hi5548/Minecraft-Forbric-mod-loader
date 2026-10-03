/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import net.forbric.kernel.util.ForbricLog;

/**
 * Moves Fabric's paired chunk-renderer setup and redirect onto the compile body that receives NeoForge's
 * additional section geometry. The short overload is only a delegate; leaving either injector there means
 * Continuity's models load but their custom quads never reach chunk buffers.
 *
 * <p>The pair keeps its captured layer map and shared renderer/emitter. Native model geometry remains
 * contextual through {@code FabricModelContextTransformer}; NeoForge's fluid and extra-geometry paths stay
 * in the existing compile body. A changed handler, shared state or target shape leaves both injectors alone.
 */
public final class FabricSectionCompilerMixinAdapter {
	public static final String PROPERTY = "forbric.fabricChunkRendering";
	static final String MIXIN = "net/fabricmc/fabric/mixin/client/renderer/block/render/SectionCompilerMixin";
	static final String TARGET = "net/minecraft/client/renderer/chunk/SectionCompiler";
	private static final String ARGS = "Lnet/minecraft/core/SectionPos;"
			+ "Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;"
			+ "Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;";
	static final String OLD = "(" + ARGS + ")L" + TARGET + "$Results;";
	static final String LIVE = "(" + ARGS + "Ljava/util/List;)L" + TARGET + "$Results;";
	private static final String TAIL = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;"
			+ "Ljava/util/Map;Lcom/llamalad7/mixinextras/sugar/ref/LocalRef;Lcom/llamalad7/mixinextras/sugar/ref/LocalRef;)V";
	private static final String SETUP = "beforeLoopCompile";
	private static final String ORIGINAL = SETUP + "$forbricOriginal";
	private static final String BETWEEN = "Lnet/minecraft/core/BlockPos;betweenClosed(Lnet/minecraft/core/BlockPos;"
			+ "Lnet/minecraft/core/BlockPos;)Ljava/lang/Iterable;";
	private static final String TESSELLATE = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock("
			+ "Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;"
			+ "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;"
			+ "Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V";
	/** The 1.21.1 generation of the same mixin, and the per-block redirect the merge disarmed. */
	static final String MIXIN_1_21_1 = "net/fabricmc/fabric/mixin/client/indigo/renderer/SectionBuilderMixin";
	static final String HANDLER_1_21_1 = "hookBuildRenderBlock";
	private static final String DISPATCHER = "net/minecraft/client/renderer/block/BlockRenderDispatcher";
	private static final String MODEL_DATA = "Lnet/neoforged/neoforge/client/model/data/ModelData;";
	private static final String RENDER_TYPE = "Lnet/minecraft/client/renderer/RenderType;";
	private static final String RENDER_BATCHED = "L" + DISPATCHER + ";renderBatched";
	/** Vanilla's seven-argument call — what the guest's {@code @At} names, and what the merged body no longer makes. */
	private static final String OLD_ARGS = "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;"
			+ "Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;"
			+ "Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;)V";
	/** NeoForge's nine-argument overload — the one call the merged {@code SectionCompiler.compile} makes. */
	private static final String NEW_ARGS = OLD_ARGS.substring(0, OLD_ARGS.length() - 2) + MODEL_DATA + RENDER_TYPE + ")V";
	private static final String HANDLER_OLD = "(L" + DISPATCHER + ";"
			+ OLD_ARGS.substring(1, OLD_ARGS.length() - 2) + ")V";
	private static final String HANDLER_NEW = "(L" + DISPATCHER + ";"
			+ OLD_ARGS.substring(1, OLD_ARGS.length() - 2) + MODEL_DATA + RENDER_TYPE + ")V";
	private static final String INDIGO_TESSELLATE =
			"Lnet/fabricmc/fabric/impl/client/indigo/renderer/render/TerrainRenderContext;tessellateBlock("
					+ "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;"
					+ "Lnet/minecraft/client/resources/model/BakedModel;Lcom/mojang/blaze3d/vertex/PoseStack;)V";

	private FabricSectionCompilerMixinAdapter() { }

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!enabled()) return 0;
		if (MIXIN_1_21_1.equals(mixin.name)) return renderBlockRedirect(mixin, targets);
		if (!MIXIN.equals(mixin.name) || mixin.methods.stream().anyMatch(m -> ORIGINAL.equals(m.name))) return 0;
		ClassNode target = targets.apply(TARGET);
		if (target == null) return 0;
		MethodNode stub = method(target, "compile", OLD), live = method(target, "compile", LIVE);
		if (stub == null || live == null || calls(stub, TARGET, "compile", LIVE) != 1
				|| count(stub, BETWEEN) != 0 || count(stub, TESSELLATE) != 0
				|| count(live, BETWEEN) != 1 || count(live, TESSELLATE) != 1) return 0;
		if (live.localVariables == null || live.localVariables.stream().noneMatch(v ->
				"startedLayers".equals(v.name) && "Ljava/util/Map;".equals(v.desc))) return 0;
		MethodNode setup = method(mixin, SETUP, "(" + ARGS + TAIL);
		MethodNode redirect = mixin.methods.stream().filter(m -> "tesselateBlockProxy".equals(m.name)).findFirst().orElse(null);
		if (setup == null || redirect == null || grouped(setup) || grouped(redirect)) return 0;
		String redirectDesc = "(Lnet/minecraft/client/renderer/block/ModelBlockRenderer;"
				+ TESSELLATE.substring(TESSELLATE.indexOf('(') + 1, TESSELLATE.lastIndexOf(')'))
				+ "Lcom/llamalad7/mixinextras/sugar/ref/LocalRef;Lcom/llamalad7/mixinextras/sugar/ref/LocalRef;)V";
		if (!redirectDesc.equals(redirect.desc)
				|| !sugar(setup, 5, "Local", "name", List.of("startedLayers"))
				|| !sugar(setup, 6, "Share", "value", "altBlockRenderer")
				|| !sugar(setup, 7, "Share", "value", "altQuadOutput")
				|| !sugar(redirect, 10, "Share", "value", "altBlockRenderer")
				|| !sugar(redirect, 11, "Share", "value", "altQuadOutput")) return 0;
		AnnotationNode init = MixinFit.injectorOf(setup), draw = MixinFit.injectorOf(redirect);
		if (!matches(init, "Inject", BETWEEN) || !matches(draw, "Redirect", TESSELLATE)) return 0;
		// A shared state pair must move together. A future API with another participant needs its own audit.
		if (mixin.methods.stream().filter(m -> MixinFit.injectorOf(m) != null).count() != 2
				|| setup.invisibleParameterAnnotations == null
				|| setup.invisibleParameterAnnotations.length != 8) return 0;

		setup.name = ORIGINAL;
		setup.visibleAnnotations.remove(init);
		MethodNode wrapper = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE, SETUP,
				"(" + ARGS + "Ljava/util/List;" + TAIL, null, null);
		wrapper.visibleAnnotations = new ArrayList<>(List.of(init));
		wrapper.visibleParameterAnnotations = shifted(setup.visibleParameterAnnotations);
		wrapper.invisibleParameterAnnotations = shifted(setup.invisibleParameterAnnotations);
		wrapper.visibleAnnotableParameterCount = wrapper.visibleParameterAnnotations == null ? 0 : 9;
		wrapper.invisibleAnnotableParameterCount = 9;
		// Preserve the region, builders, captured layer map and both @Share references. The added geometry
		// list belongs to NeoForge's existing compile body and is deliberately not handed to Fabric.
		for (int slot : new int[] {0, 1, 2, 3, 4, 6, 7, 8, 9}) wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, slot));
		wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, mixin.name, ORIGINAL, setup.desc, false));
		wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
		wrapper.maxLocals = 10;
		wrapper.maxStack = 9;
		mixin.methods.add(wrapper);
		setMethod(init, "compile" + LIVE);
		setMethod(draw, "compile" + LIVE);
		ForbricLog.info("[Forbric/Renderer] Fabric's renderer setup and block emission now run in the live chunk compile overload");
		return 2;
	}

	/**
	 * The 1.21.1 generation of the same mixin — {@code SectionBuilderMixin} — whose per-block redirect the merge
	 * disarmed. Its {@code @At(INVOKE)} names vanilla's SEVEN-argument
	 * {@code BlockRenderDispatcher.renderBatched}, and the merged {@code SectionCompiler.compile} does not make that
	 * call: {@code javap -c} of the staged merged base shows exactly one, ending
	 * {@code …util/RandomSource;Lnet/neoforged/neoforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)V}
	 * — NeoForge widened the callee. (The seven-argument method is still *declared*; it is simply not called there,
	 * which is why this is an anchor problem and not a missing member.) The redirect therefore bound nowhere and
	 * Indigo's wrapper never ran: a block whose model is not vanilla-adapted was handed to NeoForge's renderer with
	 * no Indigo path at all.
	 *
	 * <p>The wrapper is sound as compiled and is left alone except for the call it forwards to. For an
	 * Indigo-adapter model it routes to {@code TerrainRenderContext.tessellateBlock} — untouched — and otherwise it
	 * forwards its sample verbatim. Widening the handler by the two parameters the merged site passes and handing
	 * them straight to the same overload keeps NeoForge's model data and render type exactly where the merged body
	 * would have put them: nothing is dropped, added or reordered, and the Indigo fast path is not consulted for
	 * them. The annotation target and the handler descriptor move together, which is precisely what Mixin checks
	 * when it binds a redirect.
	 */
	private static int renderBlockRedirect(ClassNode mixin, Function<String, ClassNode> targets) {
		// Idempotent: the widened descriptor is the post-retarget shape.
		if (mixin.methods.stream().anyMatch(m -> HANDLER_1_21_1.equals(m.name) && m.desc.equals(HANDLER_NEW))) return 0;
		MethodNode handler = method(mixin, HANDLER_1_21_1, HANDLER_OLD);
		if (handler == null || grouped(handler)) return 0;
		AnnotationNode redirect = MixinFit.injectorOf(handler);
		if (redirect == null || !redirect.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;")) return 0;
		List<AnnotationNode> ats = MixinFit.atNodes(redirect);
		if (ats.size() != 1 || !(RENDER_BATCHED + OLD_ARGS).equals(MixinFit.value(ats.getFirst(), "target"))) return 0;
		// The single call the wrapper forwards to, and the Indigo fast path that must stay exactly as compiled.
		MethodInsnNode forward = null;
		for (var insn : handler.instructions) if (insn instanceof MethodInsnNode call && (RENDER_BATCHED + OLD_ARGS).equals("L" + call.owner + ";" + call.name + call.desc)) forward = call;
		if (forward == null || count(handler, RENDER_BATCHED + OLD_ARGS) != 1 || count(handler, INDIGO_TESSELLATE) != 1) return 0;
		ClassNode compiler = targets.apply("net/minecraft/client/renderer/chunk/SectionCompiler");
		if (compiler == null) return 0;
		int sites = 0;
		for (MethodNode m : compiler.methods) sites += count(m, RENDER_BATCHED + NEW_ARGS);
		if (sites != 1) return 0;
		for (int i = 0; i < ats.getFirst().values.size(); i += 2)
			if ("target".equals(ats.getFirst().values.get(i))) ats.getFirst().values.set(i + 1, RENDER_BATCHED + NEW_ARGS);
		handler.instructions.insertBefore(forward, new VarInsnNode(Opcodes.ALOAD, 9));
		handler.instructions.insertBefore(forward, new VarInsnNode(Opcodes.ALOAD, 10));
		forward.desc = NEW_ARGS;
		handler.desc = HANDLER_NEW;
		handler.maxStack = 10;
		handler.maxLocals = 11;
		ForbricLog.info("[Forbric/Renderer] retargeted Indigo's per-block redirect onto the merged compile body's nine-argument renderBatched — the tesselateBlock path is unchanged and NeoForge's model data and render type are forwarded verbatim");
		return 1;
	}

	private static boolean matches(AnnotationNode injector, String kind, String anchor) {
		if (injector == null || !("Lorg/spongepowered/asm/mixin/injection/" + kind + ";").equals(injector.desc)
				|| MixinFit.value(injector, "slice") != null || MixinFit.value(injector, "locals") != null) return false;
		List<String> selectors = MixinFit.stringList(MixinFit.value(injector, "method"));
		List<AnnotationNode> at = MixinFit.atNodes(injector);
		if (!selectors.equals(List.of("compile")) && !selectors.equals(List.of("compile" + OLD))) return false;
		return at.size() == 1 && "INVOKE".equals(MixinFit.value(at.getFirst(), "value"))
				&& anchor.equals(normalize(String.valueOf(MixinFit.value(at.getFirst(), "target"))))
				&& MixinFit.value(at.getFirst(), "ordinal") == null;
	}

	private static boolean grouped(MethodNode method) {
		return java.util.stream.Stream.of(method.visibleAnnotations, method.invisibleAnnotations)
				.filter(java.util.Objects::nonNull).flatMap(List::stream)
				.anyMatch(a -> "Lorg/spongepowered/asm/mixin/injection/Group;".equals(a.desc));
	}

	private static boolean sugar(MethodNode method, int parameter, String type, String key, Object value) {
		var annotations = method.invisibleParameterAnnotations;
		if (annotations == null || parameter >= annotations.length || annotations[parameter] == null) return false;
		return annotations[parameter].size() == 1 && annotations[parameter].stream().anyMatch(a ->
				("Lcom/llamalad7/mixinextras/sugar/" + type + ";").equals(a.desc)
				&& a.values != null && a.values.size() == 2 && value.equals(MixinFit.value(a, key)));
	}

	private static String normalize(String member) {
		if (member.startsWith("L")) return member;
		int dot = member.indexOf('.');
		return dot < 0 ? member : "L" + member.substring(0, dot) + ";" + member.substring(dot + 1);
	}

	@SuppressWarnings("unchecked")
	private static List<AnnotationNode>[] shifted(List<AnnotationNode>[] source) {
		if (source == null) return null;
		List<AnnotationNode>[] result = new List[9];
		for (int i = 0; i < source.length; i++) result[i < 4 ? i : i + 1] = source[i];
		return result;
	}

	private static void setMethod(AnnotationNode annotation, String selector) {
		for (int i = 0; i < annotation.values.size(); i += 2)
			if ("method".equals(annotation.values.get(i))) annotation.values.set(i + 1, List.of(selector));
	}

	private static MethodNode method(ClassNode type, String name, String desc) {
		return type.methods.stream().filter(m -> name.equals(m.name) && desc.equals(m.desc)).findFirst().orElse(null);
	}

	private static int count(MethodNode method, String member) {
		int count = 0;
		for (var insn : method.instructions)
			if (insn instanceof MethodInsnNode call && member.equals("L" + call.owner + ";" + call.name + call.desc)) count++;
		return count;
	}

	private static int calls(MethodNode method, String owner, String name, String desc) {
		return count(method, "L" + owner + ";" + name + desc);
	}
}
