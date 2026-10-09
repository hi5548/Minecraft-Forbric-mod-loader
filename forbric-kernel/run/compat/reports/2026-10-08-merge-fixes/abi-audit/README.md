# AbiAudit whole-jar false positive — the dropped half of a universal jar (merge-convention audit, finding #5)

**Verdict (EN).** **Fixed, boot-side, verified on the user's real 14-jar set.** `AbiLinkAudit.audit()` read every
class of a mod jar, so a jar that ships one half per loader — the Shooting Star demo carries a 1.20.1 Forge half
(`dev/aek/shootingstardemo/mc1201/...`, naming `net.minecraftforge.client.event.RenderGuiEvent*` and
`net.minecraftforge.network.*`) and a 1.21.1 NeoForge half (`mc1211`) — was marked `DEGRADED` for code
`MultiLoaderArbiter` never loads. The live half names **no** missing class. The audit now asks the arbiter, per
(mod, family), which loader family a jar declares but is not loaded as, and does not judge a dangling name in a
family the jar does not own. Both standalone twins (`abi-audit.py`, `fapi-usage.py`) carry the same rule. On the
14 jars the demo's finding group goes **1 → 0**; no other jar changes (the naive pass finds only the demo). 32
fix-surface tests pass (2 skipped on fixtures this machine lacks).

**答案（中文）。** **已修复，boot 半边，在用户真实的 14 只 mod 上验证过。** `AbiLinkAudit.audit()` 之前逐类读整个
jar，于是"每 loader 半只"的多版本 jar —— 流星 demo 里 1.20.1 Forge 半边（`mc1201/...`，引用
`net.minecraftforge.client.event.RenderGuiEvent*` 与 `net.minecraftforge.network.*`）和 1.21.1 NeoForge 半边
（`mc1211`）—— 会为 `MultiLoaderArbiter` 根本不加载的那半被判 `DEGRADED`；**活的 mc1211 半边一个缺失类都不引用**。
现在审计**按 (mod, family)** 问仲裁：某 jar 声明了、但不是它被加载成的那个 family，则该 family 的悬空名不再判罚；
两个孪生脚本同规则。14 jar 上 demo 的 finding 组 **1 → 0**，其余 jar 不变。修复面 32 条测试全绿（2 条因本机缺夹具
按设计跳过）。

---

## 1. The finding (audit slice D, finding D-2 / overall #5)

`AbiLinkAudit.audit()` (`boot/AbiLinkAudit.java:119`) iterated **every** `.class` of every jar and, for each
Forge-family class name that resolved nowhere across the carriers + merged base + installed jars, added it to the
jar's `Finding`; `report()` (→ `ModCatalog.markByJar(jar, DEGRADED, …)`) then marked **every row of that jar**
DEGRADED. It never asked whether the naming class was in a loader half the kernel actually loads.

Byte evidence (also `2026-10-08-cannot-fire/evidence/dead-half.txt`): only the **dead mc1201 half** of
`the-shooting-star-demo-1.3.1-neoforge.jar` names `net.minecraftforge.*`; the console shows
`[Forbric/MultiLoader] the-shooting-star-demo-1.3.1-neoforge.jar declares 2 loaders — loading it as NEOFORGE only,
suppressing [FORGE]`. The live `mc1211` client entrypoint registers only NeoForge events.

## 2. The fix

One rule, applied wherever a jar's classes are judged: **a dangling Forge-family name is a finding only if its
family is one the jar actually loads.**

