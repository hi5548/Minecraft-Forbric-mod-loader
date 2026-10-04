# `[Forbric/Load] N mod(s) did not finish loading` 那一行：一个根，不是十个；错的是聚合层的用词(2026-10-04)

**一句话。** 这一行点了十个 fabric 模块，十个**全部是 DEGRADED、没有一个是 FAILED**：每个都是内核自己
**按名抑制/剪掉**了一个 mixin 或 injector(`MergedBaseMixinCompat.SUPPRESSED_MIXINS` / `GuestInjectorPruner`),
记成 `CONFIRMED, required=false` 的 finding,再由 `CompatibilityFindings.project()` 投到那一行的
DEGRADED 上。没有一个是模块自己的契约失败(没有构造器/入口点抛错;那些 `required=true` 的 finding 全是
SUSPECTED,按设计不 mark)。**加载路径真正欠的,是措辞**:三个聚合面把 `ModCatalog.Status.FAILED` 专属的
"did not finish loading"套在一个**零 FAILED**的集合上,而同一个文件/同一个界面的逐行叙述用的是
"partly did not run"。修的就是这三处聚合面;抑制决定本身一个没动。提交 `37ad693b`。

---

## 1. 复现(用户真实十二,通过的那一次)

`W7Harness`,`reports/2026-10-04-reviewfix-usermods/`,`per-mod/run/000-modmenu__fabric/console.log`,
构建 `e11e4fcb`,冻结 jar sha `451c6780f682df26bfc9b7fbc5eb26d17c25f7973219b2cfe3cceda945d1025f`,
`PASS / world=true / strict=TRUE / confirmed_required: 0`,102 s。逐字:

```
[09:28:44] [Server thread/WARN]: [Forbric/Load] 10 mod(s) did not finish loading:
  fabric-content-registries-v0, fabric-events-interaction-v0, fabric-item-api-v1,
  fabric-object-builder-api-v1, fabric-particles-v1, fabric-recipe-api-v1,
  fabric-registry-sync-v0, fabric-rendering-v1, fabric-rendering-fluids-v1, modmenu
  — details in .forbric-kernel/load-report.txt
```

(启动更早、findings 尚未定型时是 `11 mod(s)`,多一个 `fabric-loot-api-v3`;其 `ReloadableRegistriesMixin`
经 `PluginDeclinedMixins` 延后再问插件,插件不要它,那句话被清掉 → 回到 10。这是延后归因机制**在正常工作**
的一个观测,不是抖动。)

同一次运行的 `.forbric-kernel/load-report.txt` 里,这十条每一个都写着"**有一部分没有跑起来**"
(`partly did not run`),没有一条写着"没有完成加载"(`did not finish loading`)。

`[Forbric/Seed]` 13 行在,`LoadingModList` 正常播种——这一行与本车道(ResourcePackLoader / Sodium 配置)
无关,是那面墙消失后**暴露**在下一层的读数。

## 2. 一个根,不是十个

`.forbric-kernel/compatibility-report.json`:**非 OK 行 = 10,全部 `DEGRADED`,0 `FAILED`**。十个的
DEGRADED 全部只来自 `CONFIRMED` 的 finding;逐条的来源两条家族:

