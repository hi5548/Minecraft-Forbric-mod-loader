# Pre-registration — written BEFORE this lane's first client run

Nothing below was edited after a run; the readings, the arms and the falsification conditions were fixed first, and
the run logs are quoted against them in `README.md`.

## The set (the user's real instance, byte for byte)

`/Applications/.minecraft/versions/1.21.1-forbric/mods`, 14 jars. Every sha256 below equals the one in
`../2026-10-06-shooting-star/evidence/mod-set.txt`:

```
15bf9bd6d8e3ae8ad35c5a4d90680424b534d9762d698c0a466965afcab5dd78  ImmediatelyFast-NeoForge-1.6.14+1.21.1.jar
38b48dd6231341c9f964ce6e42c57ec866c6cc8f72bec938390a59bafb3922df  appleskin-neoforge-mc1.21-3.0.9.jar
65e722e0d98431a07c45f8bdd8d529a217cc8c175fde1740248bd5c1b4f3c0d4  cloth-config-15.0.140-neoforge.jar
3983b981da4a0583c7e58184a8631a23e56cc3b81f4cb53491ca600382879b82  entityculling-neoforge-1.11.2-mc1.21.1.jar
79ac44b40780acbd884b34c50be1e39af682847e5f5cb3b1fddeeaa768dce800  fabric-api-0.116.17+1.21.1.jar
d87ea28262715ebff45b8a82d493e6b468e7a4521bc021df5d88302196d030a8  ferritecore-7.0.3-neoforge.jar
238b20c943bb7c1e4ed387b2d1bc463c656f44ab7c388da1eee148f54d383b52  jei-1.21.1-neoforge-19.57.0.450.jar
488f33216030c1cb0baa33e59de3d657d08d5795a6c2080c8c8bbbc133fc8757  lithium-neoforge-0.15.4+mc1.21.1.jar
e6e9446890f0feb3aab3f6e73ae18cb17575c370f232179fef7baa30e61538fe  modernfix-neoforge-5.27.24+mc1.21.1.jar
afa55fe4e7c48560126dbe654fa9647f141bc6ffd3ce35525b65dd65d88251b5  modmenu-11.0.5.jar
c0187ee299527ac7a3e0b1e83601dcdc0de631e286fe41e6be1a75958ea8e443  placeholder-api-2.4.2+1.21.jar
9b2ce1b10449eb6dcb640eec83c50666c52043f1b47b76cdaa49aba39c24f760  shooting_star_addition-1.1.0.jar
575d187b15328d7bddd8a9b1bee64333c22c14735f5780ec49eac0cd5503c08d  sodium-neoforge-0.8.13+mc1.21.1.jar
8b972bcef153eb78d4ef89f9d3bb2cc3a744e1d830a6a3e54bd120056a1762b0  the-shooting-star-demo-1.3.1-neoforge.jar
```

## Instrument

- **Surface**: `w7/harness/sweep_client.py`, subject
  `the-shooting-star-demo-1.3.1-neoforge.jar` (its closure is the other thirteen, so the run is the whole 14-jar
  instance). **One client run at a time**, no `--limit`/sweep reuse.
- **Window hiding is ON** (the harness default; `hide-window-agent.jar`, whose writer is `COMPUTE_FRAMES` — the
  hiding A/B's fix). `W7_SHOW_WINDOW=1` is the control arm and was not needed: the agent is one variable away and
  this lane changes nothing near it.
- `W7_JAVA=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin/java` — "21.0.7" 2025-04-15 LTS.
- `-Dforbric.compatibilityPolicy=strict` (the harness default).
- stage = `stubtable/stage` (`merged-base/patched-mc-merged-1.21.1.jar` sha
  `fbd531b08c34f430be851faf896410a8df9ae7e266e5cdb6eb376404b2427400` + `forge-runtime-interop.jar` +
  `neoforge-runtime/neoforge-runtime.jar`); mc root = `stubtable/mc`; remap cache = `w7/.artifacts/remap`.
- corpus = `/private/tmp/ss-lane/corpus` (the frozen W7 twelve + the two Shooting Star jars; a manifest row and a
  closure entry added for each new subject).
- timeouts = `--boot-timeout 900 --boot-stall 300` (only the timeouts widened; no criterion changed).

## Arms

| arm | what it is | sha256 |
|---|---|---|
| `before-published` | the jar the user ran (`forbric-kernel-0.3.6-beta.jar`) | `16bcd60242c54890fda728e294ad41935619ff4b499f359f539d6e44dae45b74` |
| `before` | a same-environment rebuild of this tree's HEAD (two files reverted, one class removed) | `2f561650ca30f3a4fe70e176ac3f708551b1650fe9521ed9aac0c27637ed93a0` |
| `after` | HEAD + this lane's two boot-side fixes | `48df3d2b90dfec3926fbfcee92dc6bd8a441760f27560fd7d168169d81e2fa97` |

`before` and `after` are the same build command, the same environment, and differ in exactly one class added
(`net/forbric/kernel/boot/ForgeSidedThreads.class`) and six classes edited (entry-set comparison in
`evidence/build-provenance.txt`). Both carry the published game side byte for byte (nested
`forbric-kernel-runtime.jar` sha `67c2a49b55c8ef860e552ec5b9e028d8836e68d46f5bb4595a40016debde8c6d`, the sha the
user's own extracted runtime jar has).

## Readings, fixed now

From each arm's `console.log`, as literal line counts:

1. `[Forbric/Forge] created <N> custom registr(ies) via NewRegistryEvent` → **before 0, after > 0**.
2. `NewRegistryEvent could not reach` → **before ≥ 1, after 0**.
3. `Failed to apply some object holders` → **before ≥ 1, after 0**; and
   `Unable to find registry with key forge:` → **before ≥ 1, after 0**.
4. `[Forbric/MergedBaseCompat] MinecraftForge's EffectiveSide now reads the merged base's thread groups again` →
   **before 0, after 1**.
5. `lost connection: Illegal packet received` → **predicted 0 in every arm.** No arm drives the Stellar Remote, and
   an unattended client sends no serverbound play custom payload, so Forge's raise site is never reached. A 0 here
   is this lane reporting a non-reproduction, not a failed fix; the fix's evidence is the bytecode and the
   real-carrier probe, not this row.
6. `run` / `world` / `frames` → **PASS / true / 1 in every arm** (nothing may regress into a boot failure).

## Falsification

- `after` printing `created 0`, or `NewRegistryEvent could not reach`, or an object-holder exception → fix A does
  not do what it claims, and the reading is reported unchanged.
- `after` printing the EffectiveSide line 0 times → fix B did not land in the jar that ran.
- Any arm failing to join / draw a frame / disconnect cleanly → the arm measured nothing and the lane says so.
