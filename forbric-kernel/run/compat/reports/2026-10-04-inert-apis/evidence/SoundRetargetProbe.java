package net.forbric.kernel.mixin;
import org.objectweb.asm.*; import org.objectweb.asm.tree.*; import org.objectweb.asm.tree.analysis.*;
import java.net.*; import java.nio.file.*; import java.util.*; import java.util.zip.ZipFile;

/**
 * Offline byte-level probe for the 1.21.1 sound retarget. Same three legs as the Indigo probe: shape, FRAME
 * operand types at the selected call site, and an emulated Mixin weave that is frame-verified. The negative
 * control asserts the anchor the guest names is genuinely absent from the merged play, so the retarget is
 * required rather than cosmetic.
 */
public final class SoundRetargetProbe {
  static URLClassLoader loader; static int failures = 0;
  static final String SOUND = "net/minecraft/client/resources/sounds/SoundInstance";
  static final String ENGINE = "net/minecraft/client/sounds/SoundEngine";
  static final String API = "net/fabricmc/fabric/api/client/sound/v1/FabricSoundInstance";
  static final String LIB = "Lnet/minecraft/client/sounds/SoundBufferLibrary;";
  static final String DETAIL = "Lnet/minecraft/client/resources/sounds/Sound;";
  static final String FUTURE = "Ljava/util/concurrent/CompletableFuture;";
  static final String DESC = "(" + LIB + DETAIL + "Z)" + FUTURE;
  static final String RL = "Lnet/minecraft/resources/ResourceLocation;";
  static final String PLAY = "(L" + SOUND + ";)V";
  static final String NEW_HANDLER = "(L" + SOUND + ";" + LIB + DETAIL + "Z)" + FUTURE;
  static final String NEW_TARGET = "L" + SOUND + ";getStream" + DESC;

