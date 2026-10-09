# 2026-10-08-fix-payload-damage — BugHunt5 R1 and A1, fixed on bytes

**Verdict (EN).** Both BugHunt5 items are fixed, each in its own commit, each verified by a no-JVM offline probe
over the **real** carrier bytes (red before, green after), and each then run once on W7Harness's client rig against
a kernel built from this commit (sha `c6e47ce9…`). **R1 is a STRICT PASS** (join reaches a world; no `Network
Protocol Error` failure). **A1's boot reading is MET via the landed path** (both damage classes edited, no
`made no edit`, while two other kernels show the miss) — the written criteria's "MUST-BE-PRESENT" string turned out
to be the *miss* form and is corrected in the adjudication, not by rewriting them. **R1** (`31da5fa1`):
`PayloadWorkOrderingTransformer` now removes the `isSameThread()` inline shortcut from **both** `enqueueWork`
overloads — the `Runnable` one and the returning `Supplier` one — so a clientbound-PLAY handler that defers through
`ctx.enqueueWork(() -> value)` can no longer run its work before `ClientPacketListener.handleLogin` created the
level. **A1** (`975774d8`): `ForgeDamageSeamsInjector` now reads the carrier's shape instead of hardcoding 26.2's,
so on 1.21.1 its Hurt, Damage and player-Attack seams place in `actuallyHurt(DamageSource,float)` and
`Player.hurt(DamageSource,float)` and MinecraftForge's damage events are delivered again.

**答案（中文）。** BugHunt5 的两条都已修，各自一个提交，各自先用**无 JVM** 的离线探针在**真**载体字节上验红再验绿，
随后各自在本提交构建的内核（sha `c6e47ce9…`）上由 W7Harness 的客户端装置跑了一次：**R1 严格通过**（进世界、
无 `Network Protocol Error` 失败）；**A1 的启动读数通过「生效路径」**（两具伤害类都被编辑、没有
`made no edit`，而另外两个内核显示的是 miss）。预订判据里那行 "MUST-BE-PRESENT" 字符串其实是 **miss** 形态，
在裁决里更正，而不是改判据。**R1**（`31da5fa1`）：两个 `enqueueWork` 重载的同线程捷径都摘掉。
**A1**（`975774d8`）：`ForgeDamageSeamsInjector` 改为从类里读形状；1.21.1 上接缝落进
`actuallyHurt(DamageSource,float)` 与 `Player.hurt(DamageSource,float)`。

Base: branch `fix-payload-damage` off `main` `4b565f01`, own worktree `../payload-damage-wt`. Method: read-only
`javap` of the real carrier, then the shipped transformers run over those exact classes by an offline probe, then one
W7Harness client arm per item on a kernel built from this commit (§5).

---

## 1. Decisions and commits

| commit | what | why it is one decision |
|---|---|---|
| `31da5fa1` | R1: accept both `enqueueWork` descriptors in the same loop (source + shape test). | Widening the repair to the returning overload is a semantic decision of its own; the replacement is identical for both bodies, so the change is confined to the match. |
| `975774d8` | A1: accept both damage-pipeline shapes and derive the seam slots from the class (source + shape test). | Reading the shape from the carrier, and moving the Damage anchor from a hardcoded slot to the local the body applies, is the decision; everything downstream (`KernelLivingDamage`) is untouched. |
| `本报告提交` | Report + evidence + pre-registration. | — |

Nothing else changed: `git diff --stat 4b565f01 HEAD -- forbric-kernel/src/main` is two files.

## 2. R1 — bytes first

`javap -c -p` on the real `neoforge-runtime.jar` (`evidence/javap-payload.txt`) shows **both** overloads carry the
shortcut and both branch to a `submit(...)` tail:

```
enqueueWork(Runnable)   9: isSameThread()Z  12: ifeq 26  15: run()  25: areturn   26: submit(Runnable)…guard
enqueueWork(Supplier)   9: isSameThread()Z  12: ifeq 25  15: Supplier.get()  24: areturn   25: submit(Supplier)…guard
```

