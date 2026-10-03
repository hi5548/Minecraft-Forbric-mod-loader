# 第四轮 · ids 2 / 4 / 6:三条锚点真实消失的必需注入器,按注入器剪除并记代价

范围:本轮只处理 **id 2(fabric-screen-handler)**、**id 6(Open Parties and Claims)**、**id 4(bonfires)**。
三条都判为"锚点不可表达 ⇒ 剪 + 记代价"(不是名字问题,是调用点/调用本身已不在),各自一个提交、一条红→绿测试。
读法全在真字节上:合并基底 `p0/mc-1.21.1/.forbric-build/out/patched-mc-merged-1.21.1.jar` 的 `javap`,
以及各主体 run 目录里内核实际读的 guest 类(remap 后的模组 jar)。

改动只落在一个文件族:`forbric-kernel/src/main/java/net/forbric/kernel/transform/GuestInjectorPruner.java`
(表项 + 六个伴随表 + 类注释)与其测试
`forbric-kernel/src/test/java/net/forbric/kernel/transform/GuestInjectorPrunerTest.java`。
剪除后每条都变成 `recordRemovedInjector` 记下的 **CONFIRMED、`required=false`** 损失(`CompatibilityFindings`),
不再进 `confirmed_ids.py` 的必需集合;功能本身在合并基底上早已不可用。

---

## id 2 — `fabric-screen-handler-api-v1` 的 `ServerPlayerEntityMixin#fabric_replaceMenuProvider`

**逐字节证据。** handler 是 `@ModifyArg(index=0)`,其 `@At(INVOKE)` 指向 `ServerPlayer` 内部对
`openMenu(MenuProvider,Consumer)` 的**自调用**:

```
@ModifyArg(
  method=["openMenu(Lnet/minecraft/world/MenuProvider;)Ljava/util/OptionalInt;"]
  at=@At(value="INVOKE",
         target="Lnet/minecraft/server/level/ServerPlayer;openMenu(Lnet/minecraft/world/MenuProvider;
                 Ljava/util/function/Consumer;)Ljava/util/OptionalInt;")
  index=0)
```

合并基底的 `ServerPlayer` 里**没有任何 `openMenu` 调用**(`javap -c` 全类只有两处 `openMenu` 声明与
`lambda$openMenu$15`);一参 `openMenu(MenuProvider)` 的 body 是两参重载的**内联**——它依次调用
`closeContainer / nextContainerCounter / MenuProvider.createMenu / ClientboundOpenScreenPacket /
initMenu / ForgeEventFactory.onPlayerOpenContainer`。所以调用的不是名字问题、调用点本身没了。
继承成员通路不适用:`openMenu` 是游戏属主上的成员,且该自调用在整个 `ServerPlayer` 里不存在,`InheritedMemberRefs`
(另一 mod 类上的引用按名字重解)无从施展。

**处置。** `GuestInjectorPruner` 按注入器剪除 `fabric_replaceMenuProvider`;该 mixin 的另外三个 handler
(`fabric_closeHandledScreenIfAllowed` 的 `@Redirect closeContainer`、`fabric_storeOpenedScreenHandler` 的
`@Inject send`、`fabric_replaceVanillaScreenPacket` 的 `@Redirect send`)照常应用。

**该条目是第一个 OPTIONAL 修剪。** 类由每个 fabric-api 加载,但 handler 只存在于 screen-handler **1.3.91**
(Sinytra/forgified fabric-api 0.116.15 内嵌,`BetterGrassify`/`sun_fade` 走的就是它),而 fabric-api 0.116.17
的内嵌 screen-handler 模块根本没有 `fabric_replaceMenuProvider`(只有 close/store/vanilla-packet 三个)。
缺 handler 的 revision 上"没得剪"是正确答案,不是漂移,故给 `Prune` 加了 `optional`,并让 `declaredAnchors`
对全 optional 的条目声明 **HEDGE**(而非 REQUIRED),避免 `AnchorLedger` 把"本来就没有"记成"被拒的修复"。

**代价(逐字,LOSSES 行)。**

