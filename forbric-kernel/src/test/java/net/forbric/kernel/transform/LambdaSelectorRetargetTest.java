/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.kernel.TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Pins the retarget against the REAL merged-base members, because the whole point is a shape that is present under
 * a different number. The two family members sit side by side: fabric-registry-sync-v0's selector renumbers and is
 * rewritten, fabric-item-api-v1's names a different function on the same name and number and is refused (it is the
 * mixin {@code MergedBaseMixinCompat.SUPPRESSED_MIXINS} pins whole — undo that and its hook binds the wrong lambda).
 */
class LambdaSelectorRetargetTest {
	private static final String REGISTRY_SYNC = "net/minecraft/core/RegistrySynchronization";
	private static final String ENCHANT_HELPER = "net/minecraft/world/item/enchantment/EnchantmentHelper";

	private static final String REGISTRY_ENTRY = "Lnet/minecraft/core/RegistryAccess$RegistryEntry;";
	private static final String REGISTRY_LAMBDA_4 = "L" + REGISTRY_SYNC + ";lambda$ownedNetworkableRegistries$4"
			+ "(" + REGISTRY_ENTRY + ")Z";
	private static final String REGISTRY_LAMBDA_5 = "L" + REGISTRY_SYNC + ";lambda$ownedNetworkableRegistries$5"
			+ "(" + REGISTRY_ENTRY + ")Z";
	private static final String PACK_DESC = "(Ljava/util/Set;Lnet/minecraft/resources/RegistryDataLoader$RegistryData;"
			+ "Lcom/mojang/serialization/DynamicOps;Ljava/util/function/BiConsumer;Lnet/minecraft/core/Registry;)V";
	private static final String ENCHANT_STACK_LAMBDA = "L" + ENCHANT_HELPER + ";lambda$getAvailableEnchantmentResults$41"
			+ "(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/core/Holder;)Z";

	@AfterEach
	void reset() {
		System.clearProperty(LambdaSelectorRetarget.PROPERTY);
	}

	@Test
	void theRenumberedLambdaSelectorsPointAtTheMembersTheMergedBaseDeclares() throws Exception {
		Path base = TestFixtures.mergedBase();
		TestFixtures.requireFiles("merged base", base);

		// Premise, read from the real base: the selector's number is gone, and its descriptor lives under $5.
		ClassNode owner = classFromJar(base, REGISTRY_SYNC + ".class");
		assertNull(method(owner, "lambda$ownedNetworkableRegistries$4", "(" + REGISTRY_ENTRY + ")Z"),
				"premise: the merged base does not declare the selector's number");
		assertNotNull(method(owner, "lambda$ownedNetworkableRegistries$5", "(" + REGISTRY_ENTRY + ")Z"),
				"premise: the merged base declares the selector's descriptor under $5");
		assertNull(method(owner, "lambda$packRegistry$3", PACK_DESC),
				"premise: the second selector's number is gone too");
		assertNotNull(method(owner, "lambda$packRegistry$4", PACK_DESC),
				"premise: its descriptor lives under $4");

		ClassNode node = mixin(REGISTRY_SYNC);
		handler(node, "filterNonSyncedEntries", REGISTRY_LAMBDA_4);
		handler(node, "filterNonSyncedEntriesAgain", "L" + REGISTRY_SYNC + ";lambda$packRegistry$3" + PACK_DESC);
		byte[] mixin = bytes(node);

		byte[] retargeted = new LambdaSelectorRetarget(resolver())
				.transform("net.fabricmc.fabric.mixin.registry.sync.SerializableRegistriesMixin", mixin, null);

		assertNotSame(mixin, retargeted, "a renumbering is an edit, not a pass-through");
		assertEquals(List.of(REGISTRY_LAMBDA_5), selectors(retargeted, "filterNonSyncedEntries"));
		assertEquals(List.of("L" + REGISTRY_SYNC + ";lambda$packRegistry$4" + PACK_DESC),
				selectors(retargeted, "filterNonSyncedEntriesAgain"),
				"both real SerializableRegistriesMixin selectors move to the number the base declares");
	}