| file | change |
|---|---|
| `src/main/java/net/forbric/kernel/boot/AbiLinkAudit.java` | after collecting a jar's `missing` names, remove those whose family (`familyOf`, by `net/minecraftforge/` vs `net/neoforged/`) is in `droppedFamilies(jar)` — the declared families `MultiLoaderArbiter` did not give the jar. A single-manifest (or manifest-less) jar drops nothing, so its dangling names are still judged; the arbiter is asked **only of a jar that already has a finding**, so the common path pays nothing and boot ordering is untouched (`ownerOf` is lazy and idempotent). |
| `run/compat/abi-audit.py` | `dropped_families(archive)` (manifest presence + `DEFAULT_PREFERENCE`, mirroring `MultiLoaderArbiter`'s manifest-level decision) and `family_of(name)`; `scan_classes` now also yields each archive's dropped families — a **nested** `META-INF/jars` mod arbitrates on its own manifests, not its parent's — and `main` skips those references. |
| `run/compat/fapi-usage.py` | consumes the same 4-tuple and skips a reference whose family the archive drops: a dropped loader half is not the live mod consuming the API. |
| `src/test/.../boot/AbiLinkAuditTest.java` | +3 tests (dropped half not a finding; live half still a finding; a family the jar never declares still judged) and arbiter reset around each test. |
| `src/test/.../boot/AbiLinkAuditStagedTest.java` | doc names both false-positive rules (out-of-scope packages; dropped family) it now depends on. |
| `src/test/.../compat/AbiAuditCompatTest.java` | +2 tests: universal jar dropped-half vs single-manifest control; a nested jar arbitrates on its own manifests. |
| `src/test/.../compat/FabricApiUsageCompatTest.java` | +1 test: a dropped loader half is not a consumer of its own family's API (with a Forge-only control). |

## 3. Verification (user's real 14-jar set)

| reading | before | after | evidence |
|---|---|---|---|
| `abi-audit.py` over the 14 jars + 3 carriers | 1 group — demo, 7 names, all `net/minecraftforge/*` from `mc1201` | **0 groups** | `evidence/abi-audit-14-jar-before-after.txt` |
| Java `AbiLinkAudit.audit()` over the same 14 jars (throwaway probe: naive loop vs this branch) | `naive-groups=1` (demo, dropped `[FORGE]`, the same 7 names) | **`arbitration-aware-groups=0`** | `evidence/java-audit-14-jar-before-after.txt` |
| `fapi-usage.py --preset minecraftforge-events` on the demo jar | 1 consumer group (9 mc1201 references) | **0** | `evidence/fapi-usage-demo-before-after.txt` |
| fix-surface tests (`AbiLinkAuditTest`, `AbiLinkAuditStagedTest`, `MultiLoaderEntrypointsTest`, `AbiAuditCompatTest`, `FabricApiUsageCompatTest`) | — | **32 tests, 2 skipped, 0 failed** | `evidence/unit-tests.txt` |

No other row changes: the naive pass over the 14 jars flags **only** the demo. `entityculling-neoforge` also
declares a second family (arbitration drops its FORGE claim) but names **no** missing class, so it has no finding
before or after.

## 4. Boundaries, honestly

- **No client window was run.** This is a boot-side audit; its `DEGRADED` row is exactly what
  `AbiLinkAudit.audit()` returns, so the row was verified by running `audit()` (and both twins) directly on the
  same jars a boot would read, not by a client session. A client run is owned by the harness queue (one at a time).
- **`AbiLinkAuditStagedTest` is skipped here**: its 97-jar staged pack (`run/client-merged-pack/mods`) is absent on
  this machine. Its assertion (0 findings over the staged pack) is unaffected and only strengthened — the pack's
  own universal jars now also lose their dropped half.
- **The twin is manifest-level.** `abi-audit.py` mirrors `MultiLoaderArbiter`'s manifest-presence + preference
  decision; the kernel's initializer refinement (`@Mod` / Fabric entrypoint) can narrow a claim further and is
  deliberately not re-implemented in Python. Where they could disagree, the twin can only drop a family the
  manifest declares — it never drops a family the jar did not declare, so a genuine wrong-Forge single-half jar
  still fires in both.
- **Pre-existing red, not this change:** `CompatProtocolTest.protocolNamesEveryShippedScript` fails at HEAD too —
  PROTOCOL.md does not name 11 already-committed `reports/*/evidence/*.py|sh` scripts. See
  `evidence/known-red-compat-protocol.txt`.

## 5. Evidence index (`evidence/`)

| file | content |
|---|---|
| `abi-audit-14-jar-before-after.txt` | twin output over the 14 jars, HEAD vs this branch |
| `java-audit-14-jar-before-after.txt` | `AbiLinkAudit.audit()` naive vs arbitration-aware over the 14 jars |
| `fapi-usage-demo-before-after.txt` | `fapi-usage.py` on the demo jar, before vs after |
| `unit-tests.txt` | gradle reading for the fix-surface tests |
| `known-red-compat-protocol.txt` | the unrelated, pre-existing `CompatProtocolTest` red at HEAD |
| `diff.patch` | the whole change |
| `build-provenance.txt` | branch/HEAD, changed-file sha256, user-jar and carrier sha256 |
