package net.forbric.kernel.boot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Offline R3 probe, over a REAL jar set: the four guest audits' independent passes (the boot path before the fold)
 * against the one {@link GuestClassScan} pass (after it). Counts readAllBytes calls and times both.
 * args[0..] = directories holding jars.
 */
public final class R3Probe {
	public static void main(String[] args) throws Exception {
		List<Path> jars = new ArrayList<>();
		List<Path> universe = new ArrayList<>();
		for (String dir : args) {
			try (var list = Files.list(Path.of(dir))) {
				list.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(jars::add);
			}
		}
		universe.addAll(jars);
		System.out.println("jars=" + jars.size());

		// BEFORE: four independent passes, each opening every jar and reading every class.
		resetAll();
		int[] reads = {0};
		AbiLinkAudit.prepare(universe);
		long t0 = System.nanoTime();
		loopPass(jars, reads, 0);
		loopPass(jars, reads, 1);
		loopPass(jars, reads, 2);
		loopPass(jars, reads, 3);
		long beforeNanos = System.nanoTime() - t0;
		System.out.println("BEFORE reads=" + reads[0] + " ms=" + beforeNanos / 1_000_000
				+ " fabricUsers=" + FabricApiModuleLossAudit.users().size()
				+ " fieldHits=" + FieldDriftAudit.hits().size()
				+ " abiFindings=" + AbiLinkAudit.findings().size());

		// AFTER: ONE pass, one read per class.
		resetAll();
		long t1 = System.nanoTime();
		GuestClassScan.scan(jars, universe);
		long afterNanos = System.nanoTime() - t1;
		System.out.println("AFTER  reads=" + countClasses(jars) + " ms=" + afterNanos / 1_000_000
				+ " fabricUsers=" + FabricApiModuleLossAudit.users().size()
				+ " fieldHits=" + FieldDriftAudit.hits().size()
				+ " abiFindings=" + AbiLinkAudit.findings().size());
		System.out.println("ratio=" + String.format("%.2f", beforeNanos / (double) afterNanos));
	}

	private static void loopPass(List<Path> jars, int[] reads, int which) {
		for (Path jar : jars) {
			String name = jar.getFileName().toString();
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (ZipEntry entry : zip.stream().toList()) {
					if (!entry.getName().endsWith(".class")) continue;
					byte[] bytes;
					try (InputStream in = zip.getInputStream(entry)) {
						bytes = in.readAllBytes();
					}
					reads[0]++;
					switch (which) {
						case 0 -> FabricApiModuleLossAudit.note(name, bytes);
						case 1 -> FieldDriftAudit.note(name, bytes);
						case 2 -> net.forbric.kernel.mixin.MergedBaseUncalledMethods.noteGuest(bytes);
						default -> AbiLinkAudit.note(jar, bytes);
					}
				}
			} catch (IOException unreadable) {
				// skipped, like the audits
			}
		}
	}

	private static int countClasses(List<Path> jars) throws IOException {
		int n = 0;
		for (Path jar : jars) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (ZipEntry entry : zip.stream().toList()) if (entry.getName().endsWith(".class")) n++;
			}
		}
		return n;
	}

	private static void resetAll() {
		FabricApiModuleLossAudit.reset();
		FieldDriftAudit.reset();
		AbiLinkAudit.reset();
		net.forbric.kernel.mixin.MergedBaseUncalledMethods.forgetGuests();
	}

	private R3Probe() {
	}
}
