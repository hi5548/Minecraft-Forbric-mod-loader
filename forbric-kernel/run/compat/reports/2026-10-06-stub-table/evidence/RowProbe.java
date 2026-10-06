package net.forbric.kernel.mixin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Per-row offline probe: for every line of carrier-stubs.txt, re-reads the staged 1.21.1 artifacts and asserts the row
 * is exactly what they say — the merged base declares the stub and it is a pure delegation (by the SHIPPED
 * {@link MixinStubRebind#delegation}) to the row's delegate descriptor, vanilla declares the stub but not the
 * delegate, and each carrier column equals {@link MixinStubRebind.Shape#of} on that carrier's own patched class.
 */
public final class RowProbe {
    public static void main(String[] args) throws Exception {
        Path vanillaJar = Path.of(args[0]);
        Path mergedJar = Path.of(args[1]);
        Path forgeJar = Path.of(args[2]);
        Path neoJar = Path.of(args[3]);
        Path table = Path.of(args[4]);

        Map<String, ClassNode> vanilla = read(vanillaJar);
        int pass = 0, fail = 0, inert = 0;
        try (ZipFile zip = new ZipFile(mergedJar.toFile());
             ZipFile forge = new ZipFile(forgeJar.toFile());
             ZipFile neo = new ZipFile(neoJar.toFile())) {
            for (String line : Files.readAllLines(table)) {
                String row = line.trim();
                if (row.isEmpty() || row.startsWith("#")) continue;
                String why = "";
                String body = row.replaceAll(" (forge|neo)=\\S+", "").trim();
                int arrow = body.indexOf(" -> ");
                String head = body.substring(0, arrow);
                String owner = head.substring(0, head.indexOf('#'));
                String rest = head.substring(head.indexOf('#') + 1);
                String stubName = rest.substring(0, rest.indexOf('('));
                String stubDesc = rest.substring(rest.indexOf('('));
                String delegateDesc = body.substring(arrow + 4);
                String forgeToken = token(row, "forge=");
                String neoToken = token(row, "neo=");

                ClassNode merged = readOne(zip, owner + ".class");
                ClassNode original = vanilla.get(owner);
                if (merged == null) { why = "merged class absent"; }
                else if (original == null) { why = "class not in client-official (not a Minecraft class here)"; }
                else {
                    MethodNode stub = find(merged, stubName, stubDesc);
                    if (stub == null) why = "merged does not declare the stub signature";
                    else if (!declares(original, stubName, stubDesc)) why = "vanilla does not declare the stub signature";
                    else {
                        MixinStubRebind.Delegation delegation = MixinStubRebind.delegation(merged, stub);
                        if (delegation == null) why = "the merged method is not a pure delegation";
                        else if (!delegation.delegate().desc.equals(delegateDesc)) why = "delegation describes " + delegation.delegate().name + delegation.delegate().desc;
                        else if (declares(original, delegation.delegate().name, delegateDesc)) why = "vanilla declares the delegate too";
                        else {
                            String f = MixinStubRebind.Shape.of(entry(forge, owner), stubName, stubDesc, delegateDesc).token;
                            String n = MixinStubRebind.Shape.of(entry(neo, owner), stubName, stubDesc, delegateDesc).token;
                            if (!f.equals(forgeToken)) why = "forge column reads " + forgeToken + ", the jar says " + f;
                            else if (!n.equals(neoToken)) why = "neo column reads " + neoToken + ", the jar says " + n;
                        }
                    }
                }
                if (why.isEmpty()) { pass++; System.out.println("PASS\t" + row); }
                else { fail++; System.out.println("FAIL\t" + why + "\t" + row); }
            }
        }
        System.out.println("PROBE rows=" + (pass + fail) + " pass=" + pass + " fail=" + fail);
    }

    private static String token(String row, String prefix) {
        for (String part : row.split(" ")) if (part.startsWith(prefix)) return part.substring(prefix.length());
        return "-";
    }

    private static MethodNode find(ClassNode cn, String name, String desc) {
        for (MethodNode m : cn.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
        return null;
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

    private static Map<String, ClassNode> read(Path jar) throws Exception {
        Map<String, ClassNode> out = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry e : Collections.list(zip.entries())) {
                if (!e.getName().endsWith(".class") || !e.getName().startsWith("net/minecraft/")) continue;
                out.put(e.getName().substring(0, e.getName().length() - 6), readOne(zip, e.getName()));
            }
        }
        return out;
    }

    private RowProbe() { }
}
