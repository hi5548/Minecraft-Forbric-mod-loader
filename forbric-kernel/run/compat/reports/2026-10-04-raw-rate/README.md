# Fabric's three non-strict rows — the corpus/closure side of the raw-rate gap (2026-10-04)

Scope: the fabric random-50 bucket (`w7/corpus/samples/fabric-50.json`, seed `20261003`), na 14,
applicable 36, world 35, strict 33 — raw **33/36 = 92 %**, closure-complete **33/34 = 97 %**. Its three
remaining non-strict rows are `cobblemon_pufferfish_api` (subject-side), `gardnercraft` (dependency-side) and
`beilin-data-portability` (closure exclusion / environment). This report answers one question per row — *from
the real bytes and Modrinth metadata, what does it actually need at run time, and can the **corpus** legitimately
supply it?* — and then hands the three proof boots to `W7Harness`. **After the one legitimate flip (pufferfish):
raw 34/36 = 94 %, closure-complete 34/34 = 100 %.**

**This run moves the raw rate only.** The closure-complete rule (`harness/declared_reached.py` →
`corpus/closure-reached.json` → `bucket_table.closure_complete`) and the denominator are untouched; in
particular `gardnercraft` must not drift back into the closure-complete denominator.

Summary of the verdicts:

| # | subject | what it actually needs | corpus action | flip? |
|---|---|---|---|---|
| 1 | `cobblemon_pufferfish_api_fabric-0.9.1-beta.jar` | Puffish's Skills **and** Cobblemon at run time; its `fabric.mod.json` declares neither | **add both to the closure** (undeclared-but-required; MidnightLib precedent) | expected **yes** |
| 2 | `gardnercraft-2.0.0.jar` | `polymer-core >=0.15.2+1.21.11`; no 1.21.1 Polymer build provides it | none is legitimate | **no** |
| 3 | `beilin-data-portability-fabric-1.21.x-1.2.7.jar` | a privately-issued `apiKey` for `beilin-entry-control` | none is legitimate (closure was already whole) | **no** |

Pre-committed, before the boots ran: **only #1 flips → 34/36 raw (94 %)**, #2 and #3 keep their named errors.
An honest 34/36, not a manufactured 35/36.

---

## 1. `cobblemon_pufferfish_api` — CORPUS FIX (add the two undeclared providers)

### 1.1 What the bytes say it needs

`cobblemon_pufferfish_api_fabric-0.9.1-beta.jar` (`fabric.mod.json`, read from the jar):

```
"entrypoints": { "main": ["com.cobblemon_puffish_skills.api.CobblemonSkillsBridgeFabric"] }
"depends": { "fabricloader": ">=0.18.4", "minecraft": "1.21.1", "fabric": "*" }
```

