package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * The three composed providers must be Forge's own {@code CapabilityProvider$AsField} composition and nothing
 * else: that is the type the transformer's synthesised delegates call, and the type Forge's own field-holding
 * classes (the merged base's {@code LevelChunk}) construct.
 *
 * <h2>PORT(1.21.1): the 26.2 per-root twins no longer exist</h2>
 *
 * <p>The 26.2 test compared each of {@code CapabilityProvider$Entities}, {@code $BlockEntities} and
 * {@code $Levels} — the carrier's own per-root providers, each with a {@code fireAttachCapabilitiesEvent} /
 * {@code shouldFireAttachCapabilitiesEvent} pair — instruction for instruction against our composition. Forge
 * 52.1.16 has no such classes: {@code javap} over the staged {@code forge-runtime.jar} shows exactly one nested
 * class there, {@code CapabilityProvider$AsField}, with neither hook name anywhere in it. Forge 52 posts the
 * generic {@code AttachCapabilitiesEvent} from {@code ForgeEventFactory.gatherCapabilities(baseClass, provider,
 * parent)}, which {@code AsField} reaches through {@code initInternal}/{@code getCapabilities}, and its
 * {@code IEventBus} has no {@code hasListeners}, so there is no per-root listener-presence short-circuit left to
 * mirror. The per-root comparison therefore had no 1.21.1 counterpart and was deleted in favour of the invariant
 * it protected, which is still an instruction-shape comparison against the carrier: our providers ARE Forge's
 * {@code AsField}, each built with its own root class, through the carrier's own three-argument lazy constructor.
 */
class KernelForgeCapabilitiesShapeTest {
	private static final Path RUNTIME = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
	private static final Path FORGE = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run",
			"forge-runtime", "forge-runtime.jar").normalize();
	private static final String AS_FIELD = "net/minecraftforge/common/capabilities/CapabilityProvider$AsField";
	private static final String COMPOSED = "net/forbric/kernel/runtime/KernelForgeCapabilities$Composed";
	/** Each composed provider, and the root class Forge's own patched classes pass as its base class. */
	private static final Map<String, String> ROOTS = Map.of(
			"KernelForgeCapabilities$Entities", "net/minecraft/world/entity/Entity",
			"KernelForgeCapabilities$BlockEntities", "net/minecraft/world/level/block/entity/BlockEntity",
			"KernelForgeCapabilities$Levels", "net/minecraft/world/level/Level");

	@Test
	void eachComposedProviderIsForgeAsFieldBuiltFromItsOwnRootClass() throws Exception {
		assumeTrue(Files.isRegularFile(FORGE), "staged Forge carrier absent");
		ClassNode asField = parse(bytesOf(AS_FIELD));
		// The carrier must really expose the constructor our composition calls; the descriptor asserted below is
		// read from the carrier, so a Forge that renames or reshapes it fails here rather than silently drifting.
		MethodNode carrierCtor = constructor(asField,
				"(Ljava/lang/Class;Lnet/minecraftforge/common/capabilities/ICapabilityProviderImpl;Z)V");
		assertNotNull(carrierCtor, AS_FIELD + " lacks the (Class, ICapabilityProviderImpl, boolean) constructor");
		for (Map.Entry<String, String> root : ROOTS.entrySet()) {
			Path compiled = RUNTIME.resolve("net/forbric/kernel/runtime/" + root.getKey() + ".class");
			assumeTrue(Files.isRegularFile(compiled), "runtime not compiled: " + compiled);
			ClassNode ours = parse(Files.readAllBytes(compiled));
			assertEquals(AS_FIELD, superOf(ours), root.getKey() + " must be composed as Forge's own AsField");
			assertEquals(List.of("class " + root.getValue(),
							"INVOKESPECIAL " + COMPOSED + ".<init>(Ljava/lang/Class;Ljava/lang/Object;)V"),
					constructorShape(ours),
					root.getKey() + " must pass its own root class into the composition and add nothing else");
		}
		ClassNode composed = parse(Files.readAllBytes(RUNTIME.resolve(COMPOSED + ".class")));
		assertEquals(AS_FIELD, composed.superName, "the composition itself is Forge's AsField");
		assertEquals(List.of("CHECKCAST net/minecraftforge/common/capabilities/ICapabilityProviderImpl", "ICONST_1",
						"INVOKESPECIAL " + AS_FIELD + ".<init>" + carrierCtor.desc),
				constructorShape(composed),
				"the composition must call Forge's own (Class, ICapabilityProviderImpl, boolean) constructor in lazy mode");
	}

	private static String superOf(ClassNode node) {
		// Composed sits between: Entities -> Composed -> AsField. Walk one level through the compiled output.
		if (node.superName.endsWith("KernelForgeCapabilities$Composed")) {
			try {
				return parse(Files.readAllBytes(RUNTIME.resolve(COMPOSED + ".class"))).superName;
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		}
		return node.superName;
	}

	/** The class constants, casts and super call a constructor emits — its whole meaningful shape. */
	private static List<String> constructorShape(ClassNode node) {
		MethodNode ctor = null;
		for (MethodNode m : node.methods) {
			if (m.name.equals("<init>") && (m.access & Opcodes.ACC_SYNTHETIC) == 0) { ctor = m; break; }
		}
		assertNotNull(ctor, node.name + " has no constructor");
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode insn : ctor.instructions) {
			if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type) out.add("class " + type.getInternalName());
			else if (insn instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST) out.add("CHECKCAST " + cast.desc);
			else if (insn instanceof InsnNode one && one.getOpcode() == Opcodes.ICONST_1) out.add("ICONST_1");
			else if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL) out.add("INVOKESPECIAL " + call.owner + ".<init>" + call.desc);
		}
		return out;
	}

	private static MethodNode constructor(ClassNode node, String desc) {
		for (MethodNode m : node.methods) {
			if (m.name.equals("<init>") && m.desc.equals(desc)) return m;
		}
		return null;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] bytesOf(String internal) throws Exception {
		try (ZipFile zip = new ZipFile(FORGE.toFile())) {
			ZipEntry entry = zip.getEntry(internal + ".class");
			assertNotNull(entry, internal);
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}