| # | mod | 来源 | 抑制掉的东西 |
|---|-----|------|--------------|
| 1 | `fabric-content-registries-v0` | `GuestInjectorPruner` | `canUseAsFuelRedirect` / `getFuelTimeRedirect`(`AbstractFurnaceBlockEntity.isFuel`) |
| 2 | `fabric-events-interaction-v0` | pruner | `injectUseEntityCallback`(`Minecraft.startUseItem`)、`breakBlock` / `onBlockBroken`(`ServerPlayerGameMode.destroyBlock`) |
| 3 | `fabric-item-api-v1` | pruner + `SUPPRESSED_MIXINS` | 5 条 crafting-remainder/enchanting injector + `EnchantmentHelperMixin` |
| 4 | `fabric-object-builder-api-v1` | pruner | `disableVanillaCheck`(`EmeraldsForVillagerTypeItem.<init>`) |
| 5 | `fabric-particles-v1` | `SUPPRESSED_MIXINS` | `BlockDustParticleMixin` |
| 6 | `fabric-recipe-api-v1` | pruner | `useCustomIngredientPacketCodec`(`Ingredient.<clinit>`) |
| 7 | `fabric-registry-sync-v0` | `SUPPRESSED_MIXINS` | `BlockColorsMixin` / `ItemColorsMixin` / `ParticleManagerMixin` / `RegistryLoaderMixin` |
| 8 | `fabric-rendering-v1` | `SUPPRESSED_MIXINS` | `BlockColorsMixin` / `ItemColorsMixin` |
| 9 | `fabric-rendering-fluids-v1` | `SUPPRESSED_MIXINS` | `FluidRendererMixin` |
| 10 | `modmenu` | pruner | `MixinTitleScreen.onRender`(`TitleScreen.render`) |

机制是一条:`recordRemovedInjector` / `reportNamedSuppressions` 记 `CONFIRMED, required=false` →
`CompatibilityFindings.project()`(第 130 行)对**任何** `CONFIRMED` 命中 modId 的行设
`Status.DEGRADED` → `ModCatalog.failures()` 返回它。`required=true` 的那些全是 `SUSPECTED`,按
`CompatibilityFindings.suspected()` 的契约"从不 mark mod",所以一条都没 mark——这正是设计的样子。

**所以是"一个根、若干次决定":** 根 = 内核自己的**命名抑制账本**(两类来源)被投影成 mod 级 DEGRADED;
"若干" = 十次各自独立、各自**有据可查**的抑制。它**不是**十个模块各自失败——没有一个模块自己的代码抛错
(那会是 `FAILED`,而 `FAILED` 只有两个来源:`@Mod` 构造被撤、Fabric 入口点抛错)。

## 3. 归谁:加载路径 vs 模块自己的契约

按 `read-mod-failed-rows.md` 的同一把尺子:

* **加载路径拥有(全部十条)**:每一条都是内核的**兼容机制自己**做的移除决定——`GuestInjectorPruner` 的
  表项或 `SUPPRESSED_MIXINS` 的 pin。这不是模块的构造器/入口点失败,是内核对"合并基底上这个锚点还在不在"
  的判定。
* **模块自己的契约拥有(零条)**:没有任何一条是模块自己的 required 契约未被满足。反向读也对:那十条里
  `required=true` 的 finding(如 `fabric-events-interaction` 的 `ServerPlayNetworkHandlerMixin` 漂移、
  `fabric-rendering-v1` 的 `ShaderProgramMixin` 缺 2 锚)全部是 `SUSPECTED`,**没有**参与 mark。

**被抑制的东西本身是刻意的、量过的**:`SUPPRESSED_MIXINS` 每一条都带 `javap` 证据与结明的代价(字段
descriptor 被 merge 改掉、`@Slice` 在合并基底上没有 end、seam 整体换了生态系统……),`GuestInjectorPruner`
每一条都有 `COSTS`/`REASONS` 行。**这些决定一个都没动。**

## 4. 加载路径真正欠的那一处:聚合层的措辞

`ModCatalog.Status`(第 125–135 行)把话定死:
`FAILED` = "did not finish loading",`DEGRADED` = "part of this mod did not run"。
`KernelLoadReport.render` 的**逐行**部分也照此区分。但**三处聚合面**把失败那句套在整个非 OK 集合上:

1. `KernelLoadReport.writeTo` 的 `[Forbric/Load] %d mod(s) did not finish loading: …`(parent 引的那一行);
2. `KernelLoadReport.render` 的报告标题 `N mod(s) did not finish loading this time.`;
3. `KernelModListScreen.summary()` 的 `N did not finish loading`。

在**零 FAILED**的集合上,这三句把十个**类都在、其余 mixin 都织上**的模块说成"没有完成加载"。这正是
`e253f116`("a report that cries wolf is read the same way the next time, when it is real")与
`ba679a99`("The wording is the part that is easy to get wrong")一族里反复要避免的形状,而且同一个报告/
同一个界面自己的逐行叙述已经说了"partly did not run"——聚合面与逐行面自相矛盾。

