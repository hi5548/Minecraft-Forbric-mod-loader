# 客户端 datapack 声明跑在 NeoForge 总线存在之前 —— `neoforge:biome_modifier` 从未被声明（Shooting Star Demo 语境）

**Verdict (EN).** **Diagnosed and fixed, boot-side only.** The `Missing registry:
ResourceKey[minecraft:root / neoforge:biome_modifier]` in the context's log is not a merge gap and needs no new
declaration code: the merged base plus its companion `neoforge-runtime.jar` carry the whole biome-modifier
machinery, and `neoforge:biome_modifier` / `neoforge:structure_modifier` are declared by **NeoForge's own baseline
listener** (`NeoForgeMod.lambda$new$71`, verified with `javap`). What is broken is *when* the kernel posts that
declaration on a **client**: the Fabric client-entrypoint hook is wired into `Minecraft.<init>` **ahead of** the call
that drives `KernelLifecycle.driveNativeRegistration`, so the declaration runs with `baselineBus == null`, posts to
0 buses, declares nothing, and spends the process-wide one-shot. `ServerLifecycleHooks.runModifiers`' first
instruction is `registryOrThrow(neoforge:biome_modifier)`, so it threw before reading a single modifier and **the
entire biome/structure modifier pass did nothing** — NeoForge's own and MinecraftForge's, which ride the same pass.
Two boot-side edits fix it: `waitsForFabric` stops waiting once the Fabric mains have already run, and
`registerDataPackRegistries` checks `baselineBus` (field read only) before the one-shot and declines it when there is
nobody to declare to. One W7Harness client run on the user's exact 14 mods, against a reading pre-registered before
it: `before` = `posted … to 0 bus(es) — 0 declared` + `did not apply — -1 biome and -1 structure modifier(s)` +
`Missing registry: … neoforge:biome_modifier`; `after` = `posted … to 19 bus(es) — 2 declared`
(**`neoforge:biome_modifier`** and **`neoforge:structure_modifier`** in the list), that WARN **0**, that exception
**0**, and `applied NeoForge's 0 biome modifier(s) and 0 structure modifier(s)` instead. Both arms join the world
(`run=PASS world=true frames=1`).

**答案（中文）。** **已定位并修复，只动 boot 半边。** 语境日志里的 `Missing registry:
ResourceKey[minecraft:root / neoforge:biome_modifier]` **不是合并缺口，也不需要新写声明代码**：合并基底 + 伴生
`neoforge-runtime.jar` 完整携带 biome-modifier 机制，`neoforge:biome_modifier` / `neoforge:structure_modifier`
由 **NeoForge 自己的基线监听器**声明（`NeoForgeMod.lambda$new$71`，`javap` 已证）。坏的是**客户端**上这条声明被
post 的**时机**：Fabric 客户端 entrypoint 钩子接在 `Minecraft.<init>` 里、**早于**驱动
`KernelLifecycle.driveNativeRegistration` 的那次调用，于是声明在 `baselineBus == null` 时执行，post 到 0 条总线、
什么也没声明、并把进程级的一次性机会花掉。`ServerLifecycleHooks.runModifiers` 的第一条指令就是
`registryOrThrow(neoforge:biome_modifier)`，因此在读到任何 modifier 之前就抛异常 —— **整条 biome/structure
modifier 通道什么都没做**（NeoForge 的，以及搭同一趟车的 MinecraftForge 的）。两处 boot 半边修改即可：
`waitsForFabric` 在 Fabric main 已经跑过之后不再等待；`registerDataPackRegistries` 在花掉一次性机会之前先读
`baselineBus`（纯字段读），没有总线可投递时就不花。在用户真实的 14 只 mod 上跑一次 W7Harness（判据运行前已登记）：
`before` 逐字出现 `posted … to 0 bus(es) — 0 declared` + `did not apply — -1 biome and -1 structure modifier(s)` +
`Missing registry: … neoforge:biome_modifier`；`after` 变成 `posted … to 19 bus(es) — 2 declared`（清单里就是
`neoforge:biome_modifier` 与 `neoforge:structure_modifier`），那条 WARN **0** 次、那个异常 **0** 次，代之以
`applied NeoForge's 0 biome modifier(s) and 0 structure modifier(s)`。两臂都进世界（`run=PASS world=true frames=1`）。

---

## 0. 语境（用户自己的日志）与本次"复现的到底是哪一个失败"

