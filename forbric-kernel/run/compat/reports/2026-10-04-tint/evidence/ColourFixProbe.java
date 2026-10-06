import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Throwaway probe: drives the real compiled repair over the real merged-base + guest bytes. */
public class ColourFixProbe {
	static final String MERGED = "/Applications/.minecraft/.forbric-build/out/patched-mc-merged-1.21.1.jar";
	static final String RENDERING = "/Applications/.minecraft/versions/1.21.1-forbric/.forbric-kernel/remap/"
			+ "fabric-rendering-v1-0.116.17-264af485988b52bd.jar";

	static int failures = 0;

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok   " : "  FAIL ") + what);
		if (!ok) failures++;
	}

	static byte[] entry(String jar, String name) throws IOException {
		try (ZipFile zf = new ZipFile(jar)) {
			ZipEntry e = zf.getEntry(name);
			if (e == null) throw new FileNotFoundException(name + " in " + jar);
			try (InputStream in = zf.getInputStream(e)) { return in.readAllBytes(); }
		}
	}

	static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	static String render(MethodNode m) {
		StringBuilder sb = new StringBuilder();
		for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
			if (i instanceof MethodInsnNode c) sb.append("  invoke ").append(c.owner).append('.').append(c.name).append(c.desc).append('\n');
			else if (i instanceof FieldInsnNode f) sb.append("  field  ").append(f.owner).append('.').append(f.name).append(':').append(f.desc).append(" op=").append(f.getOpcode()).append('\n');
			else if (i instanceof TypeInsnNode t) sb.append("  cast   ").append(t.desc).append('\n');
			else if (i.getOpcode() >= 0) sb.append("  op     ").append(i.getOpcode()).append(" (").append(i.getClass().getSimpleName()).append(")\n");
		}
		return sb.toString();
	}

	static int countDelegate(ClassNode node) {
		int n = 0;
		for (MethodNode m : node.methods)
			for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext())
				if (i instanceof MethodInsnNode c && "getDelegateOrThrow".equals(c.name)) n++;
		return n;
	}

	static MethodNode method(ClassNode n, String name, String desc) {
		for (MethodNode m : n.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
		return null;
	}

	static String frames(MethodNode m) {
		StringBuilder sb = new StringBuilder();
		for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext())
			if (i instanceof FrameNode f) sb.append(f.type).append(f.local).append(f.stack).append(';');
		return sb.toString();
	}

	static boolean invoke(Class<?> t, ClassNode node) throws Exception {
		Method m = t.getDeclaredMethod("restoreTheRawColourKeysAndRebindTheColourMixins", ClassNode.class);
		m.setAccessible(true);
		return (Boolean) m.invoke(null, node);
	}

	static byte[] write(ClassNode node) {
		ClassWriter cw = new ClassWriter(0);
		node.accept(cw);
		return cw.toByteArray();
	}

	public static void main(String[] args) throws Exception {
		Class<?> t = Class.forName("net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer");

		// ---------------- step 2: the merged base ----------------
		System.out.println("== step 2: merged base raw keys ==");
		byte[] blockBytes = entry(MERGED, "net/minecraft/client/color/block/BlockColors.class");
		byte[] itemBytes = entry(MERGED, "net/minecraft/client/color/item/ItemColors.class");

		ClassNode before = parse(blockBytes);
		MethodNode threeArgBefore = method(before, "getColor",
				"(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)I");
		MethodNode fourArgBefore = method(before, "getColor",
				"(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;I)I");
		String framesThreeBefore = frames(threeArgBefore), framesFourBefore = frames(fourArgBefore);
		check(countDelegate(before) == 2, "merged BlockColors starts with 2 Forge delegate lookups (was " + countDelegate(before) + ")");

		ClassNode block = parse(blockBytes);
		check(invoke(t, block), "repair reports a change on BlockColors");
		check(countDelegate(block) == 0, "BlockColors has 0 Forge delegate lookups after (was " + countDelegate(block) + ")");
		MethodNode threeArg = method(block, "getColor",
				"(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)I");
		System.out.println("--- BlockColors.getColor(BlockState,Level,BlockPos) ---");
		System.out.print(render(threeArg));
		check(render(threeArg).contains("invoke net/minecraft/client/color/block/BlockColors") == false
						&& render(threeArg).contains("java/util/Map.get"), "3-arg getColor now keys the map with the raw block");
		check(frames(threeArg).equals(framesThreeBefore), "3-arg StackMapTable frames unchanged");
		MethodNode fourArg = method(block, "getColor",
				"(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;I)I");
		check(frames(fourArg).equals(framesFourBefore), "4-arg StackMapTable frames unchanged");
		ClassNode item = parse(itemBytes);
		MethodNode itemBefore = method(item, "getColor", "(Lnet/minecraft/world/item/ItemStack;I)I");
		String framesItemBefore = frames(itemBefore);
		check(invoke(t, item), "repair reports a change on ItemColors");
		MethodNode itemGet = method(item, "getColor", "(Lnet/minecraft/world/item/ItemStack;I)I");
		System.out.println("--- ItemColors.getColor(ItemStack,int) ---");
		System.out.print(render(itemGet));
		check(frames(itemGet).equals(framesItemBefore), "ItemColors frames unchanged");
		check(countDelegate(item) == 0, "ItemColors has 0 Forge delegate lookups after");
		check(write(block) != null && write(item) != null, "both classes re-serialize");
		// idempotency
		check(!invoke(t, block) && !invoke(t, item), "second pass is a no-op (idempotent)");

		// negative control: an unexpected shape must stand the class down
		ClassNode mutated = parse(blockBytes);
		MethodNode m = method(mutated, "getColor",
				"(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)I");
		for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext())
			if (i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && f.owner.contains("ForgeRegistries")) f.owner = "net/example/NotAForgeRegistry";
		check(!invoke(t, mutated), "NEGATIVE CONTROL: a non-Forge getstatic makes the repair decline");
		check(countDelegate(mutated) == 2, "NEGATIVE CONTROL: the declined class is left untouched");

		// ---------------- step 1: the guest mixins ----------------
		System.out.println("== step 1: fabric-rendering-v1 guest mixins ==");
		ClassNode blockMixin = parse(entry(RENDERING, "net/fabricmc/fabric/mixin/client/rendering/BlockColorsMixin.class"));
		FieldNode bf = blockMixin.fields.get(0);
		check("Lnet/minecraft/core/IdMapper;".equals(bf.desc), "guest BlockColorsMixin starts with an IdMapper shadow (" + bf.desc + ")");
		check(invoke(t, blockMixin), "repair reports a change on BlockColorsMixin");
		check("Ljava/util/Map;".equals(bf.desc), "BlockColorsMixin shadow is now java.util.Map (" + bf.desc + ")");
		MethodNode bget = method(blockMixin, "get", "(Lnet/minecraft/world/level/block/Block;)Lnet/minecraft/client/color/block/BlockColor;");
		System.out.println("--- BlockColorsMixin.get(Block) ---");
		System.out.print(render(bget));
		check(render(bget).contains("java/util/Map.get") && !render(bget).contains("IdMapper") && !render(bget).contains("BuiltInRegistries"),
				"BlockColorsMixin.get reads the live map by raw block");
		check(write(blockMixin) != null, "BlockColorsMixin re-serializes");

		ClassNode itemMixin = parse(entry(RENDERING, "net/fabricmc/fabric/mixin/client/rendering/ItemColorsMixin.class"));
		FieldNode itf = itemMixin.fields.get(0);
		check(invoke(t, itemMixin), "repair reports a change on ItemColorsMixin");
		check("Ljava/util/Map;".equals(itf.desc), "ItemColorsMixin shadow is now java/util/Map (" + itf.desc + ")");
		MethodNode iget = method(itemMixin, "get", "(Lnet/minecraft/world/level/ItemLike;)Lnet/minecraft/client/color/item/ItemColor;");
		System.out.println("--- ItemColorsMixin.get(ItemLike) ---");
		System.out.print(render(iget));
		check(render(iget).contains("java/util/Map.get") && render(iget).contains("ItemLike.asItem") && !render(iget).contains("IdMapper"),
				"ItemColorsMixin.get reads the live map by item.asItem()");
		check(!invoke(t, itemMixin), "second pass on the mixin is a no-op");

		// the shadow descriptors now match the live target fields exactly (what Mixin binds on)
		check(hasField(parse(blockBytes), "blockColors", "Ljava/util/Map;"), "merged BlockColors declares Map blockColors (mixin binds)");
		check(hasField(parse(itemBytes), "itemColors", "Ljava/util/Map;"), "merged ItemColors declares Map itemColors (mixin binds)");

		// ---------------- structural verification of the rewritten bytes ----------------
		System.out.println("== verifier ==");
		verify("net/minecraft/client/color/block/BlockColors", write(block));
		verify("net/minecraft/client/color/item/ItemColors", write(item));
		verify("net/fabricmc/fabric/mixin/client/rendering/BlockColorsMixin", write(blockMixin));
		verify("net/fabricmc/fabric/mixin/client/rendering/ItemColorsMixin", write(itemMixin));

		System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
		if (failures != 0) System.exit(1);
	}

	static boolean hasField(ClassNode n, String name, String desc) {
		for (FieldNode f : n.fields) if (f.name.equals(name) && f.desc.equals(desc)) return true;
		return false;
	}

	static void verify(String name, byte[] bytes) {
		try {
			StringWriter sw = new StringWriter();
			CheckClassAdapter.verify(new ClassReader(bytes), ColourFixProbe.class.getClassLoader(), false, new PrintWriter(sw));
			String report = sw.toString();
			boolean clean = report.isBlank() || !report.contains("Exception") && !report.contains("Error");
			check(clean, name + " passes CheckClassAdapter.verify" + (clean ? "" : " -> " + report.replace('\n', ' ')));
		} catch (Throwable e) {
			System.out.println("  note  " + name + " verify raised " + e.getClass().getSimpleName() + ": " + e.getMessage());
		}
	}
}
