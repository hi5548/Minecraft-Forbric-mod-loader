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

package net.forbric.kernel.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import cpw.mods.jarhandling.SecureJar;
import net.forbric.kernel.boot.ForgeSecureJarStandIn;
import net.forbric.kernel.discovery.ModFileScanner;
import net.forbric.kernel.util.ForbricLog;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.ModFileScanData;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

/**
 * The {@code IModFile} behind a kernel-constructed mod: its real jar when it has one, a placeholder when it does
 * not.
 *
 * <h2>What this class made visible</h2>
 *
 * <p>It replaces a {@link java.lang.reflect.Proxy} that switched on method NAMES and answered everything it did
 * not name through a {@code defaultReturn} — null for objects, false for booleans, empty for collections. It was
 * first written answering every one of the interface's methods the way the proxy had, deliberately, so that the
 * rewrite could not move behaviour, and then {@link #getFileName()}, {@link #getType()} and
 * {@link #getModFileInfo()} were corrected to the values a real mod file has. Each was null, and null is not a
 * value any consumer expects from them: a mod filtering the file list by type dropped every kernel-loaded mod, and
 * a mod walking from a file back to its info — the direction NeoForge's own error path takes — dereferenced null.
 *
 * <h2>PORT(1.21.1): the jar-contents seam</h2>
 *
 * <p>PORT(1.21.1): 26.2's {@code net.neoforged.fml.jarcontents.JarContents} does not exist on 1.21.1; the file's
 * contents are a {@code cpw.mods.jarhandling.SecureJar} and the interface accessor is {@link #getSecureJar()}
 * (26.2 named it {@code getContents()}). The jar's {@code SecureJar} is built through
 * {@link ForgeSecureJarStandIn}, the same stand-in the seeded Forge/NeoForge {@code ModFile}s get, because the
 * carrier's own {@code SecureJar.from(Path...)} cannot initialise off ModLauncher (see {@link #secureJarOf}).
 *
 * <p>PORT(1.21.1): {@code JarContents.empty(path)} has no 1.21.1 counterpart: there is no public empty
 * {@code SecureJar} factory, so a presence alias — a mod id the kernel publishes with no jar behind it — gets a
 * null {@link #getSecureJar()} where 26.2 got an empty container. Every consumer that walks the file list must
 * tolerate that null; a mod that reads files out of its OWN jar is unaffected, because it has one.
 *
 * <p>PORT(1.21.1): the interface also gained {@link #findResource(String...)} (vanilla 1.21.1's shape, mirroring
 * {@code net.neoforged.fml.loading.moddiscovery.ModFile}) and {@link #setSecurityStatus(SecureJar.Status)}; it lost
 * 26.2's {@code getId()}, which is not part of the 1.21.1 interface and had no other reader.
 */
public final class KernelModFile implements IModFile {
	private final String modId;
	private final Path path;
	private final SecureJar secureJar;
	private final Path jar;

	/**
	 * The info that owns this file. Set by {@link KernelModFileInfo}'s constructor, which is what builds this
	 * object: the two refer to each other, so one of them has to be filled in second.
	 */
	private IModFileInfo modFileInfo;

	/** Memoised: most instances are never asked, and walking a hundred jars for nobody is pure boot cost. */
	private ModFileScanData scanResult;

	/** Written by {@link #setSecurityStatus(SecureJar.Status)}; the kernel verifies no signatures of its own. */
	private SecureJar.Status securityStatus = SecureJar.Status.NONE;

	/**
	 * @param jar the mod's real jar, or null for a presence alias which has none. A mod that reads files out of
	 *            its own jar through {@code getModInfo().getOwningFile().getFile().getSecureJar()} gets nothing
	 *            without it: Tectonic builds its bundled datapack that way, and against a null it produced a null
	 *            Pack, after which {@code PackRepository.discoverAvailable} died on "Cannot invoke
	 *            Pack.streamSelfAndChildren() because pack is null" and the world would not load.
	 */
	public KernelModFile(String modId, Path jar) {
		this.modId = modId;
		this.jar = jar;
		this.path = jar != null ? jar : Path.of("forbric-kernel", modId + ".jar");
		// Native visitors enumerate every published identity, including cross-ecosystem aliases. A real jar is
		// opened lazily by SecureJar; an alias contributes no resources and cannot build a SecureJar from a path
		// that is not a file at all, so it reports null (PORT(1.21.1), see the class note).
		this.secureJar = jar == null ? null : secureJarOf(jar);
	}

