# 0.3.7-beta —— 发布说明（三档）

**版本**：`0.3.7-beta`（安装器 `forbric-kernel-installer-0.3.7-beta`）
**来源**：`main` 尖端 `f9d4719b`（= `2026-10-06-shooting-star-disconnect` 的两处 boot 修复：
Forge 自定义注册表的 `post(Event)` 按参数类型取 + `EffectiveSide` 的 CLIENT 兜底改写为 `ForgeSidedThreads`），
版本提交 `4f4935b9`。此前已发布 `0.3.6-beta`（内核 `16bcd602…`，源 `13e4ff3f`）。
**内核**（客户端 boot jar，带游戏侧）sha256：

```
48df3d2b90dfec3926fbfcee92dc6bd8a441760f27560fd7d168169d81e2fa97
```

**构建方式（偏离，必须写在明面上）**：`/Volumes/ORICO` 未挂载、`energy-4.1.0-named.jar` 与 staged fixtures
在本机不存在，game-side 无法用 staged 属性重建。故沿用 `0.3.5`/`0.3.6` 记录的同一装配，**在干净 worktree 里做**：

1. `git worktree add --detach /private/tmp/rel037/wt 4f4935b9`（**不在主工作树里构建**）；
2. `./gradlew --offline -q jar`（boot 半，697 条目，0 条 `X N.class` 陈旧重名条目）；
3. runtime 半**逐字节**取自已发布 `0.3.6-beta` 的 `META-INF/jars/forbric-kernel-runtime.jar`
   （sha256 `67c2a49b…`；`git diff --stat b1b90566 HEAD -- forbric-kernel/src/runtime` 为空 ⇒ 游戏侧与本版无关）；
4. `pack.py` 注入 runtime 半，合计 **736** 条目。
   boot 半相对已发布 0.3.6 **新增 1 个类、改动 9 个类、删除 0**，且这 10 个类**恰好**落在
   `git diff --stat 13e4ff3f HEAD -- forbric-kernel/src/main` 列出的 7 个源文件上（其余每一条都逐字节相同）。
   证据：`evidence/build-provenance.txt`。

**构建 JDK（记在明面上，因为它决定字节）**：本机默认 `java` 是 21.0.7，但 **0.3.6-beta 的 boot 半是 JDK 25 编的**
（用 JDK 25 重编 `13e4ff3f` 逐字节复现 0.3.6 记录的 boot 半 sha `30cef190…`；用 JDK 21 有 121 个类不同）。
本版因此固定 JDK 25 编译。`48df3d2b…` 重编可复现，且与 sibling 车道独立构建的 `after` 臂逐字节相同。

**三臂 gate 运行**：`W7Harness` 客户端面（`w7/harness/sweep_client.py`），**一遍即过、无重跑**，
三臂都 `--kernel-jar` 钉住上面这只 jar（未重建、未替换）；JDK 21.0.7，`strict` 策略，`mixinFit=default`，
**窗口隐藏 agent 开着**，热 remap 缓存 `/private/tmp/rel037/remap`，stage `stubtable/stage`，MC 根 `stubtable/mc`，
世界夹具 `corpus/client-world/W7Client`，一次一臂。预登记（运行前写下）在 `evidence/preregistration.md`。

| | 集合 | corpus | 逐字读数 |
|---|---|---|---|
| arm A | 用户真实 12（subject `modmenu-11.0.5.jar`，+11 闭包） | `/private/tmp/rel037/corpus12` | `run=PASS exit=0 world=true frames=1 stopped=true killed=false strict=TRUE confirmed_required=0 loaded=true na=false mod=OK cause=None`，11 只依赖全 `OK`，`catalog_failures=[]`，0 份 crash-report，33s |
| arm B | 同一 12 + Create 6.0.10（subject `create-1.21.1-6.0.10.jar`，+12 闭包） | `/private/tmp/rel037/corpus13` | `run=PASS exit=0 world=true frames=1 stopped=true killed=false strict=TRUE confirmed_required=0 loaded=true na=false mod=OK cause=None`，12 只依赖全 `OK`，`catalog_failures=[]`，0 份 crash-report，38s |
| arm C | 同一 12 + 两只 Shooting Star（subject `the-shooting-star-demo-1.3.1-neoforge.jar`，+13 闭包） | `/private/tmp/rel037/corpus14` | `run=PASS exit=0 world=true frames=1 stopped=true killed=false strict=FALSE confirmed_required=0 loaded=false na=false mod=DEGRADED cause=mod-degraded`，`catalog_failures=['shooting_star_demo']`，0 份 crash-report，35s |

