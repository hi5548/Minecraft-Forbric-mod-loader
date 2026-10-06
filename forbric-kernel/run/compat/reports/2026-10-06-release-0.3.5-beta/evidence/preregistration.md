# 预登记（运行前登记，禁止事后改判据）—— 2026-10-06 `0.3.5-beta` 发布 gate

本文件在**任何**测量运行之前写成。判据一经写下，后续读数只能"达成 / 被证伪"，不得改写。

## 0. 被测量的形状

- **发布候选内核**：`main` 尖端 `b1b90566`（`修复 Sodium cutout 降级链…`）构建的客户端 boot jar（带游戏侧，
  `META-INF/jars/forbric-kernel-runtime.jar` 在内），sha256
  `a56bf626e983b1dda81cf1b15f5c7f485eaec9c13cb099f1d96ef1d136a6e721`。
  与 `2026-10-04-sodium-cutout` 车道的修复臂**同一字节**（同一 sha）—— 本 gate 是在发布路径上对该字节的另一次独立运行。
- **集合**：用户真实十二 mod（subject `modmenu-11.0.5.jar` (fabric)；closure = fabric-api 0.116.17 / JEI /
  Sodium 0.8.13 / Lithium / FerriteCore / ModernFix / EntityCulling / ImmediatelyFast / AppleSkin /
  Cloth Config / Placeholder API），`/private/tmp/repro-corpus`。
- **装置**：`W7Harness` 客户端面（`w7/harness/sweep_client.py`），quick-play 进 `W7Client`，
  `-Dforbric.compatibilityPolicy=strict`，`-Dforbric.mixinFit=default`，热 remap 缓存 `/private/tmp/tint-remap`，
  JDK 21（`W7_JAVA`），stage `/private/tmp/tint-stage`，MC 根 `/private/tmp/tint-mc`，
  世界夹具 `--world-source /private/tmp/sodcut-fixture`，boot 超时 600 / 停滞 300（只放宽超时，不改判据）。

## 1. 预登记的 boot gate 行（与 `0.3.4-beta` 发布 gate 同形，只多"本版新增落地点"）

- `run=PASS`、`exit=0`、`world=true`、`frames>=1`、`stopped=true`、`killed=false`、`strict=TRUE`
- `compatibility_policy=strict`、`mixin_fit=default`
- `confirmed_required=0`、`loaded=true`、`na=false`、11 只依赖全部 `OK`、`catalog_failures` 为空、0 份 crash-report
- `joined world via quick-play` ≥ 1
- `[Forbric/Seed] seeded NeoForge LoadingModList` ≥ 1

## 2. 本版三处修复在本 artifact 上必须仍成立

- **Sodium cutout 降级链（`b1b90566`）**：
  - 控制台 `now targets net.minecraft.client.renderer.texture.SpriteContents.<init>(…ForgeTextureMetadata;)V` 恰 **2** 条；
  - 控制台 `applies only partially … SpriteContents.originalImage` = **0** 条；
  - `load-report.txt` 里提到 `sodium` 或 `SpriteContents` 的行 = **0**。
- **颜色修复（`ad9d5615`，两步一体）**：控制台四条 marker 各 ≥ 1：
  - `BlockColors looked its colours up through a Forge registry delegate … (2 site(s) re-keyed to getBlock)`
  - `ItemColors looked its colours up through a Forge registry delegate … (1 site(s) re-keyed to getItem)`
  - `BlockColorsMixin's @Shadow blockColors is an IdMapper …`
  - `ItemColorsMixin's @Shadow itemColors is an IdMapper …`
- **加载报告聚合面（`37ad693b`）**：`load-report.txt` 的聚合句按状态拆句（零 `FAILED` 的集合不再被说成"没有完成加载"）。
  本集合仍可能出现 `[Forbric/Load] N mod(s) did not finish loading` 并**如实记录**，它不阻断 `strict`。

## 3. 像素副读数（与 `2026-10-04-sodium-cutout` 同夹具、同分类器；只在控制臂逐字复现时可比）

- 三个 ROI 的**纯黑 `(0,0,0)` 占比 = 0.0000**（草方块侧面 `(1180,455,1240,725)` / 蒲公英格 `(640,440,860,690)` /
  树叶格 `(1290,420,1450,560)`），分类器 `evidence/sampler.py` 逐字相同。
- 本夹具（`/private/tmp/sodcut-fixture`）是 `val7-control` 那次运行的副本，其控制臂预登记读数为
  草方块侧面 `black 0.9327` / 蒲公英 `0.8516` / 树叶 `0.6279`；若控制臂读数漂移，本条记为**不可判**并如实写。

## 4. 证伪与撤回

- 第 1、2 节任一不达 → 该条记"**被证伪**"，如实上报，不得改判据、不得用别处读数顶替。
- 出现 `confirmed_required>0` 或任何 crash-report → **撤回候选，不发布**（崩掉的修复比记录在案的缺陷更糟）。
