# 预登记 — 2026-10-08-fix-sync-perf（判据在任何构建/运行之前写下，事后不回改）

Lane: `fix-sync-perf`（worktree `../syncperf-wt`，base `main` @ `4b565f01`）。
对象：BugHunt5 报告（`forbric-kernel/run/compat/reports/2026-10-08-bughunt-5/README.md`）的 **R2 / R3 / R4**。

## 0. 装置与边界（先写死）

| 项 | 值 |
|---|---|
| 字节源 | 合并基底 `/Applications/.minecraft/.forbric-build/out/patched-mc-merged-1.21.1.jar`；已重映射 guest `…/1.21.1-forbric/.forbric-kernel/remap/fabric-transfer-api-v1-0.116.17-67967d3fdf11b159.jar`；出厂实例日志 `…/1.21.1-forbric/logs/latest.log` |
| 载体 | 本车道**不重建**合并基底；合并基底非本车道可改（`OwnedBy: merge`）。只改内核 `forbric-kernel/` |
| 运行时装置 | `W7Harness` 客户端面（`w7/harness/sweep_client.py`），quick-play 进 `W7Client`，`--ready-ticks 200 --linger-ticks 20`，`--kernel-jar` 钉本车道构建的 jar，`-Dforbric.compatibilityPolicy=continue` |
| **装置硬边界（W7Harness 当面确认）** | 客户端面**只能单人 quick-play**（`sweep_client.py:223` 硬编码 `--quickPlaySingleplayer`，无 `--quickPlayMultiplayer`，全 harness 无 server join 路径）。因此 **R2 的"加入纯 Fabric 服务器"无法由本装置行使**——这一点进预设，不靠事后解释 |
| 机器 | 与并列车道共享；运行行里带负载提示（rule 3），本车道无时限判据 |

## 1. 三条 finding 的**预期**与判据（跑之前写下）

### R2 — 纯 Fabric 服务器上 17 个 Forge-wrapped 注册表无 id 重映射
**预期：REFUTE（该修复早已存在，BugHunt5 读的是一段过时 javadoc）。**
- 字节判据（离线，必做）：
  1. `RegistrySyncParityInjector.giveWrapperBothContracts` 对真合并基底 `net.minecraftforge.registries.NamespacedWrapper` 运行后，**必须**产出 `public void remap(Object2IntMap, RemappableRegistry$RemapMode)`（方法体 `INVOKESTATIC KernelForgeWrapperSync.stageFabricRemap`）。
  2. `RegistrySyncParityInjector.flushAroundFabricApply` 对已重映射的 `net.fabricmc.fabric.impl.client.registry.sync.ClientRegistrySyncHandler` 运行后，`apply(payload)V` 的**每个 RETURN 前**必须有 `INVOKESTATIC KernelForgeWrapperSync.finishFabricRemap`。
  3. `KernelForgeWrapperSync.stageFabricRemap` / `finishFabricRemap` 存在于当前 main（已读）。
- 历史判据：`git merge-base --is-ancestor 72b01fd5 <audited-sha 61b93a04>` **必须为真**（修复在审计内核之前就落地）；`RegistrySyncParityInjectorTest.theRealWrapperGainsBothEcosystemsRemapContracts` 断言 `remap` 存在（已读）。
- **证伪条件**：若上述 1 或 2 在真合并基底/真 guest 字节上不成立，或 72b01fd5 不是 61b93a04 的祖先 → R2 成立，本车道须实现注入。
- **若 REFUTE，唯一残留**：`KernelForgeWrapperSync` 类 javadoc 第 48–53 行仍写 "Known limit, on purpose … a Forbric client against a PURE Fabric server gets no remap of these seventeen registries from either ecosystem" —— 与代码相反，且正是 BugHunt5 引用的那句。决定：改正该段 javadoc（只改文档，不改行为）。

### R3 — 启动期 ≥5 次互不复用的全 jar 扫描
**预期：CONFIRM，且可折叠为一次。**
- 代码判据（已读）：`KernelBoot.java:361–375` 依次调用
  `PortingLayerAudit.report` / `FabricApiModuleLossAudit.scan` / `FieldDriftAudit.scan` /
  `MergedBaseUncalledMethods.scanGuests` / `AbiLinkAudit.scan`；
  其中后四者**各自**对 `shadowCandidates` 逐 jar `ZipFile` 打开、逐 `.class` 条目 `readAllBytes()`。
- **改造判据（预承诺）**：折叠 `FabricApiModuleLossAudit` + `FieldDriftAudit` + `MergedBaseUncalledMethods` +
  `AbiLinkAudit` 四条 needle 扫描为**一次** jar 遍历；`KernelBoot` 只调用一次入口。
- **读数判据（离线探针，必做）**：一个计数探针分别计"每类 readAllBytes 次数"在折叠前后——
  预期折叠后对同一 `shadowCandidates` 的**类字节读取遍数由 4 降到 1**（PortingLayerAudit 只读 Forge-family 类，
  不在此四次之内，明确不算）。判据：`passesBefore == 4 && passesAfter == 1`（同一 jar 集、同一类集）。
- **等价性判据**：折叠前后，对同一合成 jar 集，四个 audit 各自记录的 finding 集合**逐元素相同**
  （新增单测：同一 jar 分别走"旧 scan"与"折叠入口"，比较 `hits()/users()/findings`）。
- **证伪条件**：若折叠后任 audit 的 finding 集合或 catalog 标记与折叠前不同 → 折叠作废，如实回退并记录。
- 保留：`PortingLayerAudit`（只读 Forge-family 类，另行处理，不在本决定内）。

