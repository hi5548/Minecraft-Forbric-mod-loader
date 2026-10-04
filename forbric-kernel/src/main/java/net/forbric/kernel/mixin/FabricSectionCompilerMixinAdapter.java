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

	// --- the 1.21.1 generation of the same mixin, and the per-block redirect the merge disarmed ----------

	/** {@code SectionBuilderMixin}, whose two injectors name the FOUR-argument {@code compile} overload. */
	static final String MIXIN_1_21_1 = "net/fabricmc/fabric/mixin/client/indigo/renderer/SectionBuilderMixin";
	private static final String BLOCK_REDIRECT = "hookBuildRenderBlock", BLOCK_RETURN = "hookBuildReturn";
	private static final String DISPATCHER = "net/minecraft/client/renderer/block/BlockRenderDispatcher";
	private static final String MODEL_DATA = "Lnet/neoforged/neoforge/client/model/data/ModelData;";
	private static final String RENDER_TYPE = "Lnet/minecraft/client/renderer/RenderType;";
	private static final String RENDER_BATCHED = "L" + DISPATCHER + ";renderBatched";
	/** Vanilla's seven-argument call — what the guest's {@code @At} names, and what the four-argument body makes. */
	private static final String OLD_ARGS = "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;"
			+ "Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;"
			+ "Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;)V";
	/** NeoForge's nine-argument overload — the one call the LIVE merged compile body makes, with its OWN ModelData. */
	private static final String NEW_ARGS = OLD_ARGS.substring(0, OLD_ARGS.length() - 2) + MODEL_DATA + RENDER_TYPE + ")V";
	private static final String HANDLER_OLD = "(L" + DISPATCHER + ";" + OLD_ARGS.substring(1, OLD_ARGS.length() - 2) + ")V";
	private static final String HANDLER_NEW = "(L" + DISPATCHER + ";" + OLD_ARGS.substring(1, OLD_ARGS.length() - 2)
			+ MODEL_DATA + RENDER_TYPE + ")V";
	private static final String INDIGO_TESSELLATE =
			"Lnet/fabricmc/fabric/impl/client/indigo/renderer/render/TerrainRenderContext;tessellateBlock("
					+ "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;"
					+ "Lnet/minecraft/client/resources/model/BakedModel;Lcom/mojang/blaze3d/vertex/PoseStack;)V";
	/** The 1.21.1 names of the two compile overloads. Note {@code RenderChunkRegion}, not 26.2's RenderSectionRegion. */
	private static final String BLOCK_ARGS = "Lnet/minecraft/core/SectionPos;"
			+ "Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;"
			+ "Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;";
	private static final String COMPILE_OLD = "(" + BLOCK_ARGS + ")L" + TARGET + "$Results;";
	private static final String COMPILE_LIVE = "(" + BLOCK_ARGS + "Ljava/util/List;)L" + TARGET + "$Results;";
	private static final String REBUILD_TASK =
			"net/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection$RebuildTask";
	private static final String CIR = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
	private static final String RETURN_HANDLER_OLD = "(" + BLOCK_ARGS + CIR + ")V";
	private static final String RETURN_HANDLER_NEW =
			"(" + BLOCK_ARGS + "Ljava/util/List;" + CIR + ")V";

	private FabricSectionCompilerMixinAdapter() { }

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!enabled()) return 0;
		if (MIXIN_1_21_1.equals(mixin.name)) {
			int moved = renderBlockRedirect(mixin, targets);
			// A decline must not be silent: an adapter that returns 0 while the mixin still carries the un-retargeted
			// handler is Indigo's per-block hook staying dead, and the only difference from success on the console was
			// nothing at all -- the same "0 hits proved nothing" shape the pruner audit was added for.
			if (moved == 0 && mixin.methods.stream().anyMatch(m -> BLOCK_REDIRECT.equals(m.name) && HANDLER_OLD.equals(m.desc)))
				net.forbric.kernel.util.ForbricLog.warn("[Forbric/Renderer] Indigo's 1.21.1 retarget DECLINED (fail-closed) on %s "
						+ "-- its per-block hook stays unbound; the guard order is in FabricSectionCompilerMixinAdapter.renderBlockRedirect",
						mixin.name);
			return moved;
		}
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
	 * The 1.21.1 generation — {@code SectionBuilderMixin} — whose paired injectors the merge disarmed.
	 *
	 * <p>Both guest injectors name the FOUR-argument {@code compile}, and on the merged base that overload is dead:
	 * the merge kept Forge's full four-argument body beside NeoForge's five-argument one (they differ by
	 * {@code List<AdditionalSectionRenderer>} and by WHICH {@code ModelData} class the block-render call carries),
	 * and the single surviving caller — {@code SectionRenderDispatcher$RenderSection$RebuildTask} — calls only the
	 * five-argument body. So the {@code @At(INVOKE)} anchor the guest wrote
	 * ({@code BlockRenderDispatcher.renderBatched} in its SEVEN-argument form) is present in neither live path: the
	 * merged bodies call the NINE-argument overload, with Forge's {@code ModelData} in the dead body and NeoForge's
	 * in the live one. The redirect therefore bound nowhere and Indigo's wrapper never ran.
	 *
	 * <p>What makes this retarget safe rather than a second guess is that the operand TYPES at the chosen call site
	 * were read from the frame, not assumed: the live body's ninth operand is
	 * {@code net/neoforged/neoforge/client/model/data/ModelData} and its tenth {@code RenderType}. A first attempt
	 * ({@code 55164ff3}, reverted) moved the descriptor and the handler but left the injectors on the dead overload,
	 * whose ninth operand is <b>Forge's</b> {@code ModelData} — a shim that verifies nowhere and crashed the client
	 * with {@code VerifyError: Bad type on operand stack}. So the guard below pins the call site to the live overload
	 * AND to the exact descriptor, and refuses when the shape is not the measured one.
	 *
	 * <p>The wrapper itself is left alone except for the call it forwards to: the Indigo fast path
	 * ({@code TerrainRenderContext.tessellateBlock}) is byte-for-byte as compiled, and the forwarding branch hands
	 * the two extra operands straight to the same nine-argument overload — nothing dropped, added or reordered.
	 */
	private static int renderBlockRedirect(ClassNode mixin, Function<String, ClassNode> targets) {
		// Idempotent: the widened descriptor is the post-retarget shape, and it is what a second pass must see.
		if (mixin.methods.stream().anyMatch(m -> BLOCK_REDIRECT.equals(m.name) && HANDLER_NEW.equals(m.desc))) return 0;
		MethodNode redirectHandler = method(mixin, BLOCK_REDIRECT, HANDLER_OLD);
		MethodNode returnHandler = method(mixin, BLOCK_RETURN, RETURN_HANDLER_OLD);
		if (redirectHandler == null || returnHandler == null) return 0;
		if (grouped(redirectHandler) || grouped(returnHandler)) return 0;
		// The guest's two injectors were compiled for the four-argument overload, in its descriptor form.
		String oldSelector = "L" + TARGET + ";compile" + COMPILE_OLD, liveSelector = "L" + TARGET + ";compile" + COMPILE_LIVE;
		AnnotationNode redirect = MixinFit.injectorOf(redirectHandler);
		if (redirect == null || !"Lorg/spongepowered/asm/mixin/injection/Redirect;".equals(redirect.desc)
				|| !List.of(oldSelector).equals(MixinFit.stringList(MixinFit.value(redirect, "method")))
				|| MixinFit.value(redirect, "slice") != null || MixinFit.value(redirect, "locals") != null) return 0;
		AnnotationNode injection = MixinFit.injectorOf(returnHandler);
		if (injection == null || !"Lorg/spongepowered/asm/mixin/injection/Inject;".equals(injection.desc)
				|| !List.of(oldSelector).equals(MixinFit.stringList(MixinFit.value(injection, "method")))
				|| MixinFit.value(injection, "slice") != null || MixinFit.value(injection, "locals") != null) return 0;
		List<AnnotationNode> ats = MixinFit.atNodes(redirect);
		if (ats.size() != 1) return 0;
		if (!"INVOKE".equals(MixinFit.value(ats.getFirst(), "value"))
				|| !(RENDER_BATCHED + OLD_ARGS).equals(MixinFit.value(ats.getFirst(), "target"))
				|| MixinFit.value(ats.getFirst(), "ordinal") != null) return 0;
		List<AnnotationNode> returns = MixinFit.atNodes(injection);
		if (returns.size() != 1 || !"RETURN".equals(MixinFit.value(returns.getFirst(), "value"))) return 0;
		// The wrapper as compiled: exactly one forwarding call to the seven-argument overload and one Indigo fast path.
		MethodInsnNode forward = null;
		for (var insn : redirectHandler.instructions)
			if (insn instanceof MethodInsnNode call && (RENDER_BATCHED + OLD_ARGS).equals("L" + call.owner + ";" + call.name + call.desc)) forward = call;
		if (forward == null || count(redirectHandler, RENDER_BATCHED + OLD_ARGS) != 1
				|| count(redirectHandler, INDIGO_TESSELLATE) != 1) return 0;
		// The merged base must be the measured shape: the live body is the five-argument overload, its single
		// nine-argument call carries NEOFORGE's model data, and the four-argument body is the one nothing calls.
		ClassNode compiler = targets.apply(TARGET);
		if (compiler == null) return 0;
		MethodNode stub = method(compiler, "compile", COMPILE_OLD), live = method(compiler, "compile", COMPILE_LIVE);
		if (stub == null || live == null) return 0;
		if (count(stub, RENDER_BATCHED + NEW_ARGS) != 0 || count(live, RENDER_BATCHED + NEW_ARGS) != 1) return 0;
		ClassNode rebuild = targets.apply(REBUILD_TASK);
		if (rebuild == null) return 0;
		int liveCalls = 0, deadCalls = 0;
		for (MethodNode method : rebuild.methods) {
			liveCalls += calls(method, TARGET, "compile", COMPILE_LIVE);
			deadCalls += calls(method, TARGET, "compile", COMPILE_OLD);
		}
		if (liveCalls != 1 || deadCalls != 0) return 0;
		ClassNode dispatcher = targets.apply(DISPATCHER);
		if (dispatcher == null || method(dispatcher, "renderBatched", OLD_ARGS) == null) return 0;

		for (int i = 0; i < ats.getFirst().values.size(); i += 2)
			if ("target".equals(ats.getFirst().values.get(i))) ats.getFirst().values.set(i + 1, RENDER_BATCHED + NEW_ARGS);
		setMethod(redirect, liveSelector);
		setMethod(injection, liveSelector);
		// The guest compiled its scratch for the resolved model into slot 9. The two new parameters TAKE slots 9
		// and 10, so that scratch moves past them: left where it is, the handler would store a {@code BakedModel}
		// into a {@code ModelData} parameter and the copied method would not verify.
		for (var insn : redirectHandler.instructions)
			if (insn instanceof VarInsnNode local && local.var == 9) local.var = 11;
		redirectHandler.instructions.insertBefore(forward, new VarInsnNode(Opcodes.ALOAD, 9));
		redirectHandler.instructions.insertBefore(forward, new VarInsnNode(Opcodes.ALOAD, 10));
		forward.desc = NEW_ARGS;
		redirectHandler.desc = HANDLER_NEW;
		redirectHandler.signature = null;
		redirectHandler.maxStack = 10;
		redirectHandler.maxLocals = 12;
		// The return handler now runs in the five-argument body, so it declares that body's fifth argument too.
		returnHandler.desc = RETURN_HANDLER_NEW;
		returnHandler.signature = null;
		returnHandler.maxLocals = 7;
		ForbricLog.info("[Forbric/Renderer] retargeted Indigo's per-block redirect onto the LIVE merged compile "
				+ "body's nine-argument renderBatched (NeoForge model data + render type forwarded verbatim; the "
				+ "tesselateBlock fast path is byte-for-byte as compiled)");
		return 2;
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