**修法(仅此三处,语义零变)**:按 `Status` 拆成两句,各用各自的话:
`X mod(s) did not finish loading`(FAILED)与 `Y mod(s) partly did not run`(DEGRADED);报告标题、WARN、
Mods 头行三处一致。`clean` 判定(`failures.isEmpty() && unattributed.isEmpty()`)与文件写入时机**未动**:
DEGRADED 仍写文件、仍上 Mods 界面、仍带原因。改的只是**不再用失败的话说一个没有失败者的集合**。

## 5. 红 → 绿(离线,真实 staged 字节)

两处主源码改动:`KernelLoadReport`(WARN + 标题)与 `KernelModListScreen.summary`。

```
cd forbric-kernel && ./gradlew test \
  --tests 'net.forbric.kernel.boot.KernelLoadReportTest' \
  --tests 'net.forbric.kernel.runtime.KernelModListScreenPointersTest' \
  -Pforbric.stagedRoot=/Volumes/ORICO/forbric/p0/p0/run \
  -Pforbric.rebornEnergy=/Volumes/ORICO/forbric/p0/p0/fixtures/energy-4.1.0-named.jar \
  -Pforbric.fabricApi=/Volumes/ORICO/forbric/p0/p0/fixtures/fabric-api-0.116.17+1.21.1-named.jar \
  --console=plain --offline
```

* **修前**(`git stash` 掉两处主源码改动,只留新断言):`20 tests, 4 failed` —— 正是
  `besideARealFailure…:252`、`aModThatOnlyDegradedIsNotCalledBroken:358`、
  `theWarningNamesTheTwoSeveritiesSeparately:387` 等。
* **修后**:同一命令 `22 tests, 0 skipped, 0 failed`。
* 直接受影响的整套(含 `LoadReportDedupContractTest`、`PushAndRunTest`、`KernelModCatalogTest`):
  `65 tests, 0 skipped, 0 failed`。
* **全量套件**(同一 staged 属性):`2947 tests, 668 skipped, 3 failed` —— 三条全部**修前既有**,与本改动无关
  (把三处改动全部 `git stash` 掉后重跑这三类:同样的 `19 tests, 3 failed`),分别是
  `KernelRuntimeClassesTest.everyGameSideClassTheBootSideNamesIsInTheRegistry`、
  `CompatProtocolTest.protocolNamesEveryShippedScript`、`ForeignTypeTest.noConceptIsStillWrittenOutUnderBothFamiliesOutsideThisEnum`。

新增/收紧的用例(行为断言,不测源码文本):
`aModThatOnlyDegradedIsNotCalledBroken` 现在断言 DEGRADED-only 的报告标题是
`1 mod(s) partly did not run this time.` 且**整篇不含** `did not finish loading`;
`aMixedReportNamesEachSeverityInItsOwnWording` 断言 FAILED+DEGRADED 混篇的标题与逐行;
`theWarningNamesTheTwoSeveritiesSeparately` 断言 WARN 分两句、且**不**把两档并成一个 id 列表。

## 6. 交给 W7Harness 的验证读数(运行前登记,禁止事后改判据)

构建自本提交,集合 = 用户真实十二,冷/热 remap 缓存均可,JDK 21。四条:

1. `[Forbric/Load] 10 mod(s) partly did not run: fabric-content-registries-v0, … , modmenu` **出现**
   (同样的十个 id,同样的顺序)。
2. `[Forbric/Load] N mod(s) did not finish loading:` 在本运行里**出现 0 次**(零 FAILED)。
3. `world=true`、`confirmed_required: 0`、`joined world via quick-play` ≥ 1 —— 与修复前**逐字不变**。
4. `load-report.txt` 里仍是这十条、每条仍是 `有一部分没有跑起来`,原因逐字不变;无新增崩溃报告。

