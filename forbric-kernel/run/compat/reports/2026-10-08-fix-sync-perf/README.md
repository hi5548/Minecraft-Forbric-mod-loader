# 修复车道:registry-sync 的 fabric 半边重导、启动扫描折叠、MixinFit accessor 解析

**Verdict (EN).** BugHunt5's R2, R3 and R4 are all landed, but R2 came out **not** as BugHunt5 described it.
R2 is a **real, live defect**, and the mechanism is the opposite of the finding's own words: the kernel's
fabric-registry-sync parity hook is **26.2-shaped** (`ClientRegistrySyncHandler.apply(RegistrySyncPayload)V`, a
**two-arg** `remap`) while the shipped 1.21.1 fabric-api 0.116.17 has neither that class nor that signature
(entry: `RegistrySyncManager.apply(Map, RemapMode)V`; `RemappableRegistry.remap` is **three-arg**
`(String, Object2IntMap, RemapMode)V`). The named class never loads, so the flush was never injected — and
because the transform was never handed the class, not even its own "re-derive" warning fired. A pure Fabric 1.21.1
server's ids were staged by the wrapper and silently never applied. R3 (four redundant full-jar scans) is folded
into one pass, measured. R4 is a **MixinFit false positive**, not a runtime loss: the four fabric-transfer-api
accessors' targets exist and bind; MixinFit was reading Fabric's remapped `@Accessor`/`@Invoker` value as the whole
member name. All three verified by an **offline byte probe** and by a **W7Harness client arm** (STRICT PASS,
world=True, frames=1, mod=OK, 46 s).

**答案（中文）。** BugHunt5 的 R2/R3/R4 都已落地,但 **R2 的真相比它说的更严重**。R2 不是它写的"已知限制(故意不修)"
——那是内核**26.2 时代**的既有 javadoc;内核的 fabric 注册表同步钩子此刻仍是 26.2 形状
(`ClientRegistrySyncHandler.apply(RegistrySyncPayload)V` + **二元** `remap`),而随本代出厂的 fabric-api
0.116.17(1.21.1)**既没有那个类、也没有那个签名**(入口是 `RegistrySyncManager.apply(Map, RemapMode)V`,
`RemappableRegistry.remap` 是**三元** `(String, Object2IntMap, RemapMode)V`)。被命名的类从不加载 ⇒ flush 从不
注入,而且连它自己的"re-derive"警告都不响(transform 从未拿到那个类)。**纯 Fabric 1.21.1 服务器上,这 17 个
Forge 包裹注册表的 id 被 wrapper 收下却从未应用。** R3 把启动期四条互不复用的全 jar 扫描折叠成一条,有实数。
R4 是 **MixinFit 的误报**,不是运行时损失:四个 transfer-api accessor 的目标成员都在、都能绑;MixinFit 把
Fabric 重映射后的 `@Accessor`/`@Invoker` 注解值整串当了成员名。三条都有**离线真字节探针**与一次
**W7Harness 客户端臂**(STRICT PASS / world=True / frames=1 / mod=OK / 46 s)佐证。

Method: read-only 字节先行 + 离线探针 + 一次真机客户端运行。合并基底与 guest 字节只读,不改。证据在
`evidence/`。**装置边界先写死**:W7Harness 客户端面**只能单人 quick-play**(`sweep_client.py:223` 硬编码
`--quickPlaySingleplayer`),所以 R2 的"加入纯 Fabric 服务器"**没有真机读数**,只有离线探针 + 客户机启动里
flush 行首次出现(见 §2)。

---

## 决定与提交(一次提交一个决定)

| # | 决定 | 提交 |
|---|---|---|
| D1 | **R2 修正** —— 同时注入二元/三元 `remap`;flush 同时挂 1.21.1 `RegistrySyncManager.apply` 与 26.2 `ClientRegistrySyncHandler.apply`;两入口登进 anchor ledger(HEDGE);改写"Known limit" javadoc | `dd9d0e9f` |
| D2 | **R3 折叠** —— 新增 `GuestClassScan`,一次遍历喂四条 guest audit;`AbiLinkAudit` 采纳 FixAbiAudit 的仲裁修复并改流式 `prepare/note/recordScan`;`FabricApiModuleLossAudit.scan`(无调用者)删除 | `d83a0387` |
| D3 | **R4 修正** —— `accessorAnchor/invokerAnchor` 用 `parseMember(value).name()`(与 Mixin 的 `TargetSelector.parseName` 同规则) | `023e9315` |

