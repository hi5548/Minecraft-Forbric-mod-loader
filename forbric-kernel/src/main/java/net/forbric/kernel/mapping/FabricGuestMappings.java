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

package net.forbric.kernel.mapping;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The mapping data the kernel needs to load a Fabric guest — the two files the installer stages and the launch
 * argument names — plus the one place they are parsed into a {@link ForbricMappings} spine.
 *
 * <p><b>Why two files.</b> Neither permitted source alone reaches from intermediary to Mojmap: Fabric's
 * intermediary mappings join the obfuscated column to intermediary, Mojang's client mappings (ProGuard) join the
 * same obfuscated column to Mojmap. {@link ForbricMappings#load} joins them on that shared column. Neither is
 * redistributed — the installer fetches both at install time, exactly like the game artifacts.
 *
 * <h2>Absent is not broken</h2>
 * The absence of the launch argument is a legal state, not an error: on 26.2 the game is already Mojmap-named and
 * a Fabric mod for that version is compiled against Mojmap too, so the kernel's remap is the identity and always
 * was. {@link #none()} is that state and {@link #mappings()} answers {@code null} for it.
 *
 * <p>An argument that IS given is a claim that the files are there and readable, and a wrong claim must not
 * degrade into "load it as-is and see what happens": a missing file, an argument with the wrong number of
 * entries, or data that does not parse all fail loudly, with the path in the message. Silent identity is exactly
 * the failure mode this whole layer exists to remove — a guest would then be defined with intermediary names,
 * link against nothing, and die far from the cause.
 *
 * <p>The spine is parsed lazily, at most once, the first time something asks ({@link #mappings()}). A boot with
 * no Fabric mods never pays for it; the thread that first needs it does.
 */
public final class FabricGuestMappings {
	/**
	 * The launch argument that names the two staged files, joined the way a classpath is (see
	 * {@link #fromArgument}). One flag, not two, for the same reason {@code --runtimeJar} is one: a launcher may
	 * read game arguments as a flag-to-value map and keep only the last occurrence of a repeated flag.
	 */
	public static final String ARGUMENT = "--mappings";

	/** The instance that means "no mapping data staged" — the 26.2 path, and any pack with no Fabric mods. */
	private static final FabricGuestMappings NONE = new FabricGuestMappings(null, null);

	/** What the last launch configured; see {@link #install} for why this is process-wide. */
	private static volatile FabricGuestMappings installed;

	private final Path intermediary;
	private final Path mojmap;
	/** Parsed once, under {@link #mappings()}'s monitor. Null means "the parse failed" is impossible: it throws. */
	private ForbricMappings spine;

	private FabricGuestMappings(Path intermediary, Path mojmap) {
		this.intermediary = intermediary;
		this.mojmap = mojmap;
	}

	/** No mapping data: every lookup is the identity and no guest is remapped. */
	public static FabricGuestMappings none() {
		return NONE;
	}

	/**
	 * The two files, checked to be readable files here rather than at first use — a launch argument that names
	 * data it does not have is refused before any mod code runs.
	 *
	 * @param intermediary Fabric's intermediary mappings for the game version (a jar holding {@code mappings/mappings.tiny})
	 * @param mojmap       Mojang's client mappings for the same version (ProGuard {@code named → official})
	 */
	public static FabricGuestMappings of(Path intermediary, Path mojmap) {
		requireReadable(ARGUMENT, intermediary);
		requireReadable(ARGUMENT, mojmap);
		return new FabricGuestMappings(intermediary, mojmap);
	}

	/**
	 * Parses the launch argument: exactly two paths, joined by the platform's path separator, intermediary
	 * first and Mojang's client mappings second (the order {@code Installer} writes). Blank or absent means
	 * {@link #none()}.
	 */
	public static FabricGuestMappings fromArgument(String value) {
		if (value == null || value.isBlank()) return none();

		List<Path> paths = new ArrayList<>(2);
		for (String entry : value.split(Pattern.quote(File.pathSeparator))) {
			if (!entry.isBlank()) paths.add(Path.of(entry));
		}

		if (paths.size() != 2) {
			throw new IllegalStateException(ARGUMENT + " names exactly two files — Fabric's intermediary mappings"
					+ " then Mojang's client mappings — joined by '" + File.pathSeparator + "'; got: " + value);
		}

		return of(paths.get(0), paths.get(1));
	}

	/** Whether this boot has mapping data at all. False for {@link #none()} and for anything never installed. */
	public boolean present() {
		return intermediary != null && mojmap != null;
	}

	public Path intermediary() {
		return intermediary;
	}

	public Path mojmap() {
		return mojmap;
	}

	/**
	 * The parsed spine, or {@code null} when no data was staged. Parsed once and reused; a failed parse throws
	 * {@link IllegalStateException} rather than answering null, so a broken mapping file cannot become a
	 * silently identity-mapped game.
	 */
	public synchronized ForbricMappings mappings() {
		if (!present()) return null;

		if (spine == null) {
			try {
				spine = ForbricMappings.load(intermediary, mojmap);
			} catch (IOException | RuntimeException broken) {
				throw new IllegalStateException("the mapping data named by " + ARGUMENT + " could not be read, so"
						+ " Fabric guests cannot be remapped onto the runtime namespace: " + intermediary + " + "
						+ mojmap, broken);
			}
		}

		return spine;
	}

	public String describe() {
		if (!present()) return "none";
		return intermediary.getFileName() + " + " + mojmap.getFileName();
	}

	/**
	 * Publishes this launch's mapping data to the resolver and the remap stage.
	 *
	 * <p>It is process-wide because the two consumers are constructed by code the boot orchestrator does not own
	 * and cannot pass arguments to: {@code KernelFabricLoader} builds its {@code MappingResolver} as a field, and
	 * what it needs is a property of the launch rather than of the loader. A process has exactly one launch, so
	 * this is a launch argument with extra steps, not shared mutable state.
	 */
	public static void install(FabricGuestMappings mappings) {
		installed = mappings == null ? NONE : mappings;
	}

	/** What {@link #install} last published, or null when nothing configured this process (unit tests, tooling). */
	public static FabricGuestMappings installed() {
		return installed;
	}

	private static void requireReadable(String what, Path file) {
		if (file == null || !Files.isRegularFile(file)) {
			throw new IllegalStateException(what + " names mapping data that is not a file: " + file);
		}
	}
}
