# 冻结的 12 条 CONFIRMED-required id:逐条对合并基底的读法、已落地处置、以及剩下的六条各自缺什么

`harness/confirmed_ids.py`(166 主体)给 12 条,形状两类(8 × `mixin-injector:`、4 × `mixin:`),分布在 8 个主体上。
本文件是**读法 + 处置**的记录;每条的原因都在真字节上核过(guest jar 的安装态 + 合并基底 `javap`)。

## 已落地(三个提交,零损失或已记代价)

| id | 原因(逐字节) | 处置 | 提交 |
|---|---|---|---|
| 5 connector `boot.ServerMainMixin#earlyInit` | **内核自伤**:`LifecycleHookInjector` 把 `Main.main` 里的 `ServerModLoader.load()V` 改写成内核钩子,普查随后报它"丢失" | 改道者自己登记替换行 ⇒ `MixinRetarget` 把锚点搬到钩子上(同一程序点) | `c91b5dbe` |
| 8 shadowguard `$protectFireTarget`(连带 7、12) | 合并把 `FireBlock.checkBurnOut` **加宽**了(多一个 `Direction`),`Expected (…,I,Direction,CallbackInfo)V but found (…,I,CallbackInfo)V`;未绑定的必需注入器**中止整个 mixin** | 剪掉该 handler,另两条(`tick` 的 `@Inject(HEAD)` 与 `ServerLevel.setBlock` 的 `@Redirect`)照常应用 | `f54f5b10` |
| 1 architectury `onBreak`、3 `checkPhantomSpawn` | 锚点能绑,**本地捕获**失配:`LVT in …ServerPlayerGameMode::destroyBlock(…)Z has incompatible changes at opcode 39`(另一处 `PhantomSpawner::tick(…)I` at 267) | 剪掉两处 handler(捕获不是选择器,无处可搬) | `f54f5b10` |

代价已写进每个条目的注释:shadowguard 的 fire-target 保护、architectury 的方块破坏事件与幻翼生成事件在这些主体上不再触发;
connector 那条**无代价**(handler 仍运行在装载窗口之前)。

## 剩下的六条:原因已定,各自缺的最后一步

| id | 主体的现状 | 原因 | 下一步(具体) |
|---|---|---|---|
| 9 cobblecoop `BattlePositionsCompatibilityMixin` | 内核 auto-suppress 后仍计 CONFIRMED-required | 已定:锚点所在的 `com/cobblemon/mod/common/battles/ActiveBattlePokemon.class` 在闭包里的 **Cobblemon 1.8.1** 中存在,但**不含** `battlePositions$getSlotIndex`(该版本没有这个成员),而 handler 是 `@Inject(method="battlePositions$getSlotIndex", require=0, remap=false)`——**可选**注入器 ⇒ Mixin 原生行为是"施加该 mixin、跳过这条注入",本无损失 | `MixinFit` 已经有软失配通道(`softMisses`,`anyHardResolved = resolved > 0 \|\| bound > 0 \|\| unresolved.size() == softMisses`,第 226/262/276 行)。要在真实 mixin 上跑一次普查,确认 `require=0` 是否真的进到 `anchor.soft`——若进了,UNFIT 与 required 都不该成立(判定/报告修正,零损失);若没进,修那条通路 |
| 2 fabric-screen-handler `ServerPlayerEntityMixin#fabric_replaceMenuProvider` | `missing: @At(INVOKE) ServerPlayer.openMenu in openMenu` | 合并后的 `ServerPlayer.openMenu(MenuProvider)` 里**没有**对 `openMenu` 的自调用(NeoForge 把两参重载并进了一参:javap 的调用表里只有 `closeContainer/nextContainerCounter/MenuProvider.createMenu/ClientboundOpenScreenPacket` 等) | 锚点不可表达(不是名字问题,是调用点本身没了)⇒ 剪/钉 + 记代价(Fabric 的 modded-menu 支持在该主体上失效) |
| 4 bonfires `getOrDefaultRedirect` | `missing: @At(INVOKE) ItemStack.getOrDefault in forEachModifier` | 合并的 `ItemStack` **不声明** `getOrDefault`;`getOrDefault` 在 `DataComponentHolder` 里(两处类文件都含该名字,但 `ItemStack` 的方法表里没有) | 先判"成员搬到接口"是否可由 `MixinNames`/继承成员通路表达(调用点的主人写法);可表达则重定位,否则剪/钉 |
| 6 OPAC `MixinOptionalExperienceOrb#onScanForEntities` | `missing: @At(INVOKE_ASSIGN) Level.getNearestPlayer in ExperienceOrb.scanForEntities` | 合并的 `scanForEntities` 走的是 NeoForge 的 `XpOrbTargetingEvent` + `Level.getEntities(EntityTypeTest,AABB,Predicate)`,`getNearestPlayer` 那条调用没了 | 查 `MergedBaseCalleeSwaps#SUBSTITUTED` 是否已有对应行;无行且语义不可等同 ⇒ 剪/钉 + 记代价 |
| 10/11 polymer `PacketCodecsEntriesMixin`/`PacketCodecsRegistryMixin` | 目标类是 `ByteBufCodecs$22`/`$23`(匿名类编号) | 合并里的匿名类**重新编号**:内核证据逐字 `ByteBufCodecs$22 is not the class vanilla compiled at that name (vanilla's body now lives at …$16 or $18 or $4 or $5 or $6)` | `MixinAnonymousRetarget.home(...)` 只在候选**唯一**时移动;此处候选 3–5 个 ⇒ 用 handler 自己的 `@Inject` 目标描述符(`$22`→`encode(ByteBuf,Object)V`、`$23`→`encode(RegistryFriendlyByteBuf,Object)V`)在候选中挑出唯一匹配者 ⇒ 可重定位、零损失(并且同一机制会一并清掉同族的 `PacketCodecsRegistryEntry{List,}Mixin`) |