分支 `fix-sync-perf`,worktree `../syncperf-wt`,基 `main @ 4b565f01`。

---

## R2 —— 纯 Fabric 服务器:1.21.1 入口与 remap 签名重导

**机制(字节)。** 出厂的 fabric-api 0.116.17 里:
- `net/fabricmc/fabric/impl/client/registry/sync/ClientRegistrySyncHandler` **不存在**(该包只有
  `FabricRegistryClientInit`)——`RegistrySyncParityInjector` 却按这个名字找 flush 目标。
- 真正的客户端同步入口是 `net/fabricmc/fabric/impl/registry/sync/RegistrySyncManager.apply(Map, RemapMode)V`
  (static,void),由 `receivePacket(...)` 调用,而 `receivePacket` 只被客户端 `FabricRegistryClientInit` 调。
- `RemappableRegistry.remap` 是 **三元** `(String, Object2IntMap, RemapMode)V`(fabric 的 `SimpleRegistryMixin`
  把它加在 `MappedRegistry` 上,wrapper 继承);内核注入的是 **二元**
  `(Object2IntMap, RemapMode)V`,与接口不符 ⇒ `invokeinterface` 落到 fabric 自己的空字段 mixin 方法上(wrapper
  的 `MappedRegistry` 字段终生为空 ⇒ 静默 no-op)。
- gate-m14(证明"真 Fabric 服务器"的那次)钉的是 **26.2** 的 fabric-api 0.154.0
  (`run/gate-m14-fabric-server.sh:34`),那代确实有 `ClientRegistrySyncHandler` 与二元 `remap`。**该修复从未按
  1.21.1 重导**,BugHunt5 读到的"Known limit"javadoc 是 26.2 时代原件。

**修法(D1)。** `RegistrySyncParityInjector`:
- `giveWrapperBothContracts` 同时注入二元(26.2)与三元(1.21.1)`remap` 覆盖,分别转发
  `KernelForgeWrapperSync.stageFabricRemap`(新增 4 参重载)。
- `flushAroundFabricApply` 同时接受两个入口类:1.21.1 用 `apply(Map, RemapMode)V`,26.2 用 `apply(payload)V`。
- 两个入口登记为 anchor ledger HEDGE ⇒ 该类静默失效不再无声(旧代码正是这样漏掉的)。
- 改写 `KernelForgeWrapperSync` 与 `KernelRegistryRevert` 里过时的 javadoc(那句"Known limit, on purpose")。

**证据。** 离线探针(真字节,`evidence/r2-fabric-hook-probe.txt`):
真合并 `NamespacedWrapper` → 得到 `remap(String,Object2IntMap,RemapMode)V`(另有二元);真 remapped
`RegistrySyncManager` → `apply(Map,RemapMode)V` 头部得到 `beginSnapshotApplication`、RETURN 前得到
`finishFabricRemap`(RETURNs=1 flushed=1)。客户机臂里这一行**首次出现**:
`[Forbric/RegistrySync] flushing the Forge-wrapped registries' staged ids at 1 return(s) of
net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager.apply`(出厂 0.3.8 实例日志里**没有**这行)。
`RegistrySyncParityInjectorTest` 增加三元 remap 断言与 `RegistrySyncManager.apply` flush 测试。

**如实记的空白:** 没有真机 Fabric 服务器读数(装置只支持单人;gate-m14 要 26.2 的 fabric-api 与联网下载,
本机不可行)。R2 的运行时证据止于真字节探针 + 客户机加载该类时的 flush 行。

---

## R3 —— 启动期四条全 jar 扫描折叠为一次

`KernelBoot:361-375` 原本四条独立扫描,各自逐 jar `ZipFile` 打开、逐 `.class` `readAllBytes`:
`FabricApiModuleLossAudit`、`FieldDriftAudit`、`MergedBaseUncalledMethods`、`AbiLinkAudit`。D2 新增
`GuestClassScan.scan(jars, abiUniverse)`:一次遍历,每类字节读一次,分发给所有启用的 audit;各 audit 的
`note()` 判定不变,`PortingLayerAudit` 不折(只读 Forge-family 类,形状不同)。`AbiLinkAudit` 改为流式
`prepare/note/recordScan`(并采纳 FixAbiAudit 的仲裁半丢弃修复)。

**度量(离线探针,真 62 jar,`evidence/r3-scan-probe.txt`):**

