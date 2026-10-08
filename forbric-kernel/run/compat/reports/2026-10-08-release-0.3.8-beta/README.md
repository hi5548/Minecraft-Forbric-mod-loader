# 0.3.8-beta —— 发布说明（三档）

**版本**：`0.3.8-beta`（安装器 `forbric-kernel-installer-0.3.8-beta`）
**来源**：`main` 尖端 `66200a3e`（= `2026-10-08-play-payload-dispatch` 的服务端 PLAY 分发修复：fall-through 不再
借道超类、直接调 `NetworkRegistry.handleModdedPayload`，开关默认打开），版本提交 `2a9e6938`。此前已发布
`0.3.7-beta`（内核 `48df3d2b…`，源 `f9d4719b`）。
**内核**（客户端 boot jar，带游戏侧）sha256：

```
97d894f5946829d46a163e179cc71508424bb8d4bf21ae2534acafa555d6780d
```

**构建方式（与 0.3.5～0.3.7 同一装配）**：`/Volumes/ORICO` 未挂载、staged game jars 在本机不存在，game-side 无法
在本机重建。故在**干净 worktree** `/tmp/rel038/wt`（`git worktree add --detach … 2a9e6938`）里做：

1. `./gradlew --offline -q jar`（**JDK 25**）→ **boot 半** 735 条目，sha `b9e19aa3…`（无 staged 属性 ⇒ 不 wire nested runtime，按设计）；
2. runtime 半**逐字节**取自已发布 `0.3.7-beta` 的 `META-INF/jars/forbric-kernel-runtime.jar`（sha `67c2a49b…`；
   `git diff --stat b1b90566 HEAD -- forbric-kernel/src/runtime` 为空 ⇒ 游戏侧与本版无关）；
3. `pack.py` 注入 runtime 半，合计 **736** 条目，**0 条** `X N.class` 陈旧重名条目。

**入口集（entry set）**相对已发布 `0.3.7-beta`：added `[]`、removed `[]`、differing **恰 1** =
`net/forbric/kernel/transform/CommonNetworkInteropInjector.class` —— 正好是
`git diff --stat v0.3.7-beta-1.21.1 HEAD -- forbric-kernel/src/main` 列出的**唯一**源文件（62+/22-）。
本 jar 与 sibling 车道独立构建的 `kernel-playpay.jar` **逐字节相同**。证据：`evidence/build-provenance.txt`。

**构建 JDK（记在明面上，因为它决定字节）**：本机默认 `java` 现为 25.0.1，而 0.3.6/0.3.7 的 boot 半都是 **JDK 25** 编的
（0.3.7 已记录：JDK 25 重编 `13e4ff3f` 逐字节复现 0.3.6 的 boot 半 sha；JDK 21 有 121 个类不同）。本版因此固定 JDK 25，
使入口集 delta 是**源码**而非编译器噪声。

**三臂 gate 运行**：`W7Harness` 客户端面（`w7/harness/sweep_client.py`），**一遍即过、无重跑**，三臂都 `--kernel-jar`
钉住上面这只 jar（未重建、未替换）；JDK 21.0.7，`strict` 策略，`mixinFit=default`，**窗口隐藏 agent 开着**，
热 remap 缓存 `/tmp/rel038/remap`，stage `stubtable/stage`，MC 根 `stubtable/mc`，世界夹具 `corpus/client-world/W7Client`，
一次一臂。预登记（运行前写下）在 `evidence/preregistration.md`，事后裁决追加在同一文件末尾。

| | 集合 | corpus | 逐字读数 |
|---|---|---|---|
| arm A | 用户真实 12（subject `modmenu-11.0.5.jar`，+11 闭包） | `/tmp/rel038/corpus12` | `run=PASS exit=0 world=true frames=1 stopped=true killed=false strict=TRUE confirmed_required=0 loaded=true na=false mod=OK cause=None`，11 只依赖全 `OK`，`catalog_failures=[]`，0 份 crash-report，31s |
| arm B | 同一 12 + Create 6.0.10（subject `create-1.21.1-6.0.10.jar`，+12 闭包） | `corpus13` | 同上 `mod=OK cause=None`，12 只依赖全 `OK`，`catalog_failures=[]`，0 份 crash-report，37s |
| arm C | 同一 12 + 两只 Shooting Star（subject `the-shooting-star-demo-1.3.1-neoforge.jar`，+13 闭包） | `corpus14` | `run=PASS exit=0 world=true frames=1 stopped=true killed=false strict=FALSE confirmed_required=0 loaded=false na=false mod=DEGRADED cause=mod-degraded`，`catalog_failures=['shooting_star_demo']`，0 份 crash-report，32s |