## 第二轮验证(W7Harness,内核 `a4fdec5a…` 由干净 worktree 构建,全新空缓存)

| 主体 | 结果 | 预判 |
|---|---|---|
| better-teleport | PASS / world=true / cr=0 | id 1、3 消失 ✓ |
| shadowguard | PASS / world=true / cr=0 | id 7、8、12 消失 ✓ |
| sun_fade | PASS / world=true / cr=2 | **id 5 未消失 ✗(已修,见下)**;id 2 仍在 ✓ |
| cobblecoop | FAIL / world=false / cr=0 | **id 9 不可测** |

无任何主体新增 id。**三次落地共 5 个预判在"到达世界"的启动上得到证明,1 个被证伪后修好。**

### id 5 被证伪的原因与修复(`7eef89e4`)

替换行**已发布**(`[Forbric/Lifecycle] redirected … ServerModLoader.load …` 在),但锚点**没被搬**——普查仍报
`1/2 anchors resolve, missing: @At(INVOKE) …ServerModLoader.load in Main.main`。原因在真字节上复现:替换行的
`ecosystems` 写成**触发臂那个家族**,而 `substitution(...)` 按**客方生态**过滤。这对**载体**替换是对的
(按别家 jar 编译的 mod 本来就没有那条调用),对**内核自己做的**替换是错的——内核把基底里那条调用换掉了,
对所有客方一样。Sinytra Connector 正是反例:Fabric 生态、刻意锚在 NeoForge 的装载器类上。
修复:内核替换行三家全列(家族只作**出处**);`KernelLifecycleSwapTest` 中"另一生态不移"的用例翻转为"任何生态都移"。
复现/验证:FABRIC、FORGE 两个生态在改前**不移**、改后**都移**,NEOFORGE 三条一致绿。

### id 9:判据已证,启动级确认在本主体上不可能 —— 不计入分母

- **已证(真字节)**:对该 mixin 与它自己闭包里的 Cobblemon 1.8.1,`MixinFit` 由 `UNFIT` 变 `PARTIAL`
  (unresolved 仍如实列出),即产生类级 finding 的那次 auto-suppress 不再发生。
- **不可测**:`cobblecoop-1.5.1-fabric.jar` 在**任何**内核上都到不了世界(`cause=registry-load`,早于本次改动),
  因此它的 `cr 2 → 0` 两侧都是**世界之前**的读数,不构成证据。
- **对分母的处置(明确写下,不留给读者推断)**:主体因**自身**注册表原因到不了世界者,不在闭环完整集合内,
  **id 9 不计入 loader 的账**;理由是上面的 `cause=registry-load` 早于本轮全部改动,且该主体的 `world=false`
  与本清单的任何一条修复无关。

## 已落地(第二轮)

| 决定 | 提交 | 逐字节证据 |
|---|---|---|
| **id 9 的判据修正**:`require=0` 的缺席不再被记成必需损失(判定侧软掉 + 报告侧不再让 config 的 `required` 替注入器表态) | `25a2f77f` | 真实字节:cobblecoop 安装态 mixin + Cobblemon 1.8.1 ⇒ `UNFIT` → `PARTIAL`(unresolved 仍如实列出该锚点) |

