package net.forbric.kernel.transform;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * FINAL, post-Mixin certificates for the reviewed Forge implementations and their transfer-critical helpers.
 * ItemStack is checked by reachable critical methods, NOT whole-class identity: unrelated tooltip/use mixins may
 * remain. The selected method set follows same-class calls and lambda handles from the actual 1.21.1 bytecode.
 * Fingerprints include executable instructions, member descriptors/access and referenced fields; debug/frame
 * metadata is excluded. Unknown changes remove the marker and deny writes until explicitly reviewed.
 *
 * <p>Call certify after every other class transformation, immediately before defineClass. Native registry lookups,
 * immutable component-value contracts and JDK/fastutil collections are read-only contract boundaries. The lazy
 * ItemStack capability constructor is pinned with its provider classes: it parks the supplier rather than firing
 * attachment callbacks. Custom Item overrides of the capacity/component helpers are separately declined at use.
 */
public final class ForgeTransferShapeAudit {
	private ForgeTransferShapeAudit() { }
	public static final String MARKER = "forbric$auditedTransferSnapshot";
	public static final Set<String> NON_TRANSFER_FABRIC_INTERFACES = Set.of(
			"net/fabricmc/fabric/api/item/v1/FabricItemStack", "net/fabricmc/fabric/api/item/v1/FabricItem",
			"net/fabricmc/fabric/mixin/transfer/ItemStackAccessor", "net/fabricmc/fabric/impl/item/ItemExtensions",
			"net/fabricmc/fabric/impl/transfer/item/ItemVariantCache");
	private static final Map<String, Set<String>> ROOTS = Map.of(
			"net.minecraft.world.item.ItemStack", Set.of("<init>", "isEmpty", "getCount", "getMaxStackSize", "grow", "shrink", "copy", "copyWithCount", "setCount", "getComponentsPatch", "isSameItemSameComponents", "getPopTime", "setPopTime", "typeHolder", "getItem", "getComponents", "getPrototype", "getOrDefault", "get"),
			"net.minecraft.world.item.Item", Set.of("builtInRegistryHolder", "components", "getDefaultMaxStackSize", "computeDefaultResource"),
			"net.minecraftforge.items.ItemHandlerHelper", Set.of("canItemStacksStack", "copyStackWithSize"),
			"net.neoforged.neoforge.common.extensions.IItemExtension", Set.of("getMaxStackSize"));
	/**
	 * PORT(1.21.1): re-derived against the staged 1.21.1 carriers and merged base.
	 *
	 * <p>26.2's {@code neoforge.transfer.*} resource classes are gone, as is Forge's
	 * {@code CapabilityProvider$ItemStacks}; 21.1 NeoForge's standard item/fluid/energy stores are now covered (the
	 * runtime bridge authorises them too). {@code DataComponentGetter} does not exist on this generation. The hashes
	 * are {@link #fingerprint} over the exact staged bytes, computed with the same ROOTS below.
	 */
	private static final Map<String, String> AUDITED = Map.ofEntries(
			Map.entry("net.minecraftforge.items.ItemStackHandler", "572fb811850e7abd3a96b21253965664924e995f5361cc0d57c2aab2846a9185"),
			Map.entry("net.minecraftforge.items.ItemHandlerHelper", "b8bfaa89d80d5f950c708cf1251adf2f9fc345329c1feec2e20bd799424bad05"),
			Map.entry("net.minecraftforge.fluids.capability.templates.FluidTank", "f7ad8934eb46fc4ae9b94770ed51ae6d5b5c20dbcaf50ef03e854ecf1dc46d6e"),
			// Forge's standard energy store: the whole class, since its int energy field IS its whole transferable state.
			Map.entry("net.minecraftforge.energy.EnergyStorage", "21b61e64ea5bdbe6a80e759f2c25fae2db8d4e445145d3c9303231801adc934b"),
			Map.entry("net.minecraftforge.fluids.FluidStack", "59300c2200e3209e86bf239d7adbf66fb72f55c12c13b3920e17adb578136536"),
			Map.entry("net.minecraftforge.common.capabilities.CapabilityProvider", "7ec42620f337441648477605b9e57ac10450e5e882803fc3645972fc7a64bbfd"),
			Map.entry("net.minecraft.world.item.ItemStack", "d6c70b6f64d24bc8c3e36ea33e521411d7872ea53abf834cfee780a0313127d2"),
			Map.entry("net.minecraft.world.item.Item", "54eb06f83dfd3d08352e812f19e8f8d5027fc210c2d10472554c7e9048dd5d02"),
			Map.entry("net.minecraft.core.NonNullList", "aea8029c0db368c8dfb2a00da5f1936c85d0b671c6a51f60ec5081a1d9c1f3b7"),
			Map.entry("net.minecraft.core.component.PatchedDataComponentMap", "9696917b36c8e3ab8881a54f606a40f996020b14110e830a07b0fb8d60e7e03a"),
			Map.entry("net.minecraft.core.component.DataComponentPatch", "285c25d423027156de49a043db75ffc1d5ef82ae51f547b0d988adc7291ea861"),
			Map.entry("net.minecraft.core.component.DataComponentHolder", "271188b14a5d74c0da85d7e855510b3654b1db9aa847f16fda7d46f08d73bdd1"),
			Map.entry("net.minecraft.nbt.CompoundTag", "1ca987508bde52b9bf2cb257c2d2cad10e78f590eaf2c74f0bfba8b814d30290"),
			Map.entry("net.neoforged.neoforge.common.MutableDataComponentHolder", "ad3f750687a0e032cc59a1ab4b18a832cc76788e6348afd547992d9a0804a6d6"),
			Map.entry("net.neoforged.neoforge.common.extensions.IItemExtension", "c31798eaac8e69c11eda038886bbc2ce3044f177403fa139b9d6ffb7b7e2a8f6"),
			Map.entry("net.neoforged.neoforge.fluids.FluidStack", "6180f6ebf22a2aad898b084c41f47de33d74d50a479dd0cb60794a0c6d9fd6d7"),
			// PORT(1.21.1): the NeoForge standard stores the runtime bridge also authorises.
			Map.entry("net.neoforged.neoforge.items.ItemStackHandler", "44ad28a68bf45e9ddf84e32bc1bc3198f072c343a164846600d7e77dec178594"),
			Map.entry("net.neoforged.neoforge.fluids.capability.templates.FluidTank", "0512b5607a50cca242b54f0a1344946856ed78850c7af213a9c93bab3614ad44"),
			Map.entry("net.neoforged.neoforge.energy.EnergyStorage", "f069e198aef44f2cc754588474fdb988b4fd182573c1fac7e93bcec91fe283a2"));
	public static final List<String> ITEM_HELPERS = List.of("net.minecraftforge.items.ItemHandlerHelper", "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.Item", "net.minecraft.core.NonNullList", "net.minecraft.core.component.PatchedDataComponentMap", "net.minecraft.core.component.DataComponentPatch", "net.minecraft.core.component.DataComponentHolder", "net.minecraftforge.common.capabilities.CapabilityProvider", "net.neoforged.neoforge.common.MutableDataComponentHolder", "net.neoforged.neoforge.common.extensions.IItemExtension");
	public static final List<String> FLUID_HELPERS = List.of("net.minecraftforge.fluids.FluidStack", "net.minecraft.nbt.CompoundTag", "net.minecraft.core.component.DataComponentPatch", "net.minecraft.core.component.DataComponentHolder", "net.neoforged.neoforge.fluids.FluidStack");
	/** ForgeEnergyAdapters writes only through this class's own code and restores only its energy field. */
	public static final List<String> ENERGY_HELPERS = List.of("net.minecraftforge.energy.EnergyStorage");
	private static final Map<String, String> DECLINED = new ConcurrentHashMap<>();
	public static String declined(String name) { return DECLINED.getOrDefault(name, "the final definition did not receive a transfer-shape certificate"); }