The shipped match was one descriptor. The fix accepts `(Ljava/lang/Runnable;)…` and
`(Ljava/util/function/Supplier;)…`; the `POP; GOTO submit` replacement is the same for both, so the change is the
match and nothing else (javadoc updated, no longer says the `Supplier` form is "deliberately NOT touched").

Offline probe (`evidence/probe-red.txt` → `evidence/probe-green.txt`), real class, no JVM:

```
baseline: enqueueWork(Supplier)  after: isSameThread-then-conditional-jump = PRESENT (defect); POP+GOTO = none
fixed:    enqueueWork(Supplier)  after: isSameThread-then-conditional-jump = none
         POP+GOTO lands on [ … ReentrantBlockableEventLoop.submit:(Ljava/util/function/Supplier;)… ]
         BasicVerifier OK; class links + initialises under the JVM verifier;  -Dforbric.payloadWorkOrdering=off → byte-identical
```

SHA-256 of the transformed class: baseline `d79b547a…` (5455 B) → fixed `9db0660b…` (5474 B); input `7f6c5bfd…`.

## 3. A1 — bytes first (and the anchor BugHunt5 named is the wrong one)

`javap -c -p` on the real `patched-mc-merged-1.21.1.jar` (`evidence/javap-merged-121.txt`): the seams' 26.2
descriptors match nothing — the merged `LivingEntity.actuallyHurt` is `(DamageSource,float)V`, `Player` has
`hurt(DamageSource,float)Z`, and there is **no `hurtServer`**. That is the boot log's
`[Forbric/Anchor] … made no edit — its anchor is gone`.

**Correction to BugHunt5's lead, from the bytes.** BugHunt5 §A1 named "the single
`CommonHooks.onLivingDamagePre` + `getNewDamage(); fstore_3`" as the Damage anchor. On the 1.21.1 body there are
**two** `getNewDamage(); <fstore>` pairs — `fstore_3` right after `onLivingDamagePre` (the damage *before*
absorption is taken out), and `fstore 5` after `setAbsorptionAmount`, which is the value the body actually applies
(`getHealth() - fload 5 → setHealth`, and `recordDamage`). The applied one is the later local, so the seam is
anchored there; `fstore_3` would have rewritten a value the method then reduces. `evidence/javap-forge-121.txt`
confirms the same position is MinecraftForge's: Forge 1.21.1 calls `onLivingDamage` immediately before the `fstore`
that feeds `recordDamage`/`setHealth`, and `onPlayerAttack` is the **first** three instructions of `Player.hurt`
(`aload0; aload1; fload2; invokestatic ForgeHooks.onPlayerAttack`) — byte-for-byte the head the seam now emits.

Offline probe (`evidence/probe-red.txt` → `evidence/probe-green.txt`):

```
baseline: LivingEntity edited=false ; Player edited=false   (anchor gone on this carrier)
fixed:    LivingEntity 97f671cc… → d68408ed…   Player f2a813a6… → 29786b13…
          actuallyHurt(DamageSource,F)V  call order =
            [isInvulnerableTo, hurt, getDamageAfterArmorAbsorb, onLivingDamagePre, setAbsorptionAmount, damage, setHealth]
          damage seam then FSTORE 5 ; BasicVerifier OK ; <clinit> notePlayerSeam = 0 (Living) / 1 (Player)
          player hurt entry = hurt(DamageSource,F)Z, head = [aload0, aload1, fload2, playerAttack]
          second pass byte-identical ; MinecraftForge base byte-identical ; =off byte-identical
```

The 26.2 shapes are kept: the guard is read from the `isInvulnerableTo` call's own descriptor, and the slots follow
from the descriptor, so a 26.2 carrier takes the same path (`HURT_DESC`/`SERVER_DESC` unchanged).

## 4. The permanent shape tests

`PayloadWorkOrderingTransformerTest` now runs its assertions for **both** descriptors, and
`ForgeDamageSeamsInjectorTest` reads the staged base by version (`TestFixtures.mergedBase()` /
`stagedJar("forge-patched", …)`) and looks the seams up in whichever shape the carrier has — it no longer names
`patched-mc-merged-26.2.jar`, which is why it was skipping on this 1.21.1-only machine.

