# 预登记（任何测量臂跑之前写成；判据一经写下，只可“达成 / 被证伪”，不得改写）—— 2026-10-09 `0.3.9-beta` 发布 gate

## 0. 被测量的形状

- **发布候选内核**：`main` 尖端 `289af0b8`（版本提交；其父 `5aec8773` 为 `fix-payload-damage` 车道的回填，
  再往上依次是六条修车道的合并提交）。**全量内核**（boot 半 + 游戏侧 runtime 半，后者本次**从源码编译**，
  不再借道已发布版本）。客户端 boot jar sha256：

  ```
  c9c6abea9f81e9adf3ee363e53473067400749d25c3ace3f0b593cd594441cc8
  ```

  **737 条目**（699 文件 + 38 目录），**0 条** `X N.class` 陈旧重名条目，3297731 B；内嵌 runtime 半
  sha256 `ffcffd1106a3e015a9a3a8892e31505a1ad91d953429262ac7eda02f8c4132c4`（535542 B，292 条目）。

- **装配（与 0.3.5–0.3.8 同一装配，本版首次补全游戏侧）**：干净 worktree `/tmp/rel039/wt-rel`
  （`git worktree add --detach … 289af0b8`）里，`w7/harness/build-kernel.sh`（`./gradlew --offline -q clean jar`），
  **JDK 25.0.1**，staged root `/tmp/rel039/stage`，`-Pforbric.fabricApi` / `-Pforbric.rebornEnergy` /
  `-Pforbric.mcLibraries` 指向恢复后的夹具（见下）。boot 半与游戏侧同一次构建产出。

- **恢复的 energy 夹具（本版与 0.3.8 唯一的构建输入差别）**：0.3.8 那版游戏侧**不可编译**——`energy-4.1.0-named.jar`
  丢失（`build.gradle:91` 钉 sha `cec89d1c…`），故 0.3.8 的 runtime 半是从 0.3.7 逐字节借来的。ORICO 卷重挂后
  夹具复现：`/Volumes/ORICO/forbric/p0/p0/fixtures/energy-4.1.0-named.jar` sha256
  `cec89d1c2e1d1eed9a43ccf60a049668900232c9ef7387a32dd670d20cd297d3`（= 钉值）。本版因此是**全量内核**：
  游戏侧 `src/runtime`（含 G8/G12/G13 三条一次性闸修复触到的 `KernelPacketContext`、`BlockTransferBridge`、
  `RebornEnergyBridge`、`KernelFabricConditions`）从源码编出。

- **入口集（entry set）相对已发布 0.3.8-beta 的逐条目 sha256 比较**（解压后内容，非 zip 字节）：
  added **1** = `net/forbric/kernel/boot/GuestClassScan.class`（sync-perf R3 折叠四条启动扫描的新类）；
  removed **0**；differing **26**（25 个 boot 类 + nested runtime.jar），逐条为七条车道的源码改动所覆盖
  （`AbiLinkAudit`、`FabricApiModuleLossAudit`、`FieldDriftAudit`、`KernelBoot`、`KernelForgeWrapperSync`、
  `KernelRegistryRevert`、`MergedBaseUncalledMethods`、`MixinFit`、`RegistrySyncParityInjector`〔sync-perf〕；
  `PassiveSeeder`〔seeder〕；`KernelBusSupport`、`KernelFabricLoader`、`KernelLanguageAdapters`、`ClientShutdown`
  〔guards，0.3.8 早于它们〕；`ForbricMergedBaseCompatTransformer`〔merged-base〕；`ForgeDamageSeamsInjector`、
  `PayloadWorkOrderingTransformer`〔payload-damage〕；nested runtime 半 = guards 的四条 runtime 修复）。
  证据：`evidence/build-provenance.txt`。

