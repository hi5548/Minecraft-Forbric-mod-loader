# 2026-10-08-fix-payload-damage — pre-registration (written before any probe/test run)

Two BugHunt5 items, each fixed in its own commit, each with an offline probe and one W7Harness run.
This file is written before the probe or the arm runs; nothing below is edited afterwards. Adjudication is
appended at the end, in its own section, so the criteria stay readable as written.

Base: branch `fix-payload-damage` off `main` `4b565f01`, own worktree `../payload-damage-wt`.

---

## R1 — the `enqueueWork(Supplier)` overload keeps NeoForge's inline same-thread shortcut

Decision carried: **widen the existing repair to the returning overload** (BugHunt5 R1's smallest honest fix).
The replacement (`POP; GOTO <submit>`) is identical for both bodies because both push a boolean and fall through
an inline path to a `submit(...)` tail.

### Offline probe — the real `ClientPayloadContext` out of `neoforge-runtime.jar`, no JVM launch

Expected **before** the fix (red): `enqueueWork(Supplier)` still carries
`INVOKEVIRTUAL ReentrantBlockableEventLoop.isSameThread()Z` immediately followed by a conditional jump, and the
shipped transformer changes the class only through its `enqueueWork(Runnable)` body.

Expected **after** the fix (green):
1. neither overload carries an `isSameThread`-then-conditional-jump;
2. each replaced `IFEQ` is a `POP` + `GOTO` landing on that method's own submit block
   (`ReentrantBlockableEventLoop.submit` + `NetworkRegistry.guard`);
3. the whole transformed class still links under the JVM verifier (the test's `URLClassLoader` gate), and the
   carrier's `enqueueWork(Supplier)` resolved out of the transformed bytes is edited.

Falsifier: `-Dforbric.payloadWorkOrdering=off` leaves the class byte-for-byte unchanged.

### W7Harness run (client arm, one)

`--only modmenu --corpus corpus-user12` on a kernel pinned to this commit. Pre-committed reading:
`run=PASS`, `exit=0`, `world=true`, `strict=TRUE`, `frames=1`, `confirmed_required=0`, `crash-reports/*.txt` count 0,
no `Network Protocol Error` (the harness fails the row on that marker), `joined world via quick-play: W7Client`,
`client-ready after 200 world tick(s)`, `requesting clean disconnect after 220 world tick(s)`.

**Stated limit, because it bounds what this reading means:** a constant-pool scan says a mod *mentions*
`enqueueWork`; it cannot tell the returning `Supplier` form from the `Runnable` one. So this arm proves
**"the widened repair does not break a join"**, not **"the returning form was exercised in a session"**. The
firing half (the `Supplier` call site actually deferring) is the offline probe, not the run.

---

## A1 — Forge damage seams anchored on 26.2 shapes, so they never place on 1.21.1

Decision carried: **add the 1.21.1 shapes** (`actuallyHurt(DamageSource,float)V`, `Player.hurt(DamageSource,float)Z`)
and read the seam slots from the class instead of hardcoding the 26.2 ones, keeping the 26.2 shapes working.

### Offline probe — the real merged 1.21.1 `LivingEntity` / `Player`, no JVM launch

Expected **before** the fix (red): the injector hands both classes back byte-identical (the 26.2 descriptors match
nothing), which is the boot log's `[Forbric/Anchor] forbric-forge-damage-seams … made no edit — its anchor is gone`.

Expected **after** the fix (green), in `LivingEntity.actuallyHurt` **and** `Player.actuallyHurt`:
1. the call order is `isInvulnerableTo, hurt, getDamageAfterArmorAbsorb, onLivingDamagePre, setAbsorptionAmount,
   damage, setHealth` (Hurt after the guard and before armour; Damage replacing the health the body applies);
2. the `KernelLivingDamage.damage` call's next instruction is an `FSTORE` of the local the body applies;
3. `playerAttack` sits at the head of `Player`'s hurt entry (`hurt` on 1.21.1, `hurtServer` on 26.2), before
   difficulty scaling and the zero-damage return; `Player.<clinit>` calls `notePlayerSeam` exactly once and
   `LivingEntity.<clinit>` does not;
4. `BasicVerifier` passes on the edited methods, and a second pass changes nothing.

Falsifiers: the MinecraftForge-patched base (already calls `ForgeHooks.onLivingHurt` / `onLivingDamage` /
`onPlayerAttack`) is left byte-identical; `-Dforbric.forgeDamageSeams=off` leaves the merged class unchanged.

### W7Harness run (client arm, one)

`--only modmenu --corpus corpus-user12` on the same pinned kernel. Pre-committed reading, both directions:
- **absent:** `[Forbric/Anchor] forbric-forge-damage-seams was handed net.minecraft.world.entity.LivingEntity and
  made no edit — its anchor is gone…` and the same for
  `net.minecraft.world.entity.player.Player`;
- **present:** `[Forbric/Anchor]   forbric-forge-damage-seams on net.minecraft.world.entity.LivingEntity:
  MinecraftForge…` and the `player.Player` line.

**Stated limit:** the harness cannot drive combat (no input, no mob, no attack), so **"a LivingHurt/LivingDamage
listener actually fires" is not drivable by any run** — the anchor pair is the run's reading, and the firing half
is the offline shape probe (the seams are placed at MinecraftForge's own 1.21.1 positions, verified against the
MinecraftForge-patched base's bytecode).

---

## Commit discipline

One commit per decision: R1 (source + its shape test), A1 (source + its shape test), then the report/evidence.

---

## Adjudication (appended after the runs; the criteria above are untouched)

### Offline probes — both items, achieved

- **R1.** `evidence/probe-red.txt`: `enqueueWork(Supplier)` after `= PRESENT (defect)`, `POP+GOTO = none`.
  `evidence/probe-green.txt`: both overloads `none`, each `POP+GOTO` lands on that method's own
  `ReentrantBlockableEventLoop.submit(...)` block, `BasicVerifier: OK`, the transformed class links and
  initialises under the JVM verifier, and `-Dforbric.payloadWorkOrdering=off` is byte-identical. Transformed
  class sha256: baseline `d79b547a115e5a0d28023268af49c954904764fb04ea19fde99b7cdd56613686` →
  fixed `9db0660b848c0cecd1b1dda10996ef96dd4ecfeba2ffa88ab89bd1a0946ac5ac`.
- **A1.** `evidence/probe-red.txt`: both classes `edited = false`. `evidence/probe-green.txt`: LivingEntity
  `97f671cc… → d68408edc782dcd1cca0cd2e0279bac7faa016f49fe444558077d1445167fa12`, Player
  `f2a813a6… → 29786b13b318f934eb7bd35b99664f930156f40231feacd6d1639f8ec0d62c28`; call order
  `[isInvulnerableTo, hurt, getDamageAfterArmorAbsorb, onLivingDamagePre, setAbsorptionAmount, damage, setHealth]`;
  damage seam followed by `FSTORE 5`; both transformed classes link under the JVM verifier; `Player.<clinit>`
  `notePlayerSeam = 1`, LivingEntity `= 0`; MinecraftForge base and `=off` byte-identical; second pass byte-identical.
- **Module shape tests** (`evidence/module-tests-{red,green}.txt`, run directly because Gradle's `test` task needs the
  absent game-side fixture): `pass=3 fail=3` → `pass=6 fail=0 skip=0`.

### W7Harness arms — first adjudication (SUPERSEDED by the section below: the volume was remounted and the arms ran)

> Kept as written at the time. The pinned fixture was later found on the remounted ORICO volume and W7Harness built
> a real kernel from `975774d8`; see “W7Harness arms — run” at the end. Nothing below is a claim about the final
> result.

Both arms are blocked on the kernel's game-side build. It needs `energy-4.1.0-named.jar`, pinned at
sha256 `cec89d1c2e1d1eed9a43ccf60a049668900232c9ef7387a32dd670d20cd297d3` (`build.gradle:91`), and that file is
absent from the machine (it lived on the ORICO volume, no longer mounted). The absence is checked three ways: no
file named `energy-4.1.0*`, `*reborn*energy*` or `*TechReborn*` exists under the repo tree, `/private/tmp` or
`/Applications/.minecraft` (name search); W7Harness searched every lane worktree, `/private/tmp` and the repo for the
file and for the sha and reported the same; and the only compiled runtime classes on disk
(`Minecraft-Forbric-mod-loader/forbric-kernel/build/classes/java/runtime`) are dated Oct 4, before the four runtime
commits that the pinned fixture would have compiled. W7Harness measured the failure on a clean detached worktree at
`975774d8`: `:verifyRebornEnergy` fails in seconds; it will not hand a sha from a mixed tree.

The two workarounds do not apply:

- a boot-half-only build (the conditional-task route) leaves the bundled runtime half empty;
- injecting the released runtime half is not legitimate at this commit — `git diff --stat v0.3.8-beta-1.21.1
  975774d8 -- forbric-kernel/src/runtime` is 4 files (`KernelFabricConditions`, `KernelPacketContext`,
  `BlockTransferBridge`, `RebornEnergyBridge`), and so is the same comparison against `v0.3.4`–`v0.3.7`; no released
  tag matches. The only compiled runtime classes on disk
  (`Minecraft-Forbric-mod-loader/forbric-kernel/build/classes/java/runtime`) are dated Oct 4, before those four
  commits, so they are stale too.

**World-depth verification impossible today: the pinned build fixture is absent from the machine.** The gate is not
weakened and no jar is frozen from a different tree. Both arms stay preregistered as written and will run within
minutes of `energy-4.1.0-named.jar` appearing at `<kernel>/.dev/api/` (W7Harness already holds a clean worktree at
`975774d8`).

---

## W7Harness arms — run (supersedes the section above; the criteria at the top are untouched)

The pinned fixture was found on the remounted ORICO volume; W7Harness verified its sha itself
(`cec89d1c…`, matching `build.gradle:91`) and built a **real** kernel from `975774d8` — sha256
`c6e47ce91c6658a31669afee06657b68918d01631e6fd4c1614eb0108fcbd594`, 3,291,447 B, embedded runtime half 535,542 B
(so `compileRuntimeJava` and `:verifyRebornEnergy` both ran; not a borrowed half). Stage `.stage-orico`, guest
mappings `--mc .stage-scratch/mc`, window hiding on, one client at a time.

### R1 — MET

Row: `run=PASS exit=0 world=true frames=1 stopped=true killed=false strict=TRUE confirmed_required=0 cause=null
seconds=46`. Reading fields: `joined world via quick-play: W7Client` ×1, `client-ready after 200 world tick(s)` ×1,
`requesting clean disconnect after 220 world tick(s)` ×1, `crash-reports/*.txt` 0, no `lost connection`/`Disconnected`
/`Game crashed` markers.

The written criterion "no `Network Protocol Error`" needed adjudication, and it is **met on intent**: the string
occurs 2 times, and both occurrences are the **fix's own** `[Forbric/PayloadOrdering]` line
(`… enqueueWork now submits its work unconditionally — the inline same-thread path ran payload work before the client
level existed, which is how the data-map sync died with "Network Protocol Error"`), i.e. the repair quoting the
failure mode it removes. There is no occurrence of any other shape. Qualifier recorded for future readers: **absent
outside `[Forbric/PayloadOrdering]` lines**.

### A1 — boot reading MET via the landed path

First arm (`--only modmenu`, `corpus-user12`): the anchor pair read 0/0, which was **misread**. `corpus-user12`
suppresses `[FORGE]` for every subject (its Forge claims are manifest-only), but that is not why the pair was empty:
the string I had pinned as MUST-BE-PRESENT — `[Forbric/Anchor]   forbric-forge-damage-seams on <class>: …` — is the
**miss** listing, emitted only inside the `else` of `if (r.clean())` (`transform/TransformChain.java:207-212`), i.e.
the form a *declining* kernel prints. W7Harness built that string from a console where it appeared without checking
which branch emitted it and handed it to me as the working-fix observable, so the criteria as written could not be
satisfied by a correct outcome. This is recorded as **W7Harness's error, not the fix's**; the criterion is unchanged.

The landed form is `[Forbric/Damage] <class>: MinecraftForge's … back in NeoForge's damage pipeline`. On `975774d8`:

- **`forbric-forge-damage-seams … made no edit — its anchor is gone`: 0** (MUST-BE-ABSENT met);
- **`[Forbric/Damage]` landed lines: 2, both classes** —
  `net.minecraft.world.entity.LivingEntity: MinecraftForge's Hurt and Damage in actuallyHurt …` and
  `net.minecraft.world.entity.player.Player: MinecraftForge's Hurt and Damage in actuallyHurt, player Attack in hurt …`;
- anchor summary `68 of 201 declared repair(s) landed` with **no damage-seam miss**.

Second arm (`cherishedworlds-forge-10.1.1+1.21.1` — screened as a genuine Forge jar: `META-INF/mods.toml`, no
`neoforge.mods.toml`/`fabric.mod.json`, plus three Forge deps as closure): the Forge family was genuinely active
(`[Forbric/Catalog] 4 installed mod(s) … 0 Fabric, 0 NeoForge, 4 MinecraftForge`), row `run=PASS exit=0 world=true
frames=1 strict=TRUE`. Same landed reading.

Cross-kernel contrast (same rig/corpus/stage; only the kernel differs): `16bcd602` → 0 landed / 2 miss;
`117edc56` → 0 landed / 2 miss; `975774d8` → **2 landed / 0 miss**. That is what makes the landed reading a repair
rather than a coincidence.

**Limits (unchanged):** the rig cannot drive combat, so A1 proves the seam **placed at MinecraftForge's 1.21.1
position**, not that a listener fired; R1 proves "the widened repair does not break a join", not that the returning
overload was exercised in a session. `corpus-user12` has grown past its name; both arms ran `--only modmenu`.
