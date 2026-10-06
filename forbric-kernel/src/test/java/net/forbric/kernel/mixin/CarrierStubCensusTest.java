package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.kernel.TestFixtures;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Re-derives {@code carrier-stubs.txt} against the base this checkout ships — every merged-base method that is a pure
 * delegating stub to an overload, or a RENAMED method, the CARRIER added (vanilla has the stub's signature and not the
 * delegate's) — and asserts the shipped table equals it, so the table cannot silently drift to another generation.
 * MixinStubRebind moves an injector only along a row here: where vanilla has both overloads itself, a mod that chose
 * the short one meant it. Each row also says, per Forge family, what that carrier's OWN patched class has at the stub's
 * signature ({@link MixinStubRebind.Shape}) — whether a mod of that family was compiled against code there or against
 * the same stub.
 *
 * <p>The paths are the VERSION-AWARE ones every other staged test uses ({@code forbric.mcVersion}, the merge outputs
 * beside {@code -Pforbric.mcLibraries}), not 26.2 constants: the previous revision hardcoded
 * {@code ../forbric-loader/run/merged-base/patched-mc-merged-26.2.jar} and skipped when it was absent, so on the
 * 1.21.1 port the assertion below never ran and the table stayed the 26.2 census.
 */
class CarrierStubCensusTest {
	/** The Minecraft version this checkout is built for; {@code build.gradle} keys every staged path on it. */
	private static final String VERSION = System.getProperty("forbric.mcVersion", "1.21.1");
	private static final Path MC = TestFixtures.minecraftDir();
	/** The merged base the kernel boots: the staged one when the build hands it over, else the merge's own output. */
	private static final Path MERGED = staged("merged-base", "patched-mc-merged-", mergeOutput("patched-mc-merged"));
	/** The carrier's OWN patched game jars: what a MinecraftForge / NeoForge mod was compiled against. */
	private static final Path FORGE = staged("forge-patched", "patched-mc-forge-", mergeOutput("patched-mc-forge"));
	private static final Path NEO = staged("neoforge-patched", "patched-mc-neoforge-", mergeOutput("patched-mc-neoforge"));
	/**
	 * Vanilla in the Mojmap namespace the merged base is in. The NeoForm intermediate, NOT
	 * {@code versions/&lt;v&gt;/&lt;v&gt;.jar}: a launcher jar is obfuscated, and a census read off it would compare
	 * Mojmap descriptors to obfuscated ones and find nothing.
	 */
	private static final Path VANILLA = MC.resolve(".forbric-build/client-official.jar");

	/** The jar the merge scripts wrote under the staged {@code run/}, when this build has one. */
	private static Path staged(String subdirectory, String prefix, Path fallback) {
		Path jar = TestFixtures.stagedJar(subdirectory, prefix);
		return jar != null ? jar : fallback;
	}

	/** Where {@code build-merged-base.sh} / the carrier scripts put the merge output beside MC_DIR. */
	private static Path mergeOutput(String name) {
		return MC.resolve(".forbric-build/out/" + name + "-" + VERSION + ".jar");
	}

	/**
	 * The staged artifacts, or a skip on a checkout that has none (CI). {@code FORBRIC_COMPAT_FIXTURES_REQUIRED=1}
	 * turns the skip into a failure, so a machine that is supposed to hold them cannot pass by quietly skipping.
	 */
	private static void requireFixtures() {
		TestFixtures.requireFiles("the census fixtures (merged base, named vanilla, both carriers)",
				MERGED, VANILLA, FORGE, NEO);
	}

	@Test void theShippedTableIsExactlyWhatTheArtifactsSay() throws Exception {
		requireFixtures();
		TreeSet<String> rows = derive();
		assertFalse(rows.isEmpty(), "the census found no delegating stub at all — the merged base is not the one this "
				+ "table was derived from");
		List<String> shipped = new ArrayList<>();
		try (InputStream in = MixinStubRebind.class.getResourceAsStream(MixinStubRebind.TABLE)) {
			assertNotNull(in, MixinStubRebind.TABLE + " is missing");
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				if (!line.isBlank() && !line.startsWith("#")) shipped.add(line.trim());
			}
		}
		if (System.getenv("FORBRIC_WRITE_CARRIER_STUBS") != null) {
			Files.writeString(Path.of("src/main/resources" + MixinStubRebind.TABLE), HEADER + String.join("\n", rows) + "\n");
		}
		// Set equality, both directions: a derived row the table is missing fails it, and so does a row the base no
		// longer has. The previous revision skipped here; on the 1.21.1 port this is the assertion that runs.
		assertEquals(rows, new TreeSet<>(shipped), "carrier-stubs.txt must equal what the staged merged base, vanilla and "
				+ "both carriers say; regenerate with FORBRIC_WRITE_CARRIER_STUBS=1 after a base rebuild");
	}

	/**
	 * A handful of rows pinned by name, read off the 1.21.1 artifacts: the shapes the kernel's Forge-family rule turns
	 * on. These are the same {@link MixinStubRebind.Shape} outcomes the 26.2 revision pinned, re-pointed at rows that
	 * exist on this base (ModelManager, PackDetector, MultiPartModel, ServerExplosion and AxeItem are gone from
	 * 1.21.1's merged base and head no row there).
	 */
	@Test void theShapesTheForgeFamilyRuleTurnsOnAreStillThere() throws Exception {
		requireFixtures();
		TreeSet<String> rows = derive();
		// A carrier kept vanilla's signature as its own BODY: a mod of that family was compiled against code.
		assertRow(rows, "net/minecraft/client/multiplayer/ClientPacketListener#startWaitingForNewLevel(", "forge=body neo=stub");
		assertRow(rows, "net/minecraft/client/resources/language/ClientLanguage#<init>(", "forge=body neo=stub");
		assertRow(rows, "net/minecraft/client/renderer/entity/layers/HumanoidArmorLayer#renderArmorPiece(", "forge=body neo=stub");
		assertRow(rows, "net/minecraft/client/multiplayer/ClientLevel#addBreakingBlockEffect(", "forge=descriptor-body neo=stub");
		// The mirror: NeoForge kept vanilla's signature as the body where MinecraftForge forwards.
		assertRow(rows, "net/minecraft/network/protocol/login/custom/DiscardedQueryAnswerPayload#<init>(", "forge=stub neo=body");
		assertRow(rows, "net/minecraft/world/item/crafting/RecipeManager#<init>(", "forge=stub neo=body");
		// NeoForge RENAMED the overload: Player.getDestroySpeed(BlockState) forwards to getDigSpeed(BlockState,
		// BlockPos), which a same-name-only rule cannot see, so the row was inert and its columns were read off the
		// wrong shape. The descriptor is the same either way, which is what makes the row usable.
		assertRow(rows, "net/minecraft/world/entity/player/Player#getDestroySpeed(", "forge=stub neo=stub");
		// Forge's creativeNameSearch forwards to the renamed getSearchTree(Key) too: a Forge mod must not move there.
		assertRow(rows, "net/minecraft/client/multiplayer/SessionSearchTrees#creativeNameSearch(", "forge=stub neo=stub");
		assertRow(rows, "net/minecraft/client/multiplayer/SessionSearchTrees#creativeTagSearch(", "forge=stub neo=stub");
	}

	/** The census of the staged base: {@code owner#stubNameDesc -> delegateDesc} plus both carriers' Shape. */
	private static TreeSet<String> derive() throws Exception {
		Map<String, ClassNode> vanilla = read(VANILLA, true);
		TreeSet<String> rows = new TreeSet<>();
		try (ZipFile zip = new ZipFile(MERGED.toFile()); ZipFile forge = new ZipFile(FORGE.toFile());
				ZipFile neo = new ZipFile(NEO.toFile())) {
			for (ZipEntry entry : Collections.list(zip.entries())) {
				if (!entry.getName().endsWith(".class") || !entry.getName().startsWith("net/minecraft/")) continue;
				ClassNode merged = read(entry, zip);
				ClassNode original = vanilla.get(merged.name);
				if (original == null) continue;
				for (MethodNode stub : merged.methods) {
					// A compiler's generic bridge forwards to its typed overload too, and is no carrier's doing.
					if ((stub.access & (org.objectweb.asm.Opcodes.ACC_BRIDGE | org.objectweb.asm.Opcodes.ACC_SYNTHETIC)) != 0) continue;
					MixinStubRebind.Delegation delegation = MixinStubRebind.delegation(merged, stub);
					if (delegation == null) continue;
					String delegate = delegation.delegate().desc;
					// Vanilla must have the stub and NOT the delegate: where it has both, the merge changed nothing.
					if (!declares(original, stub.name, stub.desc) || declares(original, delegation.delegate().name, delegate)) continue;
					rows.add(merged.name + "#" + stub.name + stub.desc + " -> " + delegate
							+ " forge=" + MixinStubRebind.Shape.of(entry(forge, merged.name), stub.name, stub.desc, delegate).token
							+ " neo=" + MixinStubRebind.Shape.of(entry(neo, merged.name), stub.name, stub.desc, delegate).token);
				}
			}
		}
		return rows;
	}

	/**
	 * The header {@code FORBRIC_WRITE_CARRIER_STUBS=1} writes, and the one the shipped table carries. Kept verbatim
	 * so a regeneration does not rewrite the file's first seven lines.
	 */
	private static final String HEADER =
			"# Generated by CarrierStubCensusTest (FORBRIC_WRITE_CARRIER_STUBS=1): merged-base delegating stubs to a\n"
			+ "# same-name overload, or a renamed method, the carrier added. MixinStubRebind moves an injector only along\n"
			+ "# these rows: a Fabric mod's on every row; a MinecraftForge (forge=) or NeoForge (neo=) mod's only where its\n"
			+ "# own carrier's patched class ran that selector on code - body (vanilla's signature is the body there, first\n"
			+ "# of its name), descriptor-body (a body, declared after another overload: a descriptor selector only),\n"
			+ "# overload-body (only the widened overload is there: a name-only selector only). stub (the carrier keeps the\n"
			+ "# same stub) and absent never move.\n";

	private static void assertRow(TreeSet<String> rows, String head, String columns) {
		List<String> matching = rows.stream().filter(r -> r.startsWith(head)).toList();
		assertEquals(1, matching.size(), head + ": " + matching);
		assertTrue(matching.getFirst().endsWith(" " + columns), matching.getFirst());
	}

	private static ClassNode entry(ZipFile jar, String name) throws Exception {
		ZipEntry entry = jar.getEntry(name + ".class");
		if (entry == null) return null;
		return read(entry, jar);
	}

	private static ClassNode read(ZipEntry entry, ZipFile jar) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return node;
	}

	private static boolean declares(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return true;
		return false;
	}

	private static Map<String, ClassNode> read(Path jar, boolean skipCode) throws Exception {
		Map<String, ClassNode> out = new HashMap<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : Collections.list(zip.entries())) {
				if (!entry.getName().endsWith(".class") || !entry.getName().startsWith("net/minecraft/")) continue;
				ClassNode node = new ClassNode();
				new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, skipCode ? ClassReader.SKIP_CODE : 0);
				out.put(node.name, node);
			}
		}
		return out;
	}
}