Its class set references **two** undeclared roots (parsed from the jar's constant pools, not from any manifest):

* **Puffish's Skills** — 15 `net/puffish/skillsmod/api/*` classes (incl. `reward/Reward`, `SkillsAPI`);
* **Cobblemon** — **94** `com/cobblemon/mod/*` classes, plus six mixin targets
  (`BattleRegistry`, `Pokemon`, `PokemonEntity`, `GraalShowdownService`, `CookingPotResultSlot`,
  `BattleInitializePacket$ActiveBattlePokemonDTO$Companion`).

Between them the two roots carry **224 member references** (46 field / 153 method / 25 interface-method); all 224
resolve against the providers added below (215 declared directly, 9 through a supertype — verified by walking the
class hierarchy). Evidence: `evidence/pufferfish-undeclared-providers.txt`.

### 1.2 The recorded first cause (verbatim)

`w7/reports/2026-10-04-recheck-walls/per-mod/run/003-cobblemon_skills_api__fabric/console.log:702-703`, `:726`:

```
[07:06:29] [main/ERROR]: [Forbric/Fabric] main entrypoint of cobblemon_puffish_skills_api failed
java.lang.NoClassDefFoundError: net/puffish/skillsmod/api/reward/Reward
...
Caused by: java.lang.ClassNotFoundException: net.puffish.skillsmod.api.reward.Reward
```

(The same console also shows the Cobblemon mixin targets skipped — `Error loading class: com/cobblemon/...` — because
Cobblemon was absent too; the fabric-gap evidence file named only the Puffish half, but the entrypoint cannot finish
without the Cobblemon half either.)

### 1.3 What was added, and why these versions

| provider | version | why this one | sha256 |
|---|---|---|---|
| `puffish_skills` | **0.19.1** (`puffish_skills-0.19.1-1.21-fabric.jar`) | newest 1.21.1 fabric build of project `hqQqvaa4`; the API surface the subject references is identical across 0.17.3 / 0.18.3 / 0.19.1 (all 15 classes and all 224 member refs present in each), so the newest is the same shape as the contemporaneous one | `3225697016632cce5a3207ae4c1c0f0f19bf135f76ee714c9e46ecabd306b5ac` |
| `Cobblemon` | **1.8.1+1.21.1** | already in the corpus as a dependency of `cobblemon-coop`; verified from bytes that all 94 referenced `com/cobblemon/mod/*` classes and all six mixin targets exist in it (`com/cobblemon/mod/` refs missing = 0). Cobblemon JiJ-bundles its Kotlin/Graal/Truffle libs, so it needs only fabric-api. | `4d90ba6775e3a332aad9d5e49a9f6366f973b3e1a12e0004d22ce21c600eebc5` |

The **subject jar is unchanged** (sha256 `21f2009455d168562dd2fa29608b4a0df7a64b949a4a463a4b58ad05cdce0b2c`), exactly as the
MidnightLib-for-Puzzle precedent added a provider without rewriting the jar.

### 1.4 Documented changes (existing shape)

* `w7/corpus/closure.json` — `cobblemon_pufferfish_api_fabric-0.9.1-beta.jar`:
  `["fabric-api-0.116.17+1.21.1.jar"]` → `["Cobblemon-fabric-1.8.1+1.21.1.jar", "fabric-api-0.116.17+1.21.1.jar",
  "puffish_skills-0.19.1-1.21-fabric.jar"]`; new key `puffish_skills-0.19.1-1.21-fabric.jar` → `["fabric-api-0.116.17+1.21.1.jar"]`.
* `w7/corpus/manifest.json` — appended the dependency row (slug `skills`, project `hqQqvaa4`, version `0.19.1`,
  kind `dependency`, `needed_by: cobblemon_skills_api`, sha1 `b7c0116ebb32c6e47e364e753fc6ef306c951765`,
  644 067 bytes, CDN url from the Modrinth API).
* `w7/corpus/manifest.sha256.json` — added `puffish_skills-0.19.1-1.21-fabric.jar` → sha256 above.
* `w7/corpus/closure-cobblemon-skills-correction.json` — new provenance record in the shape of the 26.2
  `closure-midnightlib-correction.json` (`subject` / `previous` / `corrected` / `reason` + the two providers with
  url, sha1, sha256, and the verbatim console first cause).

Reproducibility note: `harness/closure.py` joins Modrinth's required edges with each jar's own `depends`; pufferfish
declares no such edge for either provider, so a bare re-run of `closure.py` would drop them again. The correction is
therefore carried in `closure.json` **plus** the correction record and this report — the same manual-plus-provenance
form the 26.2 corpus used for MidnightLib. No `graph.modrinth.json` edge was invented (Modrinth genuinely records none).

---

## 2. `gardnercraft` — NO LEGITIMATE FIX (the mismatch is in the subject's own metadata)

### 2.1 What it declares (from its own `fabric.mod.json`)

```
"minecraft": "~1.21",
"polymer-core": ">=0.15.2+1.21.11",
"polymer-resource-pack": ">=0.15.2+1.21.11",
"packet_tweaker": ">=0.6.0"
```

### 2.2 The recorded first cause (verbatim)

`w7/reports/2026-10-03-recheck-frozen/per-mod/run/004-gardnercraft-mod__fabric/console.log:1105-1108`:

```
java.lang.NoSuchMethodError: 'net.minecraft.sounds.SoundEvent eu.pb4.polymer.core.api.other.PolymerSoundEvent.registerOverlay(net.minecraft.sounds.SoundEvent, net.minecraft.core.Holder, java.util.UUID)'
	at forbric/com.gardnercraft.ModSounds.registerReference(ModSounds.java:38) ~[gardnercraft-2.0.0-85ccfca8d710208d.jar:?]
	at forbric/com.gardnercraft.ModSounds.<clinit>(ModSounds.java:25) ~[gardnercraft-2.0.0-85ccfca8d710208d.jar:?]
	at forbric/com.gardnercraft.GardnercraftMod.onInitialize(GardnercraftMod.java:15) ~[gardnercraft-2.0.0-85ccfca8d710208d.jar:?]
```

(The row's stored `cause` was `registry-load`, a downstream symptom: the killed entrypoint leaves
`gardnercraft:trim_pattern/gardnercraft.json` half-registered. The root is the `NoSuchMethodError` above.)

### 2.3 Why no build/version can be re-resolved to fix it

The parent asked the one remaining corpus question directly: **is the resolved build the right one for 1.21.1?**
Checked on Modrinth (per-version metadata, not filenames):

* **Gardnercraft Mod (`jJXL1Bwm`) has exactly one version, ever: 2.0.0**, one file `gardnercraft-2.0.0.jar`
  (published 2026-05-11); a project search returns `total_hits = 1`, so there is no earlier 1.21.1 release and no
  sibling project. The single jar's `game_versions` is `['1.21','1.21.1',…,'1.21.11']` — it *claims* 1.21.1 while
  demanding a 1.21.11 Polymer. The corpus did not pick the wrong file; there is no other file.
* **Polymer (`xGdtZczs`) has no 1.21.1 build that satisfies the declaration.** Its 1.21.1 line ends at `0.9.19+1.21.1`
  (2026-01-11; the eleven 1.21.1 releases are 0.9.9 … 0.9.19). `0.15.2+1.21.11`'s `game_versions` is `["1.21.11"]`
  alone. Byte check of `PolymerSoundEvent` in each:
  * `polymer-core-0.9.19+1.21.1.jar` — methods are `of(...)` ×4, `<init>`, `getPolymerReplacement(...)`; **no
    `registerOverlay`** (verified from the class bytes; also in `../2026-10-04-fabric-gap/evidence/gardnercraft-polymer-mismatch.txt`).
  * `polymer-core-0.15.2+1.21.11.jar` — **has** `registerOverlay` (verified from that jar's bytes).

So the `>=0.15.2+1.21.11` predicate is unsatisfiable on 1.21.1 by construction. This is a subject-side metadata
defect (an over-broad `game_versions` on a jar built against a later Polymer line), not a closure-resolution error,
and it is exactly the case the evidential closure rule was built to name. **No corpus change made; the subject stays
named and stays excluded from the closure-complete denominator.**

---

## 3. `beilin-data-portability` — NO LEGITIMATE FIX (a privately-issued credential)

### 3.1 The closure is already whole

`beilin-data-portability-fabric-1.21.x-1.2.7.jar` declares `fabricloader >=0.18.4 · minecraft >=1.21 ·
java >=21 · fabric-api * · beilin-entry-control >=1.2.6`, and its closure ships `beilin-entry-control` 1.2.7
(`56a40fef…`, depends satisfied) plus `fabric-api-0.116.17+1.21.1.jar` (`79ac44b4…`). Nothing is missing on the
dependency side; the subject itself logs `Beilin Data Portability block recorder configured for Mixin capture` and
reaches its own code.

### 3.2 The recorded first cause (verbatim)

`w7/reports/2026-10-03-bucket-fabric/per-mod/run/003-beilin-data-portability__fabric/console.log:696-698`:

```
[09:53:33] [main/ERROR]: [Forbric/Fabric] main entrypoint of beilin-entry-control failed
java.lang.IllegalStateException: Beilin Entry Control requires an apiKey. Please configure /…/config/beilin-entry-control.json
	at forbric/us.beiyue.beilinentrycontrol.platform.fabric121x.BeilinEntryControl121x.onInitialize(BeilinEntryControl121x.java:42)
```

(with the subject's own warning at `:694`: `Beilin Data Portability cannot start because …/config/beilin-entry-control.json has no valid apiKey.`)

### 3.3 Why a config cannot legitimately be provisioned here

The dependency's documented config (`config/beilin-entry-control.json`, fields read from `ModConfig121x`:
`apiKey`, `baseHost`, `wsBackupDnsHost`, `wsPrimaryProbeIntervalSec`, `useHttps`, `useWss`) does not ask for a
*placeholder* — the mod rejects the shipped default `YOUR_API_KEY_HERE` (`CommonConfig.isApiKeyConfigured`, with
`isBlank`/`trim` guards) and then authenticates against the member-only service `beiyue.us`. The documented
requirement is itself a credential: *“请向 Narek 获取专用的 `apiKey`”* (obtain the key from the operator). There is no
value a hermetic corpus/closure run can supply that is both the documented config and not a fabrication; writing a
made-up key would manufacture the flip, not earn it. **No corpus change made; the row stays named.** Its exclusion
from the closure-complete denominator is the ordinary `dep_status != OK` predicate, not the evidential rule.

---

## 4. Verification (focused only — no JVMs of my own)

`fetch_corpus.py --dry-run` resolves each subject plus its declared closure without downloading:

```
$ python3 harness/fetch_corpus.py --slug cobblemon_skills_api --dry-run
4 jar(s) wanted (subject + closure): 4 already present, 0 to fetch
$ python3 harness/fetch_corpus.py --slug gardnercraft-mod --dry-run
3 jar(s) wanted (subject + closure): 3 already present, 0 to fetch
$ python3 harness/fetch_corpus.py --slug beilin-data-portability --dry-run
3 jar(s) wanted (subject + closure): 3 already present, 0 to fetch
```

and the on-disk set hash-checks clean against the updated sidecar:

| jar | sha256 (first 16) | vs sidecar |
|---|---|---|
| `cobblemon_pufferfish_api_fabric-0.9.1-beta.jar` | `21f2009455d16856` | OK |
| `Cobblemon-fabric-1.8.1+1.21.1.jar` | `4d90ba6775e3a332` | OK |
| `puffish_skills-0.19.1-1.21-fabric.jar` | `3225697016632cce` | OK |
| `fabric-api-0.116.17+1.21.1.jar` | `79ac44b40780acbd` | OK |
| `gardnercraft-2.0.0.jar` | `37dbb1c40d61a37b` | OK (unchanged) |
| `polymer-bundled-0.9.19+1.21.1.jar` | `8c6bb7897bf53e32` | OK (unchanged) |
| `beilin-data-portability-fabric-1.21.x-1.2.7.jar` | `53ee0021a5fd229d` | OK (unchanged) |
| `beilin-entry-control-fabric-1.21.x-1.2.7.jar` | `56a40fef8b08d6fd` | OK (unchanged) |

No subject jar and no subject metadata was modified; only the corpus sidecars and the correction record changed.

## 5. Proof boots (`W7Harness`) — 1 of 3 flipped, as pre-committed

Kernel: corpus-only change, kernel unchanged — HEAD `17dcbc44`; the frozen jar `/tmp/w7-gap-kernel.jar`
(sha256 `e61560cfe979a29233f2982b1842d255bd8cbd23ef35bfc0b2fddbe7818bf279`, built from `7d21924f`) is byte-identical
in kernel code (`git diff 7d21924f..HEAD` touches only `forbric-kernel/run/compat/reports/`). JDK 21 pinned, fresh
cache `/tmp/w7-cache-rawrate`, `--boot-timeout 2400 --boot-stall 1200`, serial quiet boots; every row carries
`java: …/jdk-21.jdk/…/java — java version "21.0.7" 2025-04-15 LTS` and `kernel_sha256: e61560cf…`. The frozen set is
exactly the closure above (`Cobblemon-1.8.1`, `fabric-api-0.116.17`, `puffish_skills-0.19.1`), all four sha256s
re-verified against the updated `manifest.sha256.json` before launch. Rows: `w7/reports/2026-10-04-rawrate/per-mod/results.jsonl`.

| # | subject | run | world | strict | cr | cause | named error |
|---|---|---|---|---|---|---|---|
| 1 | `cobblemon_pufferfish_api_fabric-0.9.1-beta.jar` | PASS | true | **true** | 0 | — | **absent** (0 hits) |
| 2 | `gardnercraft-2.0.0.jar` | FAIL | false | false | 2 | registry-load | **present** |
| 3 | `beilin-data-portability-fabric-1.21.x-1.2.7.jar` | PASS | true | false | 0 | dependency-not-ok | **present** |

Verbatim from the new consoles (`w7/reports/2026-10-04-rawrate/per-mod/run/`):

**1 — flipped.** The two errors the subject used to die on are gone, and both entrypoints — the subject and the
dependency just added — now run:

```
1454: [Forbric/Fabric] invoked main entrypoint of cobblemon_puffish_skills_api
1473: [Forbric/Fabric] invoked main entrypoint of puffish_skills
1769: [Server thread/INFO]: Done (4.939s)! For help, type "help"
```

`grep -c 'NoClassDefFoundError: net/puffish'` = 0, `grep -c 'ClassNotFoundException: net.puffish'` = 0,
`world/level.dat` present, `strict=true`, 358 s.

**2 — did not flip.** First cause still present (`001-gardnercraft-mod__fabric/console.log:1115`):

```
java.lang.NoSuchMethodError: 'net.minecraft.sounds.SoundEvent eu.pb4.polymer.core.api.other.PolymerSoundEvent.registerOverlay(net.minecraft.sounds.SoundEvent, net.minecraft.core.Holder, java.util.UUID)'
```

with its downstream symptom at `:1333` (`IllegalStateException: Failed to parse gardnercraft:trim_pattern/gardnercraft.json`);
no `Done (`, no `world/level.dat`, 54 s.

**3 — did not flip.** First cause still present (`002-beilin-data-portability__fabric/console.log:694`):

```
java.lang.IllegalStateException: Beilin Entry Control requires an apiKey. Please configure <rundir>/config/beilin-entry-control.json
```

`Done (4.827s)!` at `:1001` and `world/level.dat` present, but `strict=false` on `dependency-not-ok`, 56 s.

So the flip count is exactly what was pre-committed — **1 of 3**, pufferfish only → fabric raw **34/36 = 94 %**.
Attribution: the *same* jar that failed this morning with the two puffish errors now boots strict, and no kernel
byte changed, so the flip is attributable to the closure change (the only variable).

## 6. Account

* Legitimate corpus change: 1 of 3 (pufferfish). The other two cannot be moved by the corpus — gardnercraft's declared
  range is unsatisfiable on 1.21.1, and beilin's dependency wants a privately-issued credential (its closure was
  already whole).
* Raw fabric moves 33/36 (92 %) → **34/36 (94 %)**; closure-complete **33/34 (97 %) → 34/34 (100 %)** — pufferfish was
  the one non-strict subject *inside* that denominator, so its flip completes it.
* For context (Main's classification, not a corpus/closure action): the new **`rig-testable`** column — a dependency
  demanding an operator-supplied credential, evidenced by the dependency's own error, the same standard as `na` —
  removes `beilin-data-portability` (1 fabric, 0 neoforge, 0 forge), giving fabric raw **34/36 = 94 %**,
  rig-testable **34/35 = 97 %**, closure **34/34 = 100 %**, with the unadjusted raw rate stated beside it.
* Open/named: `gardnercraft` (subject-side metadata defect; closure-complete exclusion stands, untouched),
  `beilin-data-portability` (environment/credential), both left named by design.