三臂都 `joined world via quick-play: W7Client` → `client-ready after 200 world tick(s)` → `clean disconnect observed`；
`[Forbric/Seed] seeded NeoForge LoadingModList with 64 / 67 / 66 mod(s)`（A/B/C）。
完整行与 `results.jsonl` 逐字在 `evidence/gate-reading.txt`。

**安装**：`java -jar forbric-kernel-installer-0.3.7-beta.jar --dir /Applications/.minecraft`（exit 0）；
用户 14 只 mod 逐字节不变、实例文件未被改动、装上的内核 = 本次 gate 的 `48df3d2b…`，
且安装重建的游戏侧与三臂所跑 stage **逐条目内容相同**（0 差异）。逐条证据 `evidence/install-verification.txt`。

---

## 一档 · 实测通过（在真客户端、世界深度跑过）

1. **用户真实 12-mod 组合仍一遍进世界**（arm A）：`run=PASS / world=true / frames=1 / strict=TRUE /
   confirmed_required=0`，11 只依赖全部 `OK`、`catalog_failures` 空、0 份 crash-report。
   **更强的一条**：arm A 的 `load-report.txt` 与 `0.3.6-beta` 发布 gate 的同一文件**逐字节相同**
   （19427 B，md5 `68ae1f74f2606768561176ebe6ef005e`）—— 本版四处内核改动没有在十二 mod 集合上挪动任何一行。
2. **Create 6.0.10 仍能与这 12 只共存并 `strict` 进世界**（arm B）：`mod=OK`、`cause=None`、
   `catalog_failures=[]`、12 只闭包全 `OK`、0 份 crash-report；`grep -c "create failed during client setup"` = **0**、
   `grep -c "Render layers can only be set"` = **0**。arm A→arm B 的 `load-report` diff 只增加信息级 Create
   arbitration 记录（3 条 entrypoint-closure + 1 条 entrypoint-reachable，外加 flywheel 相关），
   **没有增加任何一个"没有跑起来"的 owner**（`evidence/load-report-comparison.txt`）。
3. **本版 Fix A 在真实集合上发射**（三臂）：`[Forbric/Forge] created 9 custom registr(ies) via NewRegistryEvent`
   恰 **9**（对照：0.3.6-beta 在 14 只集合上读 `created 0`）；
   `NewRegistryEvent could not reach` = **0**、`Failed to apply some object holders` = **0**、
   `Unable to find registry with key forge:` = **0**（对照 0.3.6：1 / 1 / 26）。
   ⇒ 用户日志里"世界退出才炸"的整条 holder 链在本版真实集合上消失。
4. **本版 Fix B 的"运行里观察不到"如预登记所料**：三臂 `lost connection: Illegal packet received` = **0**，
   `MinecraftForge's EffectiveSide now reads` = **0**（该类在一次无人值守运行里从不被加载）。
   这不是失败，是预登记就写下的形状；Fix B 的证明是真载体字节探针（见
   `2026-10-06-shooting-star-disconnect/evidence/effective-side-probe.txt`），**不是**本次运行。
5. **fabric-particles-v1 的接口 mixin 拒绝消失、Sodium cutout / 颜色修复未回归**（三臂）：
   `@Mixin target type mismatch` = 0、`ParticleEngine is not an interface` = 0、
   `SYNTHETIC default method over ParticleEngine` = 2；
   `now targets … SpriteContents.<init>(…ForgeTextureMetadata;)V` 恰 **2**、
   `applies only partially … SpriteContents.originalImage` = **0**、
   四条颜色 marker 各在（`getBlock` 2、`getItem` 1、两个 `@Shadow … is an IdMapper`）。
6. **像素不变量**（三臂，同一 corpus 夹具、同一分类器）：三个 ROI（草方块侧面 / 蒲公英 / 树叶）纯黑
   `(0,0,0)` 占比 = **0.0000**。见 §不作超档声明 里对"夹具不可比"的说明。
7. **安装校验（headless）**：`--dir /Applications/.minecraft`，exit 0；用户 14 只 mod sha256 14/14 逐字节不变；
   `versions/` 目录不变；全局 `mods/`（129 jar）mtime 未变；`options.txt`/`saves/`/`config/`/`.forbric-kernel/`/
   `log4j2.xml`/`resourcepacks`/`data`/`downloads`/`logs`/`shooting_star.log` mtime 均未变；
   唯一有意变更是 `1.21.1-forbric.json`（0.3.6 → 0.3.7）；
   装上的内核 `libraries/net/forbric/forbric-kernel/0.3.7-beta/…jar` sha256 `48df3d2b…`
   = gate artifact = 安装器内嵌内核。

## 二档 · 仅离线证明（字节证据 / 单测；本集合未在世界深度复跑）