Gradle's `test` task pulls in the game side and refuses without the pinned Team Reborn Energy jar, which this
machine does not have; so the tests were run directly by `evidence/ModuleTestRunner.java` against the same staged
jars (JUnit used only for its annotations and `TestAbortedException`):

| | red (baseline) | green (fixed) |
|---|---|---|
| `evidence/module-tests-red.txt` / `-green.txt` | `pass=3 fail=3` (the three A1/R1 shape assertions) | `pass=6 fail=0 skip=0` |

## 5. Pre-registration and the W7Harness arms — both run, both adjudicated

`evidence/preregistration.md` was written before the probe or any arm ran; nothing in it is edited afterwards (the
adjudication is appended). It states the two expected readings and, so a passing row is not read as more than it
measured: a constant-pool scan cannot tell the `Supplier` from the `Runnable` overload, so the R1 arm proves
**"the widened repair does not break a join"**, and the harness cannot drive combat, so the A1 arm proves the
**anchor** reading while the **firing** half is the offline shape probe. The arms are owned by W7Harness, one client
arm per item.

**Outcome.** Both arms ran on W7Harness's rig (`--stage .stage-orico`, `--mc .stage-scratch/mc`, window hiding on),
on a real kernel built from `975774d8`: sha256
`c6e47ce91c6658a31669afee06657b68918d01631e6fd4c1614eb0108fcbd594`, 3,291,447 B, embedded runtime half 535,542 B
built against the verified `energy-4.1.0-named.jar` (`cec89d1c…`), so **both halves are this commit's source** —
not a borrowed runtime half.

| arm | row | reading | verdict |
|---|---|---|---|
| **R1** (`--only modmenu`, `corpus-user12`) | `run=PASS exit=0 world=true frames=1 stopped=true killed=false strict=TRUE confirmed_required=0 seconds=46` | `joined world via quick-play: W7Client`; `client-ready after 200 world tick(s)`; `requesting clean disconnect after 220 world tick(s)`; `crash-reports/*.txt` 0; no `lost connection`/crash markers. `Network Protocol Error`: 2 occurrences, **both** inside the fix's own `[Forbric/PayloadOrdering]` line quoting the failure mode it removes — 0 as a failure. | **MET** |
| **A1** (`cherishedworlds-forge-10.1.1`, a real `mods.toml`-only Forge subject + its three Forge deps) | `run=PASS exit=0 world=true frames=1 strict=TRUE confirmed_required=0` | Forge family genuinely active: `[Forbric/Catalog] … 0 Fabric, 0 NeoForge, 4 MinecraftForge`. Landed path: `[Forbric/Damage] net.minecraft.world.entity.LivingEntity: MinecraftForge's Hurt and Damage in actuallyHurt back in NeoForge's damage pipeline` and the `player.Player … player Attack in hurt …` line — **both classes edited**. `forbric-forge-damage-seams … made no edit`: **0**. Anchor summary `68 of 201 declared repair(s) landed`, **no damage-seam miss**. | **MET** |

The A1 landed reading is also in the R1/modmenu console (the seams are placed on the **merged** classes at boot,
independent of the subject's ecosystem): `[Forbric/Damage]` appears there too, and its anchor summary carries no
damage-seam miss.

**Cross-kernel contrast — what makes it a repair and not a coincidence** (same rig, stage and corpus; only the
kernel differs; evidence `w7-cross-kernel.txt`):

| kernel | `[Forbric/Damage]` landed lines | damage-seam miss entries (classes declined) |
|---|---|---|
| `16bcd602` | 0 | 2 |
| `117edc56` | 0 | 2 |
| **`975774d8`** | **2** (forge arm; **4** in the `--only modmenu` arm) | **0** |

The seams are placed on the merged classes at boot, so every arm carries the landed reading regardless of subject
ecosystem; the `--only modmenu` console shows 4 landed lines (two per class across two transform passes) and the
forge arm 2. The transformer *name* `forbric-forge-damage-seams` appears only in miss lines, which is why a grep for
it reads 0 on a working kernel and 4 on a declining one (2 `made no edit` per-class lines + 2 miss-list lines).

**Adjudication of the written criteria — corrected in the adjudication, not by rewriting them.**

- **R1.** The literal `Network Protocol Error` occurs only inside the fix's own log line (both occurrences are that
  one line, on `[Forbric/PayloadOrdering]`, describing the failure it prevents). The criterion's intent — the error
  does not occur — is met; the qualifier for future readers is **"absent outside `[Forbric/PayloadOrdering]`
  lines"**.
