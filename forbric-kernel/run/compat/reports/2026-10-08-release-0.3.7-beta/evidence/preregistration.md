# 预登记（任何测量运行之前写成；判据一经写下，只可"达成 / 被证伪"，不得改写）—— 2026-10-08 `0.3.7-beta` 发布 gate

## 0. 被测量的形状

- **发布候选内核**：`main` 尖端 `f9d4719b`（版本提交 `4f4935b9`，二者源码相同：版本号不进 boot 字节）构建的
  客户端 boot jar（带游戏侧 `META-INF/jars/forbric-kernel-runtime.jar`），sha256
  `48df3d2b90dfec3926fbfcee92dc6bd8a441760f27560fd7d168169d81e2fa97`，736 条目，0 条 `X N.class` 陈旧重名条目。
  装配：干净 worktree `/tmp/rel037/wt`（`git worktree add --detach … 4f4935b9`）里 `./gradlew --offline -q jar`
  （**JDK 25**，见下），runtime 半**逐字节**取自已发布 `0.3.6-beta` 的
  `META-INF/jars/forbric-kernel-runtime.jar`（sha256 `67c2a49b55c8ef860e552ec5b9e028d8836e68d46f5bb4595a40016debde8c6d`；
  `git diff --stat b1b90566 HEAD -- forbric-kernel/src/runtime` 为空 ⇒ 游戏侧与本版无关）。
  三臂都钉这只 jar（`--kernel-jar`，未重建、未替换）。
- **构建 JDK 的澄清（写在前头）**：本机默认 `java` 是 21.0.7，但 0.3.6-beta 的 boot 半是 **JDK 25** 编的
  （用 JDK 25 重编 `13e4ff3f` 逐字节复现 0.3.6 记录的 boot 半 sha `30cef190…`；用 JDK 21 则 121 个类不同）。本版
  因此固定 JDK 25 编译，且 boot 半 sha 与 `2026-10-06-shooting-star-disconnect` 车道独立构建的 `after` 臂
  （`48df3d2b…`）逐字节相同 —— 两个不同 worktree、同一源码、同一结果。

- **arm A（用户真实十二）** `corpus12`：subject `modmenu-11.0.5.jar`（fabric），closure = 其余 11 只。
  12 只与本机安装实例 `versions/1.21.1-forbric/mods/` 逐字节相同（sha256 见 `evidence/mod-set.txt`）。
- **arm B（十二 + Create 6.0.10）** `corpus13`：subject `create-1.21.1-6.0.10.jar`（neoforge），
  closure = 那 12 只。Create jar 取自 `/Applications/.minecraft/versions/1.21.1-NeoForge/mods/`，
  sha256 `ef87fe5709f1ba1f5b8bb20a2925b5afb4669e178fd6d8bf10c167759eefe37a`（与 0.3.6 gate 同一只）。
- **arm C（十二 + 两只 Shooting Star）** `corpus14`：subject `the-shooting-star-demo-1.3.1-neoforge.jar`，
  closure = 其余 13 只（12 + `shooting_star_addition-1.1.0.jar`）。两只 SS jar 与用户实例逐字节相同
  （demo `8b972bce…`、addition `9b2ce1b1…`，与 `2026-10-06-shooting-star-disconnect` 车道同一对）。

- **装置**：`W7Harness` 客户端面（`w7/harness/sweep_client.py`），quick-play 进 `W7Client`，
  `-Dforbric.compatibilityPolicy=strict`、`-Dforbric.mixinFit=default`，`W7_JAVA` 钉 JDK 21.0.7，
  stage `stubtable/stage`（merged-base sha `fbd531b0…` = 安装实例 `.forbric-build/out/patched-mc-merged-1.21.1.jar`），
  mc 根 `stubtable/mc`，热 remap 缓存 `/tmp/rel037/remap`（实例 `.forbric-kernel/remap` 的副本，49 条目），
  世界夹具 `corpus/client-world/W7Client`，boot 超时 600 / 停滞 300。
  **窗口隐藏 agent 开着**（`W7_SHOW_WINDOW` 未设 → javaagent 注入 + OS 级 hide 都请求）。一次只跑一臂。

## 1. 三臂共同的 boot gate 行