**影响面(四份 campaign 报告的已存 findings)**:`mixin-injector:` 行 1354,其中 `required: true` **312** 行
(full-corpus 186 / bucket-fabric 52 / bucket-neoforge 4 / bucket-forge 70) ⇒ 这 312 行是本判据可能影响的上界;
真正被抬高的子集是其中 handler 有效要求为 0 的那些(逐行读 `require`,或重跑重派生)。套件:`mixin.*`+`transform.*`
失败集合在本修前后逐条相同(24 = 24)。

## 每条 id 的证据等级:在**启动**上定了,还是只在**驱动器读数**上定了

判据不同,结论的强度就不同;下表让读者不必从提交历史里反推。

| id | 证据等级 | 说明 |
|---|---|---|
| 1、3 | **启动**(better-teleport `PASS`/`world=true`/`cr=0`) | 首轮 5 个预判之一 |
| 7、8、12 | **启动**(shadowguard `PASS`/`world=true`/`cr=0`) | 同上 |
| 5 | **启动待定(第三次假设已被自己的可证伪条件否掉)** | `7eef89e4` 家族放宽 → 启动仍不动;`eaf80d0c` 未知生态假设 → **我预登记的标记行在启动上没有出现**,按事先写下的规则该假设**作废**(生态不是 null)。已排除:合并基底里触发调用只有一条(occurrences≠2)、驱动器读 raw 与 candidate 两份字节都能移、`substitutedCalls` 确实被调用且该 handler 无其它改写(锚点文本与前内核逐字符相同)。下一步:在替换决策处打印四个输入(row/ecosystem/occurrences/captures),由一次启动判读 |
| 9 | **仅驱动器读数**(启动不可测) | `cobblecoop` 自身 `registry-load` 到不了世界;真字节上 `UNFIT`→`PARTIAL` 已证,类级 finding 是否消失未在启动上确认 |
| 2、4、6、10、11 | **未定**(仅驱动器读数 + 代码阅读) | 转 FrozenIds2;每条的原因与下一步已在上表列明 |

**为什么要把这件事写下来**:今天**三次**无头驱动与真实路径不一致,两次都是**驱动器的输入比真实路径干净**——
剪枝器那次读的是加载器并未安装的那个类;这次读的是一个真实路径**说不出家族**的客方(捆绑库)。
驱动器的绿只能说明"在这个输入下成立",不能说明真实条件成立。

## 未由本车道处理 / 已知注意项

- **ids 2、4、6、10、11** 转由 FrozenIds2 接手(连同 census 影响面计数)。原因与下一步见上表。
- **归因注意项(报告问题,不是重定位问题;Main 已决定纳入最终报告)**:connector 的 finding 被记为
  "belongs to no installed mod" —— 它以**捆绑库**形式到达,`modIdOf` 知道它的配置但没有同名已安装 mod。
  这与 id 5 的存活**无关**(锚点确实没被搬);读者不应把该行当作修复未生效的证据。

## 环境事实(不是发现)

本轮派出的九个子代理(scout×8、sonic×1)全部在 provider 处 `401 INVALID_API_KEY` 失败,零内容产出;
本文件的全部读法与验证因此为本轮独力完成(headless,无游戏 JVM)。

**id 9 的判据(可验伪)**:`MixinFit.evaluate` 里 `anyHardResolved = resolved > 0 || bound > 0 || unresolved.size() == softMisses`(第 276 行),
`if (anchor.soft) softMisses++`(第 262 行)。若 `require=0` 真的进了 `anchor.soft`,则该 mixin 应得 FIT(或至多 PARTIAL),
而它现在被 auto-suppress 成 UNFIT——**说明"必需要求"这条链没有把 `require=0` 读进来**,于是被算成冻结清单里的一条 required。
**已落地**:见下方"已落地(第二轮)"——判定侧 `MixinFit` 把 `require=0` 的未命中记为 soft(⇒ PARTIAL,不再 UNFIT ⇒ 不再 auto-suppress),
报告侧 `FinalMixinApplications` 的 `required` 改由 `injector.minimum()` 单独决定。零代价、零功能损失。

## 复现命令(全部 headless,无游戏 JVM)

```bash
# 主体 jar 关在各自 run 目录里(语料已按用户指示清空;mods/ 与 .forbric-kernel/candidates/ 仍在本地产物中)
R=w7/reports/2026-10-03-full-corpus/per-mod/run
# 安装态(名字层处理后)的 guest 类:
java -cp <kernel-classes>:<deps> IdProbe mixin "$(readlink $R/088-shadowguard__fabric/.forbric-kernel/remap)"/*hadow*.jar \
     org/krripe/shadowguard/mixin/FireBlockMixin
# 合并基底里锚点是否存在:
java -cp ... IdProbe base p0/stage-1.21.1/merged-base/patched-mc-merged-1.21.1.jar net/minecraft/server/Main main
# 冻结清单本身:
python3 w7/harness/confirmed_ids.py
```

