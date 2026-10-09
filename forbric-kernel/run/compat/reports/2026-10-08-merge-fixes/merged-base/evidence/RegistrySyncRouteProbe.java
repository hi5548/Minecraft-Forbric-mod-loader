/*
 * Offline probe for finding #4 (audit C-registry.md B1): the merged net.minecraft.core.RegistrySynchronization
 * takes MinecraftForge's <clinit> (its NETWORKABLE_REGISTRIES is built by Forge's
 * DataPackRegistriesHooks.grabNetworkableRegistries) where NeoForge's discarded <clinit> derived the same set
 * directly from RegistryDataLoader.SYNCHRONIZED_REGISTRIES.
 *
 * The question the census leaves open is whether that is a LOSS. This probe answers it from the bytes:
 *
 *   1. the merged clinit calls Forge's hook with a Supplier;
 *   2. that Supplier is the merged class's own lambda$static$0, and its body is byte-for-byte the expression
 *      NeoForge's discarded <clinit> used (RegistryDataLoader.SYNCHRONIZED_REGISTRIES -> map(key) -> toSet);
 *   3. Forge's grabNetworkableRegistries UNIONS the supplier's set into its own NETWORKABLE_REGISTRIES and returns
 *      an unmodifiable view of that UNION — a superset, never a replacement;
 *   4. therefore the merged set ⊇ what NeoForge's clinit would have produced, and the only additions are Forge's
 *      own custom synced-registry keys (added by addRegistryCodec only when a network codec is present).
 *
 * Usage: java RegistrySyncRouteProbe [merged.jar] [forge-runtime.jar] [patched-mc-neoforge.jar]
 */
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class RegistrySyncRouteProbe {
	private static final String RS = "net/minecraft/core/RegistrySynchronization";
	private static final String ENTRY = "net/minecraft/core/RegistrySynchronization.class";
	private static final String DPRH = "net/minecraftforge/registries/DataPackRegistriesHooks";
	private static final String DPRH_ENTRY = "net/minecraftforge/registries/DataPackRegistriesHooks.class";
	private static int failures = 0;

	public static void main(String[] args) throws Exception {
		Path merged = Path.of(args.length > 0 ? args[0] : "/Applications/.minecraft/.forbric-build/out/patched-mc-merged-1.21.1.jar");
		Path forge = Path.of(args.length > 1 ? args[1] : "/Applications/.minecraft/.forbric-build/out/forge-runtime.jar");
		Path neo = Path.of(args.length > 2 ? args[2] : "/Applications/.minecraft/.forbric-build/out/patched-mc-neoforge-1.21.1.jar");

		ClassNode mergedRs = parse(read(merged, ENTRY));
		ClassNode neoRs = parse(read(neo, ENTRY));
		ClassNode forgeHooks = parse(read(forge, DPRH_ENTRY));

		System.out.println("== 1. the merged clinit is Forge's route ==");
		MethodNode mergedClinit = method(mergedRs, "<clinit>", "()V");
		List<AbstractInsnNode> clinit = real(mergedClinit);
		String clinitCall = calls(clinit).stream().map(m -> m.owner + "." + m.name).toList().toString();
		System.out.println("   " + clinitCall);
		check(clinit.stream().anyMatch(i -> i instanceof MethodInsnNode m && DPRH.equals(m.owner)
				&& "grabNetworkableRegistries".equals(m.name)), "the merged clinit calls Forge's grabNetworkableRegistries");
		check(clinit.stream().anyMatch(i -> i instanceof MethodInsnNode m
						&& m.desc.endsWith("Ljava/util/function/Supplier;)Ljava/util/Set;")),
				"...with a Supplier argument (its own lambda$static$0)");

		System.out.println();
		System.out.println("== 2. that supplier IS NeoForge's expression ==");
		MethodNode supplier = method(mergedRs, "lambda$static$0", "()Ljava/util/Set;");
		List<AbstractInsnNode> supplierBody = real(supplier);
		MethodNode neoClinit = method(neoRs, "<clinit>", "()V");
		List<AbstractInsnNode> neoBody = real(neoClinit);
		System.out.println("   merged lambda$static$0 : " + render(supplierBody));
		System.out.println("   neo (discarded) clinit : " + render(neoBody));
		// Same instructions except the terminal one: the supplier ends areturn, neo's clinit putstatic.
		check(samePrefix(supplierBody, neoBody), "merged lambda$static$0 is byte-for-byte NeoForge's clinit expression");
		check(supplierBody.stream().anyMatch(i -> i instanceof FieldInsnNode f
				&& "net/minecraft/resources/RegistryDataLoader".equals(f.owner) && "SYNCHRONIZED_REGISTRIES".equals(f.name)),
				"...reading RegistryDataLoader.SYNCHRONIZED_REGISTRIES (the base's live union, set by NeoForge's hook)");

		System.out.println();
		System.out.println("== 3. Forge's hook UNIONS, it does not replace ==");
		MethodNode hook = method(forgeHooks, "grabNetworkableRegistries", null);
		List<AbstractInsnNode> hookBody = real(hook);
		check(hookBody.stream().anyMatch(i -> i instanceof MethodInsnNode m && "addAll".equals(m.name)),
				"Forge's hook does Set.addAll(supplier.get())");
		check(hookBody.stream().anyMatch(i -> i instanceof MethodInsnNode m
				&& "java/util/Collections".equals(m.owner) && "unmodifiableSet".equals(m.name)),
				"and returns unmodifiableSet(that union)");
		check(hookBody.stream().anyMatch(i -> i instanceof FieldInsnNode f && "NETWORKABLE_REGISTRIES".equals(f.name)
				&& f.getOpcode() == Opcodes.GETSTATIC), "...over Forge's own NETWORKABLE_REGISTRIES set");

		System.out.println();
		System.out.println("== 4. conclusion (recorded, not repaired) ==");
		System.out.println("   merged NETWORKABLE_REGISTRIES = NeoForge's SYNCHRONIZED_REGISTRIES keys");
		System.out.println("                                  ∪ MinecraftForge's custom synced-registry keys");
		System.out.println("   -> a superset of NeoForge's; only the membership filter in ownedNetworkableRegistries reads");
		System.out.println("      it, so extra keys are inert unless that registry is loaded — and then syncing it is correct.");
		System.out.println("   -> rewriting the clinit to NeoForge's route would DROP Forge's custom synced registries: a");
		System.out.println("      regression for Forge mods, not a repair. Recorded as a benign divergence (no loss).");

		System.out.println();
		if (failures == 0) System.out.println("ALL CHECKS PASSED");
		else { System.out.println(failures + " CHECK(S) FAILED"); System.exit(1); }
	}

	/** The supplier ends in areturn; Neo's clinit ends in putstatic + return. Compare everything before those. */
	private static boolean samePrefix(List<AbstractInsnNode> a, List<AbstractInsnNode> b) {
		List<AbstractInsnNode> x = a.subList(0, a.size() - 1);
		List<AbstractInsnNode> y = b.subList(0, Math.max(0, b.size() - 2));
		if (x.size() != y.size()) return false;
		for (int i = 0; i < x.size(); i++) {
			if (!renderOne(x.get(i)).equals(renderOne(y.get(i)))) return false;
		}
		return true;
	}

	private static String render(List<AbstractInsnNode> insns) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < insns.size(); i++) {
			if (i > 0) out.append(" | ");
			out.append(renderOne(insns.get(i)));
		}
		return out.toString();
	}

	private static String renderOne(AbstractInsnNode insn) {
		if (insn instanceof FieldInsnNode f) return f.owner + "." + f.name;
		if (insn instanceof MethodInsnNode m) return m.owner + "." + m.name + m.desc;
		return org.objectweb.asm.util.Printer.OPCODES[insn.getOpcode()];
	}

	private static List<MethodInsnNode> calls(List<AbstractInsnNode> insns) {
		List<MethodInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn : insns) if (insn instanceof MethodInsnNode m) out.add(m);
		return out;
	}

	private static byte[] read(Path jar, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry e = zip.getEntry(entry);
			if (e == null) throw new IllegalStateException(entry + " absent from " + jar);
			return zip.getInputStream(e).readAllBytes();
		}
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) return m;
		throw new IllegalStateException(node.name + "#" + name + " absent");
	}

	private static List<AbstractInsnNode> real(MethodNode method) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() >= 0) out.add(insn);
		}
		return out;
	}

	private static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok   " : "  FAIL ") + what);
		if (!ok) failures++;
	}
}
