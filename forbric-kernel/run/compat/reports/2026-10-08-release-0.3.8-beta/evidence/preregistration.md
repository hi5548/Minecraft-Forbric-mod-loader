# 预登记（任何测量运行之前写成；判据一经写下，只可"达成 / 被证伪"，不得改写）—— 2026-10-08 `0.3.8-beta` 发布 gate

## 0. 被测量的形状

- **发布候选内核**：`main` 尖端 `66200a3e`（版本提交 `2a9e6938`，二者源码相同：版本号不进 boot 字节）构建的
  客户端 boot jar（带游戏侧 `META-INF/jars/forbric-kernel-runtime.jar`），sha256

  ```
  97d894f5946829d46a163e179cc71508424bb8d4bf21ae2534acafa555d6780d
  ```

  **736 条目**（735 boot + 1 nested runtime），**0 条** `X N.class` 陈旧重名条目。
  装配：干净 worktree `/tmp/rel038/wt`（`git worktree add --detach … 2a9e6938`）里
  `./gradlew --offline -q jar`（**JDK 25**，见下）→ boot 半 sha256
  `b9e19aa30ab9e7fde2ab06d6caf404141e052ad2bf549e25813d989bdd5e3e01`（735 条目，无 nested runtime，按设计）；
  runtime 半**逐字节**取自已发布 `0.3.7-beta` 的 `META-INF/jars/forbric-kernel-runtime.jar`
  （sha256 `67c2a49b55c8ef860e552ec5b9e028d8836e68d46f5bb4595a40016debde8c6d`）；`pack.py` 注入，合计 736 条目。
  三臂都钉这只 jar（`--kernel-jar`，未重建、未替换）。

- **入口集（entry set）相对已发布 0.3.7-beta 的逐条目 sha256 比较**（解压后内容，非 zip 字节）：
  added `[]`、removed `[]`、differing **恰 1** =
  `net/forbric/kernel/transform/CommonNetworkInteropInjector.class` ——
  正是 `git diff --stat v0.3.7-beta-1.21.1 HEAD -- forbric-kernel/src/main` 列出的**唯一**源文件（62+/22-）。
  其余每一条 boot 条目、以及整个 `src/runtime` 游戏侧都逐字节相同。

- **与 sibling 车道的一致性**：本 jar 与 `2026-10-08-play-payload-dispatch` 车道独立构建的 `kernel-playpay.jar`
  **逐字节相同**（同一 sha `97d894f5…`）—— 两个 worktree、同一源码、同一结果。

- **构建 JDK 的澄清（写在前头）**：本机默认 `java` 是 21.0.7，但 0.3.6/0.3.7 的 boot 半都是 **JDK 25** 编的
  （0.3.7 已记录：用 JDK 25 重编 `13e4ff3f` 逐字节复现 0.3.6 的 boot 半 sha；JDK 21 有 121 个类不同）。本版因此
  同样固定 **JDK 25**（`25.0.1`）编译，使入口集 delta 是**源码**而非编译器噪声。

- **arm A（用户真实十二）** `//private/tmp/rel038/corpus12`：subject `modmenu-11.0.5.jar`（fabric），
  closure = 其余 11 只。12 只与本机安装实例 `versions/1.21.1-forbric/mods/` 逐字节相同。
- **arm B（十二 + Create 6.0.10）** `corpus13`：subject `create-1.21.1-6.0.10.jar`（neoforge），closure = 那 12 只。
- **arm C（十二 + 两只 Shooting Star）** `corpus14`：subject `the-shooting-star-demo-1.3.1-neoforge.jar`，
  closure = 其余 13 只（12 + `shooting_star_addition-1.1.0.jar`）。
  三个 corpus 与 `0.3.7-beta` gate 相同（manifest sha256：12 `74f33c52…`、13 `375fed43…`、14 `014c68a1…`）。

- **装置**：`W7Harness` 客户端面（`w7/harness/sweep_client.py`），quick-play 进 `W7Client`，
  `-Dforbric.compatibilityPolicy=strict`、`-Dforbric.mixinFit=default`，`W7_JAVA` 钉 JDK 21.0.7，
  stage `stubtable/stage`（merged-base sha256 `46af0529…` = 安装实例 `.forbric-build/out/patched-mc-merged-1.21.1.jar`；
  forge 半 `forge-runtime-interop.jar` sha `0f53e530…`，neoforge 半 `3d862fd9…`），mc 根 `stubtable/mc`，
  热 remap 缓存 `/tmp/rel038/remap`（`/tmp/rel037/remap` 的副本，49 条目），世界夹具 `corpus/client-world/W7Client`，
  boot 超时 600 / 停滞 300。**窗口隐藏 agent 开着**（`W7_SHOW_WINDOW` 未设 → javaagent 注入 + OS 级 hide 都请求）。
  一次只跑一臂。

> 记在明面上的一处输入变化：`0.3.7-beta` 的三臂跑在 merged-base `fbd531b0…` 上（当时 `stubtable/stage` 的内容）；
> 本版跑在 `46af0529…` 上（= 现在 `stubtable/stage` 与安装实例 `.forbric-build/out` 的内容，也是
> `cannot-fire`/`play-payload-dispatch` 两条车道用的那一份）。合并基底不是本版内核的输入，本版内核相对 0.3.7 只差
> 一个类；但**若** arm A 行与 0.3.7 基线在判定字段上不同，必须按这条输入变化归因，不得算作内核回归，也不得事后
> 改判据。

## 1. 三臂共同的 boot gate 行