- **Fix B（`EffectiveSide`）**：真载体字节探针（不是本次运行）——在 NeoForge `SidedThreadGroups.SERVER`
  组里的线程上，改前 `CLIENT`、改后 `SERVER`；无 side 的线程两者都 `CLIENT`。`MergedBaseEffectiveSideTest`
  在本机因缺 `run/forge-runtime/forge-runtime.jar` 被 skip，由探针实际行使。
- **Fix A（`post(Event)` 按类型取）**：`KernelForgeModContextPostTest` 钉住契约（双 `post` 重载的总线上取一参那个并可
  `invoke(bus, event)`；没有该方法时指名类型报错）。`2026-10-06-shooting-star-disconnect` 车道记录的
  `getMethods()` 顺序不稳定（同一条探针 12 次分 10/2 与 6/6）是本修复的动机。
- **Create 的 in-world 行为**：本次只证明"加载完成、进世界、`strict`、0 崩溃"。机械动力的配方、渲染、
  物流方块（`PackageRenderer#renderBox` 一类 entrypoint-reachable 路径）**未做**游戏内行为验证。
- **1.21.1 carrier-stub 表 95 行**：本 jar 内 `carrier-stubs.txt` 95 行、sha256 `4736ce5d…`（与 0.3.6 同）。

## 三档 · 已记录损失（不假装可用）

- **arm C 的 Shooting Star 集合是 `strict=FALSE`**（`mod=DEGRADED cause=mod-degraded`，
  `catalog_failures=['shooting_star_demo']`）：demo 自身的 `DEGRADED` 是**既有**状态
  （`2026-10-06-shooting-star` 与 `-disconnect` 两条车道在 0.3.5/0.3.6 上都读到同一个；本版与 0.3.6 **逐字段相同**），
  不是本版回归，也不阻断进世界（`run=PASS world=true frames=1`）。**预登记里"arm C 仍应为 `strict=TRUE`"
  这一条被证伪**，如实记：见 §被证伪的预登记。
- **`0.3.5/0.3.6-beta` 已记录的损失原样保留**：Indigo per-block 钩子（重锚撤回，区块内自定义几何可能渲染错误或不渲染）；
  Mod Menu 标题行替换；`fabric-registry-sync-v0` 的三条 pin 维持失效（`fabric-rendering-v1` 的两条 client mixin 已重绑，marker 为证）。
- **启动日志仍见 `[Forbric/Load] 9 / 9 / 10 mod(s) 有一部分没有跑起来`**（A/B/C）：本版**未修**。
  它不阻断 `strict`（不是 CONFIRMED required），但确实有 mod 未加载完；arm A 的该文件与 0.3.6 逐字节相同。
- **第三条、独立的非本版缺陷仍在**：`[Forbric/EventMux] handleServerStopped forward failed`（night-config
  `SimpleCommentedConfig`→`CommentedFileConfig` 的 `ClassCastException`）三臂各 **1** 次。本版不修，如实记录。
- **Create 带来信息级 arbitration 记录**（entrypoint closure/reachable 未证），不阻断加载，如实记录。

## 被证伪的预登记

预登记 §1/§6 预测 arm C `strict=TRUE`（理由："`DEGRADED` 不是 confirmed-required"）。**读数证伪**：
harness 的 `strict` 判据包含"subject 与每一只依赖状态 `OK`"，而 subject `shooting_star_demo` 是 `DEGRADED`
（并在 `catalog_failures` 里），故三臂中唯 arm C 读 `strict=FALSE`。判据不改写；这是对该集合既有形状的准确描述，
**非**本版引入。§1..§7 其余各条均达成。

## 不作超档声明

- 二档各项只有字节/单测证据，**没有**在真客户端世界深度复跑。
- 一档各项的上限是"这一次运行观测到"，不是"每只 mod 的每项功能都试过"。
- **像素读数只作三臂之间的不变量**：本夹具是 corpus 夹具（ROI 框到的是草顶），
  `dirt_brown`/`grey_tex` 绝对值与 `2026-10-04` 那些周期（`/private/tmp/sodcut-fixture` 已随 `/private/tmp` 消失）
  **不可比**，故只声明"三臂皆无纯黑回归"。
- 本版未测更长的游戏内行为；未在**安装实例**上再启动一次（启动级读数以上面三臂为准，字节同一、
  游戏侧逐条目同）。
- 三条 harness 行都带 `rule 3` 的机器负载提示（`316% / 258% / 294% CPU busy`，load ≈ 4.4–6.1）——本车道没有任何
  时限判据，如实记下。

---

## 发布结果回填

_（本节的 tag / 附件 sha / 安装校验在报告提交后回填；见 `evidence/release-result.txt`。）_
