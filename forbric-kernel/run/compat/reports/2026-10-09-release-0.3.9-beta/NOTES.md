# 0.3.9-beta —— 发布笔记（notes）

**报告目录**：`forbric-kernel/run/compat/reports/2026-10-09-release-0.3.9-beta/`
（本文件 = 该目录下的 notes path：`…/2026-10-09-release-0.3.9-beta/NOTES.md`）

## 一句话

七条修车道的分支全部合入 `main`（冲突按**真字节测量过的那一侧**裁决），用**恢复后的 energy 夹具**编出
**全量内核**（boot 半 + 游戏侧 runtime 半），三臂 gate 全 `STRICT PASS`，`0.3.9-beta` 已打 tag、发布、
headless 装进 `/Applications/.minecraft`；用户的 14 只 mod 逐字节未动，装上的内核 sha == 本次 gate 的工件。

## 版本坐标

| 项 | 值 |
|---|---|
| tag | `v0.3.9-beta-1.21.1` |
| 源码尖端 / version commit | `289af0b8` |
| 内核（boot jar，带游戏侧）sha256 | `c9c6abea9f81e9adf3ee363e53473067400749d25c3ace3f0b593cd594441cc8` |
| 安装器 jar sha256 | `4b87876a3a481da7742757327896508524c9e42d5a400449892d355e4348eb94` |
| 安装器 zip sha256 | `a5dcf3d2932c249452c416520c49e6ad6fd70fb8f14deaa8b33c35970aebdcd3` |
| 装上的内核 | `/Applications/.minecraft/libraries/net/forbric/forbric-kernel/0.3.9-beta/…jar`（= `c9c6abea…`） |

## 七条车道怎么合的（一条决策一行）

| 车道 | 贡献 | 合入方式 |
|---|---|---|
| `fix-sync-perf` | R2（1.21.1 fabric-registry-sync 入口与三元 remap）、R3（四条启动扫描折叠成一条 `GuestClassScan`）、R4（MixinFit 的 @Accessor 解析） | 3 路干净合并 |
| `merge-fixes-seeder` | S3（`FMLLoader.loadingModList` 从空表改为携带 mod + read-back） | 干净合并 |
| `merge-fixes-abi-audit` | finding #5（AbiAudit 不判罚仲裁丢弃的那半个 jar） | 冲突：`AbiLinkAudit.java` 与 sync-perf 的流式改写重叠 → **取 sync-perf 侧**（它已内含仲裁修复，且是流式单遍实测版） |
| `merge-fixes-merged-base` | #1/A3（Forge `setRenderLayer(Block,ChunkRenderTypeSet)` 回原始 Block 键 + 值转 NeoForge）、#4/B1（`RegistrySynchronization` 记为良性分歧） | 干净合并 |
| `fix-payload-damage` | R1（`enqueueWork` 两个重载都摘掉同线程捷径）、A1（Forge 伤害接缝认 1.21.1 形状） | 干净合并（含 `d0a2ec22` / `7b2dbf2a` 的读数回填） |
| `inert-apis` | 声音流重定向、Indigo per-block 重锚、颜色族形状 | **空合并**：`main` 已含等价提交（`2868ebd6`/`ca3152e7` 等）；冲突（README、`MergedBaseMixinCompat`、`GuestInjectorPruner`）全部取 **main 侧** |
| `inert-main-fix` | Indigo 重锚的**撤回**（第二次世界深度读数 NPE）+ 编译修复 | **空合并**：`main` 已含等价提交（`27208fbf`/`c4835ceb`） |

**冲突裁决规则（本任务要求“按真字节测量过的那一侧”）**：
1. `AbiLinkAudit` —— `fix-sync-perf` 的 `prepare/note/recordScan` 流式版本已把 abi-audit 的“按 (mod, family) 问仲裁、
   不判罚被丢弃的 family”规则包含进去（`note()` 里的 `droppedFor/familyOf` 过滤），且其读数（R3 折叠：reads 20052→5013）
   是实测的；abi-audit 的分支版本是同一规则的旧（非流式）形态。⇒ 取 sync-perf。
