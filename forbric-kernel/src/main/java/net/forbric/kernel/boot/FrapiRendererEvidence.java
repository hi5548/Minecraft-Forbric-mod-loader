package net.forbric.kernel.boot;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Whether a jar can actually take the Fabric Rendering API's renderer slot.
 *
 * <p>{@code "fabric-renderer-api-v1:contains_renderer"} is a promise: whoever declares it will register a renderer,
 * so Indigo stands down. A Forge-family build may carry the declaration without the code — Sodium 0.9.1's NeoForge
 * build does, its Fabric build being the one that ships the renderer. When that build wins arbitration, forwarding
 * the promise leaves the slot empty and the first block drawn through FRAPI (a block in an item frame) dies on
 * "Attempted to retrieve active rendering plug-in before one was registered".
 *
 * <p>The evidence is a direct call to a registration entry point, anywhere in the jar or the jars nested in it. The
 * entry point's name moved between generations — 1.21.1 calls
 * {@code net.fabricmc.fabric.api.renderer.v1.RendererAccess.registerRenderer}, which is exactly what Sodium
 * 0.8.13's NeoForge build calls, while the newer API moved that package under {@code api/client/renderer/v1} — so
 * both are recognised (see {@link #isRegistrationEntryPoint}). A reflective registrar would be missed; the caller
 * keeps the promise when the jar cannot be read.
 */
final class FrapiRendererEvidence {
	/**
	 * The class-name needles: any of these in the bytes is the cheap "does this class even mention a renderer
	 * entry point" test. The API's package moved between generations — 1.21.1's fabric-renderer-api-v1 exposes
	 * {@code fabric/api/renderer/v1} ({@code RendererAccess}, {@code Renderer}), the newer one moved it to
	 * {@code api/client/renderer/v1} — and Sodium's NeoForge build (measured, 0.8.13) calls the former.
	 */
	private static final byte[][] NEEDLES = {
			needle("fabric/api/renderer/v1/Renderer"),
			needle("fabric/api/client/renderer/v1/Renderer"),
			needle("fabric/impl/renderer/Renderer"),
			needle("fabric/impl/client/renderer/Renderer"),
	};

	private FrapiRendererEvidence() { }

	/** @throws IOException when the jar itself is not a readable archive (the caller then keeps the declaration) */
	static boolean registersRenderer(Path jar) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (var entries = zip.entries(); entries.hasMoreElements(); ) {
				ZipEntry entry = entries.nextElement();
				String name = entry.getName();
				if (name.endsWith(".jar")) {
					try (InputStream in = zip.getInputStream(entry)) { if (scan(in, 1)) return true; }
				} else if (name.endsWith(".class")) {
					try (InputStream in = zip.getInputStream(entry)) { if (registers(in.readAllBytes())) return true; }
				}
			}
		}
		return false;
	}
	private static boolean registers(byte[] bytes) {
		return mentions(bytes) && callsRegister(bytes);
	}

	private static boolean scan(InputStream stream, int depth) throws IOException {
		ZipInputStream zip = new ZipInputStream(stream);
		for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
			String name = entry.getName();
			if (name.endsWith(".jar") && depth < 4) {
				if (scan(new ByteArrayInputStream(zip.readAllBytes()), depth + 1)) return true;
			} else if (name.endsWith(".class")) {
				if (registers(zip.readAllBytes())) return true;
			}
		}
		return false;
	}

	static boolean callsRegister(byte[] bytes) {
		boolean[] found = { false };
		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				return new MethodVisitor(Opcodes.ASM9) {
					@Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
						if (isRegistrationEntryPoint(owner, method)) found[0] = true;
					}
				};
			}
		}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return found[0];
	}

	/**
	 * The FRAPI registration entry points, by the names each generation gives them.
	 *
	 * <p>The 1.21.1 API is {@code net.fabricmc.fabric.api.renderer.v1.RendererAccess.registerRenderer}, and that
	 * is exactly what Sodium 0.8.13's NeoForge build calls (measured in its own bytes: {@code getstatic
	 * RendererAccess.INSTANCE; getstatic SodiumRenderer.INSTANCE; invokeinterface
	 * RendererAccess.registerRenderer}). The impl behind it is {@code ...impl.renderer.RendererAccessImpl}. The
	 * older check looked only for {@code api/client/renderer/v1.Renderer.register} and
	 * {@code impl/client/renderer/RendererManager.registerRenderer}, so it never saw that call — it stripped
	 * Sodium's {@code contains_renderer}, Indigo took the FRAPI slot, and Sodium's own registration then threw
	 * "A second rendering plug-in attempted to register" out of {@code SodiumForgeMod.<init>}. Both generations'
	 * names are recognised.
	 */
	private static boolean isRegistrationEntryPoint(String owner, String method) {
		if (method.equals("registerRenderer")) {
			return owner.equals("net/fabricmc/fabric/api/renderer/v1/RendererAccess")
					|| owner.equals("net/fabricmc/fabric/api/client/renderer/v1/RendererAccess")
					|| owner.equals("net/fabricmc/fabric/impl/renderer/RendererAccessImpl")
					|| owner.equals("net/fabricmc/fabric/impl/client/renderer/RendererAccessImpl")
					|| owner.equals("net/fabricmc/fabric/impl/renderer/RendererManager")
					|| owner.equals("net/fabricmc/fabric/impl/client/renderer/RendererManager");
		}
		return method.equals("register")
				&& (owner.equals("net/fabricmc/fabric/api/renderer/v1/Renderer")
						|| owner.equals("net/fabricmc/fabric/api/client/renderer/v1/Renderer"));
	}

	private static boolean mentions(byte[] bytes) {
		for (byte[] needle : NEEDLES) if (contains(bytes, needle)) return true;
		return false;
	}

	private static byte[] needle(String ascii) {
		return ascii.getBytes(StandardCharsets.UTF_8);
	}

	private static boolean contains(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i <= haystack.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
			return true;
		}
		return false;
	}
}
