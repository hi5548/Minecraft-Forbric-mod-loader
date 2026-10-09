import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.transform.ForgeDamageSeamsInjector;
import net.forbric.kernel.transform.PayloadWorkOrderingTransformer;

/**
 * Offline probe for 2026-10-08-fix-payload-damage: runs the SHIPPED transformers over the REAL carrier bytes
 * (neoforge-runtime.jar, patched-mc-merged-1.21.1.jar) and prints what they emit. No game, no client launch; the
 * R1 link gate loads the transformed class under the JVM's own verifier.
 *
 * <p>Usage: {@code DamagePayloadProbe <neoforge-runtime.jar> <patched-mc-merged-1.21.1.jar> <forge-runtime.jar>}
 */
public final class DamagePayloadProbe {
	private static final String OWNER = "net.neoforged.neoforge.network.handling.ClientPayloadContext";
	private static final String ENTRY = "net/neoforged/neoforge/network/handling/ClientPayloadContext";
	private static final String RUNNABLE_DESC = "(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;";
	private static final String SUPPLIER_DESC = "(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;";
	private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	private static final String PLAYER = "net/minecraft/world/entity/player/Player";
	private static final String RUNTIME = "net/forbric/kernel/runtime/KernelLivingDamage";

	public static void main(String[] args) throws Exception {
		Path neo = Path.of(args[0]);
		Path merged = Path.of(args[1]);
		Path forgeRt = Path.of(args[2]);
		Path forgePatched = args.length > 3 ? Path.of(args[3]) : null;

		System.out.println("=== R1 - enqueueWork work ordering ===");
		System.out.println("carrier       = " + neo);
		byte[] raw = read(neo, ENTRY);
		byte[] out = new PayloadWorkOrderingTransformer().transform(OWNER, raw, null);
		System.out.println("input  sha256 = " + sha(raw) + "  (" + raw.length + " bytes)");
		System.out.println("output sha256 = " + sha(out) + "  (" + out.length + " bytes)");
		System.out.println("edited        = " + !Arrays.equals(raw, out));
		for (String desc : List.of(RUNNABLE_DESC, SUPPLIER_DESC)) {
			System.out.println();
			System.out.println("enqueueWork " + desc);
			MethodNode before = method(parse(raw), "enqueueWork", desc);
			if (before == null) {
				System.out.println("  [!!] absent from the carrier");
				continue;
			}
			JumpInsnNode shortcut = shortcut(before);
			System.out.println("  before: isSameThread-then-conditional-jump = " + (shortcut == null ? "none" : opcode(shortcut.getOpcode())));
			if (shortcut != null) System.out.println("    inline path: " + block(before, shortcut.label, 4));
			MethodNode after = method(parse(out), "enqueueWork", desc);
			JumpInsnNode go = popThenGoto(after);
			System.out.println("  after : isSameThread-then-conditional-jump = "
					+ (shortcut(after) == null ? "none" : "PRESENT (defect)"));
			System.out.println("  after : POP+GOTO lands on = " + (go == null ? "none" : block(after, go.label, 5)));
			new Analyzer<>(new BasicVerifier()).analyze(OWNER, after);
			System.out.println("  BasicVerifier: OK");
		}

		System.out.println();
		System.out.println("link gate (JVM verifier over the transformed class):");
		try (URLClassLoader deps = new URLClassLoader(
				new URL[] { neo.toUri().toURL(), merged.toUri().toURL(), forgeRt.toUri().toURL() },
				DamagePayloadProbe.class.getClassLoader())) {
			ClassLoader defining = new ClassLoader(deps) {
				@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
					if (OWNER.equals(name)) {
						Class<?> loaded = findLoadedClass(name);
						if (loaded == null) loaded = defineClass(name, out, 0, out.length);
						if (resolve) resolveClass(loaded);
						return loaded;
					}
					return super.loadClass(name, resolve);
				}
			};
			Class<?> loaded = Class.forName(OWNER, true, defining);
			System.out.println("  " + loaded.getName() + " linked and initialised = OK");
			for (java.lang.reflect.Method m : loaded.getDeclaredMethods()) {
				if (m.getName().equals("enqueueWork")) System.out.println("    declared: " + m.toGenericString());
			}
		}

