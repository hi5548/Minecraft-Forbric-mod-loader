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

package net.forbric.kernel.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer;

/**
 * The merged codec provider's captured fields are read by two consumers — the {@code findCodec} body the builder
 * splices in, and {@code PayloadInterop}'s reflective mirror pass. Both must take the names from the class that
 * declares them: the builder's splice carries whatever its own generation called the captures, and on 1.21.1 that
 * is {@code val$idToType}/{@code val$fallback} against a class whose fields are {@code val$map}/{@code val$p_319839_}.
 * The first read is a hard {@code NoSuchFieldError} on the connect packet; the second is silent and collects
 * nothing. These checks run over the REAL staged base bytes, so they fail if either spelling is reintroduced.
 */
class PayloadInteropCaptureFieldsTest {
	private static final String CODEC =
			"net.minecraft.network.protocol.common.custom.CustomPacketPayload$1$forbricneo";

	@Test
	void theSplicedCodecReadsFieldsTheMergedClassDeclares() throws Exception {
		Path base = mergedBase();
		assumeTrue(Files.isRegularFile(base), "staged merged base absent: " + base);

		byte[] in = read(base, CODEC.replace('.', '/') + ".class");
		byte[] out = new ForbricMergedBaseCompatTransformer().transform(CODEC, in, null);
		ClassNode codec = parse(out);

		String mapField = PayloadCaptureFields.uniqueDeclared(codec, PayloadCaptureFields.ID_TO_TYPE);
		assertNotNull(mapField, "the merged provider declares one java.util.Map capture");

		List<String> reads = new ArrayList<>();
		for (MethodNode method : codec.methods) {
			if (!method.name.equals("findCodec")) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD) {
					reads.add(field.name);
				}
			}
		}
		assertFalse(reads.isEmpty(), "the spliced codec has a findCodec that reads its captures");

		for (String read : reads) {
			boolean declared = false;
			for (FieldNode field : codec.fields) {
				if (field.name.equals(read)) declared = true;
			}
			assertTrue(declared, "findCodec reads " + read + ", which the merged class does not declare: " + reads);
		}
		assertTrue(reads.contains(mapField),
				"findCodec must read the declared id→type capture " + mapField + ", not a pre-merge name: " + reads);
	}

	@Test
	void theRuntimeIdToTypeResolutionPicksTheMapCaptureAndStandsDownOnDoubt() {
		assertNull(PayloadCaptureFields.idToType(null));
		assertNull(PayloadCaptureFields.idToType(NoCapture.class), "no Map capture: stand down");
		assertNull(PayloadCaptureFields.idToType(TwoCaptures.class), "two Map captures: stand down, never guess");
		assertEquals("val$map", PayloadCaptureFields.idToType(OneCapture.class),
				"the sole java.util.Map field is the id→type map, whatever generation named the others");
	}

	/** The 1.21.1 shape: one Map capture beside game-typed ones. */
	public static final class OneCapture {
		@SuppressWarnings("unused") private final Map<?, ?> val$map = Map.of();
		@SuppressWarnings("unused") private final Object val$protocol = new Object();
		@SuppressWarnings("unused") private final Object val$packetFlow = new Object();
	}

	/** The shape this resolution must refuse to pick from. */
	public static final class TwoCaptures {
		@SuppressWarnings("unused") private final Map<?, ?> val$one = Map.of();
		@SuppressWarnings("unused") private final Map<?, ?> val$two = Map.of();
	}

	public static final class NoCapture {
	}

	// --- helpers -------------------------------------------------------------------------------------------------

	/**
	 * The merged base of this build: the {@code forbric.stagedRoot} the build handed the tests, else
	 * {@code FORBRIC_OLD + "/run"} as the run/ scripts resolve it. Falls back to any staged
	 * {@code patched-mc-merged-*.jar} so a version bump does not skip the check silently.
	 */
	private static Path mergedBase() throws Exception {
		String staged = System.getProperty("forbric.stagedRoot");
		Path root = staged != null && !staged.isBlank()
				? Path.of(staged)
				: Path.of(System.getenv().getOrDefault("FORBRIC_OLD",
						System.getProperty("user.dir") + "/../forbric-loader"), "run");
		Path dir = root.resolve("merged-base").normalize();
		String version = System.getProperty("forbric.mcVersion", "1.21.1");
		Path exact = dir.resolve("patched-mc-merged-" + version + ".jar");
		if (Files.isRegularFile(exact)) return exact;
		if (!Files.isDirectory(dir)) return exact;
		try (Stream<Path> entries = Files.list(dir)) {
			return entries.filter(p -> p.getFileName().toString().startsWith("patched-mc-merged-")
							&& p.getFileName().toString().endsWith(".jar") && Files.isRegularFile(p))
					.findFirst().orElse(exact);
		}
	}

	private static byte[] read(Path jar, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			assertNotNull(found, entry + " in " + jar);
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}
}
