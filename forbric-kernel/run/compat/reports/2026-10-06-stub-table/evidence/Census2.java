package net.forbric.kernel.mixin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Derives carrier-stubs.txt against the staged 1.21.1 artifacts with the SHIPPED {@link MixinStubRebind#delegation}
 * (i.e. exactly the rule the kernel runs), and can write the table back. Modes:
 *   derive                     print the derived rows
 *   check                      print per-shipped-row verdicts + the diff against the derived set
 *   write32                    rewrite the shipped table: add the derived rows that carry a body column and are
 *                              absent, drop the three 26.2-descriptor rows they replace
 *   writeall                   rewrite the shipped table as exactly the derived set
 */
public final class Census2 {
    public static void main(String[] args) throws Exception {
        Path vanillaJar = Path.of(args[0]);
        Path mergedJar = Path.of(args[1]);
        Path forgeJar = Path.of(args[2]);
        Path neoJar = Path.of(args[3]);
        Path table = Path.of(args[4]);
        String mode = args.length > 5 ? args[5] : "derive";

        Map<String, ClassNode> vanilla = read(vanillaJar, false);
        TreeSet<String> rows = new TreeSet<>();
        try (ZipFile zip = new ZipFile(mergedJar.toFile());
             ZipFile forge = new ZipFile(forgeJar.toFile());
             ZipFile neo = new ZipFile(neoJar.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (!entry.getName().endsWith(".class") || !entry.getName().startsWith("net/minecraft/")) continue;
                ClassNode merged = readOne(zip, entry.getName());
                ClassNode original = vanilla.get(merged.name);
                if (original == null) continue;
                for (MethodNode stub : merged.methods) {
                    if ((stub.access & (Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)) != 0) continue;
                    MixinStubRebind.Delegation delegation = MixinStubRebind.delegation(merged, stub);
                    if (delegation == null) continue;
                    MethodNode delegate = delegation.delegate();
                    if (!declares(original, stub.name, stub.desc)) continue;
                    if (declares(original, delegate.name, delegate.desc)) continue;
                    rows.add(merged.name + "#" + stub.name + stub.desc + " -> " + delegate.desc
                            + " forge=" + MixinStubRebind.Shape.of(entry(forge, merged.name), stub.name, stub.desc, delegate.desc).token
                            + " neo=" + MixinStubRebind.Shape.of(entry(neo, merged.name), stub.name, stub.desc, delegate.desc).token);
                    if (delegate.name.equals(stub.name)) continue;
                    System.out.println("RENAMED " + merged.name + "#" + stub.name + stub.desc + " -> " + delegate.name + delegate.desc
                            + " (stub access " + stub.access + ", delegate " + delegate.access + ")");
                }
            }
        }

        List<String> shipped = Files.readAllLines(table).stream()
                .map(String::trim).filter(l -> !l.isBlank() && !l.startsWith("#")).toList();

        if (mode.equals("derive")) {
            System.out.println("DERIVED_ROWS " + rows.size());
            for (String r : rows) System.out.println("D " + r);
            return;
        }

        Map<String, String> derivedByKey = new HashMap<>();
        for (String r : rows) derivedByKey.put(key(r), r);
        Map<String, String> shippedByKey = new HashMap<>();
        for (String s : shipped) shippedByKey.put(key(s), s);

        if (mode.equals("check")) {
            System.out.println("DERIVED_ROWS " + rows.size() + " SHIPPED_ROWS " + shipped.size()
                    + " BOTH " + rows.stream().filter(r -> shippedByKey.containsKey(key(r))).count());
            for (String s : shipped) {
                String d = derivedByKey.get(key(s));
                if (d == null) System.out.println("STALE_OR_EXTRA\t" + s);
                else if (!d.equals(s)) System.out.println("COLUMNS_DIFFER\n  derived: " + d + "\n  shipped: " + s);
            }
            for (String r : rows) if (!shippedByKey.containsKey(key(r))) System.out.println("ABSENT" + (moves(r) ? "_BODY" : "_STUB") + "\t" + r);
            return;
        }

        // The three rows whose descriptor spells a 26.2 class (LevelLoadingScreen$Reason, Identifier, LivingEntity)
        // and the 1.21.1 row that takes each one's place.
        String[][] replaced = replacedOn1211();
        TreeSet<String> out = new TreeSet<>();
        for (String s : shipped) {
            boolean drop = false;
            for (String[] pair : replaced) if (s.startsWith(pair[0])) drop = true;
            if (!drop) out.add(s);
        }
        if (mode.equals("writeall")) out = new TreeSet<>(rows);
        else for (String r : rows) {
            boolean replacement = false;
            for (String[] pair : replaced) if (key(r).equals(pair[1])) replacement = true;
            if ((replacement || moves(r)) && !shippedByKey.containsKey(key(r))) out.add(r);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# Generated by CarrierStubCensusTest (FORBRIC_WRITE_CARRIER_STUBS=1): merged-base delegating stubs to a\n");
        sb.append("# same-name overload, or a renamed method, the carrier added. MixinStubRebind moves an injector only along\n");
        sb.append("# these rows: a Fabric mod's on every row; a MinecraftForge (forge=) or NeoForge (neo=) mod's only where its\n");
        sb.append("# own carrier's patched class ran that selector on code - body (vanilla's signature is the body there, first\n");
        sb.append("# of its name), descriptor-body (a body, declared after another overload: a descriptor selector only),\n");
        sb.append("# overload-body (only the widened overload is there: a name-only selector only). stub (the carrier keeps the\n");
        sb.append("# same stub) and absent never move.\n");
        for (String r : out) sb.append(r).append('\n');
        Files.writeString(table, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("WROTE " + out.size() + " ROWS");
    }

    /** Whether the row's own carrier ran that selector on code: any body/descriptor-body/overload-body column. */
    private static boolean moves(String row) {
        return row.contains("forge=body") || row.contains("forge=descriptor-body") || row.contains("forge=overload-body")
                || row.contains("neo=body") || row.contains("neo=descriptor-body") || row.contains("neo=overload-body");
    }

    /** Pair of {the 26.2 row's prefix to drop, the 1.21.1 row's owner#name+descriptor key that takes its place}. */
    private static String[][] replacedOn1211() {
        return new String[][] {
            {"net/minecraft/client/multiplayer/ClientPacketListener#startWaitingForNewLevel(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/gui/screens/LevelLoadingScreen$Reason;)V",
             "net/minecraft/client/multiplayer/ClientPacketListener#startWaitingForNewLevel(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/gui/screens/ReceivingLevelScreen$Reason;)V"},
            {"net/minecraft/world/flag/FeatureFlagRegistry$Builder#create(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/world/flag/FeatureFlag;",
             "net/minecraft/world/flag/FeatureFlagRegistry$Builder#create(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/world/flag/FeatureFlag;"},
            {"net/minecraft/world/item/BucketItem#emptyContents(Lnet/minecraft/world/entity/LivingEntity;",
             "net/minecraft/world/item/BucketItem#emptyContents(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/BlockHitResult;)Z"},
        };
    }

    private static String key(String row) {
        int arrow = row.indexOf(" -> ");
        return arrow < 0 ? row : row.substring(0, arrow);
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        if (node == null || node.methods == null) return false;
        for (MethodNode m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return true;
        return false;
    }

    private static ClassNode entry(ZipFile jar, String name) { return readOne(jar, name + ".class"); }

    private static ClassNode readOne(ZipFile jar, String name) {
        try {
            ZipEntry e = jar.getEntry(name);
            if (e == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(jar.getInputStream(e).readAllBytes()).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        } catch (Exception failed) {
            return null;
        }
    }

    private static Map<String, ClassNode> read(Path jar, boolean skipCode) throws Exception {
        Map<String, ClassNode> out = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry e : Collections.list(zip.entries())) {
                if (!e.getName().endsWith(".class") || !e.getName().startsWith("net/minecraft/")) continue;
                ClassNode node = new ClassNode();
                new ClassReader(zip.getInputStream(e).readAllBytes()).accept(node,
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES | (skipCode ? ClassReader.SKIP_CODE : 0));
                out.put(node.name, node);
            }
        }
        return out;
    }

    private Census2() { }
}