2. `GuestInjectorPruner` / `MergedBaseMixinCompat` —— `inert-apis` 的版本摘掉了 Indigo 的 interim 剪枝条目并把 Mod Menu
   重新 pin 回去；但**第二次世界深度读数**（`64f91eb8…`：retargeted handler 内 `TerrainRenderContext` 为 null 的 NPE）
   已把 Indigo 重锚**证伪**，`main` 因此恢复了 interim 剪枝（`27208fbf`）；颜色族也已由 `main` 的 `ad9d5615`（2026-10-06，
   fabric-rendering-v1 两个颜色 mixin 重绑）推进到 3 条 pin + 2 条重绑。⇒ 取 main。
3. `abi-audit.py` / `fapi-usage.py` / `PORT-1.21.1.md` / inert-apis 的探针证据文件：无冲突，自动合并（脚本与文档按各自规则并存）。

## 与 0.3.8 的实质差别

- **恢复的 energy 夹具**：0.3.8 的游戏侧不可编译（`energy-4.1.0-named.jar` 丢失，钉值 `cec89d1c…`），runtime 半
  是从 0.3.7 逐字节借的；ORICO 卷重挂后夹具复现，本版**从源码**编出游戏侧（含 guards 车道四条 runtime 修复）。
- **入口集相对 0.3.8**：added 1（`GuestClassScan`）、removed 0、differing 26（25 boot 类 + nested runtime）。
- **arm C 从 DEGRADED 变 OK**：`the-shooting-star-demo` 之前每版都读 `mod=DEGRADED cause=mod-degraded`；abi-audit 的
  finding #5 修好后，AbiAudit 不再判罚该 jar 被仲裁丢弃的 Forge 半边 ⇒ 三臂都 `strict=TRUE`、`mod=OK`。

## 如实记录（不藏）

- **两条预登记被证伪**（判据原文未改，见 `evidence/preregistration.md` §Adjudication）：
  - §2-#5「`guest accessor mixin … cannot bind` = 0」：arm B 读 **1** —— 是 Create 自己的
    `accessor.SystemReportAccessor`（`SystemReport.oPERATING_SYSTEM`/`jAVA_VERSION` 被合并基底重写/移除），**真 miss**，
    不是 R4 修掉的那类误报（出厂 0.3.8 实例日志有 **56** 行 transfer-api 误报，本版 **0**）。
  - §2-#7「render-layer 修复行 = 0」：三臂各读 **2** —— 该修复在**合并基底类本身**（`ItemBlockRenderTypes`）上改写，
    与“语料里有没有 mod 走 Forge 重载”无关；我按“语料 0 个走该重载”预登记，被读数证伪。修复的**行为半**（不透明→cutout）
    仍只有离线探针证据（`merge-fixes/merged-base` 车道）。
  - §5 arm C 预登记 `DEGRADED/strict=FALSE`：读数为 `OK/strict=TRUE`（见上，是**变好**的证伪）。
- **第三条独立缺陷仍在**：`[Forbric/EventMux] handleServerStopped forward failed`（night-config 的
  `ClassCastException`）三臂各 1 次，本版不修（预登记即如此）。
- **rule-3 负载提示**：三臂 `contended=true`（323–405% CPU busy，load 15–21），本车道无时限判据。
- **构建环境的一次不顺（写在前头）**：canonical worktree 的 `forbric-kernel/build/classes` 里残留过一批
  `X 2.class`（某次带重名源的增量编译留下的陈旧产物，源文件已不在），`./gradlew jar`（不带 clean）会把它们打包。
  `w7/harness/build-kernel.sh` 正是为此拒绝陈旧重名 jar；release kernel 在**干净 worktree** 里 `clean jar` 产出
  （`c9c6abea…`，0 条陈旧），安装器 bundling 前也先 `clean` 了 canonical 引擎，装上的内核 sha 与 gate 工件逐字节相同。
