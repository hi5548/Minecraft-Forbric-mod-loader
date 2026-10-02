# The two remaining fabric rows were one layer each — pruner prefix and remap cache key

Slice `2026-10-03-fabric-5ids`（W7Harness，10 主体）在 10 条 stand-down 之后只剩两个 id：`disableVanillaCheck`(8×)、
lifecycle 的 `hookOnPlayerConnect`(7×)。两者都不是「某个 mixin 的问题」，各自是一层的错。

## 1. `disableVanillaCheck` — 剪枝条目的前缀写成了模块的拼写（`5996c81f`）

真实启动里剪枝器**拒单**（W7Harness 的控制台原文）：

```
[Forbric/GuestInjectorPruner] …TradeOffers…FactoryMixin.disableVanillaCheck no longer injects into <init>
  — fabric-api reshaped the mixin, leaving it untouched
```

而我无头驱动 `transform(...)` 时它被剪掉——同一条目、同一个类。差别只在读到的字节。

读 **slice 自己那份** guest 字节（`…/run/000-cristel-lib__fabric/.forbric-kernel/remap -> /tmp/w7-remap-cache-8827/
fabric-object-builder-api-v1-0.116.17-785dfcb712156a4a.jar`，即加载器实际读的那份）：

```
disableVanillaCheck  @Redirect  method=[Lnet/minecraft/world/entity/npc/VillagerTrades$EmeraldsForVillagerTypeItem;<init>(IIILjava/util/Map;)V]
```

名字层（`MixinNames`）会**先**把 member 选择器过一遍模块 refmap（`"<init>"` → 上面那个活描述符），所以
`Prune.selectorPrefix` 必须写活选择器；我写的 `"<init>"` 与它没有公共前缀，守卫于是判定"注入器不再注入到
`<init>`"并整体 stand-down。**不是 fabric-api 重塑了 mixin，是守卫按模块的写法去看运行期的名字。**

逐字节红→绿（同一份 slice 字节，同一驱动器）：

| 前缀 | AFTER |
|---|---|
| `"<init>"`（旧） | `[<init>, disableVanillaCheck, failOnNullItem]` ← 控制台那次拒单 |
| `L…EmeraldsForVillagerTypeItem;<init>`（新） | `[<init>, failOnNullItem]` ← 剪掉，且 `@At(NEW)` 的 `failOnNullItem` 留下 |

**软跳 vs CONFIRMED 的对账**：两个读者、两个时刻。控制台那行是剪枝器**自己的守卫**在拒单，**它没有改字节**；
审计的 CONFIRMED 来自 MixinFit 对**将被子装载的类**的判定——未剪的 mixin 带着一条绑不上的 redirect，施加时软跳，
审计照旧计入。守卫一修，两条同时消失。

## 2. `hookOnPlayerConnect` — 热缓存供应了修复前的 jar（`459687e9`，常量值与另一 lane 共享为 `-11`）

**审计渲染的是翻译后的字节**，这一点有 file:line：`KernelGuestMixinAdapter:192`
`byte[] classBytes = resource.apply(pkgPath + "/" + mixin + ".class")` 读的是**classpath 资源**（加载器将装载的
那份，post-remap/post-`MixinNames`），`:218` 交给 `MixinFit.evaluate`，`:151` 渲染 `N/M anchors resolve, missing: …`。
所以那条 pre-rename 拼写**不是**渲染顺序的歧义——审计与 Mixin 读同一个字符串（这正是 `MixinNames` 注释里写的设计）。

同一份 jar 里的指纹：

```
hookOnDataPacksReloaded @At.target = Lnet/minecraft/network/protocol/common/ClientboundUpdateTagsPacket;<init>(Ljava/util/Map;)V   ← 已翻译
hookOnPlayerConnect     @At.target = Lnet/minecraft/network/packet/s2c/play/SynchronizeRecipesS2CPacket;<init>(Ljava/util/Collection;)V ← 未翻译
```

同一个文件、同一条路径，method 选择器**两者都已翻译**（旧行为），`@At` 目标只有一条是——这正是
「缓存由修复前的内核产出」的指纹。缓存键是 `ForbricCache.key(REMAP_VERSION, intermediary, mojmap)` + 输入 jar
的 SHA-256：**输入没变、代码变了**，热缓存于是继续供应旧输出。该阶段自己的 javadoc 早已写明这个坑，
上一轮为 `InheritedMemberRefs` 提到 `-9` 就是同一处置。

**处置**：提常量让每份热缓存重派生一次（每个缓存目录一次全量 remap）。值与 FabricBootFails2 的
`-11-inherited-member-decls` **合并为一个**（见下）：常量只是缓存键的 token，一次新缓存目录同时覆盖两处修复。

**判据（预先写定）**：冷缓存重跑 10 主体切片后，lifecycle 那 7× 行消失；若仍在，待查的是 refmap **值**重写把已翻译
的选择器映回（代码顺序上不太可能：`translate()` 先用**原始** refmap 表翻译选择器、再改写 refmap 值，值改写无法把
named 变回 intermediary）。

