# 预登记（运行前登记，禁止事后改判据）—— 2026-10-07 `0.3.6-beta` 发布 gate

本文件在**任何**测量运行之前写成。判据一经写下，后续读数只能"达成 / 被证伪"，不得改写。

## 0. 被测量的形状

- **发布候选内核**：`main` 尖端 `13e4ff3f` 构建的客户端 boot jar（带游戏侧，
  `META-INF/jars/forbric-kernel-runtime.jar` 在内），sha256
  `16bcd60242c54890fda728e294ad41935619ff4b499f359f539d6e44dae45b74`。
  装配：干净 worktree `/tmp/rel036/wt`（`git worktree add --detach … 13e4ff3f`）里
  `./gradlew --offline -q jar`（boot 半，734 条目，0 条 `X N.class` 陈旧重名条目），runtime 半**逐字节**取自
  已发布 `0.3.5-beta` 的 `META-INF/jars/forbric-kernel-runtime.jar`（sha256 `67c2a49b…`；`src/runtime` 在
  `b1b90566..13e4ff3f` 上 `git diff --stat` 为空）。合计 735 条目。两臂都钉这只 jar（`--kernel-jar`，未重建、未替换）。
- **arm A（用户真实十二）** `/tmp/rel036/corpus12`：subject `modmenu-11.0.5.jar`（fabric，`kind=popular`），
  closure = 其余 11 只（fabric-api 0.116.17 / JEI 19.57.0.450 / Sodium 0.8.13 / Lithium 0.15.4 /
  FerriteCore 7.0.3 / ModernFix 5.27.24 / EntityCulling 1.11.2 / ImmediatelyFast 1.6.14 / AppleSkin 3.0.9 /
  Cloth Config 15.0.140 / Placeholder API 2.4.2）。12 只 jar 与本机安装实例
  `/Applications/.minecraft/versions/1.21.1-forbric/mods/` **逐字节相同**（sha256 见 `mod-set.txt`）。
- **arm B（十二 + Create）** `/tmp/rel036/corpus13`：subject `create-1.21.1-6.0.10.jar`（neoforge，`kind=popular`），
  closure = 上面那 12 只（同装，非 Create 声明的依赖）。Create jar 取自本机
  `/Applications/.minecraft/versions/1.21.1-NeoForge/mods/create-1.21.1-6.0.10.jar`，sha256
  `ef87fe5709f1ba1f5b8bb20a2925b5afb4669e178fd6d8bf10c167759eefe37a`（与 `2026-10-06-bughunt-create` 同一只）。
  Create 的 required 依赖 `flywheel`/`ponder`（及 Registrate）以 JiJ 内嵌，闭包不添文件。
- **装置**：`W7Harness` 客户端面（`w7/harness/sweep_client.py`），quick-play 进 `W7Client`，
  `-Dforbric.compatibilityPolicy=strict`、`-Dforbric.mixinFit=default`，`W7_JAVA` 钉 JDK 21.0.7，
  stage `/tmp/rel036/stage` -> `/Applications/.minecraft/.forbric-build/out/`，
  MC 根 `/tmp/rel036/mc`（安装实例 `libraries/ assets/ .forbric/mappings/ versions/1.21.1` 的符号链接），
  热 remap 缓存 `/tmp/rel036/remap`（安装实例 `.forbric-kernel/remap` 的副本），
  世界夹具 `corpus/client-world/W7Client`，boot 超时 600 / 停滞 300（只放宽超时，不改判据）。

## 1. 两臂共同的 boot gate 行

- `run=PASS`、`exit=0`、`world=true`、`frames>=1`、`stopped=true`、`killed=false`、`strict=TRUE`
- `compatibility_policy=strict`、`mixin_fit=default`
- `confirmed_required=0`、`loaded=true`、`na=false`、`catalog_failures` 为空、0 份 crash-report、
  闭包里每一只 mod 状态 `OK`
- `joined world via quick-play` >= 1
- `[Forbric/Seed] seeded NeoForge LoadingModList` >= 1

## 2. arm A 上必须仍成立（0.3.5 已发布形状，不得回归）

- **Sodium cutout 降级链（`b1b90566`）**：控制台
  `now targets net.minecraft.client.renderer.texture.SpriteContents.<init>(…ForgeTextureMetadata;)V` 恰 **2** 条；
  `applies only partially … SpriteContents.originalImage` = **0**；
  `load-report.txt` 里提到 `sodium` 或 `SpriteContents` 的行 = **0**。
- **颜色修复（`ad9d5615` 两步一体）**：控制台四条 marker 各 >= 1
  （`BlockColors … (2 site(s) re-keyed to getBlock)`、`ItemColors … (1 site(s) re-keyed to getItem)`、
  `BlockColorsMixin's @Shadow blockColors is an IdMapper`、`ItemColorsMixin's @Shadow itemColors is an IdMapper`）。
- **加载报告聚合面（`37ad693b`）**：`load-report.txt` 的聚合句按状态拆句，本集合读
  `9 个 mod 有一部分没有跑起来`（与 `0.3.5-beta` 发布 gate 同）。

## 3. 本版新增，必须在两臂上成立

- **(a) Create 可加载（arm B；对 `2026-10-06-bughunt-create` 的预登记读数取反）。** 那条预登记预测
  "今天不可加载、`strict=false`"并给了证伪条件；本版预期**该证伪条件成立**：
  `run=PASS`、`world=true`、`frames>=1`、`confirmed_required=0`、`strict=TRUE`、`exit=0`、`mod=OK`、
  `catalog_failures=[]`、`cause=None`；`grep -c "create failed during client setup"` = 0、
  `grep -c "Render layers can only be set"` = 0、`grep -c "ClientModLoader"` 不出现失败行；
  `load-report.txt` 聚合句回到 `9 个 mod 有一部分没有跑起来`（Create 不在其中）。
  arm B 的 12 只闭包全部 `OK`。
- **(b) fabric-particles-v1 的访问器拒绝消失（两臂）**：`grep -c "@Mixin target type mismatch"` = **0**、
  `grep -c "ParticleEngine is not an interface"` = **0**、
  `grep -c "SYNTHETIC default method over ParticleEngine"` >= **1**（预期 2：main + Render thread）。
- **(c) 1.21.1 表新钉行确实发射（两臂）**：控制台 `now targets … HumanoidArmorLayer.renderArmorPiece(…)V` >= **1**。

## 4. 像素副读数（同一夹具、两臂之间可比；与 2026-10-04 的夹具不可比）

三个 ROI 的**纯黑 `(0,0,0)` 占比 = 0.0000**（分类器 `evidence/sampler.py` 逐字相同；ROI 坐标同 `0.3.5` gate）。
本夹具是 corpus 夹具，与 `/private/tmp/sodcut-fixture`（已随 `/private/tmp` 一起消失）不同，故只作
"两臂皆无黑色回归"的不变量，`dirt_brown` 一类绝对值**不与旧周期比较**。

## 5. 证伪与撤回

- 第 1、2、3 节任一不达 → 该条记"**被证伪**"，如实上报，不得改判据、不得用别处读数顶替。
- 出现 `confirmed_required>0` 或任何 crash-report → **撤回候选，不发布**（崩掉的修复比记录在案的缺陷更糟）。