- `run=PASS`、`exit=0`、`world=true`、`frames>=1`、`stopped=true`、`killed=false`
- `compatibility_policy=strict`、`mixin_fit=default`
- `confirmed_required=0`、`catalog_failures` 为空、0 份 crash-report、闭包里每一只 mod 状态 `OK`
  （arm C 的 subject 例外，见 §5）
- `joined world via quick-play: W7Client` >= 1、`[Forbric/Seed] seeded NeoForge LoadingModList` >= 1

`strict` 预登记：arm A = **TRUE**、arm B = **TRUE**、arm C = **FALSE**（`strict` 判据包含"subject 与每一只依赖状态
`OK`"，而 subject `shooting_star_demo` 是既有 `DEGRADED`；0.3.7 曾预登记 arm C 为 TRUE 并被读数证伪，本次照实预登记）。

## 2. 本版改动（`play-payload-dispatch`）的预登记

本版唯一源码改动：`CommonNetworkInteropInjector.letNeoForgePayloadsThrough` 的 PLAY 阶段 fall-through 不再
`invokespecial ServerCommonPacketListenerImpl.handleCustomPayload`（借道超类），改为 `invokestatic
NetworkRegistry.handleModdedPayload(ServerCommonPacketListener, ServerboundCustomPayloadPacket)`；并
`playFallThroughEnabled()` **默认打开**（`-Dforbric.playPayloadFallThrough=off` 仍可关，留作证伪臂）。

- **预登记为"本次三臂预期不观测到该路径"**：无人值守客户端不发出任何 serverbound PLAY 自定义载荷
  （cast 需输入注入，harness 没有），故 `neo owns …`（"handing the play payload to its dispatcher"）行 = **0**、
  `Unknown addon` = **0**、`lost connection` = **0**。
  这也正是本版要消除的那个失败面：**0.3.7 默认关着 fall-through，所以默认路径上根本不经过它**；本版默认打开后，
  若该路径被触发且行为不对，读数会是 `Unknown addon` + 断线 —— 本次判据要求这两个计数为 0。
- 该修复的**功能证明在 sibling 车道**（`2026-10-08-play-payload-dispatch`，arm FIX：`RESULT=CAST_ACCEPTED`、冷却
  1197 tick 经 mod 自己的载荷回来、`Unknown addon`=0、没被踢；F1 `=off` → `CAST_DROPPED`；F2 release 内核 +`=on`
  → `Unknown addon` 断线；F3 fabric 自己的 PLAY receive 实测收到 `shooting_star_demo:cast` 与 `minecraft:register`），
  **不是**本次运行。

## 3. 必须仍成立（0.3.5/0.3.6/0.3.7 已发布形状，不得回归）

- **Forge 自定义注册表（0.3.7 Fix A）**：`[Forbric/Forge] created 9 custom registr(ies) via NewRegistryEvent` 恰 **9**；
  `NewRegistryEvent could not reach` = **0**、`Failed to apply some object holders` = **0**、
  `Unable to find registry with key forge:` = **0**。
- **Sodium cutout 降级链**：`now targets … SpriteContents.<init>(…ForgeTextureMetadata;)V` 恰 **2**；
  `applies only partially … SpriteContents.originalImage` = **0**。
- **颜色修复**：四条 marker 各 >= 1（`(2 site(s) re-keyed to getBlock)`、`(1 site(s) re-keyed to getItem)`、
  两个 `@Shadow … is an IdMapper`）。
- **fabric-particles-v1 接口 mixin**：`@Mixin target type mismatch` = 0、`ParticleEngine is not an interface` = 0、
  `SYNTHETIC default method over ParticleEngine` = 2。
- **加载报告聚合面**：`load-report.txt` 存在，聚合句按状态拆句。
- **像素副读数**（同一夹具、三臂之间可比）：三个 ROI（草方块侧面 / 蒲公英 / 树叶）的**纯黑 `(0,0,0)` 占比 = 0.0000**。

## 4. arm B（Create 6.0.10 与这 12 只共存）

- `mod=OK`、`cause=None`、`catalog_failures=[]`、13 只闭包全 `OK`、0 份 crash-report。
- `grep -c "create failed during client setup"` = 0、`grep -c "Render layers can only be set"` = 0。

## 5. arm C（Shooting Star，subject 的 DEGRADED 是既有状态）

- demo 与 addition 都可加载、进世界；`confirmed_required=0`。
- **预登记**：subject `shooting_star_demo` 的 `mod` 读 **DEGRADED**、`cause=mod-degraded`、
  `catalog_failures=['shooting_star_demo']`、`loaded=false`、`strict=FALSE` —— 这是该 jar 的**既有**状态
  （0.3.5/0.3.6/0.3.7 三版都读到同一个，且本版与 0.3.7 逐字段相同），不是本版回归。
- addition 与其余 12 只状态 `OK`。

## 6. 证伪与撤回

- 第 1..5 节任一不达 → 该条记"**被证伪**"，如实上报，不改判据、不用别处读数顶替。
- 出现 `confirmed_required>0` 或任何 crash-report → **撤回候选，不发布**。
- 一条例外按 0.3.5/0.3.6/0.3.7 惯例预登记：`[Forbric/EventMux] handleServerStopped forward failed`（night-config
  `SimpleCommentedConfig`→`CommentedFileConfig` 的 `ClassCastException`）是**第三条、独立、非本版的**缺陷，本版不修，
  出现 1 次不算回归。
- 三条 harness 行都可能带 rule 3 的机器负载提示（`contended=true`）；本车道没有任何时限判据，如实记下。

---

## §Adjudication

*（在全部臂跑完之后追加；以上判据原文未改）*