> the kernel removed this injector: Fabric's ExtendedScreenHandlerFactory substitution inside
> ServerPlayer.openMenu(MenuProvider) no longer happens — the @ModifyArg replaced argument 0 of the
> openMenu(MenuProvider,Consumer) self-call with the extended factory, and the merged body inlines that overload and
> makes no such call, so a Fabric mod's extended-screen opening data is never installed; the mixin's
> close-handled-screen redirect, its store-opened handler and its vanilla-packet replacement still bind

**用户失去什么:** Fabric 的 **menu-provider 替换**——`ExtendedScreenHandlerFactory` 的开屏数据
(`getScreenOpeningData`)不再被装进 `openMenu`;通过 `ServerPlayer.openMenu` 打开扩展屏幕处理器的 Fabric mod
拿不到那一段数据。该 mixin 的其余三个注入仍生效。

**提交** `ba223064`;**红→绿** `GuestInjectorPrunerTest`:
`theScreenHandlerMenuProviderModifyArgIsPrunedWhereItExists`(存在即剪、记 CONFIRMED 非必需损失、detail 含
`ExtendedScreenHandlerFactory`)、`theScreenHandlerEntryIsANoOpOnARevisionWithoutTheHandler`(缺失即原样透传、
不记 finding、条目声明 HEDGE)。

---

## id 6 — Open Parties and Claims 的 `MixinOptionalExperienceOrb#onScanForEntities`

**逐字节证据。** handler 是 `@Inject`,锚在 `ExperienceOrb.scanForEntities` 里对 `Level.getNearestPlayer` 的赋值:

```
@Inject(method=["scanForEntities"],
        at=@At(value="INVOKE_ASSIGN",
               target="Lnet/minecraft/world/level/Level;getNearestPlayer(Lnet/minecraft/world/entity/Entity;D)
                       Lnet/minecraft/world/entity/player/Player;"))
```

合并基底的 `ExperienceOrb.scanForEntities` 整段换了选择方式:`new XpOrbTargetingEvent(orb, d)` →
`getFollowingPlayer()` → `EntityTypeTest.forClass(...)` → `Level.getEntities(EntityTypeTest,AABB,Predicate)`,
**全类没有 `getNearestPlayer`**。

**处置。** `MergedBaseCalleeSwaps#SUBSTITUTED` **无对应行**——那张表的语义是"同一指令点上一条调用换成另一条"
(`covers` 要求 `target`/`method`/成员名一致),而这里是选择逻辑被整体重写;且 `getNearestPlayer` 返回 `Player`、
不是 `List<ExperienceOrb>`,handler 无法改道到幸存调用。语义不等价 ⇒ 剪除整条 handler(该 mixin 仅此一条)。

**代价(逐字,LOSSES 行)。**

> the kernel removed this injector: Open Parties and Claims' experience-orb pickup hook no longer runs — the handler
> fed the player Level.getNearestPlayer returned into ServerCore.onExperiencePickup to track followingPlayer, and the
> merged scanForEntities targets orbs through NeoForge's XpOrbTargetingEvent and
> Level.getEntities(EntityTypeTest,AABB,Predicate) instead; this mixin has no other handler

**用户失去什么:** OPAC 的 **经验球目标钩子**——`ServerCore.onExperiencePickup` 不再在该主体上运行,
`followingPlayer`(经验球跟随玩家)的追踪不再更新。

**提交** `1760a08d`;**红→绿** `GuestInjectorPrunerTest`:
`theOpacOrbTargetingInjectorIsPrunedIntoAConfirmedFindingThatAsksNothing`。

---

## id 4 — bonfires 的 `ItemStackMixin#getOrDefaultRedirect`(自身造成的链)

**逐字节证据。** handler 是 `@Redirect`:

```
@Redirect(method=["Lnet/minecraft/world/item/ItemStack;forEachModifier(
                     Lnet/minecraft/world/entity/EquipmentSlot;Ljava/util/function/BiConsumer;)V"],
          at=@At(value="INVOKE",
                 target="Lnet/minecraft/world/item/ItemStack;getOrDefault(
                         Lnet/minecraft/core/component/DataComponentType;Ljava/lang/Object;)Ljava/lang/Object;"))
```