- **staged 输入（三臂与构建共用）**：

  | 输入 | sha256 |
  |---|---|
  | `merged-base/patched-mc-merged-1.21.1.jar` | `46af05299bafd3140321b25246138def88dd4f938b9ae9451f976b57bd1c587f` |
  | `merged-base/forge-runtime-interop.jar` | `0f53e530b978c1c822249c3d6d0ef6528f6ee55743e275e16bdea9a22fbd5d0b` |
  | `neoforge-runtime/neoforge-runtime.jar` | `3d862fd92a42efddca77ba3d97d7a53229b4386b9fa8b4bdf6f8ffa01d1d0494` |
  | `forge-runtime/forge-runtime.jar`（仅编译用） | `dd64a728ee69eaeffd3c575ca50ed23b16575aabb5a44981616a2e53d56b8d44` |
  | `fabric-api-0.116.17+1.21.1-named.jar` | `221986a81c0d3d30c5f0b0e1bf7b1f4ff7a29ee4f04971115082e9c2f092193a` |
  | `energy-4.1.0-named.jar` | `cec89d1c2e1d1eed9a43ccf60a049668900232c9ef7387a32dd670d20cd297d3` |

- **三臂语料**：arm A = 用户真实 12（`w7/corpus-user12`，subject `modmenu-11.0.5.jar`，manifest sha
  `c667884318a1359d…`）；arm B = 同一 12 + Create 6.0.10（`/tmp/rel039/corpus13`，subject
  `create-1.21.1-6.0.10.jar`，manifest sha `15eb503b302ce908…`）；arm C = 同一 12 + 两只 Shooting Star
  （`/tmp/rel039/corpus14`，subject `the-shooting-star-demo-1.3.1-neoforge.jar`，manifest sha `0c7b0d0476441071…`）。

- **装置**：`W7Harness` 客户端面（`w7/harness/sweep_client.py`），quick-play 进 `W7Client`，
  `-Dforbric.compatibilityPolicy=strict`、`-Dforbric.mixinFit=default`，`W7_JAVA` 钉 JDK 21.0.7，
  stage `/tmp/rel039/stage`，mc 根 `stubtable/mc`，remap 缓存 `/tmp/rel039/remap`
  （`w7/.artifacts/remap` 的只读副本），世界夹具 `corpus-user12/client-world/W7Client`，boot 超时 600 / 停滞 300。
  三臂都 `--kernel-jar /tmp/rel039/release-kernel.jar`（未重建、未替换）。一次只跑一臂。

## 1. 三臂共同的 boot gate 行

- `run=PASS`、`exit=0`、`world=true`、`frames>=1`、`stopped=true`、`killed=false`
- `compatibility_policy=strict`、`mixin_fit=default`、`confirmed_required=0`、`catalog_failures=[]`、
  0 份 crash-report、闭包里每一只 mod 状态 `OK`（arm C 的 subject 例外，见 §5）
- `joined world via quick-play: W7Client` >= 1、`[Forbric/ClientSmoke] clean disconnect observed` >= 1

`strict` 预登记：arm A = **TRUE**、arm B = **TRUE**、arm C = **FALSE**（`strict` 判据含“subject 与每只依赖状态 `OK`”，
而 subject `shooting_star_demo` 是既有 `DEGRADED`；0.3.5–0.3.8 每版预登记 arm C 为 FALSE 且读数一致）。

## 2. 本版七条修车道的新 marker（必须在 console 里看到；每一条都是“生效形态”）