`/Applications/.minecraft/versions/1.21.1-forbric/logs/latest.log`（2026-10-08，内核
`forbric-kernel-0.3.6-beta.jar`，该实例唯一一次装上两只 Shooting Star jar 的运行）。逐字锚点，按行号：

| 行 | 内容 |
|---|---|
| 921 | `[10:34:55] registries reopened for the Fabric client entrypoints` |
| 998 | `[10:34:56] posted datapack-registry declaration to 0 bus(es) — 0 declared ([]), 25 total` |
| 999 | `[10:34:56] declared forge:biome_modifier and forge:structure_modifier … MinecraftForge's serializer registries hold -1 biome and -1 structure modifier serializer(s)` |
| 1018 / 1026 | `[10:34:57] kernel CLIENT mod-loading window …` / `constructed NeoForge baseline mod on a native bus` |
| 1134 | `[10:34:59] datapack-registry declaration waits for the Fabric main and client entrypoints in Minecraft.<init>` |
| 1491 | `net.minecraft.server.ChainedJsonException: Invalid shaders/core/halley_sky.json` ← **日志里第一个致命 throwable**，见 §6 |
| 1540… | `The following mods have version differences that were not resolved:` ← 语境里的 **MISSING** 块（4 处） |
| 1607 / 1608 | `NeoForge's biome/structure modifiers did not apply — -1 biome and -1 structure modifier(s)` / `java.lang.IllegalStateException: Missing registry: ResourceKey[minecraft:root / neoforge:biome_modifier]` |
| 1723 / 1743 | `Charles_cai_5332 lost connection: Illegal packet received, terminating connection` / `RuntimeException: Failed to apply some object holders` ← 语境里的 world-exit，见 §6 |

**本车道复现并修复的，是 1607/1608 这一个失败**（每次进世界各一次，共 3 次）。日志里那个真正的"退出世界"
（1723，玩家被踢）**不是**它造成的：它出自 `net.minecraftforge.common.ForgeHooks.onCustomPayload` 的 side
检查（"Illegal packet received" 这个字符串只存在于 `forge-runtime.jar` 的 `ForgeHooks`），与本缺陷无因果。§6
把它写清楚，避免把不是本车道的账记在本车道头上。

## 1. 缺陷（字节与顺序，`evidence/byte-evidence.txt`）

1. **合并基底携带全部机制。** `neoforge-runtime.jar` 里 `BiomeModifier(+$Phase)`、`BiomeModifiers`、
   `NoneBiomeModifier`、`ModifiableBiomeInfo`、`NeoForgeRegistries$Keys`、`NeoForgeRegistries`、
   `NeoForgeRegistriesSetup`、`NeoForgeMod`、`DataPackRegistryEvent`、`DataPackRegistriesHooks`、
   `ServerLifecycleHooks` 全在；合并 jar 自身 `net/neoforged` 计数为 0（这些类全来自伴生 runtime jar，这是设计）。
2. **只有基线监听器声明这两个键。**
   `NeoForgeMod.lambda$new$71(DataPackRegistryEvent$NewRegistry)` 头两条语句就是
   `dataPackRegistry(Keys.BIOME_MODIFIERS, BiomeModifier.DIRECT_CODEC)` 与
   `dataPackRegistry(Keys.STRUCTURE_MODIFIERS, StructureModifier.DIRECT_CODEC)`；它在 `NeoForgeMod` 构造器里绑成
   `IEventBus` 消费者，也就是内核传进去的 baseline bus。整个 runtime jar 里没有第二个 `dataPackRegistry` 调用点。
3. **`runModifiers` 的第一条指令就是要这个键。**
   `ServerLifecycleHooks.runModifiers` 偏移 0–14：`registryAccess()` →
   `registryOrThrow(NeoForgeRegistries$Keys.BIOME_MODIFIERS)`。键不在，就在这里抛，任何 modifier 都还没被读；而
   MinecraftForge 的 modifier 是被 `ForgeWorldModifierInjector` splice 进这个方法**物化的那两个 List** 的，
   所以它们一起归零。
