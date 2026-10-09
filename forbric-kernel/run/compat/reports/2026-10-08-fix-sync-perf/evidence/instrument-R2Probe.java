import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.transform.RegistrySyncParityInjector;
import net.forbric.kernel.transform.TransformContext;

/**
 * Offline R2 probe: run the real injector over the REAL staged bytes.
 *   args[0] forge-runtime.jar            (NamespacedWrapper)
 *   args[1] remapped fabric-registry-sync (RegistrySyncManager, RemappableRegistry)
 *   args[2] output dir
 */
public final class R2Probe {
	public static void main(String[] args) throws Exception {
		Path forge = Path.of(args[0]);
		Path fabric = Path.of(args[1]);
		Path out = Path.of(args[2]);
		Files.createDirectories(out);

		RegistrySyncParityInjector injector = new RegistrySyncParityInjector();
		TransformContext ctx = new TransformContext(EnvType.CLIENT, false, "intermediary");

		String wrapper = "net.minecraftforge.registries.NamespacedWrapper";
		byte[] win = read(forge, wrapper);
		byte[] wout = injector.transform(wrapper, win, ctx);
		System.out.println("[wrapper] read=" + win.length + "B changed=" + (wout != win));
		Files.write(out.resolve("NamespacedWrapper.class"), wout);
		for (String m : methods(wout)) {
			if (m.startsWith("remap") || m.startsWith("clear") || m.startsWith("registerIdMapping")) {
				System.out.println("  + " + m);
			}
		}

		String sync = "net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager";
		byte[] sin = read(fabric, sync);
		byte[] sout = injector.transform(sync, sin, ctx);
		System.out.println("[fabric-sync] read=" + (sin == null ? -1 : sin.length) + "B changed="
				+ (sin != null && sout != sin));
		if (sout != null) {
			Files.write(out.resolve("RegistrySyncManager.class"), sout);
			ClassNode node = new ClassNode();
			new ClassReader(sout).accept(node, 0);
			for (MethodNode m : node.methods) {
				if (!m.name.equals("apply") || !m.desc.startsWith("(Ljava/util/Map;")) continue;
				int returns = 0;
				int flushed = 0;
				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn.getOpcode() != Opcodes.RETURN) continue;
					returns++;
					AbstractInsnNode prev = insn.getPrevious();
					if (prev instanceof MethodInsnNode call && call.name.equals("finishFabricRemap")) flushed++;
				}
				AbstractInsnNode head = m.instructions.getFirst();
				System.out.println("  apply" + m.desc + " RETURNs=" + returns + " flushed=" + flushed
						+ " headFlip=" + (head == null ? "none" : head.getClass().getSimpleName()));
			}
		}

		String iface = "net.fabricmc.fabric.impl.registry.sync.RemappableRegistry";
		byte[] iin = read(fabric, iface);
		if (iin != null) {
			ClassNode node = new ClassNode();
			new ClassReader(iin).accept(node, 0);
			for (MethodNode m : node.methods) {
				if (m.name.equals("remap")) System.out.println("[RemappableRegistry] " + m.name + m.desc);
			}
		}
	}

	private static byte[] read(Path jar, String binary) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry e = zip.getEntry(binary.replace('.', '/') + ".class");
			if (e == null) return null;
			try (InputStream in = zip.getInputStream(e)) {
				return in.readAllBytes();
			}
		}
	}

	private static List<String> methods(byte[] bytes) {
		List<String> out = new ArrayList<>();
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		for (MethodNode m : node.methods) out.add(m.name + m.desc);
		return out;
	}

	private R2Probe() {
	}
}