| | reads | ms | fabric users | field hits | abi findings |
|---|---:|---:|---:|---:|---:|
| before(四条) | 20052 | 820 | 3 | 0 | 11 |
| after(一条) | 5013 | 424 | 3 | 0 | 11 |

读数判据(预登记 `passesBefore==4 && passesAfter==1`)成立;finding 计数逐列相同。
客户机臂里 `[Forbric/AbiAudit] scanned 66 jar(s) in 81 ms`(出厂实例两次 205 / 745 ms),且折叠的
`install mods call 2 of the 209 merged-base method(s)` 汇总行仍在。`GuestClassScanTest` 钉住"一次遍历喂满
四条"与单条开关可关。

---

## R4 —— transfer API accessor:误报,已修

`evidence/r2-r4-bytes-and-shipped-log.txt`:四个 accessor 的 `@Accessor/@Invoker` 值都是 Fabric 重映射后的
选择器形式(`container1:Lnet/minecraft/world/Container;`、`items:...NonNullList;`、`content:...Fluid;`、
`getWeight(Lnet/minecraft/world/item/ItemStack;)Lorg/apache/commons/lang3/math/Fraction;`),目标成员在合并基底
**存在且 name+desc 相符**。旧 `accessorAnchor/invokerAnchor` 把整串当成员名 ⇒ 必然 miss ⇒ 每个此类 accessor
都被报"cannot bind"(整个语料库都是这样)。D3 改用已存在的 `parseMember(value).name()`(与 Mixin 的
`TargetSelector.parseName` 同规则)。

**读数(客户机臂):** `guest accessor mixin … cannot bind` 行 **0**(出厂 0.3.8 实例日志 **56** 行),
其中 `fabric-transfer-api-v1` 的 **4** 行 **0**。`MixinFitTest` 22 项全绿,含"真缺目标仍要报"的反例。

---

## 验证与边界

- **客户机臂(W7Harness,`evidence/harness-row.txt`):** `run=PASS exit=0 world=True frames=1 mod=OK strict=TRUE
  confirmed_required=0 catalog_failures=[] cause=None 46s`;frozen kernel sha `117edc56…`。
- **混合 jar,如实记:** boot half = 本车道 HEAD `023e9315`;runtime half = 出厂 0.3.8-beta 的嵌套 jar 逐字节拷贝
  (本机游戏侧不可编译:energy-4.1.0-named 与 staged fixtures 缺失、/Volumes/ORICO 未挂载)。
  `git diff v0.3.8-beta-1.21.1..023e9315 -- src/runtime` 非空(4 文件,均在本车道改动之外)。
  ⇒ 该行只证"本车道 boot-half 改动 + 出厂 0.3.8 runtime 能进世界",**不**是 clean sha,**没有**削弱
  `:verifyRebornEnergy`。(本车道三处改动全在 boot half:`RegistrySyncParityInjector`、`KernelBoot`/`GuestClassScan`、`MixinFit`。)
- **全量单测:** 2963 项,31 红 / 1017 skip —— 红的都在本车道未触处(ModsButtonRedirector、TransferHooks、
  LootTableEventBridge、Spawner、KernelHudBridge、ForeignType 等,机器既有的 fixtures 缺失类红)。
  MixinFit/RegistrySyncParity/GuestClassScan/FieldDrift/FabricApi/PortingLayer/UncalledMethods/AbiLink 全绿。

## 证据清单(`evidence/`)

| 文件 | 内容 |
|---|---|
| `preregistration.md` | 跑前写下的判据/证伪条件 + 事后 §Adjudication(R2 预登记被证伪,如实记) |
| `r2-fabric-hook-probe.txt` | 真注入器跑真 `NamespacedWrapper` 与真 remapped `RegistrySyncManager` 的原始输出 |
| `r2-r4-bytes-and-shipped-log.txt` | 四个 accessor 的注解值字节 + 合并基底目标成员 javap + 1.21.1 `remap` 三元签名 + 命名类缺失 + 出厂日志计数 |
| `r3-scan-probe.txt` | 四条 vs 一条的真实 jar 读数(reads / ms / 三类 finding 计数) |
| `harness-row.txt` | W7Harness `results.jsonl` 逐字 + 运行里关键日志行 + accessor 行计数 mine/shipped |
| `frozen-kernel-sha256.txt` | 被 pin 的内核 sha |
| `instrument-R2Probe.java` / `instrument-R3Probe.java` | 两件离线探针的源码 |
