# 2026-10-08-fix-payload-damage — BugHunt5 R1 and A1, fixed on bytes

**Verdict (EN).** Both BugHunt5 items are fixed, each in its own commit, each verified by a no-JVM offline probe
over the **real** carrier bytes (red before, green after); both client arms were preregistered to the letter before
any run, but they are **blocked today by a missing build fixture** (§5) and are reported as such rather than
worked around. **R1** (`31da5fa1`): `PayloadWorkOrderingTransformer` now removes the `isSameThread()` inline
shortcut from **both** `enqueueWork` overloads — the `Runnable` one and the returning `Supplier` one — so a
clientbound-PLAY handler that defers through `ctx.enqueueWork(() -> value)` can no longer run its work before
`ClientPacketListener.handleLogin` created the level. **A1** (`975774d8`): `ForgeDamageSeamsInjector` now reads the
carrier's shape instead of hardcoding 26.2's, so on 1.21.1 its Hurt, Damage and player-Attack seams place in
`actuallyHurt(DamageSource,float)` and `Player.hurt(DamageSource,float)` and MinecraftForge's damage events are
delivered again.

**答案（中文）。** BugHunt5 的两条都已修，各自一个提交，各自先用**无 JVM** 的离线探针在**真**载体字节上验红再验绿；
两个客户端臂的读数在任何运行之前都已逐字预登记，但**今天被缺失的构建夹具挡住**（§5），如实记录而不绕行。
**R1**（`31da5fa1`）：两个 `enqueueWork` 重载（`Runnable` 与返回值的 `Supplier`）的同线程捷径都摘掉。
**A1**（`975774d8`）：`ForgeDamageSeamsInjector` 改为从类里读形状；1.21.1 上接缝落进
`actuallyHurt(DamageSource,float)` 与 `Player.hurt(DamageSource,float)`。

Base: branch `fix-payload-damage` off `main` `4b565f01`, own worktree `../payload-damage-wt`. Method: read-only
`javap` of the real carrier, then the shipped transformers run over those exact classes by an offline probe. No game
launch was done by this lane; the two client arms are preregistered and owned by W7Harness, and their status (blocked
on an absent build fixture) is in §5.

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

## 5. Pre-registration and the W7Harness arms — blocked today

`evidence/preregistration.md` was written before the probe or any arm ran; nothing in it is edited afterwards
(adjudication is appended). It states the two expected readings and, so a passing row is not read as more than it
measured: a constant-pool scan cannot tell the `Supplier` from the `Runnable` overload, so the R1 arm proves
**"the widened repair does not break a join"**, and the harness cannot drive combat, so the A1 arm proves the
**anchor pair** (the absence of `made no edit — its anchor is gone` plus the positive per-class line) while the
**firing** half is the offline shape probe. The arms are owned by W7Harness, one client arm per item.

**Outcome: impossible today, stated rather than worked around.** Both arms are blocked on the kernel's game-side
build. It needs `energy-4.1.0-named.jar`, pinned at sha256 `cec89d1c…` (`build.gradle:91`), and that file is absent
from the machine — it lived on the ORICO volume, now unmounted. W7Harness measured `:verifyRebornEnergy` failing in
seconds on a clean detached worktree at `975774d8`. Neither workaround is honest here: a boot-half-only build leaves
the bundled runtime half empty, and injecting the released runtime half fails its own gate — `git diff --stat
v0.3.8-beta-1.21.1 975774d8 -- forbric-kernel/src/runtime` is 4 files (`KernelFabricConditions`,
`KernelPacketContext`, `BlockTransferBridge`, `RebornEnergyBridge`), as it is against `v0.3.4`–`v0.3.7`, so no
released tag matches; and the only compiled runtime classes on disk are dated before those four commits. **World-depth
verification impossible today: the pinned build fixture is absent from the machine.** No gate was weakened and no
jar was frozen from a different tree; the arms stand preregistered and run within minutes of the file appearing at
`<kernel>/.dev/api/`.

## 6. Limits

- No client run: the two arms are blocked on the missing fixture (§5). The transformer/injector were run over the
  real classes by the offline probe, which is what is claimed here.
- `./gradlew --offline test` is not runnable on this machine for the game-side suite (the same missing fixture); the
  two affected test classes were run directly instead (§4).
- The R1 arm, once run, cannot show the returning overload being *exercised in a session* (§5); the offline probe is
  the half that sees the two overloads separately.

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
