/*
 * Offline probe for the fabric-particles-v1 interface-mixin rejection.
 *
 * Runs the REAL kernel repair (ForbricMergedBaseCompatTransformer) over the REAL fabric-api accessor bytes and
 * asks MIXIN'S OWN classifier (MixinInfo.getVariant) what it makes of the result, and of the same bytes with the
 * SYNTHETIC flag cleared. No re-implementation of the classifier anywhere.
 */
import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer;
import org.spongepowered.asm.launch.MixinBootstrap;

public final class VariantProbe {
	private static final String ACCESSOR = "net.fabricmc.fabric.mixin.client.particle.ParticleManagerAccessor";
	private static final String ACCESSOR_ENTRY = "net/fabricmc/fabric/mixin/client/particle/ParticleManagerAccessor.class";
	private static final String GET_FACTORIES_DESC = "()Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;";

	public static void main(String[] args) throws Exception {
		MixinBootstrap.init();
		org.spongepowered.asm.mixin.MixinEnvironment.getDefaultEnvironment();
		Path api = Path.of(args[0]);
		byte[] original = accessorBytes(api);

		ClassNode before = parse(original);
		before.name = before.name + "$ShippedProbe";
		MethodNode beforeFactories = method(before, "getFactories", GET_FACTORIES_DESC);
		System.out.println("shipped   getFactories flags = " + flags(beforeFactories.access)
				+ "  accessors={" + accessors(before) + "}  variant=" + variant(before));

		byte[] repaired = new ForbricMergedBaseCompatTransformer().transform(ACCESSOR, original, null);
		System.out.println("repaired  bytes changed       = " + (repaired != original));

		ClassNode after = parse(repaired);
		after.name = after.name + "$RepairedProbe";
		MethodNode afterFactories = method(after, "getFactories", GET_FACTORIES_DESC);
		System.out.println("repaired  getFactories flags  = " + flags(afterFactories.access)
				+ "  accessors={" + accessors(after) + "}  variant=" + variant(after));

		ClassNode control = parse(repaired);
		control.name = control.name + "$ControlProbe";
		method(control, "getFactories", GET_FACTORIES_DESC).access &= ~Opcodes.ACC_SYNTHETIC;
		System.out.println("control   (flag cleared)      = " + flags(method(control, "getFactories", GET_FACTORIES_DESC).access)
				+ "  accessors={" + accessors(control) + "}  variant=" + variant(control));
	}

	private static String accessors(ClassNode node) {
		StringBuilder out = new StringBuilder();
		for (MethodNode method : node.methods) {
			boolean accessor = method.visibleAnnotations != null && method.visibleAnnotations.stream()
					.anyMatch(annotation -> "Lorg/spongepowered/asm/mixin/gen/Accessor;".equals(annotation.desc));
			if (accessor) {
				if (out.length() > 0) out.append(',');
				out.append(method.name);
			}
		}
		return out.toString();
	}

	private static String variant(ClassNode node) throws Exception {
		Class<?> mixinInfo = Class.forName("org.spongepowered.asm.mixin.transformer.MixinInfo");
		java.lang.reflect.Method getVariant = mixinInfo.getDeclaredMethod("getVariant", ClassNode.class);
		getVariant.setAccessible(true);
		return getVariant.invoke(null, node).toString();
	}

	private static String flags(int access) {
		return "public=" + ((access & Opcodes.ACC_PUBLIC) != 0)
				+ " abstract=" + ((access & Opcodes.ACC_ABSTRACT) != 0)
				+ " synthetic=" + ((access & Opcodes.ACC_SYNTHETIC) != 0)
				+ " (0x" + Integer.toHexString(access) + ")";
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) {
			if (method.name.equals(name) && method.desc.equals(desc)) return method;
		}
		throw new AssertionError("no " + name + desc + " in " + node.name);
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] accessorBytes(Path api) throws Exception {
		try (ZipFile outer = new ZipFile(api.toFile())) {
			ZipEntry module = outer.stream()
					.filter(entry -> entry.getName().startsWith("META-INF/jars/fabric-particles-v1-"))
					.findFirst().orElseThrow();
			try (ZipInputStream nested = new ZipInputStream(outer.getInputStream(module))) {
				for (ZipEntry entry; (entry = nested.getNextEntry()) != null; ) {
					if (ACCESSOR_ENTRY.equals(entry.getName())) {
						InputStream in = nested;
						return in.readAllBytes();
					}
				}
			}
		}
		throw new AssertionError("no " + ACCESSOR_ENTRY);
	}
}