4. **客户端顺序把声明挤到了总线之前。** 日志 921(10:34:55) → 998(10:34:56) → 1018/1026(10:34:57) → 1134(10:34:59)：
   客户端 entrypoint 钩子先跑（它在 `Minecraft.<init>` 里被接在 `Options` 之前），它末尾那次
   `registerDataPackRegistries` 撞上 `baselineBus == null`（基线要到 1026 才被构造，mod bus 更在其后），post
   到 **0 条总线**；随后 driveNativeRegistration 里的 step 3a 因为 `waitsForFabric` 仍为真而只打了一行
   "waits"，而一次性闸门 `DATAPACK_REGISTRIES_DECLARED` 已被那次 0 总线调用花掉。**"投给谁都没有的一 post"被
   当成了"声明过了"** —— 这就是全部缺陷。

## 2. 内核该不该自己 seed/forward 这两个注册表？

**不该 seed；该让 NeoForge 自己的声明跑到。** 任务问的就是这一条，答案是：合并基底**自带**这两个 datapack
注册表（§1.1/1.2），内核若再写一份 `dataPackRegistry` 声明，就是复制 NeoForge 的语义（codec、`requiredNonEmpty`、
builder 回调都得跟着抄）并且会与基线监听器重复。内核该做的是 **forward**：让那份 `NewRegistry` 事件在
**baseline bus 已经存在**的时候投出去，并且**在没有总线可投时不消耗一次性机会**。本车道就是这两件事，没有新增
任何声明代码（对照：MinecraftForge 的 `forge:biome_modifier` / `forge:structure_modifier` 确实需要内核显式声明，
因为它们没有对应的基线监听器 —— 那是 `declareMinecraftForgeModifierRegistries` 已经做对的事，本车道没动它）。

## 3. 修复（两处，boot 半边，`evidence/diff.patch`）

- `DatapackRegistryDeclaration.waitsForFabric(side, fabricActive, mainsInConstructor, mainsAlreadyRan)`：
  新增第 4 个入参并在其为真时返回 false。等待的**唯一理由**是"它所触发的初始化器会跑 Fabric mod 代码，而在原生
  Fabric 上那段代码在世界加载时才第一次运行，晚于每个 main" —— main 已经跑过之后，"晚于 main"就是这次调用所在
  位置的固有属性，再等只会把它推到 `baselineBus` 还不存在的钩子里。
- `KernelLifecycle.registerDataPackRegistries`：在 `DATAPACK_REGISTRIES_DECLARED.compareAndSet` **之前**读
  `baselineBus`，为空则打一行并 return，**不花**一次性机会；后面的调用（step 3a，或 `onNeoClientSetup` 的
  "last chance"）会真正声明。这个检查**只用字段读**，因为为回答它而 `Class.forName` 会提前初始化
  `RegistryDataLoader` —— 那正是 `DatapackRegistryDeclaration` 整个类存在的理由所要避免的中毒。
- `KernelLifecycle` step 3a 调用点把 `KernelFabricEcosystem.mainsAlreadyRan()` 传进去（该查询早已存在，
  `Hooks.java:75` 同款用法）。
- 顺带修正 step 3a 上方那段已经过期的注释，并把 `KernelLifecycle` 里那条误导性的 "waits" 日志留在正确的位置。

## 4. 单测（`evidence/unit-test-reading.txt`）

```
./gradlew --offline test --tests 'net.forbric.kernel.boot.DatapackRegistryDeclarationTest'
test: 22 tests, 1 skipped, 0 failed
```

新增的一条断言 `anEarlyClientCannotDeclareBeforeTheBaselineExists`（`baselineBus` 读必须在一次性闸门之前、
且闸门之前的方法调用只允许那条诊断日志）**只把守卫块去掉再跑就 FAILED**：

```
DatapackRegistryDeclarationTest > anEarlyClientCannotDeclareBeforeTheBaselineExists() FAILED
  … "and check it BEFORE the once-guard, or the one shot is spent on a post to nobody == expected: <true> but was: <false>"
```

恢复后重跑即 `22 tests, 1 skipped, 0 failed`。所以这条断言不是同义反复，它区分的正是本修复去掉的那个形状。
（1 个 skip 是既有的、需 staged 游戏 jar 的 `theReconcilesReflectiveTargetsExistWithTheseShapes`，与
`2026-10-06-particle-accessor` 同因。）

## 5. W7Harness 运行（`evidence/gate-reading.txt`、`console-markers.txt`、`harness-stdout.txt`）

- 集合：用户真实 14 只 mod（原样取自 `versions/1.21.1-forbric/mods`，sha256 在 `evidence/mod-set.txt`），
  主体 `the-shooting-star-demo-1.3.1-neoforge.jar`，其余 13 只作闭包 —— 就是语境里那个场景本身。
