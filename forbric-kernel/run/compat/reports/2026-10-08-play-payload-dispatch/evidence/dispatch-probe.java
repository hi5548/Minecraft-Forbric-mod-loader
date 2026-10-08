import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.transform.CommonNetworkInteropInjector;

/**
 * Offline shape probe: runs the SHIPPED injector over the REAL merged
 * {@code ServerGamePacketListenerImpl} and prints what the play-phase fall-through emits, plus the
 * {@code handleModdedPayload} call the merged super body itself makes.
 *
 * Usage: DispatchProbe <merged.jar> <on|off>
 */
public final class DispatchProbe {
	public static void main(String[] args) throws Exception {
		Path jar = Path.of(args[0]);
		String setting = args[1];
		System.setProperty("forbric.playPayloadFallThrough", setting);
		String name = "net.minecraft.server.network.ServerGamePacketListenerImpl";

		byte[] in = read(jar, name);
		byte[] out = new CommonNetworkInteropInjector().transform(name, in, null);
		if (out == null) out = in;

		System.out.println("merged jar       = " + jar);
		System.out.println("-Dforbric.playPayloadFallThrough = " + setting);
		System.out.println("input  bytes=" + in.length + " sha256=" + sha(in));
		System.out.println("output bytes=" + out.length + " sha256=" + sha(out));
		System.out.println("byte-identical to input = " + Arrays.equals(in, out));

		ClassNode node = new ClassNode();
		new ClassReader(out).accept(node, ClassReader.EXPAND_FRAMES);
		for (MethodNode m : node.methods) {
			if (!m.name.equals("handleCustomPayload")) continue;
			System.out.println(name + "." + m.name + m.desc + "  (" + m.instructions.size() + " insns)");
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call) {
					System.out.println("    INSN " + insn.getOpcode() + " " + call.owner + "." + call.name + call.desc);
				}
			}
			new org.objectweb.asm.tree.analysis.Analyzer<>(
					new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(node.name, m);
			System.out.println("    BasicVerifier: OK");
		}

		String common = "net.minecraft.server.network.ServerCommonPacketListenerImpl";
		ClassNode cn = new ClassNode();
		new ClassReader(read(jar, common)).accept(cn, 0);
		for (MethodNode m : cn.methods) {
			if (!m.name.equals("handleCustomPayload")) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call && call.name.equals("handleModdedPayload")) {
					System.out.println("  [merged super body's own tail] " + call.owner + "." + call.name + call.desc);
				}
			}
		}
	}

	private static byte[] read(Path jar, String name) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(name.replace('.', '/') + ".class");
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
}