		System.out.println();
		System.out.println("falsifier -D" + PayloadWorkOrderingTransformer.PROPERTY + "=off:");
		System.setProperty(PayloadWorkOrderingTransformer.PROPERTY, "off");
		byte[] payloadOff = new PayloadWorkOrderingTransformer().transform(OWNER, raw, null);
		System.clearProperty(PayloadWorkOrderingTransformer.PROPERTY);
		System.out.println("  the class comes back byte-for-byte unchanged = " + Arrays.equals(raw, payloadOff));

		System.out.println();
		System.out.println("=== A1 - Forge damage seams ===");
		System.out.println("carrier       = " + merged);
		for (String owner : List.of(LIVING, PLAYER)) {
			byte[] in = read(merged, owner);
			byte[] res = new ForgeDamageSeamsInjector().transform(owner.replace('/', '.'), in, null);
			System.out.println();
			System.out.println(owner);
			System.out.println("  input  sha256 = " + sha(in));
			System.out.println("  output sha256 = " + sha(res) + "   edited = " + !Arrays.equals(in, res));
			if (Arrays.equals(in, res)) {
				System.out.println("  [!!] no edit - its anchor is gone on this carrier");
				continue;
			}
			ClassNode node = parse(res);
			MethodNode hurt = byName(node, "actuallyHurt");
			System.out.println("  " + hurt.name + hurt.desc);
			List<String> order = new ArrayList<>();
			for (AbstractInsnNode insn : hurt.instructions) {
				if (insn instanceof MethodInsnNode call && (call.owner.equals(RUNTIME)
						|| call.name.equals("isInvulnerableTo") || call.name.equals("getDamageAfterArmorAbsorb")
						|| call.name.equals("onLivingDamagePre") || call.name.equals("setAbsorptionAmount")
						|| call.name.equals("setHealth"))) order.add(call.name);
			}
			System.out.println("  call order  = " + order);
			MethodInsnNode damage = firstCall(hurt, "damage");
			System.out.println("  damage seam then: " + (damage == null ? "none" : describe(damage.getNext())));
			new Analyzer<>(new BasicVerifier()).analyze(owner, hurt);
			System.out.println("  BasicVerifier: OK");
			System.out.println("  <clinit> notePlayerSeam count = " + count(method(node, "<clinit>", "()V"), "notePlayerSeam"));
			if (owner.equals(PLAYER)) {
				MethodNode entry = hurtEntry(node);
				System.out.println("  player hurt entry = " + entry.name + entry.desc);
				System.out.println("    head (4 insns) = " + firstReal(entry, 4));
			}
			byte[] again = new ForgeDamageSeamsInjector().transform(owner.replace('/', '.'), res, null);
			System.out.println("  second pass byte-identical = " + Arrays.equals(res, again));
		}

		System.out.println();
		System.out.println("link gate (JVM verifier over the transformed damage classes):");
		URL[] deps = new URL[] { merged.toUri().toURL(), neo.toUri().toURL(), forgeRt.toUri().toURL() };
		for (String owner : List.of(LIVING, PLAYER)) {
			byte[] in = read(merged, owner);
			byte[] res = new ForgeDamageSeamsInjector().transform(owner.replace('/', '.'), in, null);
			final String name = owner.replace('/', '.');
			try (URLClassLoader base = new URLClassLoader(deps, DamagePayloadProbe.class.getClassLoader())) {
				ClassLoader defining = new ClassLoader(base) {
					@Override protected Class<?> loadClass(String n, boolean r) throws ClassNotFoundException {
						if (n.equals(name)) {
							Class<?> c = findLoadedClass(n);
							if (c == null) c = defineClass(n, res, 0, res.length);
							if (r) resolveClass(c);
							return c;
						}
						return super.loadClass(n, r);
					}
				};
				Class.forName(name, false, defining);
				System.out.println("  " + name + " linked under the JVM verifier = OK");
			}
		}

