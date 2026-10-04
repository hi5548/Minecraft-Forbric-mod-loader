package net.forbric.kernel.mixin;
import net.forbric.kernel.mixin.FabricSectionCompilerMixinAdapter;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/**
 * Offline byte-level probe for the 1.21.1 Indigo retarget. It runs the adapter on the REAL remapped guest
 * against the REAL merged base and then does three things a shape-only probe does not:
 *   (a) the annotation/handler shape;
 *   (b) READS THE OPERAND TYPES OUT OF THE FRAME at the selected call site and compares them with the
 *       handler's declared parameters — the check whose absence let 55164ff3 land a VerifyError;
 *   (c) EMULATES Mixin's weave (handler copied into the live compile body, the call replaced by a redirector
 *       with the handler's own descriptor) and frame-verifies the whole method.
 * A negative control asserts the same frame check REJECTS the dead four-argument overload, whose ninth
 * operand is Forge's ModelData — the exact mis-binding that crashed.
 */
public final class IndigoRetargetProbe {
  static URLClassLoader loader;
  static int failures = 0;

  static final String SC = "net/minecraft/client/renderer/chunk/SectionCompiler";
  static final String BLOCK_ARGS = "Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;"
      + "Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;";
  static final String COMPILE_OLD = "(" + BLOCK_ARGS + ")L" + SC + "$Results;";
  static final String COMPILE_LIVE = "(" + BLOCK_ARGS + "Ljava/util/List;)L" + SC + "$Results;";
  static final String MODEL_DATA = "Lnet/neoforged/neoforge/client/model/data/ModelData;";
  static final String RENDER_TYPE = "Lnet/minecraft/client/renderer/RenderType;";
  static final String RB = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderBatched";
  static final String OLD_ARGS = "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;"
      + "Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;"
      + "Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;)V";
  static final String NEW_ARGS = OLD_ARGS.substring(0, OLD_ARGS.length() - 2) + MODEL_DATA + RENDER_TYPE + ")V";
  static final String HANDLER_NEW = "(Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;"
      + OLD_ARGS.substring(1, OLD_ARGS.length() - 2) + MODEL_DATA + RENDER_TYPE + ")V";
  static final String RETURN_NEW = "(" + BLOCK_ARGS
      + "Ljava/util/List;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V";
  static final String[] EXPECTED = {"net/minecraft/client/renderer/block/BlockRenderDispatcher",
      "net/minecraft/world/level/block/state/BlockState", "net/minecraft/core/BlockPos",
      "net/minecraft/client/renderer/chunk/RenderChunkRegion", "com/mojang/blaze3d/vertex/PoseStack",
      "com/mojang/blaze3d/vertex/BufferBuilder", "I", "net/minecraft/util/RandomSource",
      "net/neoforged/neoforge/client/model/data/ModelData", "net/minecraft/client/renderer/RenderType"};