	@Test
	void aDifferentFunctionSharingTheNameAndNumberIsLeftToTheStandDown() throws Exception {
		Path base = TestFixtures.mergedBase();
		TestFixtures.requireFiles("merged base", base);

		ClassNode owner = classFromJar(base, ENCHANT_HELPER + ".class");
		assertNotNull(method(owner, "lambda$getAvailableEnchantmentResults$41", "(ILjava/util/List;Lnet/minecraft/core/Holder;)V"),
				"premise: the merged base's $41 is the unrelated int accumulator");
		assertNull(method(owner, "lambda$getAvailableEnchantmentResults$41",
						"(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/core/Holder;)Z"),
				"premise: nothing in the base has the mixin's $41 shape");

		ClassNode node = mixin(ENCHANT_HELPER);
		handler(node, "onAvailableEnchantmentResults", ENCHANT_STACK_LAMBDA);
		byte[] mixin = bytes(node);

		assertSame(mixin, new LambdaSelectorRetarget(resolver())
						.transform("net.fabricmc.fabric.mixin.item.EnchantmentHelperMixin", mixin, null),
				"the same name and number name a different function: refuse, leaving the documented stand-down intact");
	}

	@Test
	void anAmbiguousNumberResolvesWhenTheClassReferencesOnlyOneBody() {
		byte[] owner = ownerClassReferencing("alpha/Foo", List.of("lambda$foo$1", "lambda$foo$2"), "(I)V", "lambda$foo$2");
		Function<String, byte[]> resolver = name -> "alpha/Foo".equals(name) ? owner : null;

		ClassNode node = mixin("alpha/Foo");
		handler(node, "handler", "Lalpha/Foo;lambda$foo$3(I)V");
		byte[] retargeted = new LambdaSelectorRetarget(resolver).transform("p.HandlerMixin", bytes(node), null);

		assertEquals(List.of("Lalpha/Foo;lambda$foo$2(I)V"), selectors(retargeted, "handler"),
				"the merge kept a dead duplicate; the one the class references through its invokedynamic is the body");
	}

	@Test
	void anAmbiguousNumberWithBothBodiesReferencedIsStillRefused() {
		byte[] owner = ownerClassReferencing("alpha/Foo", List.of("lambda$foo$1", "lambda$foo$2"), "(I)V",
				"lambda$foo$1", "lambda$foo$2");
		Function<String, byte[]> resolver = name -> "alpha/Foo".equals(name) ? owner : null;

		ClassNode node = mixin("alpha/Foo");
		handler(node, "handler", "Lalpha/Foo;lambda$foo$3(I)V");
		byte[] mixin = bytes(node);

		assertSame(mixin, new LambdaSelectorRetarget(resolver).transform("p.HandlerMixin", mixin, null),
				"both bodies are referenced, so the number still cannot be trusted");
	}

	@Test
	void twoCandidatesForOneEnclosingAndDescriptorAreNotGuessed() {
		byte[] owner = ownerClass("alpha/Foo", "(I)V", "lambda$foo$1", "lambda$foo$2");
		Function<String, byte[]> onlyFoo = name -> "alpha/Foo".equals(name) ? owner : null;

		ClassNode node = mixin("alpha/Foo");
		handler(node, "handler", "Lalpha/Foo;lambda$foo$3(I)V");
		byte[] mixin = bytes(node);

		assertSame(mixin, new LambdaSelectorRetarget(onlyFoo).transform("p.HandlerMixin", mixin, null),
				"two methods share enclosing name and descriptor, so the number cannot be trusted");
	}

	@Test
	void aNonMixinClassAndTheOffSwitchBothPassThrough() {
		ClassNode plain = new ClassNode();
		plain.version = Opcodes.V21;
		plain.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
		plain.name = "p/Plain";
		plain.superName = "java/lang/Object";
		byte[] plainBytes = bytes(plain);
		assertSame(plainBytes, new LambdaSelectorRetarget(resolver()).transform("p.Plain", plainBytes, null));

		ClassNode node = mixin(REGISTRY_SYNC);
		handler(node, "filterNonSyncedEntries", REGISTRY_LAMBDA_4);
		byte[] mixin = bytes(node);
		System.setProperty(LambdaSelectorRetarget.PROPERTY, "off");
		assertSame(mixin, new LambdaSelectorRetarget(resolver()).transform("p.M", mixin, null));
	}