| # | 车道 | marker（console 逐字前缀） | 预登记读数 |
|---|---|---|---|
| 1 | payload-damage R1 | `[Forbric/PayloadOrdering] ` | >= 1（`enqueueWork` 两重载都覆盖；引述行除外无 `Network Protocol Error`） |
| 2 | payload-damage A1 | `[Forbric/Damage] ` | >= 2（两具类落地）；`[Forbric/Anchor] forbric-forge-damage-seams … made no edit` = **0** |
| 3 | sync-perf R2 | `[Forbric/RegistrySync] flushing the Forge-wrapped registries' staged ids at 1 return(s) of net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager.apply` | >= 1（1.21.1 入口类；0.3.8 实例日志里 **0** 次） |
| 4 | sync-perf R3 | `[Forbric/AbiAudit] scanned ` | >= 1 且只有 **一条**（四条扫描已折叠） |
| 5 | sync-perf R4 | `guest accessor mixin ` … ` cannot bind` | **0**（出厂 0.3.8 实例日志 **56** 行） |
| 6 | seeder S3 | `[Forbric/Seed] seeded Forge 52's FMLLoader.loadingModList with ` | >= 1，且携带 mod 数、read-back 同数（非 empty 形态） |
| 7 | merged-base #1 | `rekeyTheForgeRenderLayerRegistration` 的 `re-keyed to …` 行 | 只在“该 jar 走 Forge 重载”时出现；语料 0 个走该重载 ⇒ 预登记 **0**（行为半由离线探针证明，见报告） |
| 8 | abi-audit #5 | `[Forbric/AbiAudit] ` 的 finding 行 | 用户 14 jar 上 **0** 组（demo 的被丢弃半边不再判罚） |

> 说明（不预判读数）：#3 的字符串是探针实测的落地点；#7 预登记为 0 是如实预登记“语料里没有走该 Forge 重载的 mod”，
> 不是“没修”。

## 3. 必须仍成立（0.3.5–0.3.8 已发布形状，不得回归）

- **Forge 自定义注册表（0.3.7 Fix A）**：`[Forbric/Forge] created 9 custom registr(ies) via NewRegistryEvent` 恰 **9**；
  `NewRegistryEvent could not reach` = **0**、`Failed to apply some object holders` = **0**、
  `Unable to find registry with key forge:` = **0**。
- **Sodium cutout 降级链**：`now targets … SpriteContents.<init>(…ForgeTextureMetadata;)V` 恰 **2**；
  `applies only partially … SpriteContents.originalImage` = **0**。
- **颜色修复**：四条 marker 各 >= 1（`(2 site(s) re-keyed to getBlock)`、`(1 site(s) re-keyed to getItem)`、
  两个 `@Shadow … is an IdMapper`）。
- **fabric-particles-v1 接口 mixin**：`@Mixin target type mismatch` = 0、`ParticleEngine is not an interface` = 0、
  `SYNTHETIC default method over ParticleEngine` = 2。
- **0.3.8 的服务端 PLAY 分发修复**：`Unknown addon` = 0、`lost connection` = 0。
- **加载报告聚合面**：`load-report.txt` 存在。

## 4. arm B（Create 6.0.10 与这 12 只共存）

- `mod=OK`、`cause=None`、`catalog_failures=[]`、13 只闭包全 `OK`、0 份 crash-report。
- `create failed during client setup` = 0、`Render layers can only be set` = 0。

## 5. arm C（Shooting Star，subject 的 DEGRADED 是既有状态）

- demo 与 addition 都可加载、进世界；`confirmed_required=0`。
- 预登记：subject `shooting_star_demo` 的 `mod` 读 **DEGRADED**、`cause=mod-degraded`、
  `catalog_failures=['shooting_star_demo']`、`loaded=false`、`strict=FALSE` —— 是**既有**状态
  （0.3.5–0.3.8 都读到同一个），不是本版回归；addition 与其余 12 只 `OK`。

## 6. 证伪与撤回

- §1..§5 任一不达 → 该条记“**被证伪**”，如实上报，不改判据、不用别处读数顶替。
- 出现 `confirmed_required>0` 或任何 crash-report → **撤回候选，不发布**。
- 一条例外按 0.3.5–0.3.8 惯例预登记：`[Forbric/EventMux] handleServerStopped forward failed`
  （night-config `SimpleCommentedConfig`→`CommentedFileConfig` 的 `ClassCastException`）是**第三条、独立、非本版的**
  缺陷，本版不修，出现 1 次不算回归。
- 三条 harness 行都可能带 rule 3 的机器负载提示（`contended=true`）；本车道没有时限判据，如实记下。

---

## §Adjudication

*（在全部臂跑完之后追加；以上判据原文未改）*