  public static void main(String[] a) throws Exception {
    Path mergedJar = Path.of(a[0]), guestJar = Path.of(a[1]);
    loader = cp(a[2]);
    Map<String, byte[]> merged = new HashMap<>();
    for (String n : new String[]{SC, "net/minecraft/client/renderer/block/BlockRenderDispatcher",
        "net/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection$RebuildTask"})
      merged.put(n + ".class", read(mergedJar, n + ".class"));
    java.util.function.Function<String, ClassNode> targets = n -> {
      byte[] b = merged.get(n + ".class");
      if (b == null) return null;
      ClassNode c = new ClassNode();
      new ClassReader(b).accept(c, 0);
      return c;
    };
    ClassNode mixin = node(read(guestJar, "net/fabricmc/fabric/mixin/client/indigo/renderer/SectionBuilderMixin.class"));

    int moved = FabricSectionCompilerMixinAdapter.adapt(mixin, targets);
    check("adapt returned 2 (both paired injectors moved)", moved == 2, "got " + moved);
    if (moved != 2) { finish(); return; }

    MethodNode draw = method(mixin, "hookBuildRenderBlock");
    MethodNode ret = method(mixin, "hookBuildReturn");
    String liveSelector = "L" + SC + ";compile" + COMPILE_LIVE;
    check("redirect handler desc uses NEOFORGE ModelData", HANDLER_NEW.equals(draw.desc), draw.desc);
    check("@Redirect.method -> the LIVE five-argument compile", List.of(liveSelector).equals(MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(draw), "method"))), String.valueOf(MixinFit.value(MixinFit.injectorOf(draw), "method")));
    AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(draw)).getFirst();
    check("@At.target -> nine-argument renderBatched with NeoForge ModelData", (RB + NEW_ARGS).equals(MixinFit.value(at, "target")), String.valueOf(MixinFit.value(at, "target")));
    check("@Inject.method -> the LIVE five-argument compile", List.of(liveSelector).equals(MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(ret), "method"))), String.valueOf(MixinFit.value(MixinFit.injectorOf(ret), "method")));
    check("return handler captures the fifth argument", RETURN_NEW.equals(ret.desc), ret.desc);
    check("guest scratch local moved off the new parameter slots",
        !storesTo(draw, 9) && storesTo(draw, 11), "astore9=" + storesTo(draw, 9) + " astore11=" + storesTo(draw, 11));

    ClassNode compiler = targets.apply(SC);
    Type[] params = Type.getArgumentTypes(draw.desc);
    check("handler arity is 10 (dispatcher + nine)", params.length == 10, "argc=" + params.length);

    // (b) the LIVE call site: frame types read, then compared with the handler's declared parameters.
    int liveSite = site(compiler, method(compiler, "compile", COMPILE_LIVE), RB + NEW_ARGS);
    check("exactly one matching call site in the LIVE body", liveSite >= 0, "site=" + liveSite);
    if (liveSite >= 0) operandCheck("LIVE", compiler, method(compiler, "compile", COMPILE_LIVE), liveSite, params, true);

    // Negative control: the same check on the DEAD four-argument overload must REJECT the binding, because
    // its ninth operand is Forge's ModelData. This is the 55164ff3 defect, reproduced and caught offline.
    String deadArgs = OLD_ARGS.substring(0, OLD_ARGS.length() - 2) + "Lnet/minecraftforge/client/model/data/ModelData;" + RENDER_TYPE + ")V";
    int deadSite = site(compiler, method(compiler, "compile", COMPILE_OLD), RB + deadArgs);
    check("the dead four-argument body makes the same nine-argument call with FORGE's ModelData", deadSite >= 0, "site=" + deadSite);
    if (deadSite >= 0) operandCheck("DEAD (negative control)", compiler, method(compiler, "compile", COMPILE_OLD), deadSite, params, false);

    // (c) emulate Mixin's weave: handler copied in, call replaced by a redirector carrying the handler desc.
    ClassNode woven = targets.apply(SC);
    MethodNode wovenLive = method(woven, "compile", COMPILE_LIVE);
    MethodNode handler = method(mixin, "hookBuildRenderBlock");
    handler.name = "forbric$probe$handler";
    handler.access = Opcodes.ACC_PRIVATE;
    handler.visibleAnnotations = null; handler.invisibleAnnotations = null;
    handler.visibleParameterAnnotations = null; handler.invisibleParameterAnnotations = null;
    handler.parameters = null;
    woven.methods.add(handler);
    MethodNode redirector = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
        "forbric$probe$redirect", HANDLER_NEW, null, null);
    redirector.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
    for (int i = 0; i < params.length; i++)
      redirector.instructions.add(new VarInsnNode(params[i].getSort() == Type.BOOLEAN ? Opcodes.ILOAD : Opcodes.ALOAD, i));
    redirector.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, woven.name, handler.name, HANDLER_NEW, false));
    redirector.instructions.add(new InsnNode(Opcodes.RETURN));
    redirector.maxStack = 11; redirector.maxLocals = params.length;
    woven.methods.add(redirector);
    int replaced = 0;
    for (int i = 0; i < wovenLive.instructions.size(); i++) {
      var insn = wovenLive.instructions.get(i);
      if (insn instanceof MethodInsnNode c && (RB + NEW_ARGS).equals("L" + c.owner + ";" + c.name + c.desc)) {
        wovenLive.instructions.set(c, new MethodInsnNode(Opcodes.INVOKESTATIC, woven.name, redirector.name, HANDLER_NEW, false));
        replaced++;
      }
    }
    check("weave replaced exactly one call", replaced == 1, "replaced=" + replaced);
    check("woven LIVE compile frame-verifies (the Indigo check)", verify(woven, wovenLive), "SimpleVerifier rejected the woven body");
    check("adapted handler frame-verifies", verify(woven, handler), "SimpleVerifier rejected the handler");
    check("redirector frame-verifies", verify(woven, redirector), "SimpleVerifier rejected the redirector");

    check("second pass is idempotent (0)", FabricSectionCompilerMixinAdapter.adapt(mixin, targets) == 0, "not idempotent");
    finish();
  }

  static void operandCheck(String label, ClassNode cn, MethodNode m, int site, Type[] params, boolean expectCompatible) throws Exception {
    SimpleVerifier v = new SimpleVerifier();
    v.setClassLoader(loader);
    Frame<BasicValue>[] frames = new Analyzer<BasicValue>(v).analyze(cn.name, m);
    Frame<BasicValue> f = frames[site];
    int base = f.getStackSize() - params.length;
    boolean all = true;
    for (int k = 0; k < params.length; k++) {
      Type actual = f.getStack(base + k).getType();
      boolean ok = same(actual, EXPECTED[k]) && assignable(actual, params[k]);
      if (!ok) all = false;
      if (expectCompatible) {
        check(label + " operand[" + k + "] frame type is " + EXPECTED[k], same(actual, EXPECTED[k]), String.valueOf(actual));
        check(label + " operand[" + k + "] is assignable to handler param " + params[k].getClassName(), assignable(actual, params[k]), actual + " -> " + params[k]);
      }
    }
    if (expectCompatible) check(label + " every operand matches the handler parameters", all, "mismatch");
    else check(label + " the frame check REJECTS this binding (negative control)", !all, "the check accepted the dead overload");
  }

  static int site(ClassNode cn, MethodNode m, String member) {
    for (int i = 0; i < m.instructions.size(); i++) {
      var insn = m.instructions.get(i);
      if (insn instanceof MethodInsnNode c && member.equals("L" + c.owner + ";" + c.name + c.desc)) return i;
    }
    return -1;
  }

  static boolean storesTo(MethodNode m, int slot) {
    for (var insn : m.instructions)
      if (insn instanceof VarInsnNode v && v.var == slot && (v.getOpcode() == Opcodes.ASTORE || v.getOpcode() == Opcodes.ISTORE)) return true;
    return false;
  }

  static boolean verify(ClassNode cn, MethodNode m) {
    try {
      SimpleVerifier v = new SimpleVerifier();
      v.setClassLoader(loader);
      new Analyzer<BasicValue>(v).analyze(cn.name, m);
      return true;
    } catch (Throwable e) {
      System.out.println("    verifier: " + e);
      return false;
    }
  }

  static boolean same(Type t, String internal) {
    if (t == null) return false;
    if (internal.length() == 1) return t.getDescriptor().equals(internal);
    return t.getSort() == Type.OBJECT && t.getInternalName().equals(internal);
  }

  static boolean assignable(Type actual, Type param) {
    if (param.getSort() != Type.OBJECT) {
      if (actual == null) return false;
      boolean intFamily = param.getSort() == Type.BOOLEAN || param.getSort() == Type.BYTE || param.getSort() == Type.CHAR
          || param.getSort() == Type.SHORT || param.getSort() == Type.INT;
      boolean actualIntFamily = actual.getSort() == Type.BOOLEAN || actual.getSort() == Type.BYTE || actual.getSort() == Type.CHAR
          || actual.getSort() == Type.SHORT || actual.getSort() == Type.INT;
      return intFamily && actualIntFamily;
    }
    if (actual == null) return false;
    try {
      return Class.forName(param.getClassName(), false, loader)
          .isAssignableFrom(Class.forName(actual.getClassName(), false, loader));
    } catch (Throwable e) { return false; }
  }

  static void check(String what, boolean ok, String detail) {
    System.out.println((ok ? "  ok   " : "  FAIL ") + what + (ok ? "" : "  [" + detail + "]"));
    if (!ok) failures++;
  }

  static void finish() {
    System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
    if (failures != 0) System.exit(1);
  }

  static ClassNode node(byte[] b) { ClassNode c = new ClassNode(); new ClassReader(b).accept(c, 0); return c; }

  static MethodNode method(ClassNode c, String name) {
    return c.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
  }

  static MethodNode method(ClassNode c, String name, String desc) {
    return c.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow();
  }

  static byte[] read(Path jar, String entry) throws Exception {
    try (ZipFile z = new ZipFile(jar.toFile())) { return z.getInputStream(z.getEntry(entry)).readAllBytes(); }
  }

  static URLClassLoader cp(String cp) {
    List<URL> urls = new ArrayList<>();
    for (String s : cp.split(java.io.File.pathSeparator)) if (!s.isBlank()) {
      try { urls.add(Path.of(s).toUri().toURL()); } catch (Exception e) { throw new RuntimeException(e); }
    }
    return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
  }
}