	// ---------------------------------------------------------------------------------------------------------------

	/** The merged base, read by the internal name the selector's owner carries. */
	private static Function<String, byte[]> resolver() {
		return name -> {
			Path base = TestFixtures.mergedBase();
			if (base == null) return null;
			try {
				return readEntry(base, name + ".class");
			} catch (Exception unreadable) {
				return null;
			}
		};
	}

	private static ClassNode mixin(String target) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.access = Opcodes.ACC_ABSTRACT | Opcodes.ACC_SUPER;
		node.name = "p/HandlerMixin";
		node.superName = "java/lang/Object";
		AnnotationNode mixin = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		mixin.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(target)))));
		node.visibleAnnotations = new ArrayList<>(List.of(mixin));
		return node;
	}

	private static void handler(ClassNode node, String name, String... selectors) {
		MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, name, "()V", null, null);
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(selectors))));
		method.visibleAnnotations = new ArrayList<>(List.of(inject));
		node.methods.add(method);
	}

	private static byte[] ownerClass(String internalName, String desc, String... lambdaNames) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
		node.name = internalName;
		node.superName = "java/lang/Object";
		for (String lambda : lambdaNames) {
			node.methods.add(new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
					lambda, desc, null, null));
		}
		return bytes(node);
	}

	/**
	 * The same owner with the named lambda bodies wired into an {@code invokedynamic}'s bootstrap arguments — the way
	 * javac leaves a live lambda body and a byte-merge can leave a dead duplicate beside it.
	 */
	private static byte[] ownerClassReferencing(String internalName, List<String> lambdas, String desc, String... referenced) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
		node.name = internalName;
		node.superName = "java/lang/Object";
		for (String lambda : lambdas) {
			node.methods.add(new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
					lambda, desc, null, null));
		}
		MethodNode carrier = new MethodNode(Opcodes.ACC_PUBLIC, "carrier", "()V", null, null);
		for (String lambda : referenced) {
			org.objectweb.asm.Handle handle = new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, internalName, lambda, desc, false);
			carrier.instructions.add(new org.objectweb.asm.tree.InvokeDynamicInsnNode("accept",
					"()Ljava/lang/Object;", handle, handle));
			carrier.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.POP));
		}
		carrier.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
		node.methods.add(carrier);
		return bytes(node);
	}

	private static byte[] bytes(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** The {@code method} selectors of the named handler's injector annotation. */
	private static List<String> selectors(byte[] mixin, String handler) {
		ClassNode node = new ClassNode();
		new ClassReader(mixin).accept(node, 0);
		for (MethodNode method : node.methods) {
			if (!method.name.equals(handler)) continue;
			for (AnnotationNode annotation : method.visibleAnnotations) {
				if (!GuestInjectorPruner.INJECTOR_DESCS.contains(annotation.desc) || annotation.values == null) continue;
				for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
					if (!"method".equals(annotation.values.get(i))) continue;
					Object value = annotation.values.get(i + 1);
					if (value instanceof String selector) return List.of(selector);
					if (value instanceof List<?> list) {
						List<String> out = new ArrayList<>();
						for (Object element : list) if (element instanceof String selector) out.add(selector);
						return out;
					}
				}
			}
		}
		throw new AssertionError("no injector selectors on " + handler);
	}

	private static ClassNode classFromJar(Path jar, String entry) throws Exception {
		byte[] bytes = readEntry(jar, entry);
		assertNotNull(bytes, entry + " absent from " + jar);
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}

	private static byte[] readEntry(Path jar, String entry) throws Exception {
		if (!Files.isRegularFile(jar)) return null;
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			if (found == null) return null;
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}
}