- 装置：`w7/harness/sweep_client.py`，quick-play 进 `W7Client`，`-Dforbric.compatibilityPolicy=strict`，
  `W7_JAVA` 钉 JDK 21.0.7，stage / mc 根 / 热 remap 缓存都在 `/private/tmp/shooting-lane/`，
  boot 超时 900 / 停滞 300（只放宽超时，判据未动）。
- 三臂（`evidence/build-provenance.txt` 给出装配与 sha）：

| 臂 | 内核 | 声明读数 | `Missing registry` | `did not apply — -1` | `applied …` | run / world / frames |
|---|---|---|---|---|---|---|
| `before-published` | `16bcd602…`（用户装的那个 0.3.6-beta 原件） | `0 bus(es), 0 declared` | **1** | **1** | 0 | PASS / True / 1 |
| `before` | `389c889b…`（同环境下 HEAD 重建） | `0 bus(es), 0 declared` | **1** | **1** | 0 | PASS / True / 1 |
| `after` | `ec81fdcd…`（同环境重建 + 本修复） | **`19 bus(es), 2 declared`** | **0** | **0** | **1** | PASS / True / 1 |

  `after` 那条清单逐字含 `ResourceKey[minecraft:root / neoforge:biome_modifier]` 与
  `… / neoforge:structure_modifier]`，且新增的
  `asked for before any NeoForge bus exists …` 出现 1 次（早的那次调用现在**拒绝**花掉机会）。
- **A/B 的隔离**：`before` / `after` 是同一环境、同一次编译命令的产物，条目集合相同（各 735），**只有 3 个
  boot 半边条目不同**，全在 `diff.patch` 改动的两个文件内。`before-published` 作为语境臂保留，因为它就是用户跑过的
  那个 jar；它与本地重建在 121 个类上不同，差别只是编译器产生的 synthetic lambda 编号，不是源码差异（见 §7）。
- 两臂共同项（非回归）：`run=PASS exit=0 stopped=true killed=false world=true frames=1 confirmed_required=0`，
  13/13 依赖 `OK`，0 份 crash-report，主体状态与 32 条 findings 逐一致。

## 6. 明写的空白、损失与"不是本车道"的部分

- **没有一只 mod 带 modifier 可应用。** 两只 Shooting Star jar 都不含任何 `biome_modifier`/`structure_modifier`
  数据、类里也不引用 `BiomeModifier`（sibling 独立取证 + 本车道核对），用户 12 只 mod 亦然。所以这次证明的是
  **"注册表在、通道跑起来、计数从 -1 变 0"**，不是"某个非空 modifier 改了生物群系"——那是服务端 m25 gate 的事：
  `gate-m25-worldgen.sh` 断言 `applied NeoForge's [1-9][0-9]* biome modifier`（即**服务端的注册表非空**），它列在
  `2026-10-01-sweep100/gates-result.json` 的 `passed` 集合里，说明**服务端**的 step 3a 本来就在基线之后声明 ——
  这就是本缺陷**只在客户端**可见的原因。
- **world-exit 不在本车道。** 日志里的踢出（1723）是 Forge `onCustomPayload` 的 side 检查；日志尾部（1743）是
  `handleServerStopped` 里 Forge 的 `ObjectHolderRegistry.applyObjectHolders` 批量失败（含
  `Unable to find registry with key forge:biome_modifier_serializers`，1842 起 5 处）。二者都不由缺一个 datapack
  注册表导致，两臂都逐字照旧，没有一臂去改它们。
- **`halley_sky` 着色器失败（1491）是 mod 侧的。** sibling 用脱离 Minecraft 的 CGL 探针复现了同样两行：macOS 的
  GLSL 驱动预声明 `noise3` 返回 `vec3`，而 Addition 的 `float noise3(vec3 p)` 落在 `halley_sky.fsh:60` 属于"返回
  类型不同的重声明"。与合并无关，本车道没碰。
- `mod=DEGRADED`（三臂一致）是主体自己的既有状态：Demo jar 是 1.20.1/1.21.1 双包，其 `mc1211` 客户端类还引用
  `net.minecraftforge.client.event.RenderGuiEvent$Post`，合并基底没有该类。

## 7. 偏离与动作清单