	public static byte[] certify(String name, byte[] bytes) {
		String expected = AUDITED.get(name); if (expected == null || bytes == null) return bytes;
		String actual = fingerprint(bytes); boolean approved = expected.equals(actual);
		dump(name, bytes, expected, actual);
		if (approved) DECLINED.remove(name); else DECLINED.put(name, "transfer-critical bytecode differs from the reviewed 1.21.1 shape (" + actual + ")");
		ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
		boolean hadMarker = node.methods.removeIf(method -> method.name.equals(MARKER));
		if (!approved && !hadMarker) return bytes;
		if (approved) {
			MethodNode marker = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, MARKER, "()V", null, null);
			marker.visitCode(); marker.visitInsn(Opcodes.RETURN); marker.visitMaxs(0, 0); marker.visitEnd(); node.methods.add(marker);
		}
		ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
	}
	/** Explicit local verification artifact only; disabled by default and never included in a kernel jar. */
	private static void dump(String name, byte[] bytes, String expected, String actual) {
		String directory = System.getProperty("forbric.transferShapeDump");
		if (directory == null || directory.isBlank()) return;
		try {
			Path output = Path.of(directory).resolve(name.replace('.', '/') + ".class");
			Files.createDirectories(output.getParent()); Files.write(output, bytes);
			Files.writeString(output.resolveSibling(output.getFileName() + ".audit.txt"),
					"reviewed=" + expected + "\nobserved=" + actual + "\n");
		} catch (IOException | RuntimeException failure) {
			net.forbric.kernel.util.ForbricLog.warn("[Forbric/TransferAudit] could not dump %s: %s", name, failure.toString());
		}
	}
	static String fingerprint(byte[] bytes) {
		ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		node.methods.removeIf(method -> method.name.equals(MARKER));
		canonicalizeErasedClassSignature(node);
		normalizeKnownNonTransferRepair(node);
		Set<String> roots = ROOTS.get(node.name.replace('/', '.'));
		if (roots != null) {
			boolean cachedVariantRead = readsOnlyCachedVariant(node);
			Set<String> selected = selected(node, roots); node.methods.removeIf(method -> !selected.contains(method.name + method.desc));
			Set<String> fields = new HashSet<>();
			for (var method : node.methods) for (var instruction : method.instructions)
				if (instruction instanceof FieldInsnNode field && field.owner.equals(node.name)) fields.add(field.name + field.desc);
			node.fields.removeIf(field -> !fields.contains(field.name + field.desc));
			node.methods.sort(Comparator.comparing(method -> method.name + method.desc));
			node.fields.sort(Comparator.comparing(field -> field.name + field.desc));
			node.visibleAnnotations = null; node.invisibleAnnotations = null; node.innerClasses.clear();
			if (node.nestMembers != null) node.nestMembers.clear();
			// These fixture interfaces do not override the audited state operations. Check the cache getter
			// BEFORE pruning methods/fields, and keep its interface in the hash if its read-only body drifted.
			node.interfaces.removeIf(contract -> NON_TRANSFER_FABRIC_INTERFACES.contains(contract)
					&& (!contract.equals("net/fabricmc/fabric/impl/transfer/item/ItemVariantCache") || cachedVariantRead));
		}
		ClassWriter writer = new ClassWriter(0); node.accept(writer);
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(writer.toByteArray())); }
		catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
	}
	/**
	 * The fixture adds a cache interface to Item, not to ItemStack. Its sole method reads an already-initialized
	 * immutable variant. Neither Item construction (equipment/damage hooks) nor a cache getter is an override of
	 * components/stack limits. Require the exact field read, so adding this interface cannot conceal a callback,
	 * lazy mutation, or replacement of one of the critical operations behind an apparently harmless interface.
	 */
	private static boolean readsOnlyCachedVariant(ClassNode node) {
		if (!node.name.equals("net/minecraft/world/item/Item")) return false;
		String descriptor = "Lnet/fabricmc/fabric/api/transfer/v1/item/ItemVariant;";
		MethodNode getter = node.methods.stream().filter(method -> method.name.equals("fabric_getCachedItemVariant")
				&& method.desc.equals("()" + descriptor)).findFirst().orElse(null);
		if (getter == null || (getter.access & Opcodes.ACC_PUBLIC) == 0 || !getter.tryCatchBlocks.isEmpty()
				|| (getter.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNCHRONIZED)) != 0) return false;
		if (node.fields.stream().noneMatch(field -> field.name.equals("cachedItemVariant") && field.desc.equals(descriptor)
				&& (field.access & Opcodes.ACC_STATIC) == 0)) return false;
		var code = java.util.Arrays.stream(getter.instructions.toArray()).filter(instruction -> instruction.getOpcode() >= 0).toList();
		return code.size() == 3 && code.get(0) instanceof VarInsnNode self && self.getOpcode() == Opcodes.ALOAD && self.var == 0
				&& code.get(1) instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD && field.owner.equals(node.name)
				&& field.name.equals("cachedItemVariant") && field.desc.equals(descriptor) && code.get(2).getOpcode() == Opcodes.ARETURN;
	}
	/**
	 * Mixin materializes a Signature even on a non-generic class: Lsuper;Liface;... . It duplicates the erased
	 * superclass/interface table, which we already inspect (and whose added known interfaces are checked again
	 * against their actual runtime Class). This does not erase type variables, generic arguments or a different
	 * hierarchy. In the real FAPI fixture this was the ONLY difference in ItemStack's complete reviewed method set.
	 */
	private static void canonicalizeErasedClassSignature(ClassNode node) {
		if (node.signature == null || node.superName == null) return;
		StringBuilder erased = new StringBuilder("L").append(node.superName).append(';');
		for (String contract : node.interfaces) erased.append('L').append(contract).append(';');
		if (node.signature.contentEquals(erased)) node.signature = null;
	}
	/**
	 * The kernel adds exactly this missing convenience factory. The reviewed CompoundTag has no builder method,
	 * so none of its unchanged copy/equality/mutation methods can call it. Strip only the exact four-instruction
	 * repair; a changed copy method, constructor, factory body, or any other added method remains in the digest.
	 */
	private static void normalizeKnownNonTransferRepair(ClassNode node) {
		if (!node.name.equals("net/minecraft/nbt/CompoundTag")) return;
		node.methods.removeIf(method -> {
			String builder = "net/minecraftforge/common/util/INBTBuilder$Builder";
			if (!method.name.equals("builder") || !method.desc.equals("()L" + builder + ";")
					|| method.access != (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC) || !method.tryCatchBlocks.isEmpty()) return false;
			var code = java.util.Arrays.stream(method.instructions.toArray()).filter(instruction -> instruction.getOpcode() >= 0).toList();
			return code.size() == 4 && code.get(0) instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW && type.desc.equals(builder)
					&& code.get(1).getOpcode() == Opcodes.DUP
					&& code.get(2) instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL && call.owner.equals(builder)
					&& call.name.equals("<init>") && call.desc.equals("()V") && !call.itf && code.get(3).getOpcode() == Opcodes.ARETURN;
		});
	}
	private static Set<String> selected(ClassNode node, Set<String> roots) {
		Set<String> selected = new HashSet<>();
		for (var method : node.methods) if (roots.contains(method.name)) selected.add(method.name + method.desc);
		boolean changed;
		do {
			changed = false;
			for (var method : node.methods) if (selected.contains(method.name + method.desc)) for (var instruction : method.instructions) {
				if (instruction instanceof MethodInsnNode call && call.owner.equals(node.name)) changed |= selected.add(call.name + call.desc);
				if (instruction instanceof InvokeDynamicInsnNode dynamic) for (Object argument : dynamic.bsmArgs)
					if (argument instanceof Handle handle && handle.getOwner().equals(node.name)) changed |= selected.add(handle.getName() + handle.getDesc());
			}
		} while (changed);
		return selected;
	}
}
