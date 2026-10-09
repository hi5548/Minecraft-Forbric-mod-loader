# 0.3.9-beta —— 发布说明（三档）

**版本**：`0.3.9-beta`（安装器 `forbric-kernel-installer-0.3.9-beta`）
**来源**：`main` 尖端 `289af0b8`（seven fix-lane merges → 版本提交）。七条修车道：`fix-payload-damage`、
`fix-sync-perf`、`merge-fixes-seeder`、`merge-fixes-abi-audit`、`merge-fixes-merged-base`、`inert-apis`、
`inert-main-fix`（后两条为**空合并**，main 已含等价提交）。冲突一律按**真字节测量过的那一侧**裁决，逐条见
`NOTES.md` 与 `evidence/build-provenance.txt`。

**内核**（客户端 boot jar，带游戏侧，本次**从源码编出**）sha256：

```
c9c6abea9f81e9adf3ee363e53473067400749d25c3ace3f0b593cd594441cc8
```

**恢复的 energy 夹具 = 本版与 0.3.8 的实质差别（构建输入）**：0.3.8 的游戏侧**不可编译**（`energy-4.1.0-named.jar`
丢失，`build.gradle:91` 钉 sha `cec89d1c…`），其 runtime 半是从 0.3.7 **逐字节借来**的。ORICO 卷重挂后，
`/Volumes/ORICO/forbric/p0/p0/fixtures/energy-4.1.0-named.jar` 复现（sha = 钉值），本版因此是**全量内核**：

1. 干净 worktree `/tmp/rel039/wt-rel`（`git worktree add --detach … 289af0b8`）里
   `./gradlew --offline -q clean jar`（**JDK 25.0.1**，staged root `/tmp/rel039/stage`）→ **737 条目**
   （699 文件 + 38 目录），**0 条** `X N.class` 陈旧重名条目，nested runtime 半 sha
   `ffcffd1106a3e015a9a3a8892e31505a1ad91d953429262ac7eda02f8c4132c4`（292 条目）；
2. 同一提交在 canonical worktree 同装配复现**同一 sha**。

**入口集（entry set）相对已发布 `0.3.8-beta`**（逐条目 sha256）：added **1** =
`net/forbric/kernel/boot/GuestClassScan.class`；removed **0**；differing **26**（25 boot 类 + nested runtime），
逐条为七条车道的源码改动所覆盖。证据：`evidence/build-provenance.txt`。

**三臂 gate 运行**：`W7Harness` 客户端面（`w7/harness/sweep_client.py`），三臂都 `--kernel-jar` 钉住上面这只 jar
（未重建、未替换）；JDK 21.0.7，`strict` 策略，`mixinFit=default`，stage `/tmp/rel039/stage`，MC 根
`stubtable/mc`，世界夹具 `corpus-user12/client-world/W7Client`，一次一臂。预登记（运行前写下）在
`evidence/preregistration.md`，事后裁决追加在同一文件末尾。

| | 集合 | corpus | 逐字读数 |
|---|---|---|---|
| arm A | 用户真实 12（subject `modmenu-11.0.5.jar`，+11 闭包） | `w7/corpus-user12` | `run=PASS exit=0 world=true frames=1 stopped=true killed=false strict=TRUE confirmed_required=0 catalog_failures=[] mod=OK cause=None`，11 只依赖全 `OK`，0 份 crash-report，194s |
| arm B | 同一 12 + Create 6.0.10（subject `create-1.21.1-6.0.10.jar`） | `/tmp/rel039/corpus13` | 同上 `mod=OK cause=None`，13 只 `OK`，0 份 crash-report，179s |
| arm C | 同一 12 + 两只 Shooting Star（subject `the-shooting-star-demo-1.3.1-neoforge.jar`） | `/tmp/rel039/corpus14` | 同上 `strict=TRUE mod=OK cause=None`，14 只 `OK`，0 份 crash-report，139s |

三臂都 `joined world via quick-play: W7Client` → `client-ready after 200 world tick(s)` → `clean disconnect observed`。
完整行与 `results.jsonl` 逐字、以及每条 marker 的计数在 `evidence/gate-reading.txt`。

**安装**：`java -jar forbric-kernel-installer-0.3.9-beta.jar --dir /Applications/.minecraft`（exit 0；先 `--doctor` 亦 0）；
用户 **14 只 mod 逐字节不变（14/14）**、实例用户文件 mtime 一律未动、装上的内核 = 本次 gate 的 `c9c6abea…`，
且安装重建的游戏侧与三臂所跑 stage **逐条目内容相同**（merged 23535 / forge 4166 / neo 5030 条目，
added 0 / removed 0 / content-diff 0）。逐条证据 `evidence/install-verification.txt`、清单 `INSTALL-CHECKLIST.md`。

---

## 一档 · 实测通过（在真客户端、世界深度跑过）

1. **三条 gate 行全部按预登记读出**：三臂一致 `STRICT PASS / world=true / frames=1 / stopped=true / killed=false /
   confirmed_required=0 / catalog_failures=[] / 0 crash-report`，闭包依赖全 `OK`。