- **只改三个文件**：`DatapackRegistryDeclaration.java`、`KernelLifecycle.java`（各含 javadoc/日志），
  `DatapackRegistryDeclarationTest.java`（新增 1 条断言 + 4 处签名跟进 + 把"一次性闸门必须是第一条指令"改成
  "必须先于任何能初始化游戏类的调用"，因为守卫现在排在它前面）。
- **先跑后登记判据，登记在手，未改**：`evidence/preregistration.md` 写在两次运行之前，事后只追加 §Adjudication。
  §Adjudication 里如实记了一条**需要收回的话**：预登记说"两臂只在 boot 半边、且只在这两处修改上不同" —— 对
  `before`→`after` 这一对为真，对 `before-published` 不成立（121 个类的编译器差异），所以补了第三臂，A/B 取在前一对上。
- **两条操作偏离**（判据均未改）：① 第一次 `after` 读数用的是 zip 时间戳未归一化前的 jar（`1543b40f…`），读数与报告
  的那次逐项相同，因可复现性复测一次；② 报告的臂里 harness 的**隐藏窗口 javaagent 是关的**——本车道运行期间
  sibling 正在改它，11:09 那版把裸 `RETURN` 注到 `GLFW.glfwShowWindow` 的入口帧之前，两臂**同样**得到
  `VerifyError: Expecting a stack map frame … GLFW.glfwShowWindow(J)V @1: getstatic` / `FAIL world=False 13s
  cause=verify`，据此判定是 agent 而非内核（作者随后确认：11:10 的 `visitFrame` 变体同样失败，真正的修复是
  `COMPUTE_FRAMES`）；随后以 `W7_SHOW_WINDOW=1`（`Transformer.transform` 直接返回 null）重跑得到上表读数。
  记为读数：两臂 console 都有 `[W7/HideWindow] agent installed; forbric.hideWindow=false`，而
  `[W7/HideWindow] patched org/lwjgl/glfw/GLFW…` **不出现** —— agent 装了但一个字节都没改。本车道每一条判据都是
  `console.log` 的行存在性 + 行的 `world`/`frames`，agent 只重写 `org.lwjgl.glfw.GLFW`，故二者等价；仍写明，
  因为"插装在被测物之外"是本行动的铁律。
- **只动 boot 半边**：`energy-4.1.0-named.jar` 在本机不存在 ⇒ `compileRuntimeJava` SKIPPED ⇒ 游戏半边无法在本机重建，
  故 `META-INF/jars/forbric-kernel-runtime.jar` 逐字节取自已发布 jar（其 sha 与用户实例解出的 runtime jar 相同）。
- 清掉了工作树 `build/` 里 **534** 个既有的 `X N.class` / `X N.*` 陈旧副本（其中 32 个已进过本车道第一只 boot jar），
  重建后两只报告 jar 各 735 条目、0 个这种条目。
- 未留诊断代码；临时物在 `/private/tmp/shooting-lane/`（臂的 rundir、jar、语料）。

## 8. 证据清单（`evidence/`）

| 文件 | 内容 |
|---|---|
| `preregistration.md` | **运行前**写下的集合、装置、逐条判据与证伪条件 + 事后 §Adjudication（判据原文未改） |
| `mod-set.txt` | 14 只 jar 的 sha256、三臂内核 sha、JVM/stage/mc/corpus/remap |
| `gate-reading.txt` | 三臂各自的 `results.jsonl` 行、`console.log` 标记计数、crash-report 数、load-report 行 + 跨臂汇总表 |
| `console-markers.txt` | 三臂逐字的标记行（含新那行 `asked for before any NeoForge bus exists`） |
| `harness-stdout.txt` | 三臂 harness stdout 原文 + 被取代的那次读数 + 两次被 agent 打断的运行 |
| `results-{before-published,before,after}.jsonl` / `results-row.json` | 该行逐字 |
| `byte-evidence.txt` | `lambda$new$71`、`runModifiers` 首指令、`Keys`、合并/伴生 jar 的类计数（`javap` 原文） |
| `unit-test-reading.txt` | `22 tests, 1 skipped, 0 failed`；以及"去掉守卫即 FAILED"那条断言 |
| `build-provenance.txt` | 三只 boot jar 的装配、sha、条目集合校验（差异恰 3 条）、产物卫生、为何补第三臂 |
| `diff.patch` | 本车道源码的全部改动（3 个文件） |