三臂都 `joined world via quick-play: W7Client` → `client-ready after 200 world tick(s)` → `clean disconnect observed`；
**三臂的 `load-report.txt` 与 0.3.7-beta 发布 gate 的同一文件逐字节相同**（md5 `68ae1f74…` / `581e8128…` / `f3e18886…`）。
完整行与 `results.jsonl` 逐字在 `evidence/gate-reading.txt`；字段级 0.3.7↔0.3.8 并排在 `evidence/row-compare.txt`。

**安装**：`java -jar forbric-kernel-installer-0.3.8-beta.jar --dir /Applications/.minecraft`（exit 0）；
用户 14 只 mod 逐字节不变、实例文件 mtime 未动、装上的内核 = 本次 gate 的 `97d894f5…`，
且安装重建的游戏侧与三臂所跑 stage **逐条目内容相同**（0 差异）。逐条证据 `evidence/install-verification.txt`。

---

## 一档 · 实测通过（在真客户端、世界深度跑过）

1. **三条 gate 行全部按预登记读出**（上表）：严格面三臂一致，唯一 `strict=FALSE` 的 arm C 是 subject 自身的
   既有 `DEGRADED`（预登记即如此写，非本版引入）。
2. **本版要消除的失败面在三臂上为 0**：`Unknown addon` = 0、`lost connection` = 0、fall-through 生效行 = 0
   （无人值守不产生 serverbound PLAY 载荷，预登记即写"预期观察不到"）。默认已打开的新开关**没有**引入它要修的
   那次断线 —— 这正是本版的风险点，读数为空。
3. **三条 arm 的 `load-report.txt` 与 0.3.7-beta 逐字节相同** ⇒ 本版没有在用户真实集合、Create 集合、Shooting Star
   集合上挪动任何一行加载报告。
4. **0.3.7 Fix A 未回归**：三臂各 `[Forbric/Forge] created 9 custom registr(ies) via NewRegistryEvent` 恰 9；
   `NewRegistryEvent could not reach` = 0、`Failed to apply some object holders` = 0、
   `Unable to find registry with key forge:` = 0。
5. **Sodium cutout / 颜色修复 / fabric-particles-v1 未回归**（三臂）：
   `now targets … SpriteContents.<init>(…ForgeTextureMetadata;)V` 恰 2、`applies only partially … originalImage` = 0；
   四条颜色 marker 全在（`2 site(s) re-keyed to getBlock`、`1 site(s) re-keyed to getItem`、两个 `@Shadow … is an IdMapper`）；
   `@Mixin target type mismatch` = 0、`ParticleEngine is not an interface` = 0、
   `SYNTHETIC default method over ParticleEngine` = 2。
6. **Create 仍与这 12 只共存并 `strict` 进世界**（arm B）：`mod=OK`、`cause=None`、`catalog_failures=[]`、12 只闭包全 `OK`、
   `create failed during client setup` = 0、`Render layers can only be set` = 0。
7. **像素不变量**：arm A / arm B 三个 ROI 纯黑 `(0,0,0)` = **0.0000**；arm C 见"被证伪的预登记"。
8. **安装校验（headless）**：`--dir /Applications/.minecraft`，exit 0；用户 14 只 mod sha256 14/14 逐字节不变；
   `versions/` 目录、`options.txt`、`saves/`、`config/`、`.forbric-kernel/`、`log4j2.xml`、全局 `mods/`（129 jar）等
   mtime 一律未动；唯一有意变更是 `1.21.1-forbric.json`（0.3.7 → 0.3.8）；装上的内核
   `libraries/net/forbric/forbric-kernel/0.3.8-beta/…jar` sha256 `97d894f5…` = gate artifact = 安装器内嵌内核。
   （注：安装前该实例在 11:13–11:17 被用户自己用 HMCL 跑过一次，写入了 `logs/`、`config/`、`options.txt`、
   `.forbric-kernel/load-report.txt` 等 —— 这些**用户侧**改动在 before/after 两份 state 里逐行相同，安装没有碰它们。）

## 二档 · 仅离线证明（字节证据 / sibling 车道；本集合未在世界深度复跑）

- **本版修复（服务端 PLAY 载荷直达 NeoForge 处理器）**：功能证明在 `2026-10-08-play-payload-dispatch` 车道
  （arm FIX：`RESULT=CAST_ACCEPTED`、冷却 1197 tick 经 mod 自己的载荷回来、`Unknown addon`=0、没被踢；
  F1 `=off` → `CAST_DROPPED`；F2 0.3.7 内核 +`=on` → `Unknown addon` 断线；F3 **实测**到 fabric 自己的 PLAY receive
  `ServerPlayNetworkAddon <- shooting_star_demo:cast`（拒收）与 `<- minecraft:register`（处理），
  以及运行期字节里 fall-through 直接调 `NetworkRegistry.handleModdedPayload`、无 `invokespecial …handleCustomPayload`）。
  **本版三臂未在世界深度复跑"点击施法"**（harness 无输入注入）。