## 3. off-arm pin 的逐条目判断（`8d4e5838`）

给两条新剪枝条目各加一条 `SUPPRESSED_UNLESS_PRUNED` 是**错**的，探针当场否掉：12 个条目里 9 个没有 pin。
该列表存在的理由是**半应用**（ModelManager：未剪的 mixin 仍施加，把读空的流交给反序列化器，静默全灭）。
两条新条目的未剪状态是**软跳**（交易所，且其已能绑定的 `create` 兄弟照常施加——正是剪枝器要保留的行为）或
**响亮中止**（balm，`InvalidInjectionException`）：pin 会**改变** OFF 臂而不是还原它。判断写进列表旁注释 +
把剪枝器 javadoc 里"off 臂放回全 mixin pin"限定到 ModelManager；另加机械检查
`everyPinNamesAPrunerEntry`（pin 必须对应条目且 config 一致——pin 比条目活得久会静默压掉一个可能已能施加的 mixin）。

## 4. 一次提交事故与由此确立的 pin 规则

`459687e9`（我提的常量）**把另一条 lane 在 `FabricGuestRemapper.java` 的未提交改动一起提了**：提交里出现
`InheritedMemberDecls.translate(out, spine)`，而该类当时只是**未跟踪文件**，干净 worktree 在该提交上**编译失败**；
共享树能编译只是因为那个未跟踪文件正好在树里。W7Harness 用 **clean worktree at the commit** 构建才发现——这正是
今天一直在清理的"看起来像成品的东西不是它声称的东西"。

**规则（本轮确立，写在这里以便下一位读者）**：*一个 sha 只有在干净 worktree 于其上能构建时才算 pin。* 提交前对每个
待提交文件 `git diff` 逐块确认「这段字是我写的」；跨 lane 共用一个文件时，提交只带走自己的 hunk。

处置：不重写该提交（其内容就是当前树上的真实状态），由 FabricBootFails2 把 `InheritedMemberDecls.java` + 调用 +
`-11` 常量作为一个单元提交，`459687e9` 随之可解析；常量保持 `-11`（一次新缓存目录覆盖两处）。**该提交已落地：
`e322c8e8`**（含 `InheritedMemberDecls.java` 与其测试）。`459687e9` 的 message 仍写 `-10`、内容为 `-11`——
不改写（改写会动到他人子提交），差异记录在此。

## 状态（2026-10-03 收尾）

- 提交（均已是 `e322c8e8` 的祖先）：`5996c81f`（前缀，1 文件）、`459687e9`（常量，1 文件）、
  `8d4e5838`（pin 判断 + 检查，3 文件）；更早的 `29622634`/`a08a03cd`（名字层拼写）与 `fbc2bc59`（两处按注入器
  stand-down）同在其祖先链上。
- **pin 有效性（本轮确立的判据）**：`git worktree add --detach` 于 `5996c81f` → `compileJava` 0 error；
  于 `e322c8e8` → `compileTestJava` 0 error（main + test）。反例：干净 worktree 于旧 HEAD `8d4e5838` →
  `FabricGuestRemapper.java:152: cannot find symbol InheritedMemberDecls`（W7Harness 先发现，我复现）。
- 套件（于 `e322c8e8` 的干净 worktree 内跑 `transform.*`/`mixin.*`/`mapping.*`）：27 条真实失败 vs 基线 28 条，
  四条陈旧 `* N.class` 失败消失；多出的三条**只在 worktree 内**出现且都是**夹具缺失**（读 staged 的
  `patched-mc-merged-*` / runtime 类，共享树里通过）：`ForbricCacheTest.resolveAndIsCached`、
  `SpawnerFinalizeInjectorTest.theSplicedDescriptorIsTheOneTheGameSideEntryActuallyDeclares`、
  `LootTableEventBridgeInjectorTest.theRoutedNameAndDescriptorMatchTheCompiledGameSideClass`。
  新增 `everyPinNamesAPrunerEntry` 通过。
- **切片（进行中）**：W7Harness 以干净 worktree 于 `e322c8e8` 构建（jar sha
  `f7714ea18e20104f0ead41f96d5c995abd96af8f019e0a52efe9f7fd4ab79436`，3,230,766 B，含
  `InheritedMemberDecls`、0 个重名类），**全新空缓存目录 `/tmp/w7-remap-cache-11`**（`-11` 因此真正被走到），
  10 个 fabric 主体；覆盖本报告两处修复 + FabricBootFails2 的 betterrailwaysystem 单元，不含尚未落地的 chipped。
  判据见第 1、2 节末尾。
- 共享树仍带一条**未跟踪且不编译**的他人测试文件 `MergedBaseSelfDependencyTest.java`（其 lambda 第 235 行返回类型
  错误）：它只影响**共享树**的 `compileTestJava`，干净 worktree 于 HEAD 不受影响；已告知该 lane。