		System.out.println();
		System.out.println("falsifiers:");
		System.setProperty(ForgeDamageSeamsInjector.PROPERTY, "off");
		byte[] mergedLiving = read(merged, LIVING);
		System.out.println("  -D" + ForgeDamageSeamsInjector.PROPERTY + "=off: merged LivingEntity byte-identical = "
				+ Arrays.equals(mergedLiving, new ForgeDamageSeamsInjector().transform(LIVING.replace('/', '.'), mergedLiving, null)));
		System.clearProperty(ForgeDamageSeamsInjector.PROPERTY);
		if (forgePatched != null) {
			for (String owner : List.of(LIVING, PLAYER)) {
				byte[] f = read(forgePatched, owner);
				System.out.println("  MinecraftForge base " + owner + " byte-identical = "
						+ Arrays.equals(f, new ForgeDamageSeamsInjector().transform(owner.replace('/', '.'), f, null)));
			}
		}
	}

	// ---- byte helpers ----

	private static byte[] read(Path jar, String internalName) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(internalName + ".class");
			if (entry == null) throw new IllegalStateException(internalName + " absent from " + jar);
			try (InputStream stream = zip.getInputStream(entry)) {
				return stream.readAllBytes();
			}
		}
	}

	private static String sha(byte[] bytes) throws Exception {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
		StringBuilder sb = new StringBuilder();
		for (byte b : digest) sb.append(String.format("%02x", b));
		return sb.toString();
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}

	private static MethodNode byName(ClassNode node, String name) {
		for (MethodNode method : node.methods) if (method.name.equals(name)) return method;
		return null;
	}

	private static MethodNode hurtEntry(ClassNode player) {
		for (MethodNode method : player.methods) {
			if ((method.name.equals("hurtServer") || method.name.equals("hurt")) && method.desc.endsWith(")Z")) return method;
		}
		return null;
	}

	private static JumpInsnNode shortcut(MethodNode method) {
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && call.name.equals("isSameThread") && call.desc.equals("()Z")
					&& insn.getNext() instanceof JumpInsnNode jump && jump.getOpcode() != Opcodes.GOTO) return jump;
		}
		return null;
	}

	private static JumpInsnNode popThenGoto(MethodNode method) {
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() == Opcodes.POP && insn.getNext() instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO) return jump;
		}
		return null;
	}

	private static List<String> block(MethodNode method, LabelNode label, int count) {
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode insn = label.getNext(); insn != null && out.size() < count; insn = insn.getNext()) {
			if (insn instanceof LabelNode || insn instanceof org.objectweb.asm.tree.LineNumberNode
					|| insn instanceof org.objectweb.asm.tree.FrameNode) continue;
			out.add(describe(insn));
		}
		return out;
	}

	private static List<String> firstReal(MethodNode method, int count) {
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null && out.size() < count; insn = insn.getNext()) {
			if (insn.getOpcode() < 0) continue;
			out.add(describe(insn));
		}
		return out;
	}

	private static MethodInsnNode firstCall(MethodNode method, String name) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.name.equals(name)) return call;
		}
		return null;
	}

	private static int count(MethodNode method, String name) {
		if (method == null) return 0;
		int n = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.name.equals(name)) n++;
		}
		return n;
	}

	private static String describe(AbstractInsnNode insn) {
		if (insn instanceof MethodInsnNode m) return "CALL " + m.owner + "." + m.name + m.desc;
		if (insn instanceof FieldInsnNode f) return "FIELD " + f.owner + "." + f.name + ":" + f.desc;
		if (insn instanceof VarInsnNode v) return "VAR " + opcode(v.getOpcode()) + " " + v.var;
		if (insn instanceof TypeInsnNode t) return "TYPE " + opcode(t.getOpcode()) + " " + t.desc;
		if (insn instanceof JumpInsnNode j) return "JUMP " + opcode(j.getOpcode());
		if (insn instanceof InsnNode i) return "INSN " + opcode(i.getOpcode());
		return insn.getClass().getSimpleName() + " " + insn.getOpcode();
	}

	private static String opcode(int opcode) {
		return org.objectweb.asm.util.Printer.OPCODES[opcode & 0xFF];
	}
}
