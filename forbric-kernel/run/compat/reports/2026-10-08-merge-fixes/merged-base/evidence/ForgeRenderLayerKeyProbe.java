/*
 * Offline probe for the merged-base render-layer key repair (audit finding A3 / #1).
 *
 * Drives the REAL repair method compiled from the current tree (net.forbric.kernel.transform.
 * ForbricMergedBaseCompatTransformer#rekeyTheForgeRenderLayerRegistration) over the REAL merged class bytes
 * (patched-mc-merged-1.21.1.jar), and answers, in order:
 *
 *   1. the premise: the Forge block overload writes BLOCK_RENDER_TYPES through a registry delegate (Holder key);
 *   2. after the repair: that delegate is gone, the key is the raw Block, and the value is converted from
 *      Forge's ChunkRenderTypeSet to NeoForge's (the type the survivor reader casts to);
 *   3. the four OTHER Forge-delegate sites in the class (the fluid writer/reader/filler) are untouched;
 *   4. the transformed class passes ASM dataflow verification (BasicVerifier) on every method;
 *   5. a second pass is a no-op (idempotent);
 *   6. NEGATIVE CONTROL: a clone whose ForgeRegistries owner is perturbed is refused byte-for-byte.
 *
 * Usage: java ForgeRenderLayerKeyProbe [merged.jar]
 * Default jar is the internal-disk staging copy the running client also uses.
 */
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.file.Files;
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
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.util.CheckClassAdapter;

public final class ForgeRenderLayerKeyProbe {
	private static final String OWNER = "net.minecraft.client.renderer.ItemBlockRenderTypes";
	private static final String ENTRY = "net/minecraft/client/renderer/ItemBlockRenderTypes.class";
	private static final String FORGE_WRITER_DESC =
			"(Lnet/minecraft/world/level/block/Block;Lnet/minecraftforge/client/ChunkRenderTypeSet;)V";
	private static final String DEFAULT_JAR =
			"/Applications/.minecraft/.forbric-build/out/patched-mc-merged-1.21.1.jar";

	private static int failures = 0;

	public static void main(String[] args) throws Exception {
		Path jar = Path.of(args.length > 0 ? args[0] : DEFAULT_JAR);
		byte[] raw = read(jar);
		System.out.println("== merged class ==");
		System.out.println(OWNER + "  from " + jar);
		System.out.println("sha256(bytes) = " + sha256(raw));

		Method repair = Class.forName("net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer")
				.getDeclaredMethod("rekeyTheForgeRenderLayerRegistration", ClassNode.class);
		repair.setAccessible(true);

		System.out.println();
		System.out.println("== 1. premise ==");
		ClassNode before = parse(raw);
		int writerDelegatesBefore = delegateSites(writer(before)).size();
		int classDelegatesBefore = delegateSites(before).size();
		System.out.println("Forge block writer delegate sites = " + writerDelegatesBefore);
		System.out.println("whole-class Forge delegate sites  = " + classDelegatesBefore);
		check(writerDelegatesBefore == 1, "the Forge block overload keys by a registry delegate");
		check(classDelegatesBefore == 4, "four delegate sites (block writer + fluid writer/reader/filler)");

		System.out.println();
		System.out.println("== 2. repair ==");
		boolean applied = (Boolean) repair.invoke(transformer(), before);
		check(applied, "the repair applies to the measured generation");
		byte[] fixed = write(before);
		ClassNode after = parse(fixed);
		List<AbstractInsnNode> writerAfter = real(writer(after));
		System.out.println("writer body after repair:");
		for (AbstractInsnNode insn : writerAfter) print("    ", insn);
		check(delegateSites(writer(after)).isEmpty(), "no registry delegate in the writer after the repair");
		check(!namesForgeBlocks(writer(after)), "no ForgeRegistries.BLOCKS read in the writer");
		check(hasCall(writerAfter, Opcodes.INVOKEVIRTUAL, "net/minecraftforge/client/ChunkRenderTypeSet", "asList"),
				"the Forge set is read as a list");
		check(hasCall(writerAfter, Opcodes.INVOKESTATIC, "net/neoforged/neoforge/client/ChunkRenderTypeSet", "of"),
				"and rebuilt as NeoForge's ChunkRenderTypeSet (the type the reader casts to)");
		check(writerAfter.size() == 9, "same instruction count (two removed, two inserted)");
		// The key that reaches Map.put must be the raw Block: ALOAD 0 immediately after the map getstatic.
		check(writerAfter.get(1) instanceof FieldInsnNode map && "BLOCK_RENDER_TYPES".equals(map.name)
				&& "Ljava/util/Map;".equals(map.desc), "the map load is still first");
		check(writerAfter.get(2).getOpcode() == Opcodes.ALOAD && ((org.objectweb.asm.tree.VarInsnNode) writerAfter.get(2)).var == 0,
				"the raw Block (aload_0) is the key handed to put");

		System.out.println();
		System.out.println("== 3. the fluid pair is not touched ==");
		check(delegateSites(after).size() == 3, "the three fluid delegate sites survive");
		check(classDelegatesBefore - delegateSites(after).size() == 1, "exactly one delegate site removed");

		System.out.println();
		System.out.println("== 4. verifier ==");
		for (MethodNode method : after.methods) {
			new Analyzer<>(new BasicVerifier()).analyze(after.name, method);
		}
		// Structural check (no dataflow): validates the class-file shape and every instruction operand without
		// needing the game types on the classpath. Dataflow is covered by BasicVerifier above.
		StringWriter structure = new StringWriter();
		CheckClassAdapter structural = new CheckClassAdapter(new org.objectweb.asm.ClassWriter(0), false);
		try {
			new ClassReader(fixed).accept(structural, 0);
		} catch (RuntimeException invalid) {
			structure.append(invalid.toString());
		}
		check(structure.toString().isBlank(), "ASM structural check is silent: " + structure.toString().trim());
		System.out.println("BasicVerifier: every method of " + after.name + " analyses clean");

		System.out.println();
		System.out.println("== 5. idempotence ==");
		ClassNode again = parse(fixed);
		boolean second = (Boolean) repair.invoke(transformer(), again);
		check(!second, "a second pass is refused (nothing left to match)");

		System.out.println();
		System.out.println("== 6. negative control ==");
		ClassNode perturbed = parse(raw);
		FieldInsnNode registry = (FieldInsnNode) real(writer(perturbed)).get(2);
		registry.owner = "net/minecraftforge/registries/ForgeRegistriesX";
		byte[] beforeInvoke = write(perturbed);
		boolean refused = !(Boolean) repair.invoke(transformer(), perturbed);
		byte[] afterInvoke = write(perturbed);
		check(refused, "a perturbed shape is refused rather than half-re-keyed");
		check(java.util.Arrays.equals(beforeInvoke, afterInvoke), "and the class is handed back unchanged");

		System.out.println();
		if (failures == 0) {
			System.out.println("ALL CHECKS PASSED");
		} else {
			System.out.println(failures + " CHECK(S) FAILED");
			System.exit(1);
		}
	}