- **A1.** The string I pinned as MUST-BE-PRESENT, `[Forbric/Anchor]   forbric-forge-damage-seams on <class>: …`, is
  the **miss** listing, emitted only inside the `else` of `if (r.clean())` (`transform/TransformChain.java:207-212`),
  i.e. it is what a *failing* kernel prints. W7Harness built that string from a console where it appeared without
  checking which branch emitted it and handed it to me as the thing a working fix should produce, so the written
  criteria could not be satisfied by a correct outcome. The landed form is
  `[Forbric/Damage] <class>: MinecraftForge's … back in NeoForge's damage pipeline`. This is recorded as the reason
  the criteria's two halves disagree **and as W7Harness's error, not the fix's**; the criterion is unchanged. The
  A1-forge arm was chosen only after screening the subject's metadata (`META-INF/mods.toml`, no
  `neoforge.mods.toml`/`fabric.mod.json`) and confirming the Forge family was active — the condition was not
  assumed.

## 6. Limits

- No combat is drivable by the rig (no input, no mob, no attack), so A1 proves the **seam placed at MinecraftForge's
  1.21.1 position** (the landed lines, no miss), **not that a `LivingHurt`/`LivingDamage` listener fired**; the
  firing half is the offline shape probe, and that limit is unchanged.
- The R1 arm proves **"the widened repair does not break a join"**, not **"the returning `enqueueWork(Supplier)`
  overload was exercised in a session"** — a constant-pool scan cannot tell the two overloads apart. The offline
  probe is the half that sees them separately.
- `corpus-user12` has grown past its name (another lane added subjects); both arms still ran `--only modmenu`, so
  the rows are unaffected.
- `./gradlew --offline test` is not runnable on this machine for the game-side suite without the energy fixture; the
  two affected test classes were run directly instead (§4).

## 7. Evidence (`evidence/`)

| file | content |
|---|---|
| `preregistration.md` | **before any run**: what each item must read, the falsifiers, the stated limits |
| `DamagePayloadProbe.java` | the offline probe: runs both shipped transformers over the real carrier bytes |
| `ModuleTestRunner.java` | runs the two shape tests without Gradle's game-side task |
| `probe-red.txt` / `probe-green.txt` | probe output before/after (per-overload shape, call order, verifier, hashes, falsifiers) |
| `module-tests-red.txt` / `module-tests-green.txt` | the two shape tests: `pass=3 fail=3` → `pass=6 fail=0 skip=0` |
| `javap-payload.txt` | both `enqueueWork` overloads from `neoforge-runtime.jar` |
| `javap-merged-121.txt` | merged 1.21.1 `LivingEntity.actuallyHurt` and `Player.hurt` |
| `javap-forge-121.txt` | MinecraftForge 1.21.1's own seam positions (the reference the seams reproduce) |
| `w7-arm-r1.txt` / `w7-r1-results.jsonl` | W7Harness ARM R1 on kernel `c6e47ce9…`: the row, the console lines, the `Network Protocol Error` occurrences (both the fix's own line) |
| `w7-arm-a1-forge.txt` / `w7-a1-forge-results.jsonl` | W7Harness ARM A1-forge (`cherishedworlds-forge`): the `[Forbric/Damage]` landed lines, the anchor summary, the Forge-active proofs |
| `w7-arm-a1-modmenu.txt` / `w7-a1-modmenu-results.jsonl` | the `--only modmenu` arm — same landed reading (the seams are placed on the merged classes at boot); the `0` for the transformer *name* is the miss-form count, not "not visited" |
| `w7-cross-kernel.txt` | the contrast (`16bcd602`/`117edc56` 0 landed / 2 miss vs `975774d8` 2 landed / 0 miss) |