### R4 — fabric-transfer-api 的 4 个 storage accessor "un-bindable and kept"
**预期：REFUTE as a runtime loss；但 CONFIRM 一处 MixinFit 预检误报并修掉。**
- 字节判据（必做，已初步读）：
  1. 4 个 accessor 的 `@Accessor/@Invoker` 注解 **value 是 Fabric 重映射后的带描述符形式**：
     `container1:Lnet/minecraft/world/Container;`、`items:Lnet/minecraft/core/NonNullList;`、
     `content:Lnet/minecraft/world/level/material/Fluid;`、
     `getWeight(Lnet/minecraft/world/item/ItemStack;)Lorg/apache/commons/lang3/math/Fraction;`。
  2. 目标成员在合并基底**存在且 name+desc 相符**：`CompoundContainer.container1/2:Container`、
     `ItemContainerContents.items:NonNullList`、`BucketItem.content:Fluid`、
     `BundleContents.getWeight(ItemStack)Fraction`。（已 javap 初读为真，构建前复核入证据）
  3. `MixinFit.accessorAnchor/invokerAnchor` 把**整个 value**当成员名用（`findField(target, value, desc)`），
     故必然 miss → 日志 `cannot bind`。证伪条件：若目标成员缺失 → R4 成立（运行时确会抛）。
- **修复判据**：`accessorAnchor`/`invokerAnchor` 用 `MixinFit.parseMember(value)` 取 `name`（复用它已有的
  "`[owner;]name[:desc]` / `name(args)ret`"解析），描述符仍由方法签名给出。修复后对 4 个真 guest accessor
  `MixinFit.evaluate(...).unresolved()` **必须为空**；且修复前为空集以外的其它不确定项不得被"顺手"掩盖
  （即：若某个 accessor 目标成员真缺，修复后仍报 unresolved）。
- **读数判据**：W7Harness 客户端运行日志中，四行 `guest accessor mixin fabric-transfer-api-v1 … cannot bind`
  **不再出现**；且不新增任何 `InvalidAccessorException`/crash。

## 2. 运行计划

1. 构建：`w7/harness/build-kernel.sh ../syncperf-wt/forbric-kernel <out>/forbric-kernel-syncperf.jar`（离线 gradle；脚本自身核对 jar 身份）。
2. 单测：`forbric-kernel` 全量 `./gradlew --offline test`（回退到目标测试 + 相关套件）。
3. 离线探针：R2 注入器跑真字节；R3 计数探针；R4 `MixinFit.evaluate` 跑真 accessor 类。
4. W7Harness：客户端一面，钉本车道 jar，读 `results.jsonl` + `latest.log`。**若时间/机位不可得，如实记为"未跑"并给装置边界**，不假装。

## 3. 事后 §Adjudication（跑完后追加；本节以上判据一字不改）

**R2 的预登记被证伪，如实记。** 我预登记写的是 REFUTE（"修复早已存在，BugHunt5 读的是过时 javadoc"），
理由来自 72b01fd5 的提交信息与 gate-m14 的"真 Fabric 服务器"读数。**字节把这条预测推翻了**：
`net.fabricmc.fabric.impl.client.registry.sync.ClientRegistrySyncHandler` 在出厂的 fabric-api 0.116.17（1.21.1）
里**不存在**；gate-m14 钉的是 **26.2** 的 fabric-api 0.154.0（`run/gate-m14-fabric-server.sh:34`），该修复
从未按 1.21.1 重导。1.21.1 的入口是 `RegistrySyncManager.apply(Map, RemapMode)V`，接口 `remap` 是
**三元** `(String, Object2IntMap, RemapMode)V`，而内核注入的是**二元**，命名类也错 ⇒ wrapper 收下 id 却从不
flush。**R2 = CONFIRM（真缺陷）**，已修（D1）。`KernelForgeWrapperSync` 那段"Known limit" javadoc 是
26.2 时代的原件，已改写。

**R3 按预期 CONFIRM。** 折叠 4 条扫描为 1 条；真字节读数 reads 20052→5013、820 ms→424 ms；
findings 计数前后一致（evidence/r3-scan-probe.txt）。PortingLayerAudit 未折（形状不同），如实记。

**R4 按预期：运行时损失 REFUTE，但确认一处 MixinFit 预检误报并已修。** 四个 accessor 的目标成员在
合并基底都在；MixinFit 把 Fabric 重映射后的 `field:desc` / `name(args)ret` 注解值整串当成员名。修复后
客户机运行里 `guest accessor mixin` 行 **0**（出厂 0.3.8 实例日志 56 行），四个 transfer-api accessor 行 0。

**装置边界（如实）：** W7Harness 客户端面**只能单人 quick-play**，"加入纯 Fabric 服务器"这一半**无法由本
装置行使**——R2 因此**没有真机服务器读数**；gate-m14 在本机不可行（它要 26.2 的 fabric-api 与联网下载，
且 /Volumes/ORICO 未挂载）。R2 的运行时证据止于**离线真字节探针 + 客户机启动里 flush 行首次出现**。

**已跑的那一臂是"混合 jar"，如实记：** boot half = 本车道 HEAD `023e9315`；runtime half =
出厂 0.3.8-beta 的嵌套 jar 逐字节拷贝（本机游戏侧不可编译：energy-4.1.0-named 与 staged fixtures 缺失、
/Volumes/ORICO 未挂载）。`git diff v0.3.8-beta-1.21.1..023e9315 -- src/runtime` 非空（4 文件），本车道
三处改动全在 boot half。故该行**只**证明"本车道 boot-half 改动 + 出厂 0.3.8 runtime 能进世界、mod=OK、
confirmed_required=0"，不证明 26.2 之后的 src/runtime 与 boot half 的兼容性；也不据此声称是 clean sha。
**没有**削弱 `:verifyRebornEnergy`。