(`[Forbric/Load] every mod finished loading` **不应**出现:clean 判定未动,十条 DEGRADED 仍非 clean。
这是预期,不是回归。)

## 7. 没做什么(以及为什么)

* **没有动任何抑制决定**(`SUPPRESSED_MIXINS` / `GuestInjectorPruner`):每条都有测过的字节证据与结明代价,
  且 `2026-10-03-merge-deleted-anchors/README.md` 已确立"整 mixin 的 pin 只在唯一锚点已死时用,per-injector
  的丢失只报告、不抑制"的判据;本行的十条都落在这套判据内。
* **没有把 DEGRADED 从"失败"里摘出去**(不改 `clean`/`failures()`):`e88a4298` 明写"fabric-api modules
  with hand-listed or pruned mixins now show DEGRADED with the reason"是刻意设计;本报告只让**话**与
  `Status` 一致,不改变**哪些**被报告。
* **记一处未修的加载路径缺口(与本行无关,留作独立车道)**:`GuestInjectorPruner` 的
  `recordRemovedInjector` 直接 `CompatibilityFindings.record`,**不**经 `PluginDeclinedMixins.defer`
  ——而命名抑制 `reportNamedSuppressions` 是经的。也就是说 `e253f116` 的不变式
  ("不为一个被它自己插件关掉的 mixin 标记该 mod")在 pruner 这条生产者上还没接上。**在本集合上它是空转的**:
  涉及的 6 个 mixin config(`fabric-content-registries-v0` / `fabric-events-interaction-v0` /
  `fabric-item-api-v1` / `fabric-object-builder-v1` / `fabric-recipe-api-v1` / `mixins.modmenu.json`)
  **全部 `plugin=None`**(已逐 config 核对 remap 缓存里的 jar),没有插件可问,故不会改变本行。

## 8. 验证读数回填(`W7Harness`,逐字)

构建 `3183f213` / 代码 `37ad693b`,sha `c1925b0ba423eecdea9e8631ef137bdb3ca67b49a43275641e90dbd3ada3378a`,
用户真实十二,JDK 21,**热** remap 缓存(与控制同 corpus,未重 remap),`reports/2026-10-04-load-row-degraded/`:

```
row: run=PASS  exit=0  world=true  frames=1  strict=TRUE  confirmed_required=0  seconds=60  crash reports: 0
joined world via quick-play: 1
'[Forbric/Load] N mod(s) did not finish loading:':   0            <- 登记 #2 达成
'[Forbric/Load] N mod(s) partly did not run:':       4 emissions (11, 11, 10, 10)   <- 登记 #1 达成
'every mod finished loading':                        0            <- must-not-appear,如期缺席
```

* **#1 达成。** 新行出现,id 集合与顺序**逐项**与对照(`bf56012d`)相同(程序化 diff:added none /
  removed none / order identical)。发射形状两边一致:**早 11(×2)→ 晚 10(×2)**——预登记的"10 mod(s)"
  是**晚**形,逐字在场;早形不是新增,对照也在发。**只有那句话变了,集合没动。**
* **#3 达成**,逐字与修前相同:`world=true`、`confirmed_required: 0`、joined quick-play。
* **#4 达成。** 报告仍列同十条、同顺序,每条仍 `有一部分没有跑起来`,原因逐字一致,0 份崩溃报告。
  标题的前后对照,就是本修复的一句话:

```
control (bf56012d):  这一次启动，有 10 个 mod 没有完成加载。
new     (3183f213):  这一次启动，10 个 mod 有一部分没有跑起来。
```

> 留一条过程教训(W7Harness 主动记录,值得转抄):他用 `grep -c 有一部分没有跑起来` 数到 **11**,一度
> 以为正文/标题不一致、差点报成"你的 bug 类复发"。没有。`grep -c` 数的是**含该短语的行**,而新的**标题**
> 就含它——10 条正文 + 1 条标题 = 11。按结构解析才是 **10 个 owner,同十条、同序**。**判据只认结构,不认
> 计数**——这是今天第五次栽在这个形状上,也是第二次栽在他自己手里。