	private static Object transformer() throws Exception {
		return Class.forName("net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer")
				.getDeclaredConstructor().newInstance();
	}

	private static byte[] read(Path jar) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(ENTRY);
			if (entry == null) throw new IllegalStateException(ENTRY + " absent from " + jar);
			return zip.getInputStream(entry).readAllBytes();
		}
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] write(ClassNode node) {
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static MethodNode writer(ClassNode node) {
		for (MethodNode method : node.methods) {
			if ("setRenderLayer".equals(method.name) && FORGE_WRITER_DESC.equals(method.desc)) return method;
		}
		throw new IllegalStateException("Forge block overload absent");
	}

	private static List<AbstractInsnNode> real(MethodNode method) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() >= 0) out.add(insn);
		}
		return out;
	}

	private static List<AbstractInsnNode> delegateSites(ClassNode node) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && "net/minecraftforge/registries/IForgeRegistry".equals(call.owner)
						&& "getDelegateOrThrow".equals(call.name)) out.add(insn);
			}
		}
		return out;
	}

	private static List<AbstractInsnNode> delegateSites(MethodNode method) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && "net/minecraftforge/registries/IForgeRegistry".equals(call.owner)
					&& "getDelegateOrThrow".equals(call.name)) out.add(insn);
		}
		return out;
	}

	private static boolean namesForgeBlocks(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof FieldInsnNode field && "net/minecraftforge/registries/ForgeRegistries".equals(field.owner)
					&& "BLOCKS".equals(field.name)) return true;
		}
		return false;
	}

	private static boolean hasCall(List<AbstractInsnNode> insns, int opcode, String owner, String name) {
		for (AbstractInsnNode insn : insns) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == opcode && owner.equals(call.owner)
					&& name.equals(call.name)) return true;
		}
		return false;
	}

	private static void print(String indent, AbstractInsnNode insn) {
		StringBuilder line = new StringBuilder(indent).append(org.objectweb.asm.util.Printer.OPCODES[insn.getOpcode()]);
		if (insn instanceof MethodInsnNode call) {
			line.append(' ').append(call.owner).append('.').append(call.name).append(call.desc);
		} else if (insn instanceof FieldInsnNode field) {
			line.append(' ').append(field.owner).append('.').append(field.name).append(':').append(field.desc);
		} else if (insn instanceof org.objectweb.asm.tree.VarInsnNode var) {
			line.append(' ').append(var.var);
		}
		System.out.println(line);
	}

	private static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok   " : "  FAIL ") + what);
		if (!ok) failures++;
	}

	private static String sha256(byte[] bytes) throws Exception {
		java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
		StringBuilder out = new StringBuilder();
		for (byte b : digest.digest(bytes)) out.append(String.format("%02x", b));
		return out.toString();
	}
}