- `run=PASS`、`exit=0`、`world=true`、`frames>=1`、`stopped=true`、`killed=false`、`strict=TRUE`
- `compatibility_policy=strict`、`mixin_fit=default`
- `confirmed_required=0`、`loaded=true`、`na=false`、`catalog_failures` 为空、0 份 crash-report、
  闭包里每一只 mod 状态 OK（arm C 的 subject 例外，见 §6）
- `joined world via quick-play: W7Client` >= 1、`[Forbric/Seed] seeded NeoForge LoadingModList` >= 1

## 2. 本版 Fix A —— Forge 自定义注册表（三臂都必须成立）

- `[Forbric/Forge] created 9 custom registr(ies) via NewRegistryEvent` = 1（恰 9，不是 0）
- `NewRegistryEvent could not reach` = 0
- `Failed to apply some object holders` = 0
- `Unable to find registry with key forge:` = 0
  （对照，非本版：0.3.6-beta 在同样的 14 只集合上读出 `created 0`、`could not reach` 1 次、
  `Unable to find registry with key forge:` 26 次、holder `RuntimeException` 1 次 —— 见
  `2026-10-06-shooting-star-disconnect` 车道 `out-published` 臂。）

## 3. 本版 Fix B —— EffectiveSide（如实预登记"运行里观察不到"）

- `lost connection: Illegal packet received` = 0（三臂）
- **预登记为"预期不被观测"**：`[Forbric/…] MinecraftForge's EffectiveSide now reads` = 0。
  理由：无人值守客户端不处理任何 serverbound play 自定义包，`EffectiveSide` 从不被加载，改写从不触发。
  该条的证明是真载体字节探针（见 shooter 车道 `evidence/effective-side-probe.txt`），**不是**本次运行。

## 4. arm A 上必须仍成立（0.3.5/0.3.6 已发布形状，不得回归）

- **Sodium cutout 降级链**：`now targets … SpriteContents.<init>(…ForgeTextureMetadata;)V` 恰 **2** 条；
  `applies only partially … SpriteContents.originalImage` = **0**。
- **颜色修复**：四条 marker 各 >= 1（`(2 site(s) re-keyed to getBlock)`、`(1 site(s) re-keyed to getItem)`、
  两个 `@Shadow … is an IdMapper`）。
- **加载报告聚合面**：`load-report.txt` 的聚合句按状态拆句；本集合读
  `N 个 mod 有一部分没有跑起来`（`N` 为读出值，0.3.5/0.3.6 的 12 只集合读 9）。

## 5. arm B（Create 6.0.10 与这 12 只共存）

- `mod=OK`、`cause=None`、`catalog_failures=[]`、13 只闭包全 OK、0 份 crash-report。
- `grep -c "create failed during client setup"` = 0、`grep -c "Render layers can only be set"` = 0。

## 6. arm C（Shooting Star，subject 的 DEGRADED 是既有状态）

- demo 与 addition 都可加载、进世界；`strict=TRUE`、`confirmed_required=0`。
- **预登记**：subject `shooting_star_demo` 的 `mod` 读 **DEGRADED**、`cause=mod-degraded` —— 这是该 jar 的
  **既有**状态（`2026-10-06-shooting-star` 与 `-disconnect` 两条车道在 0.3.5/0.3.6 上都读到同一个），
  不是本版回归；`DEGRADED` 不是 confirmed-required，故 `strict` 仍应为 TRUE。
- addition 与其余 12 只状态 OK。

## 7. 像素副读数（同一夹具、三臂之间可比）

三个 ROI（草方块侧面 / 蒲公英 / 树叶）的**纯黑 `(0,0,0)` 占比 = 0.0000**（分类器逐字相同
`evidence/sampler.py`）。只作"三臂皆无黑色回归"的不变量，绝对值不与旧周期比较。

## 8. 证伪与撤回

- 第 1..7 节任一不达 → 该条记"**被证伪**"，如实上报，不改判据、不用别处读数顶替。
- 出现 `confirmed_required>0` 或任何 crash-report → **撤回候选，不发布**。
- 一条例外按 0.3.5/0.3.6 惯例预登记：`[Forbric/EventMux] handleServerStopped forward failed`（night-config
  `SimpleCommentedConfig`→`CommentedFileConfig` 的 `ClassCastException`）是**第三条、独立、非本版的**缺陷
  （4 arms of the shooter lane 全部 1 次），本版不修，出现 1 次不算回归。