它要改的那条调用**是内核自己删的**:`ForbricMergedBaseCompatTransformer.askNeoForgeWhatAnItemsAttributesAre`
把 `ItemStack.forEachModifier` 里 vanilla 的
`getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)`(连同 `CHECKCAST`)
改写成 `getAttributeModifiers()`——NeoForge 的 `canGlide` 读的是只有 `ItemAttributeModifierEvent` 才会置上的
`neoforge:gliding_flight` 属性;不这样改,鞘翅飞行永久为假。`MixinFit` 与 Mixin 读的都是 transform **之后**的字节,
所以该 `@At(INVOKE)` 在任何主体上都不存在。这与 **id 5** 同形(客方锚在内核自己改过的地方),但不能像 id 5 那样
搬到同一程序点:`@Redirect` 不能跟随一条被删掉的调用,且 `getAttributeModifiers()` 返回的是算好的 modifiers、
不是原始 component(注入成员通路在这里表达不了)。⇒ 剪除(该 mixin 仅此一条 handler)。

**代价(逐字,LOSSES 行)。**

> the kernel removed this injector: bonfires' reinforced-item attack-damage modifier no longer applies — the redirect
> read ItemStack.getOrDefault(ATTRIBUTE_MODIFIERS, EMPTY) and added Bonfires.reinforceDamageModifier to MAINHAND for
> a reinforced item, and the kernel's own elytra repair replaced that read with getAttributeModifiers() before Mixin
> saw the class; this mixin has no other handler

**用户失去什么:** bonfires 的**原版属性重定向**——被"强化(Reinforce)"过的物品不再从该 redirect 获得
`reinforceDamageModifier`(主手攻击力加成)。

**提交** `127e205a`;**红→绿** `GuestInjectorPrunerTest`:
`theBonfiresAttributeRedirectIsPrunedIntoAConfirmedFindingThatAsksNothing`。

---

## `confirmed_ids.py` 重跑与残余

```
$ cd /Users/charlescai/Desktop/dsh/实验/forbric && python3 w7/harness/confirmed_ids.py
subjects examined: 166   distinct CONFIRMED required ids: 12
```

仍是 **12 条**(逐条列出见运行输出):1 architectury onBreak、2 fabric-screen-handler replaceMenuProvider、
3 architectury checkPhantomSpawn、4 bonfires getOrDefaultRedirect、5 connector earlyInit、6 OPAC onScanForEntities、
7/8/12 shadowguard、9 cobblecoop、10/11 polymer。

**这是读数口径,不是没修:** `confirmed_ids.py` 只读四份 campaign 报告里已存的
`per-mod/run/*/.forbric-kernel/compatibility-report.json`,是**修复前那一次普查的冻结产物**——第一/二/三轮的
落地(1、3、5、7、8、12 等)同样没有改变它。第四轮本车道(**2、4、6**)的处置是代码侧:
剪除后 `recordRemovedInjector` 记的是 CONFIRMED 且 `required=false`,因此在下一次**真跑**(`recheck.py` 重跑
受影响主体)里这三条会从必需集合消失;**id 11**(polymer 影子残差)由本轮的 `ShadowAlias` 车道负责,不在本文件。
headless 下唯一能把它们移出清单的办法是重跑 sweep,本轮未跑(见下"未完成")。

---

## 未完成 / 不可headless完成

- **未重跑 sweep**:`confirmed_ids.py` 的 12 条要真正减少,必须用 `recheck.py` 在新内核上重跑受影响主体
  (2: BetterGrassify/sun_fade;4: bonfires-extended;6: open-parties-and-claims)。本轮只做了单元级红→绿
  与逐字节读法,没有游戏/服务器重跑。
- **id 2 的 revision 分裂**已在代码里处理(optional + HEDGE);若日后某主体用的是"又一版"没有该 handler 的
  screen-handler,行为仍是"原样透传 + HEDGE",不会误报。
