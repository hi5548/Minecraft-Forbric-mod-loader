# Forbric on Minecraft 1.21.1 — 移植说明 / port notes

[中文](#中文) · [English](#english)

<sub>中文在前，英文在后 · Chinese first, English below</sub>

---

## 中文

### 这是什么

Forbric 原本针对 **Minecraft 26.2** 编写。本文档说明本分支把整个加载器**重定向到 Minecraft 1.21.1** 之后
的状态：目标版本、命名空间与重映射的做法、与 26.2 线的差别、实测兼容性，以及已知限制。

面向两类读者：想装来玩的玩家（看"安装"和"实测兼容性"就够），以及想知道这次移植到底动了什么的开发者。

### 版本与锁定

| 项 | 值 |
|---|---|
| 目标游戏 | **Minecraft 1.21.1**（唯一支持版本） |
| Forge | `1.21.1-52.1.16` |
| NeoForge | `21.1.252` |
| NFRT / 结果形态 | `2.0.18` / `gameJarNoRecomp` |
| 安装后的版本名 | `versions/1.21.1-forbric/`（启动器会显示为 Fabric，属正常） |
| 构建/运行 JDK | **21**（游戏与多个 mod 硬要求；JDK ≥22 会因 `sun.misc.Unsafe` 的移除而报错） |

版本锁定的唯一来源是 `forbric-kernel-installer/src/main/java/net/forbric/installer/kernel/Pins.java`。

### 与 26.2 线最大的差别：命名空间与重映射

这才是这次移植的主体，其余都是收尾。

- **26.2 是 Mojmap 原生的**：内核走**恒等映射**，没有重映射这一步。
- **1.21.1 是混淆的**，而且三个生态的命名并不一致。用每个加载器 50 个 Modrinth jar 做常量池普查的结果：
  Fabric 侧是 **intermediary**（49/50），MinecraftForge 与 NeoForge 侧是 **Mojmap `named`**（48/50、46/50），
  SRG 只在少数 mixin 目标里残留。
- 因此分工是**两边**而不是三边：合并基底、两个 Forge 系生态的 mod、两个载体，全都跑在 `named` 上；
  **只有 Fabric 客方需要改名**。`FabricGuestRemapper` 在 jar 进入受管类路径之前完成这件事：它用
  tiny-remapper（`ForgeModRemapper`）把每个将要被定义的 jar 从 intermediary 译到 `named`，包括解包出来的
  JiJ 子 jar；映射表来自安装器在命令行上暂存的两份 `--mappings` 文件。
- 重映射阶段还叠了一层**后处理通道**，各修一类"翻译漏项"（每一项都有独立报告与红绿测试）：
  `MixinNames`（选择器拼法）、`AccessWidenerRemapper`（访问放宽的命名空间）、`InheritedMemberRefs` /
  `InheritedMemberDecls`（继承成员）、`KotlinMetadataRemapper`（Kotlin `@Metadata` 里那张字符串表——它
  也是一个独立命名空间，不改它 `kotlin-reflect` 会按中介名找游戏类）。
- **纪律**：任何动到上述通道的改动，都必须**冷缓存重映射一个模块**才算落地；热缓存不构成证据
  （缓存按 `REMAP_VERSION` 键控，改代码不改输入时它不会自动重derive）。

合并基底本身也随之换了：`patched-mc-merged-1.21.1.jar` 的报告读数是
`forge=136 neo=7575 MERGED=572` 个类，冲突 `methods=901 fields=2 STRUCTURAL=12`
（仓库里仍提交着 26.2 那一代的那份，作为历史：`forge=193 neo=10163 MERGED=612`）。

### 安装

见 [README](README.md) 的"安装方法"一节，或直接下载本仓库 Releases 里的
`forbric-kernel-installer-*.zip`。命令行等价形式：

```bash
java -jar forbric-kernel-installer-0.3.6-beta.jar --doctor      # 只体检，不写文件
java -jar forbric-kernel-installer-0.3.6-beta.jar --dir "$HOME/Library/Application Support/minecraft"
```

三种加载器的 mod 都放进同一个 `mods` 文件夹；**卸载 = 删掉 `versions/1.21.1-forbric/`**。

### 实测兼容性

1.21.1 上，每个加载器**随机抽 50 个**主体（种子 `20261003`），逐个隔离启动、进世界、退出，JDK 21：

| 加载器 | 严格通过 | 原始率 | 可测口径 | 闭包完整口径 |
|---|---|---|---|---|
| NeoForge | 49/49 | 100% | 100% | 100% |
| Forge | 48/50 | 96% | 96% | 96% |
| Fabric | 34/36 | 94% | 97% | 100% |

三个口径的含义（同一个样本、不同分母）：

- **原始率**：所有"服务端测得了"的主体（排除纯客户端 mod）。
- **可测口径**：再排除"依赖要求运营方签发凭据、自动化测试台无法提供"的主体——与"专用服务器装不了纯客户端 mod"同一类限制。
- **闭包完整口径**：再排除"它自己声明的依赖在 1.21.1 上无法解析"的主体；**移出必须带失败证据**
  （该行自己的报错点名了那个缺失依赖的类），不是"声明了就算"。

剩下的失败全部是**主体自身原因**（自己缺依赖、自己要求的版本在 1.21.1 上不存在、依赖需要密钥），
逐条具名并附证据；在这一批样本里，**没有一个是加载器缺陷**。

### 三条纪律（这些数字为什么可信）

1. **量具在测试对象之外**：一行数据可能是被"它自己没记录下来的量具"造出来的。本分支的四个假象——
   错 JDK（4 行）、缓存冷热（1 行）、库型依赖静默（3 行）、报告写在报告根之外（1 行）——都是这一类，
   其中两次只靠"两个台架各跑一次、对不上"才发现。因此：**每次运行记录它用的 JDK**，输出一律落在报告根下。
2. **差值对"提交区间"归因**，不对某个提交的标签归因；`cr` 只在**同一 sha 的重复运行**之间可比。
3. **`world=true` 才允许引用 `cr`**：一个"从未进世界"的运行给出的 `cr=0` 什么都不是。

被撤回而不是被替换的数据也一律标注（例如两批在 JDK 25 上测得的运行）。

### 已知不生效的 Fabric API 功能（已记录损失）

这些是**内核侧已记录**的损失——启动不再被它们拦住，但对应的 Fabric API 功能在 1.21.1 上不生效：

| 功能 | 影响 |
|---|---|
| `ColorProviderRegistry`（方块/物品自定义颜色） | 注册与读取均失效；**另**：合并基底的 `BlockColors`/`ItemColors` 自身 `getColor` 查 `ForgeRegistries.*.getDelegateOrThrow` 的 Holder 键、`register` 存原始对象键、字段是 `IdentityHashMap`，三者不闭钥匙 ⇒ **原版自己的同色也在该基底上落空**（见下） |
| `FluidRenderHandlerRegistry`（自定义流体外观：染色/贴图/覆盖层） | 不生效，改由 NeoForge 的 FluidType 路径渲染 |
| `ParticleRenderEvents.ALLOW_BLOCK_DUST_TINT` | 不再被查询 |
| 客户端 `UseEntityCallback` | 不触发（同一 mixin 的其余注入器仍正常） |

原因分两类：合并时**字段名保住、类型变了**（`IdMapper` → `Map<…>`），或**调用点被另一家改写**（`renderBatched` 被加宽成 9 参）。**2026-10-04 复核**：加宽的那条（Indigo）与声音流那条已按 1.21.1 真实字节重锚并落地
（`64ea43cc` / `53f101da`，证据 `run/compat/reports/2026-10-04-inert-apis/`），已从本表移除；颜色族给出修复形状
（重绑 `@Shadow` 字段 + 正常化合并基底的取值键，两步一体）但未半落地；其余两条不是"锚点拼写"类问题，维持按代价退出。

### 已知限制与未覆盖

- **纯客户端方向未深度验证**：已验证客户端能进世界（quick-play）、能截图、能干净退出；更长的游戏内行为没有系统测过。
- **两条待办**：`gardnercraft`（其元数据要求的 Polymer 版本线在 1.21.1 上不存在）与 `beilin-data-portability`
  （依赖需要运营方密钥）——两者都是主体/环境侧，不是加载器侧。
- **26.2 时代文档保留**：`introduction.md` / `introduction.zh-CN.md` 描述的是 26.2 的设计，顶部有横幅说明；
  冲突处以 1.21.1 构建为准，冲突处已在正文中标明。
- **语料不随仓库分发**：mod jar 按需求在测试后删除，仅保留清单与每个文件的 sha256，可按哈希按需取回。

### 证据在哪

- 战役总账与复现命令：`w7/NIGHT_SHIFT.md`
- 每个内核修复的字节级证据、代价、被证伪的假设：`forbric-kernel/run/compat/reports/`
- 逐主体的运行行/日志/来源内核 sha：`w7/reports/2026-10-0*`

---

<a id="english"></a>

## English

### What this is

Forbric was written for **Minecraft 26.2**. This document describes the state of this branch after retargeting
the whole loader to **Minecraft 1.21.1**: the target version, how namespaces and remapping work now, what differs
from the 26.2 line, what was measured, and what is known to be missing.

Two audiences: players who want to install it (read *Install* and *Measured compatibility*), and developers who
want to know what the port actually changed.

### Versions and pins

| | |
|---|---|
| Target game | **Minecraft 1.21.1** (the only supported version) |
| Forge | `1.21.1-52.1.16` |
| NeoForge | `21.1.252` |
| NFRT / result shape | `2.0.18` / `gameJarNoRecomp` |
| Installed profile | `versions/1.21.1-forbric/` (your launcher will call it a Fabric version — expected) |
| JDK to build and run | **21** (the game and several mods require it; JDK ≥22 fails on the removal of `sun.misc.Unsafe` APIs) |

The single source of truth for those pins is
`forbric-kernel-installer/src/main/java/net/forbric/installer/kernel/Pins.java`.

### The real difference from the 26.2 line: namespaces and remapping

This is what the port is actually about; the rest was follow-up work.

- **26.2 ships Mojmap names.** The kernel ran **identity mapping**; there was no remap step.
- **1.21.1 ships obfuscated**, and the three ecosystems do not agree on names. A constant-pool census of 50
  Modrinth jars per loader measured Fabric as **intermediary** (49/50) and both Forge families as **Mojmap `named`**
  (48/50 and 46/50), with SRG surviving in a few mixin targets only.
- So the split is two-way, not three: the merged base, both Forge-family ecosystems' mods and both carriers all run
  as `named`, and **only a Fabric guest has to be renamed**. `FabricGuestRemapper` does that before a jar joins the
  owned classpath, driving tiny-remapper (`ForgeModRemapper`) from intermediary to `named` over every jar the loader
  will define, extracted JiJ children included; the mappings come from the two `--mappings` files the installer
  stages and names on the command line.
- The remap stage also carries a set of **post-passes**, one per class of translation gap, each with its own report
  and red→green test: `MixinNames` (selector spellings), `AccessWidenerRemapper` (the access-widener namespace),
  `InheritedMemberRefs` / `InheritedMemberDecls` (inherited members), and `KotlinMetadataRemapper` (the string table
  inside Kotlin `@Metadata` — a namespace of its own, without which `kotlin-reflect` looks up game classes by
  intermediary name).
- **Discipline:** a change to any of those passes is only "landed" after a **cold** remap of at least one module;
  a warm cache is not evidence (the cache is keyed by `REMAP_VERSION`, so identical input with changed code
  silently keeps serving the old bytes).

The merged base changed with it: `patched-mc-merged-1.21.1.jar` reads
`forge=136 neo=7575 MERGED=572` classes with `CONFLICTS: methods=901 fields=2 STRUCTURAL=12`
(the 26.2 generation's report is still committed as history: `forge=193 neo=10163 MERGED=612`).

### Install

See the *Install* section of the [README](README.md), or download
`forbric-kernel-installer-*.zip` from this repository's Releases. Headless equivalent:

```bash
java -jar forbric-kernel-installer-0.3.6-beta.jar --doctor      # check only, writes nothing
java -jar forbric-kernel-installer-0.3.6-beta.jar --dir "$HOME/Library/Application Support/minecraft"
```

Mods of all three ecosystems go into the same `mods` folder; **uninstall by deleting `versions/1.21.1-forbric/`**.

### Measured compatibility

Random 50-subject samples per loader on 1.21.1 (seed `20261003`), one isolated server per subject, enter a world
and exit, JDK 21:

| loader | strict pass | raw | rig-testable | closure-complete |
|---|---|---|---|---|
| NeoForge | 49/49 | 100% | 100% | 100% |
| Forge | 48/50 | 96% | 96% | 96% |
| Fabric | 34/36 | 94% | 97% | 100% |

The three rates share one sample and differ in their denominator:

- **raw** — every subject a dedicated server can measure (client-only mods excluded).
- **rig-testable** — additionally excluding subjects whose dependency demands an operator-issued credential, which
  an automated rig cannot provision (the same kind of limitation as "a dedicated server cannot load a client-only
  mod").
- **closure-complete** — additionally excluding subjects whose own declared dependencies cannot be resolved on
  1.21.1, where **removal requires the failure itself to name the missing dependency's class** rather than merely
  a declaration mismatch.

Every remaining failure is subject-side (a mod's own missing dependency, a version it demands that does not exist
for 1.21.1, or a dependency needing a key) and is named with its evidence; in this sample **none is a loader
defect**.

### Three rules (why these numbers are trustworthy)

1. **The instrumentation sits outside the artifact under test** — a row can be produced by an instrument the row
   does not name. Four such artefacts shaped this branch: the wrong JDK (4 rows), cache warmth (1 row), a silent
   library dependency (3 rows), and a proof boot written outside the reports root (1 row). Two were found only by
   cross-checking an identical boot against a peer's. Hence: **every row records the JDK it ran on**, and outputs
   belong under the reports root by construction.
2. **Attribute a delta to the commit range**, never to one commit's label; and **`cr` is only comparable across
   repeated runs of the same sha**.
3. **`world=true` before any `cr` counts** — a `cr=0` from a boot that never reached the world is no evidence.

Data that was withdrawn rather than replaced is labelled as such (for example two runs measured on JDK 25).

### Known inert Fabric APIs (recorded losses)

These are the kernel's **recorded** losses — they no longer stop a launch, but the Fabric API feature
itself does not take effect on 1.21.1:

| Feature | Effect |
|---|---|
| `ColorProviderRegistry` (custom block/item colours) | registration and lookup both inert; **and**: the merged base's own `BlockColors`/`ItemColors` disagree with themselves — `getColor` keys by `ForgeRegistries.*.getDelegateOrThrow`'s Holder, `register` stores the raw object, and the field is an `IdentityHashMap` — so **vanilla's own tints miss too** (see the 2026-10-04 inert-apis report) |
| `FluidRenderHandlerRegistry` (custom fluid appearance: tint/sprites/overlay) | inert; NeoForge's FluidType path renders instead |
| `ParticleRenderEvents.ALLOW_BLOCK_DUST_TINT` | no longer consulted |
| client-side `UseEntityCallback` | does not fire (the same mixin's other injectors still bind) |

Two causes: the merge **kept a field's name but changed its type** (`IdMapper` → `Map<…>`), or **another
family rewrote the call site** (`renderBatched` widened to nine arguments). **Re-checked 2026-10-04**: the widened
call site (Indigo) and the sound-stream anchor have been retargeted against the real 1.21.1 bytes and landed
(`64ea43cc` / `53f101da`, evidence in `run/compat/reports/2026-10-04-inert-apis/`), so both left this table; the
colour family has a two-step repair shape (rebind the `@Shadow` field + normalise the merged base's lookup key)
recorded but deliberately not half-landed; the remaining two are not selector-spelling problems and stay stood
down with their cost recorded.

### Known limits and what is not covered

- **The client direction is not deeply verified**: the client reaches a world (quick-play), takes a screenshot and
  stops cleanly; longer in-game behaviour has not been systematically tested.
- **Two rows remain**: `gardnercraft` (its metadata demands a Polymer version line that does not exist for 1.21.1)
  and `beilin-data-portability` (its dependency needs an operator-issued key) — both subject/environment-side, not
  loader-side.
- **26.2-era documents are kept**: `introduction.md` / `introduction.zh-CN.md` describe the 26.2 design, with a
  banner saying so; where they conflict with this branch, the 1.21.1 build is authoritative and the conflict is
  marked in the text.
- **The corpus is not distributed with the repository**: mod jars are deleted after testing as a matter of
  policy; only the manifest and each file's sha256 are kept, so any subject can be re-fetched by hash.
- **The mod jars are gone**; `w7/corpus/manifest.sha256.json` (in the campaign workspace) is what makes a re-run
  reproducible.

### Where the evidence is

- Campaign ledger and the reproduce commands: `w7/NIGHT_SHIFT.md`
- Byte-level evidence, costs and falsified hypotheses per kernel fix: `forbric-kernel/run/compat/reports/`
- Per-subject rows, logs and originating kernel sha: `w7/reports/2026-10-0*`