2. **本版七条新 marker：6 条按预登记达成**（payload-ordering、damage seams、registry-sync flush、abi 单遍扫描、
   seeder backfill、AbiAudit=0），**2 条被证伪**（accessor cannot-bind ≠ 0、render-layer 行 ≠ 0），归因见下。
3. **arm C 从 DEGRADED 变 OK（本版的改进）**：`the-shooting-star-demo` 在 0.3.5–0.3.8 每版都读 `DEGRADED`；
   abi-audit finding #5 修好后 AbiAudit 不再判罚其被仲裁丢弃的 Forge 半边 ⇒ 三臂都 `strict=TRUE`、`mod=OK`。
4. **不得回归的形状全部成立**：Forge 自定义注册表 9/0/0/0、Sodium cutout 2/0、颜色四条 marker 全在、
   fabric-particles 接口 mixin 0/0/2、`Unknown addon`/`lost connection` 各 0、`load-report.txt` 存在。
5. **安装校验（headless）**：exit 0；用户 14 只 mod 14/14 逐字节不变；实例用户文件 mtime 未动；全局 `mods/`（129 jar）
   未动；唯一有意变更 = `1.21.1-forbric.json`（0.3.8 → 0.3.9）；装上的内核 sha = gate 工件 = 安装器内嵌内核。

## 二档 · 仅离线证明（字节证据 / sibling 车道；未在世界深度复跑该条）

- **payload-damage**：R1（`enqueueWork` 两重载）与 A1（1.21.1 伤害接缝）的**功能半**由无 JVM 的离线探针在真载体
  字节上 red→green 证明（`2026-10-08-fix-payload-damage`）；本版三臂给它们的**生效路径读数**：`[Forbric/Damage]`
  各 4、miss 0；`[Forbric/PayloadOrdering]` 各 2。**A1 是 boot 级 run-proven**（并跨两具 buggy 内核
  `16bcd602`/`117edc56` 对过：0 生效 / 2 miss）；世界深度伤害行为不在任何臂的射程内（语料全 NeoForge-native，
  注入器以 Forge family 活跃为前提）。
- **merged-base #1**：render-layer 键修复的**行为半**（不透明 → cutout）只有离线真字节探针
  （`2026-10-08-merge-fixes/merged-base`）；本版三臂证明其**生效且能链接**（修复行各 2）。
- **sync-perf R2**：纯 Fabric 服务器上的 flush 无真机读数（装置只支持单人 quick-play）；运行时证据 = 真字节探针 +
  客户机加载该类时的 flush 行（三臂各 1）。
- **构建可复现**：`c9c6abea…` 在两个 worktree、同一装配下逐字节相同；入口集 delta 全是源码。

## 三档 · 已记录损失（不假装可用）

- **两条预登记被证伪，如实记**：
  - `guest accessor mixin … cannot bind` ≠ 0（arm B 读 1）：Create 自己的 `SystemReportAccessor` 真 miss，
    不是 R4 修掉的那类 transfer-api 误报（出厂 56 行 → 本版仅此 1 条真 miss）。
  - render-layer 修复行 ≠ 0（三臂各 2）：预登记按“语料无 mod 走该重载”写 0，被读数证伪（该修复在合并基底类本身改写）。
- **0.3.5–0.3.8 已记录的损失原样保留**：Indigo per-block 钩子（区块内自定义几何可能渲染错误或不渲染，
  §1.6 的第二次读数已把重锚撤回）、Mod Menu 标题行替换、`fabric-registry-sync-v0` 三条 pin 维持失效。
- **启动日志仍见** `[Forbric/Load] N / N / M mod(s) 有一部分没有跑起来`（三臂）和
  `[Forbric/EventMux] handleServerStopped forward failed`（night-config `ClassCastException`，三臂各 1 次）：
  预登记的第三条独立缺陷，本版不修。
- **`[Forbric/Mixin] … applies only partially`**：三臂各 28 条（既有形状），非本版回归。

## 不作超档声明

- 二档各项只有字节/离线证据，**没有**在真客户端世界深度复跑（含 render-layer 的行为半、纯 Fabric 服务器的 flush）。
- 一档各项的上限是“这一次运行观测到”，不是“每只 mod 的每项功能都试过”。
- 三臂都带 rule-3 的机器负载提示（`contended=true`，323–405% CPU busy）；本车道没有时限判据。
- 未在**安装实例**上再启动一次；启动级读数以上面三臂为准（内核字节同一、游戏侧逐条目同一）。

## 被证伪的预登记（原文摘要；判据未改）

| 预登记 | 读数 | 归因 |
|---|---|---|
| §2-#5 `guest accessor mixin … cannot bind` = 0 | arm B = 1 | Create 的 `SystemReportAccessor` 真 miss（非 R4 误报类） |
| §2-#7 render-layer 修复行 = 0 | 三臂各 2 | 修复在合并基底类本身改写，与语料是否用该重载无关 |
| §5 arm C `DEGRADED / strict=FALSE` | `OK / strict=TRUE` | abi-audit #5 修好后 demo 不再 DEGRADED（变好） |

## 发布结果回填

见 `evidence/release-result.txt`（tag / 附件 sha256 / headless 安装校验摘要）。