- **入口集 / 构建可复现**：`97d894f5…` 与 sibling 车道独立构建逐字节相同；boot 半相对 0.3.7 只差 1 个类。
- **1.21.1 carrier-stub 表 95 行**：本 jar 内 `carrier-stubs.txt` 与 0.3.7 逐字节相同。

## 三档 · 已记录损失（不假装可用）

- **arm C 的 Shooting Star 集合是 `strict=FALSE`**（`mod=DEGRADED cause=mod-degraded`，
  `catalog_failures=['shooting_star_demo']`）：demo 自身的 `DEGRADED` 是**既有**状态（0.3.5/0.3.6/0.3.7 都读到同一个，
  本版与 0.3.7 **逐字段相同**），不是本版回归，也不阻断进世界（`run=PASS world=true frames=1`）。
- **`0.3.5～0.3.7` 已记录的损失原样保留**：Indigo per-block 钩子（区块内自定义几何可能渲染错误或不渲染）、
  Mod Menu 标题行替换、`fabric-registry-sync-v0` 三条 pin 维持失效。
- **启动日志仍见 `[Forbric/Load] 9 / 9 / 10 mod(s) 有一部分没有跑起来`**（A/B/C）：本版**未修**；不阻断 `strict`，
  且三臂该文件与 0.3.7 逐字节相同。
- **第三条、独立的非本版缺陷仍在**：`[Forbric/EventMux] handleServerStopped forward failed`（night-config
  `SimpleCommentedConfig`→`CommentedFileConfig` 的 `ClassCastException`）三臂各 **1** 次。本版不修，如实记录。
- **超类体的五条前置分支在 PLAY 阶段仍不可达**（`MinecraftRegisterPayload` 等）。这不是本版引入的：它们在那道门里
  不可能出现，且修复前同样从未在 PLAY 跑过；是否在 PLAY 阶段也把它们接回来，是 `cannot-fire` 报告点名的另一个问题，
  本版不动。

## 被证伪的预登记

预登记 §7/§6 写"三个 ROI 纯黑 `(0,0,0)` 占比 = 0.0000"。**读数证伪**：arm C 的蒲公英 ROI 读 **0.0001**（6/55000，
一团 2×3 像素，位于 (728,442)）。arm A、arm B 三个 ROI 全部 0.0000。判据不改写；归因见 `pixel-readings.txt`：
世界夹具出生点逐次不同（0.3.7 车道已记录），同一臂/同一 ROI 在 0.3.7 上也有 8 个 `max(R,G,B)<40` 的暗像素（纯黑 0），
故判为夹具场景差异、非内核回归。§1..§6 其余各条均达成。

## 不作超档声明

- 二档各项只有字节/单测证据，**没有**在真客户端世界深度复跑（含本版修复的"点击施法"）。
- 一档各项的上限是"这一次运行观测到"，不是"每只 mod 的每项功能都试过"。
- **像素读数只作三臂之间的不变量**，绝对值不与旧周期比较（夹具非固定）。
- 本版未测更长的游戏内行为；未在**安装实例**上再启动一次（启动级读数以上面三臂为准，字节同一、游戏侧逐条目同）。
- 三条 harness 行都带 rule 3 的机器负载提示（`314–334% CPU busy`，load 3.9–8.3）——本车道没有任何时限判据。

---

## 发布结果回填（2026-10-08）

- tag `v0.3.8-beta-1.21.1`（轻量 tag，指向发布提交 `08fc926d`；其父 `02d05fcc` 为**预登记**提交，祖父 `2a9e6938`
  为升版本提交，曾祖父 `66200a3e` 为 play-payload-dispatch 车道的修复提交 = 发布源码尖端）
- Release `https://github.com/hi5548/Minecraft-Forbric-mod-loader/releases/tag/v0.3.8-beta-1.21.1`，附件：
  - `forbric-kernel-installer-0.3.8-beta.jar` sha256 `8d4ef1ae568960eb25a6b4e0e51a4e7178045677e1c0c6f68cf689cce0f97979`（8531173 B）
  - `forbric-kernel-installer-0.3.8-beta.zip` sha256 `70909d77d758c7993aa223c4bf87e2a3ce9e36ba7beb0584d85128b500c1fe7b`（8525337 B）
  （GitHub 回读的附件 digest 与这两条相同）
- 本地构建产物：`forbric-kernel-installer/build/{libs,dist}/`，副本 `/private/tmp/rel038/artifacts/`
- 安装器内嵌内核逐字节 = 本次 gate 的 `97d894f5…`（= 装上的 `libraries/net/forbric/forbric-kernel/0.3.8-beta/…jar`）
- 安装器 jar：105 条目、0 条陈旧重名条目
- tag 只推 tag（`git push fork v0.3.8-beta-1.21.1`），与历次发布一致；fork 的 `main`/`1.21.1-port` 不是发布线，未动
- `v0.3.7-beta-1.21.1` 已按惯例标注"已被取代"并撤下两个附件
- 逐条见 `evidence/release-result.txt`、`evidence/install-verification.txt`
