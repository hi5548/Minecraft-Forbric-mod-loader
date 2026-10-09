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

package net.forbric.kernel.boot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.util.ByteScan;
import net.forbric.kernel.util.ForbricLog;

/**
 * Names the mods compiled against a NeoForge or MinecraftForge this instance does not carry.
 *
 * <p>A mod built for another loader version links fine at load and dies at the first call into a class that is
 * not there — a {@code NoClassDefFoundError} inside a deferred task, a listener, a render pass — and the report
 * names the class, not the mod. This reads every class's constant pool for the Forge-family classes it names and
 * resolves each against the carriers, the merged base and every installed jar (a Fabric port of a Forge library
 * legitimately ships {@code net.minecraftforge.*} classes for its dependants); what resolves nowhere is dangling.
 *
 * <p>Loader-bootstrap packages are out of scope: the kernel REPLACES FML's loading layer, so
 * {@code fml/loading}, {@code fml/relauncher} and the {@code locating} SPIs are absent here by design and a mod
 * naming them (CustomSkinLoader's six references) is not compiled against the wrong Forge.
 *
 * <p>A universal jar carries one half per loader family and {@link MultiLoaderArbiter} loads exactly one of them.
 * The half it drops is still in the file, and its references — a 1.20.1 Forge half naming
 * {@code net.minecraftforge.client.event.RenderGuiEvent*} against a 1.21.1 merged base — are not the live mod's,
 * so judging the whole jar marks the row DEGRADED for code the kernel never loads. A dangling name in a family
 * arbitration dropped for THIS jar is therefore not a finding; a name in a family the jar did not declare (a
 * NeoForge-only jar's stray {@code net.minecraftforge} reference) still is.
 *
 * <p>Never throws, never refuses a jar; {@code -Dforbric.abiAudit=off}. Scanned at boot while the jar names are
 * in hand, reported after the catalog is published so the rows reach load-report.txt.
 *
 * <p>The boot scan is driven by {@link GuestClassScan}: that ONE pass over the installed jars decompresses and
 * reads each {@code .class} entry once and hands the bytes to every guest audit, so this audit's own read loop is
 * gone. {@link #prepare} builds the resolved-class set once, {@link #note} judges one class, and {@link #scan}
 * remains as the single-audit wrapper the tests and any standalone caller use.
 */
public final class AbiLinkAudit {
	static final String SWITCH = "forbric.abiAudit";

	/** What is judged: everything under either family's root… */
	static final String[] FAMILIES = { "net/neoforged/", "net/minecraftforge/" };
	/** …except the loading layer the kernel replaces. */
	static final String[] OUT_OF_SCOPE = { "net/neoforged/fml/loading/", "net/minecraftforge/fml/loading/",
			"net/minecraftforge/fml/relauncher/", "net/neoforged/neoforgespi/locating/", "net/minecraftforge/forgespi/locating/" };
	private static final byte[][] NEEDLES = { ByteScan.needle("net/neoforged/"), ByteScan.needle("net/minecraftforge/") };

	/** One jar with dangling references: which family, and the classes (internal names) that resolve nowhere. */
	public record Finding(String jar, String family, List<String> missing) {
	}

	/** jar file name → the dangling names found across its classes (its finding, once it has one). */
	private static final Map<String, Set<String>> MISSING = new LinkedHashMap<>();
	/** jar file name → the families arbitration dropped for it, cached so one pass does not re-open its manifest per class. */
	private static final Map<String, Set<Ecosystem>> DROPPED = new HashMap<>();
	/** Every class that exists in the universe {@link #prepare} was handed. */
	private static volatile Set<String> present = Set.of();
	private static int scannedJars;
	private static long scanNanos;

