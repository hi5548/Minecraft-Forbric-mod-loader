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
 * cannot-fire lane, offline payload-path probe. Runs the SHIPPED injector over the REAL merged
 * {@code ServerGamePacketListenerImpl} and prints what it does to {@code handleCustomPayload} under each setting
 * of {@code -Dforbric.playPayloadFallThrough}.
 *
 * Usage: FallThroughProbe <merged.jar> <off|on>
 */
public final class FallThroughProbe {
	public static void main(String[] args) throws Exception {
		Path jar = Path.of(args[0]);
		String setting = args[1];
		String name = "net.minecraft.server.network.ServerGamePacketListenerImpl";
		System.setProperty("forbric.playPayloadFallThrough", setting);

		byte[] in;
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(name.replace('.', '/') + ".class");
			try (InputStream stream = zip.getInputStream(entry)) {
				in = stream.readAllBytes();
			}
		}

		byte[] out = new CommonNetworkInteropInjector().transform(name, in, null);
		if (out == null) out = in;

		System.out.println("merged jar        = " + jar);
		System.out.println("-Dforbric.playPayloadFallThrough = " + setting);
		System.out.println("input  bytes=" + in.length + " sha256=" + sha(in));
		System.out.println("output bytes=" + out.length + " sha256=" + sha(out));
		System.out.println("byte-identical to input = " + Arrays.equals(in, out));

		ClassNode node = new ClassNode();
		new ClassReader(out).accept(node, 0);
		for (MethodNode m : node.methods) {
			if (!m.name.equals("handleCustomPayload")) continue;
			System.out.println(name + "." + m.name + m.desc + "  (" + m.instructions.size() + " insns)");
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call) {
					System.out.println("    INSN " + insn.getOpcode() + " " + call.owner + "." + call.name + call.desc);
				}
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