	private static SecureJar secureJarOf(Path jar) {
		try {
			// PORT(1.21.1): SecureJar.from(Path...) cannot initialise off ModLauncher — cpw.mods.jarhandling.impl.Jar's
			// <clinit> demands ModLauncher's UnionFileSystemProvider among the JDK's installed providers, and the
			// kernel replaces ModLauncher, so it throws once and then hands back NoClassDefFoundError forever. A real
			// file therefore gets the same stand-in the seeded Forge/NeoForge ModFiles get
			// (PassiveSeeder.fillForgeModFileJar): ForgeSecureJarStandIn answers the interface over a plain zip file
			// system, so findResource() resolves the file's own entries. Verified against the staged
			// neoforge/forge-runtime jars; without it getSecureJar() was null even for a real jar, contradicting the
			// contract the class doc states.
			return (SecureJar) ForgeSecureJarStandIn.create(SecureJar.class, jar);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Container] no SecureJar for %s: %s", jar.getFileName(),
					String.valueOf(t));
			return null;
		}
	}

	@Override
	public Path getFilePath() {
		return path;
	}

	/** The mod's real jar contents, or null for a jar-less presence alias. PORT(1.21.1): replaces 26.2's contents. */
	@Override
	public SecureJar getSecureJar() {
		return secureJar;
	}

	/**
	 * {@code findResource("META-INF", "mods.toml")} — the 1.21.1 SPI entry point a mod uses to read a file from
	 * its own jar, answering a path inside the jar's own file system.
	 *
	 * <p>Mirrors {@code net.neoforged.fml.loading.moddiscovery.ModFile}: at least one segment is required, and the
	 * segments are joined with {@code /} into {@code SecureJar.getPath}. Null for a jar-less alias, which has no
	 * resources, rather than the NPE a null jar would otherwise raise.
	 */
	@Override
	public Path findResource(String... path) {
		if (path == null || path.length < 1) throw new IllegalArgumentException("Missing path");
		return secureJar == null ? null : secureJar.getPath(String.join("/", path));
	}

	/**
	 * PORT(1.21.1): new on 1.21.1's interface. The kernel publishes its own mod files and verifies no signatures,
	 * so this only records what a caller set — the field FML's own {@code ModFile} keeps.
	 */
	@Override
	public void setSecurityStatus(SecureJar.Status status) {
		this.securityStatus = status;
	}

	/** The status {@link #setSecurityStatus} recorded; {@code NONE} until something sets one. */
	public SecureJar.Status getSecurityStatus() {
		return securityStatus;
	}

	/**
	 * Built on demand, and never null.
	 *
	 * <p>It is reached from further away than it looks: {@code ModList.getAllScanData()} streams sortedList →
	 * getOwningFile → getFile → getScanResult, so EVERY published mod is asked for one the moment anything calls
	 * it. Sodium does, right after its config walk, and NPE'd on a null inside {@code Minecraft.<init>} before
	 * the window ever opened.
	 *
	 * <p>The scan is real: {@code ModFileScanner.scan} walks the jar's classes and builds FML's own
	 * {@code ModFileScanData}, so annotation-driven discovery (JEI plugins, Jade providers, Sophisticated Core,
	 * Sodium's third-party config hooks) finds what it would on the carrier. Only a jar-less presence alias — a
	 * mod id the kernel publishes without a file behind it — gets the empty result, which reads exactly like a
	 * mod file that declares no annotations.
	 *
	 * <p>The index is the one the seeded {@code LoadingModList}'s {@code ModFile} for the same jar hands out
	 * ({@code ModFileScanner.scanShared}): natively {@code ModList} is built out of {@code LoadingModList}, so
	 * both hand out the same {@code ModFile} and therefore the same index — and the jar is walked once, however
	 * many entries of the two lists ask.
	 */
	@Override
	public synchronized ModFileScanData getScanResult() {
		if (scanResult == null) {
			Object real = jar == null ? null : ModFileScanner.scanShared(jar, getClass().getClassLoader());
			scanResult = real instanceof ModFileScanData data ? data : new ModFileScanData();
		}
		return scanResult;
	}

	/** The jar's file name, or the placeholder path's for a mod that has no jar. Never null. */
	@Override
	public String getFileName() {
		Path name = path.getFileName();
		return name == null ? modId + ".jar" : name.toString();
	}

	/**
	 * {@code MOD}, which is what every file the kernel publishes is.
	 *
	 * <p>Null was not a neutral answer: consumers filter the file list by type, and one comparing against
	 * {@code Type.MOD} dropped every kernel-loaded mod from whatever it was building.
	 */
	@Override
	public IModFile.Type getType() {
		return IModFile.Type.MOD;
	}

	/** The info that owns this file — the walk back up, which NeoForge's own error path takes. */
	@Override
	public IModFileInfo getModFileInfo() {
		return modFileInfo;
	}

	/** Called once by {@link KernelModFileInfo}'s constructor. */
	void setModFileInfo(IModFileInfo info) {
		this.modFileInfo = info;
	}

	// --- answered the way the proxy's defaultReturn answered them -----------------------------------------

	/** Empty, as before. The mods of this file are reached through {@code IModFileInfo.getMods()} instead. */
	@Override
	public List<IModInfo> getModInfos() {
		return List.of();
	}

	/** Null, as before. */
	@Override
	public Supplier<Map<String, Object>> getSubstitutionMap() {
		return null;
	}

	/** Null, as before. The kernel does not discover mod files the way FML's locators do. */
	@Override
	public ModFileDiscoveryAttributes getDiscoveryAttributes() {
		return null;
	}

	@Override
	public String toString() {
		return "KernelModFile[" + modId + "]";
	}
}