判据:重跑 `confirmed_ids.py` 返回零。

## 第三轮:id 10 落地、id 11 的残差,以及开工前必须知道的两件事

### id 10/11 —— 匿名类普查的根因不是"候选 3–5 个",而是**普查属于另一个基底**(提交 `0a31c033`)

原先的记法是"漂移表点名 3–5 个候选,handler 的 `@Inject` 描述符可挑出唯一匹配者"。逐字节重核**推翻了这一点**:
在 26.2 那份候选集 `{16,18,4,5,6}` 里**没有任何一个**声明 `encode(ByteBuf,Object)V`(那 5 个是 Integer/byte[]/Tag 编解码器),
所以"用描述符在候选里挑"在旧表上**无解**。真相是两条独立的错:

1. `MergedBaseAnonymousDrift` 是 **26.2 的普查**,被无条件用在 1.21.1 基底上。同一规则重新派生(具名 vanilla
   `p0/mc-1.21.1/.forbric-build/client-official.jar` 对 `out/patched-mc-merged-1.21.1.jar`)得到
   `$22 → [$24]`、`$23 → [$25]`、`$24 → [$26]`、`$25 → [$27]`,**四条都唯一**。
2. 候选闸门用"方法集完全相等",而补丁会往 vanilla body 搬过去的类上**加**方法(1.21.1 上 NeoForge 给
   `ByteBufCodecs$27` 加了两个 holderset 辅助方法)⇒ vanilla 的 `$25` 被判 RESHAPED,尽管 body 就在 `$27`。
   这正是 `4e144902` 已经为"同名类"修过的形状,只是没修到候选搜索。

**落地**:两份普查按基底选择(`forBase(present)`,用普查自己命名的 `ByteBufCodecs$33` 判别:26.2 有、1.21.1 止于 `$28`),
候选闸门改为 `stillHolds`(相等在其中精选)。**26.2 那份逐字保留**并新增一条测试钉住"基底选自己的普查"。
逐字节红→绿:Entries `$22→$24` UNFIT→**FIT**;Entry `$24→$26`、EntryList `$25→$27` 一并 FIT(题目要求的两条确认成立)。

**id 11 未清零,如实记**:类号那一半已落地(`$23→$25`,不再被 auto-suppress),但该 mixin 还 `@Shadow`
一个被合并**改名**的捕获字段 `val$registryKey`(合并基底里同类型只剩 `val$p_319942_`),`MixinFit` 对它
仍报 PARTIAL。`MixinShadowMembers` 的改名走映射 spine(源命名空间→具名),对这个已是具名形状的 `val$` 无处可映射。
**下一步**:候选唯一时按描述符给 `@Shadow` 补 `aliases`,并让 `MixinFit` 用同一判据判影子。

### 归因注意:findings 可能属于**随包的库**,而不是已安装主体

`connector` 就是例子——一个 Fabric guest 刻意锚在 NeoForge 的 loader 类上,于是它的 id 被记在**装载器/库**名下。
这是**报告口径**问题,不是重定位缺陷:读者不得把库的 id 读成被测主体的失败。该主体的判据要按主体自己的
mixin 配置归因后再读。

### id 9(cobblecoop)的闭环口径

判据在真字节上成立(`UNFIT → PARTIAL`,unresolved 仍如实列出该锚点);**boot 级确证在该主体上不可能**,
因为它的 `registry-load` 失败先于本改动、且属主体侧。对 ≥95% 的门,该主体**在 closure-complete 集之外**——
必须显式这么写,不能靠 `world=false` 让读者自己推。

### 同一形状今天出现三次:钉住值是为另一个版本校准的

registry-loader 的 pin、访问加宽器的命名空间、以及本次的匿名类普查,都是"取一个值的形状正确、基底不对"。
移植下一个基底时,这三处应一起复核(本条为 `M7`/移植清单留档)。

### 本轮未完成(如实记录,不含猜测)

- **ids 2 / 6 / 4**:未动。2/6 的一轮读法结论(锚点本身被合并改写、不可表达 ⇒ 剪 + 记代价)仍待落成条目;
  4 的"成员搬到接口"是否可由继承成员通路表达仍待一读。
- **census 影响面**(`25a2f77f` 的 per-loader 膨胀行数):未派。方法已定(对四份 campaign 报告逐行读已存
  `mixin-injector:` required 行 + 该主体 jar 里 handler 的 `require`,有效的 `require=0` 即为被抬高者,
  分 loader 计数),但数字**尚未产出**,不得引用。