  public static void main(String[] a) throws Exception {
    Path mergedJar = Path.of(a[0]), guestJar = Path.of(a[1]);
    loader = cp(a[2]);
    Map<String, byte[]> merged = new HashMap<>();
    for (String n : new String[]{ENGINE, SOUND, "net/minecraft/client/resources/sounds/Sound"})
      merged.put(n + ".class", read(mergedJar, n + ".class"));
    java.util.function.Function<String, ClassNode> targets = n -> {
      byte[] b = merged.get(n + ".class");
      if (b == null) return null;
      ClassNode c = new ClassNode(); new ClassReader(b).accept(c, 0); return c;
    };
    ClassNode mixin = node(read(guestJar, "net/fabricmc/fabric/mixin/client/sound/SoundSystemMixin.class"));

    int moved = FabricSoundMixinAdapter.adapt(mixin, targets);
    check("adapt returned 1", moved == 1, "got " + moved);
    if (moved != 1) { finish(); return; }

    MethodNode handler = method(mixin, "getStream");
    check("handler shape is (SoundInstance, SoundBufferLibrary, Sound, boolean)", NEW_HANDLER.equals(handler.desc), handler.desc);
    AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst();
    check("@At.target -> interface getStream on SoundInstance", NEW_TARGET.equals(MixinFit.value(at, "target")), String.valueOf(MixinFit.value(at, "target")));
    check("@Redirect.method stays SoundEngine.play(SoundInstance)V", List.of("L" + ENGINE + ";play" + PLAY).equals(MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler), "method"))), String.valueOf(MixinFit.value(MixinFit.injectorOf(handler), "method")));
    int dispatch = 0;
    for (var i : handler.instructions)
      if (i instanceof MethodInsnNode c && c.owner.equals(API) && c.name.equals("getAudioStream")) dispatch++;
    check("body dispatches exactly one FabricSoundInstance.getAudioStream", dispatch == 1, "dispatch=" + dispatch);
    check("body does NOT call the merged interface getStream (no recursion)",
        !calls(handler, SOUND, "getStream", DESC), "recursive getStream found");

    ClassNode engine = targets.apply(ENGINE);
    MethodNode play = method(engine, "play", PLAY);
    int site = site(play, SOUND + ".getStream" + DESC);
    check("merged SoundEngine.play makes exactly one SoundInstance.getStream call", site >= 0 && count(play, "L" + SOUND + ";getStream" + DESC) == 1, "site=" + site);
    check("the guest's original anchor is GONE from the merged play (retarget required)",
        count(play, "net/minecraft/client/sounds/SoundBufferLibrary.getStream" + RL + "Z)" + FUTURE) == 0, "old anchor still present");

    Type[] params = Type.getArgumentTypes(handler.desc);
    Frame<BasicValue>[] frames = new Analysis().frames(engine, play);
    Frame<BasicValue> f = frames[site];
    int base = f.getStackSize() - params.length;
    String[] expected = {SOUND, "net/minecraft/client/sounds/SoundBufferLibrary", "net/minecraft/client/resources/sounds/Sound", "I"};
    check("handler arity is 4 (receiver + three)", params.length == 4, "argc=" + params.length);
    for (int k = 0; k < 4; k++) {
      Type actual = f.getStack(base + k).getType();
      check("operand[" + k + "] frame type is " + expected[k], same(actual, expected[k]), String.valueOf(actual));
      check("operand[" + k + "] is assignable to handler param " + params[k].getClassName(), assignable(actual, params[k]), actual + " -> " + params[k]);
    }

    // emulated weave
    ClassNode woven = targets.apply(ENGINE);
    MethodNode wovenPlay = method(woven, "play", PLAY);
    MethodNode h = method(mixin, "getStream");
    h.name = "forbric$probe$streamHandler"; h.access = Opcodes.ACC_PRIVATE;
    h.visibleAnnotations = null; h.invisibleAnnotations = null;
    h.visibleParameterAnnotations = null; h.invisibleParameterAnnotations = null; h.parameters = null;
    woven.methods.add(h);
    MethodNode redirector = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "forbric$probe$streamRedirect", NEW_HANDLER, null, null);
    redirector.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
    for (int i = 0; i < params.length; i++) redirector.instructions.add(new VarInsnNode(params[i].getSort() == Type.BOOLEAN ? Opcodes.ILOAD : Opcodes.ALOAD, i));
    redirector.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, woven.name, h.name, NEW_HANDLER, false));
    redirector.instructions.add(new InsnNode(Opcodes.ARETURN));
    redirector.maxStack = 5; redirector.maxLocals = params.length;
    woven.methods.add(redirector);
    int replaced = 0;
    for (int i = 0; i < wovenPlay.instructions.size(); i++) {
      var insn = wovenPlay.instructions.get(i);
      if (insn instanceof MethodInsnNode c && (SOUND + ".getStream" + DESC).equals(c.owner + "." + c.name + c.desc)) {
        wovenPlay.instructions.set(c, new MethodInsnNode(Opcodes.INVOKESTATIC, woven.name, redirector.name, NEW_HANDLER, false));
        replaced++;
      }
    }
    check("weave replaced exactly one call", replaced == 1, "replaced=" + replaced);
    check("woven SoundEngine.play frame-verifies", verify(woven, wovenPlay), "SimpleVerifier rejected the woven body");
    check("adapted handler frame-verifies", verify(woven, h), "SimpleVerifier rejected the handler");
    check("second pass is idempotent (0)", FabricSoundMixinAdapter.adapt(mixin, targets) == 0, "not idempotent");
    finish();
  }

  static int count(MethodNode m, String member) {
    int c = 0;
    for (var i : m.instructions) if (i instanceof MethodInsnNode x && member.equals("L" + x.owner + ";" + x.name + x.desc)) c++;
    return c;
  }
  static boolean calls(MethodNode m, String owner, String name, String desc) {
    for (var i : m.instructions) if (i instanceof MethodInsnNode x && x.owner.equals(owner) && x.name.equals(name) && x.desc.equals(desc)) return true;
    return false;
  }
  static int site(MethodNode m, String member) {
    for (int i = 0; i < m.instructions.size(); i++) {
      var insn = m.instructions.get(i);
      if (insn instanceof MethodInsnNode c && member.equals(c.owner + "." + c.name + c.desc)) return i;
    }
    return -1;
  }
  static boolean verify(ClassNode cn, MethodNode m) {
    try { new Analysis().analyze(cn, m); return true; }
    catch (Throwable e) { System.out.println("    verifier: " + e); return false; }
  }
  static final class Analysis {
    Frame<BasicValue>[] frames(ClassNode cn, MethodNode m) throws Exception { return analyze(cn, m); }
    @SuppressWarnings("unchecked") Frame<BasicValue>[] analyze(ClassNode cn, MethodNode m) throws Exception {
      SimpleVerifier v = new SimpleVerifier(); v.setClassLoader(loader);
      return new Analyzer<BasicValue>(v).analyze(cn.name, m);
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
      boolean intFamily = param.getSort() == Type.BOOLEAN || param.getSort() == Type.INT;
      boolean actualIntFamily = actual.getSort() == Type.BOOLEAN || actual.getSort() == Type.INT;
      return intFamily && actualIntFamily;
    }
    if (actual == null) return false;
    try { return Class.forName(param.getClassName(), false, loader).isAssignableFrom(Class.forName(actual.getClassName(), false, loader)); }
    catch (Throwable e) { return false; }
  }
  static void check(String what, boolean ok, String detail) {
    System.out.println((ok ? "  ok   " : "  FAIL ") + what + (ok ? "" : "  [" + detail + "]")); if (!ok) failures++;
  }
  static void finish() { System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED"); if (failures != 0) System.exit(1); }
  static ClassNode node(byte[] b) { ClassNode c = new ClassNode(); new ClassReader(b).accept(c, 0); return c; }
  static MethodNode method(ClassNode c, String name) { return c.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(); }
  static MethodNode method(ClassNode c, String name, String desc) { return c.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow(); }
  static byte[] read(Path jar, String entry) throws Exception { try (ZipFile z = new ZipFile(jar.toFile())) { return z.getInputStream(z.getEntry(entry)).readAllBytes(); } }
  static URLClassLoader cp(String cp) {
    List<URL> urls = new ArrayList<>();
    for (String s : cp.split(java.io.File.pathSeparator)) if (!s.isBlank()) { try { urls.add(Path.of(s).toUri().toURL()); } catch (Exception e) { throw new RuntimeException(e); } }
    return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
  }
}
