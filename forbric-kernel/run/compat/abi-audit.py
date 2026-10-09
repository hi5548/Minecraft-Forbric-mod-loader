#!/usr/bin/env python3
"""Report loader classes referenced by mods but absent from the supplied carrier jars.

Usage: abi-audit.py <mods-dir-or-jar> <carrier-jar>...
CONSTANT_Class references and nested META-INF/jars are read without loading Java.
Findings are a report (exit 0); unreadable input is an incomplete audit (exit 2).

A jar that declares several loaders is loaded as exactly one of them (MultiLoaderArbiter), so the half
arbitration drops is in the file but never on the runtime classpath: a 1.20.1 Forge half naming
net.minecraftforge.client.event.RenderGuiEvent* must not mark the live 1.21.1 NeoForge mod. A dangling name in a
family the archive declares but does not own is therefore skipped; a name in a family it does not declare (a
NeoForge-only jar's stray net.minecraftforge reference) is still a finding.
"""
import argparse
import io
from pathlib import Path
import struct
import sys
import zipfile

# The loader families a manifest can claim, and the spelling -Dforbric.multiLoaderPreference takes. The default
# order must match MultiLoaderArbiter.DEFAULT_PREFERENCE.
FORGE, NEOFORGE, FABRIC = "minecraftforge", "neoforge", "fabric"
DEFAULT_PREFERENCE = (NEOFORGE, FORGE, FABRIC)
_MANIFEST_FAMILIES = (
    ("META-INF/neoforge.mods.toml", NEOFORGE),
    ("META-INF/mods.toml", FORGE),
    ("fabric.mod.json", FABRIC),
)
_CLASS_FAMILIES = (
    ("net/neoforged/", NEOFORGE),
    ("net/minecraftforge/", FORGE),
    ("net/fabricmc/", FABRIC),
)


def family_of(internal):
    """The loader family a class name belongs to, or None. Shared with the Fabric API consumer probe."""
    for prefix, family in _CLASS_FAMILIES:
        if internal.startswith(prefix):
            return family
    return None


def declared_families(archive):
    """The loader families the archive's own manifests declare, in preference-independent order."""
    names = set(archive.namelist())
    return [family for manifest, family in _MANIFEST_FAMILIES if manifest in names]


def dropped_families(archive, preference=None):
    """The loader families arbitration drops for this archive: declared, but not the one that owns it.

    Mirrors MultiLoaderArbiter's manifest-level decision (that class's initializer refinement can only narrow a
    claim further, and needs the @Mod/entrypoint scan this probe deliberately does not repeat). A single-manifest
    archive, or one with no manifest at all, drops nothing -- so a plain library and a genuine wrong-Forge
    single-half jar are still judged.
    """
    declared = declared_families(archive)
    if len(declared) < 2:
        return frozenset()
    for family in (preference or DEFAULT_PREFERENCE):
        if family in declared:
            return frozenset(f for f in declared if f != family)
    return frozenset(declared[1:])


def class_refs(data):
    """Read CONSTANT_Class entries, shared with the Fabric API consumer probe."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    count = struct.unpack_from(">H", data, 8)[0]
    off, utf8, classes, i = 10, {}, [], 1
    while i < count:
        tag = data[off]
        off += 1
        if tag == 1:
            length = struct.unpack_from(">H", data, off)[0]
            off += 2
            utf8[i] = data[off:off + length].decode("utf-8", "replace")
            off += length
        elif tag == 7:
            classes.append(struct.unpack_from(">H", data, off)[0])
            off += 2
        elif tag in (8, 16, 19, 20):
            off += 2
        elif tag == 15:
            off += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            off += 4
        elif tag in (5, 6):
            off += 8
            i += 1
        else:
            raise ValueError("unknown constant-pool tag %d" % tag)
        if off > len(data):
            raise ValueError("truncated constant pool")
        i += 1
    refs = set()
    for index in classes:
        name = utf8[index].lstrip("[")
        if name.startswith("L") and name.endswith(";"):
            name = name[1:-1]
        refs.add(name)
    return refs


def jar_paths(path):
    path = Path(path)
    if path.is_dir():
        return sorted(path.glob("*.jar"))
    if path.is_file():
        return [path]
    raise ValueError("input does not exist: %s" % path)


def scan_classes(archive, label):
    """Yield label, class entry, type references and the families arbitration DROPS for the archive the class
    came from, recursively through jar-in-jar. A nested jar arbitrates on its OWN manifests, not its parent's."""
    dropped = dropped_families(archive)
    for name in sorted(archive.namelist()):
        if name.endswith(".class"):
            try:
                yield label, name, class_refs(archive.read(name)), dropped
            except (IndexError, KeyError, ValueError, struct.error) as exc:
                raise ValueError("%s :: %s: %s" % (label, name, exc)) from exc
        elif name.endswith(".jar") and name.startswith("META-INF/jars/"):
            with zipfile.ZipFile(io.BytesIO(archive.read(name))) as inner:
                yield from scan_classes(inner, label + " :: " + name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mods", help="directory of mods or one candidate jar")
    parser.add_argument("carriers", nargs="+", help="carrier jars (and optional merged base)")
    args = parser.parse_args()
    try:
        owned = set()
        for carrier in args.carriers:
            with zipfile.ZipFile(carrier) as archive:
                owned.update(name[:-6] for name in archive.namelist() if name.endswith(".class"))
        findings = {}
        jars = jar_paths(args.mods)
        for jar in jars:
            with zipfile.ZipFile(jar) as archive:
                for label, name, refs, dropped in scan_classes(archive, jar.name):
                    for ref in refs:
                        if not ref.startswith(("net/neoforged/", "net/minecraftforge/")): continue
                        # A family this archive drops is the dead half's; its references are not the live mod's.
                        if family_of(ref) in dropped: continue
                        if ref not in owned:
                            findings.setdefault(label, {}).setdefault(ref, set()).add(name)
        print("carrier classes: %d\n" % len(owned))
        for label in sorted(findings):
            print(label)
            for missing, entries in sorted(findings[label].items()):
                users = sorted(entries)
                suffix = " (+%d more)" % (len(users) - 1) if len(users) > 1 else ""
                print("    %-80s  <- %s%s" % (missing, users[0], suffix))
            print()
        print("scanned jars: %d; finding groups: %d" % (len(jars), len(findings)))
        return 0
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        print("unreadable: %s" % exc, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
