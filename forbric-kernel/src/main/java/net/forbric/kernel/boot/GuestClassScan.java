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
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.kernel.mixin.MergedBaseUncalledMethods;
import net.forbric.kernel.util.ForbricLog;

/**
 * The ONE boot pass that decompresses and reads every installed jar's {@code .class} entries and hands each class's
 * bytes to every guest audit that wants them — in place of the four independent scans that each opened every jar
 * and read every class for a different set of needles.
 *
 * <p>At boot, before the client window and on {@code [main]}, these four audits all ran over the same
 * {@code shadowCandidates}: {@link FabricApiModuleLossAudit} (which fabric-api surface a mod uses),
 * {@link FieldDriftAudit} (a vanilla field the merge re-typed), {@link MergedBaseUncalledMethods} (which
 * merged-base methods an installed mod calls) and {@link AbiLinkAudit} (a Forge-family class that resolves
 * nowhere). Each is a {@link net.forbric.kernel.util.ByteScan} needle test over the same {@code byte[]}, so each
 * paid its own decompress and {@code readAllBytes} per class. {@code AbiLinkAudit} logged its own cost — 205 ms
 * and 745 ms on two boots of the same 68 jars — and it was one of four; the others were unmeasured.
 *
 * <p>The needle tests themselves are cheap and stay where they are, in each audit's {@code note}. What this
 * removes is the redundant I/O: one {@code ZipFile} open and one {@code readAllBytes} per class, dispatched to
 * every enabled audit. The verdicts are unchanged — the same bytes reach the same tests — and each audit keeps
 * its own summary line: {@link AbiLinkAudit} is handed the jar count and the time its own {@code note} spent, so
 * its "scanned N jar(s) in X ms" now names the cost it can still account for rather than the whole pass.
 *
 * <p>{@code PortingLayerAudit} is deliberately NOT folded in: it reads only {@code net/neoforged/**}/
 * {@code net/minecraftforge/**} classes and compares them against the carrier, a different shape and a different
 * (smaller) read set. Every audit's kill switch is honoured — an audit that is off is not called.
 */
public final class GuestClassScan {

	private GuestClassScan() {
	}

	/**
	 * One pass over {@code jars}. {@code abiUniverse} (the carriers plus the merged base) resolves
	 * {@link AbiLinkAudit}'s names; the other audits need no universe. A jar that cannot be read is skipped, never
	 * refused.
	 */
	public static void scan(List<Path> jars, List<Path> abiUniverse) {
		boolean fabric = FabricApiModuleLossAudit.enabled();
		boolean fieldDrift = FieldDriftAudit.enabled();
		boolean uncalled = MergedBaseUncalledMethods.enabled();
		boolean abi = AbiLinkAudit.enabled();
		if (!fabric && !fieldDrift && !uncalled && !abi) return;

		if (abi) AbiLinkAudit.prepare(abiUniverse);

		long abiNanos = 0;
		int scanned = 0;
		for (Path jar : jars) {
			String name = jar.getFileName().toString();
			scanned++;
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (ZipEntry entry : zip.stream().toList()) {
					if (!entry.getName().endsWith(".class")) continue;
					byte[] bytes;
					try (InputStream in = zip.getInputStream(entry)) {
						bytes = in.readAllBytes();
					}
					if (fabric) FabricApiModuleLossAudit.note(name, bytes);
					if (fieldDrift) FieldDriftAudit.note(name, bytes);
					if (uncalled) MergedBaseUncalledMethods.noteGuest(bytes);
					if (abi) {
						long before = System.nanoTime();
						AbiLinkAudit.note(jar, bytes);
						abiNanos += System.nanoTime() - before;
					}
				}
			} catch (IOException | RuntimeException unreadable) {
				ForbricLog.debug("[Forbric/Boot] guest class scan could not read %s: %s", name, unreadable);
			}
		}
		if (fieldDrift) FieldDriftAudit.recordJars(scanned);
		if (abi) AbiLinkAudit.recordScan(scanned, abiNanos);
		if (uncalled) MergedBaseUncalledMethods.finishGuestScan();
	}
}