	private AbiLinkAudit() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
	}

	/**
	 * Builds the resolved-class set once for a run — the classes of the installed jars plus the carriers and the
	 * merged base. {@link GuestClassScan} calls this before its single pass; a bare {@link #scan} does it too.
	 */
	public static void prepare(List<Path> universe) {
		if (!enabled()) return;
		present = classesOf(universe);
	}

	/**
	 * Judges ONE {@code .class} entry of {@code jar}: the same needle check, named-class scan and dropped-family
	 * rule {@link #audit} applies, incrementally. The jar is needed whole only for the arbitration manifest, and
	 * that is asked once per jar (only when it already has a dangling name).
	 */
	public static void note(Path jar, byte[] classBytes) {
		if (!enabled() || classBytes == null || jar == null) return;
		if (!ByteScan.containsAny(classBytes, NEEDLES)) return;
		Set<String> missing = missingIn(classBytes, present);
		if (missing.isEmpty()) return;
		String jarName = jar.getFileName().toString();
		Set<Ecosystem> dropped = droppedFor(jar, jarName);
		if (!dropped.isEmpty()) missing.removeIf(named -> dropped.contains(familyOf(named)));
		if (missing.isEmpty()) return;
		synchronized (MISSING) {
			MISSING.computeIfAbsent(jarName, n -> new LinkedHashSet<>()).addAll(missing);
		}
	}

	/** Folds {@code jars} scanned and {@code nanos} spent into the summary {@link #report} prints. */
	public static void recordScan(int jars, long nanos) {
		synchronized (MISSING) {
			scannedJars += jars;
			scanNanos += nanos;
		}
	}

	/** Scans {@code jars}, resolving against {@code jars} themselves plus {@code alsoAgainst} (carriers, merged base). */
	public static void scan(List<Path> jars, List<Path> alsoAgainst) {
		if (!enabled()) return;
		long start = System.nanoTime();
		List<Path> universe = new ArrayList<>(alsoAgainst);
		for (Path jar : jars) if (!universe.contains(jar)) universe.add(jar);
		prepare(universe);
		for (Path jar : jars) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (ZipEntry entry : zip.stream().toList()) {
					if (!entry.getName().endsWith(".class")) continue;
					try (InputStream in = zip.getInputStream(entry)) {
						note(jar, in.readAllBytes());
					}
				}
			} catch (IOException | RuntimeException unreadable) {
				ForbricLog.debug("[Forbric/AbiAudit] could not read %s: %s", jar.getFileName(), unreadable);
			}
		}
		recordScan(jars.size(), System.nanoTime() - start);
	}

	/** Every {@code .class} entry name (without the extension) across {@code jars}. */
	static Set<String> classesOf(List<Path> jars) {
		Set<String> present = new HashSet<>();
		for (Path jar : jars) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (ZipEntry entry : zip.stream().toList()) {
					String name = entry.getName();
					if (name.endsWith(".class")) present.add(name.substring(0, name.length() - 6));
				}
			} catch (IOException unreadable) {
				ForbricLog.debug("[Forbric/AbiAudit] could not list %s: %s", jar.getFileName(), unreadable);
			}
		}
		return present;
	}

	/** The findings over {@code jars}, given the set of classes that exist. The test's entry point. */
	static List<Finding> audit(List<Path> jars, Set<String> present) {
		List<Finding> out = new ArrayList<>();
		for (Path jar : jars) {
			Set<String> missing = new LinkedHashSet<>();
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (ZipEntry entry : zip.stream().toList()) {
					if (!entry.getName().endsWith(".class")) continue;
					byte[] bytes;
					try (InputStream in = zip.getInputStream(entry)) {
						bytes = in.readAllBytes();
					}
					if (!ByteScan.containsAny(bytes, NEEDLES)) continue;
					missing.addAll(missingIn(bytes, present));
				}
			} catch (IOException | RuntimeException unreadable) {
				ForbricLog.debug("[Forbric/AbiAudit] could not read %s: %s", jar.getFileName(), unreadable);
				continue;
			}
			Set<Ecosystem> dropped = missing.isEmpty() ? Set.of() : droppedFamilies(jar);
			if (!dropped.isEmpty()) missing.removeIf(named -> dropped.contains(familyOf(named)));
			if (missing.isEmpty()) continue;
			out.add(finding(jar.getFileName().toString(), missing));
		}
		return out;
	}

	/** The in-scope names {@code classBytes} carries that exist nowhere in {@code present}. */
	private static Set<String> missingIn(byte[] classBytes, Set<String> present) {
		Set<String> missing = new LinkedHashSet<>();
		for (String named : namedClasses(classBytes)) {
			if (inScope(named) && !present.contains(named)) missing.add(named);
		}
		return missing;
	}

	/** {@link #droppedFamilies} for one jar, cached by file name so the one boot pass asks it at most once. */
	private static Set<Ecosystem> droppedFor(Path jar, String jarName) {
		synchronized (DROPPED) {
			Set<Ecosystem> cached = DROPPED.get(jarName);
			if (cached != null) return cached;
			Set<Ecosystem> dropped = droppedFamilies(jar);
			DROPPED.put(jarName, dropped);
			return dropped;
		}
	}

	private static Finding finding(String jarName, Set<String> missing) {
		String first = missing.iterator().next();
		return new Finding(jarName, familyOf(first).displayName(), List.copyOf(missing));
	}
	/**
	 * The loader families {@code jar} declares but arbitration did not give it — the half {@link MultiLoaderArbiter}
	 * drops. Empty when the jar declares one family or none: a single-family jar is never dropped for its own
	 * family, so its dangling references are still judged. Asked only of a jar that already has a finding, so the
	 * common path pays nothing.
	 */
	static Set<Ecosystem> droppedFamilies(Path jar) {
		List<Ecosystem> declared = MultiLoaderArbiter.declaredBy(jar);
		if (declared.size() < 2) return Set.of();
		Set<Ecosystem> dropped = new LinkedHashSet<>(declared);
		dropped.remove(MultiLoaderArbiter.ownerOf(jar));
		return dropped;
	}

	/** The loader family a judged class name belongs to; the audit judges only these two. */
	static Ecosystem familyOf(String internal) {
		return internal.startsWith("net/minecraftforge/") ? Ecosystem.FORGE : Ecosystem.NEOFORGE;
	}

	/** Whether {@code internal} is a Forge-family class this audit judges. */
	static boolean inScope(String internal) {
		boolean family = false;
		for (String root : FAMILIES) family |= internal.startsWith(root);
		if (!family) return false;
		for (String skip : OUT_OF_SCOPE) if (internal.startsWith(skip)) return false;
		return true;
	}

	/** Every CONSTANT_Class in the constant pool, array descriptors reduced to their element class. */
	static Set<String> namedClasses(byte[] classBytes) {
		Set<String> out = new LinkedHashSet<>();
		ClassReader reader = new ClassReader(classBytes);
		char[] buf = new char[reader.getMaxStringLength()];
		for (int i = 1; i < reader.getItemCount(); i++) {
			int offset = reader.getItem(i);
			if (offset == 0 || reader.readByte(offset - 1) != 7) continue; // CONSTANT_Class
			String name = reader.readUTF8(offset, buf);
			if (name == null) continue;
			if (name.startsWith("[")) {
				int l = name.indexOf('L');
				if (l < 0 || !name.endsWith(";")) continue;
				name = name.substring(l + 1, name.length() - 1);
			}
			out.add(name);
		}
		return out;
	}

	/** One summary line always; one WARN per jar with dangling references; DEGRADED on every row from that jar. */
	public static void report() {
		if (!enabled()) return;
		List<Finding> findings;
		int scanned;
		long nanos;
		synchronized (MISSING) {
			findings = new ArrayList<>();
			for (Map.Entry<String, Set<String>> e : MISSING.entrySet()) findings.add(finding(e.getKey(), e.getValue()));
			scanned = scannedJars;
			nanos = scanNanos;
		}
		for (Finding f : findings) {
			List<String> shown = f.missing().subList(0, Math.min(5, f.missing().size()));
			ForbricLog.warn("[Forbric/AbiAudit] %s was compiled against a different %s than this instance carries — %d "
					+ "class(es) it names do not exist here: %s%s", f.jar(), f.family(), f.missing().size(),
					String.join(", ", shown).replace('/', '.'), f.missing().size() > shown.size() ? ", …" : "");
			ModCatalog.markByJar(f.jar(), ModCatalog.Status.DEGRADED, "compiled against a different " + f.family() + " — "
					+ f.missing().get(0).replace('/', '.') + " is not in this instance");
		}
		ForbricLog.info("[Forbric/AbiAudit] scanned %d jar(s) in %d ms: %d with dangling Forge-family references", scanned,
				nanos / 1_000_000, findings.size());
	}

	/** The findings recorded so far, in jar order. Package-private: for the test. */
	static List<Finding> findings() {
		synchronized (MISSING) {
			List<Finding> out = new ArrayList<>();
			for (Map.Entry<String, Set<String>> e : MISSING.entrySet()) out.add(finding(e.getKey(), e.getValue()));
			return out;
		}
	}

	/** Package-private, for the test. */
	static void reset() {
		synchronized (MISSING) {
			MISSING.clear();
			scannedJars = 0;
			scanNanos = 0;
		}
		synchronized (DROPPED) {
			DROPPED.clear();
		}
		present = Set.of();
	}
}
