package net.forbric.kernel.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AbiAuditCompatTest {
	@TempDir Path temporary;

	@Test void reportsMissingTypesAndNestedConsumersButNotCarrierTypes() throws Exception {
		Path mods = Files.createDirectory(temporary.resolve("mods"));
		CompatProbeJars.write(mods.resolve("consumer.jar"), Map.of(
				"mod/Entry.class", CompatProbeJars.type("mod/Entry", "net/minecraftforge/Known", "net/minecraftforge/Missing"),
				"META-INF/jars/inner.jar", CompatProbeJars.bytes(Map.of(
						"mod/Nested.class", CompatProbeJars.type("mod/Nested", "[Lnet/neoforged/Absent;")))));
		Path carrier = CompatProbeJars.write(temporary.resolve("carrier.jar"), Map.of(
				"net/minecraftforge/Known.class", CompatProbeJars.type("net/minecraftforge/Known")));
		var result = CompatProbeProcess.run(temporary, "python3", "abi-audit.py", mods.toString(), carrier.toString());
		assertEquals(0, result.exitCode(), result.output());
		assertTrue(result.output().contains("net/minecraftforge/Missing"), result.output());
		assertTrue(result.output().contains("consumer.jar :: META-INF/jars/inner.jar"), result.output());
		assertTrue(result.output().contains("net/neoforged/Absent"), result.output());
		assertFalse(result.output().contains("net/minecraftforge/Known"), result.output());
	}

	@Test void addingTheMissingCarrierClassClearsTheFinding() throws Exception {
		Path jar = CompatProbeJars.write(temporary.resolve("consumer.jar"), Map.of(
				"mod/Entry.class", CompatProbeJars.type("mod/Entry", "net/minecraftforge/Available")));
		Path carrier = CompatProbeJars.write(temporary.resolve("carrier.jar"), Map.of(
				"net/minecraftforge/Available.class", CompatProbeJars.type("net/minecraftforge/Available")));
		var result = CompatProbeProcess.run(temporary, "python3", "abi-audit.py", jar.toString(), carrier.toString());
		assertEquals(0, result.exitCode(), result.output());
		assertTrue(result.output().contains("finding groups: 0"), result.output());
	}

	@Test void corruptNestedBytecodeCannotMasqueradeAsAnEmptyAudit() throws Exception {
		Path jar = CompatProbeJars.write(temporary.resolve("consumer.jar"), Map.of("bad.class", new byte[] {1, 2, 3}));
		Path carrier = CompatProbeJars.write(temporary.resolve("carrier.jar"), Map.of());
		var result = CompatProbeProcess.run(temporary, "python3", "abi-audit.py", jar.toString(), carrier.toString());
		assertEquals(2, result.exitCode(), result.output());
		assertTrue(result.output().contains("unreadable:"), result.output());
	}

	@Test void aUniversalJarsDroppedHalfIsNotAFindingButASingleManifestJarStillFires() throws Exception {
		Path carrier = CompatProbeJars.write(temporary.resolve("carrier.jar"), Map.of());
		Path single = CompatProbeJars.write(temporary.resolve("neoforge-only.jar"), Map.of(
				"META-INF/neoforge.mods.toml", manifest(),
				"mod/Uses.class", CompatProbeJars.type("mod/Uses", "net/minecraftforge/Missing")));
		Path universal = CompatProbeJars.write(temporary.resolve("universal.jar"), Map.of(
				"META-INF/mods.toml", manifest(),
				"META-INF/neoforge.mods.toml", manifest(),
				"mod/Uses.class", CompatProbeJars.type("mod/Uses", "net/minecraftforge/Missing")));

		var singleResult = CompatProbeProcess.run(temporary, "python3", "abi-audit.py", single.toString(), carrier.toString());
		assertTrue(singleResult.output().contains("net/minecraftforge/Missing"), singleResult.output());
		assertTrue(singleResult.output().contains("finding groups: 1"), singleResult.output());

		var universalResult = CompatProbeProcess.run(temporary, "python3", "abi-audit.py", universal.toString(), carrier.toString());
		assertFalse(universalResult.output().contains("net/minecraftforge/Missing"), universalResult.output());
		assertTrue(universalResult.output().contains("finding groups: 0"), universalResult.output());
	}

	@Test void aNestedJarArbitratesOnItsOwnManifestsNotItsParents() throws Exception {
		Path carrier = CompatProbeJars.write(temporary.resolve("carrier.jar"), Map.of());
		// The parent declares both families (so arbitration drops FORGE for the parent), but the nested jar
		// declares NONE of its own: its dangling Forge name is the nested mod's, and must still be reported.
		Path parentDropsForge = CompatProbeJars.write(temporary.resolve("parent.jar"), Map.of(
				"META-INF/mods.toml", manifest(),
				"META-INF/neoforge.mods.toml", manifest(),
				"META-INF/jars/inner.jar", CompatProbeJars.bytes(Map.of(
						"mod/Nested.class", CompatProbeJars.type("mod/Nested", "net/minecraftforge/Missing")))));
		var parentResult = CompatProbeProcess.run(temporary, "python3", "abi-audit.py", parentDropsForge.toString(), carrier.toString());
		assertTrue(parentResult.output().contains("net/minecraftforge/Missing"), parentResult.output());
		assertTrue(parentResult.output().contains("finding groups: 1"), parentResult.output());

		// A manifest-less parent with a universal nested jar: the nested half is dropped, so its own name is not.
		Path nestedIsUniversal = CompatProbeJars.write(temporary.resolve("plain.jar"), Map.of(
				"META-INF/jars/inner.jar", CompatProbeJars.bytes(Map.of(
						"META-INF/mods.toml", manifest(),
						"META-INF/neoforge.mods.toml", manifest(),
						"mod/Nested.class", CompatProbeJars.type("mod/Nested", "net/minecraftforge/Missing")))));
		var nestedResult = CompatProbeProcess.run(temporary, "python3", "abi-audit.py", nestedIsUniversal.toString(), carrier.toString());
		assertFalse(nestedResult.output().contains("net/minecraftforge/Missing"), nestedResult.output());
		assertTrue(nestedResult.output().contains("finding groups: 0"), nestedResult.output());
	}

	private static byte[] manifest() {
		return "[[mods]]\nmodId=\"example\"\nversion=\"1\"\n".getBytes(StandardCharsets.UTF_8);
	}
}
