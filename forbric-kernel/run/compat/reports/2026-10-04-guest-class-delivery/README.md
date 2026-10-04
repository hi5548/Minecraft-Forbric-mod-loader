# 客方 mixin 的"类投递缺口"并不存在:Mod Menu 那条是剪枝表新键写成了斜杠内部名(2026-10-04)

**结论一句话。** 上一轮把 `GuestInjectorPruner` 的 AUDIT 静默读成"链子从不把该类递进来",据此把 Mod Menu
改走整配置 pin(`716caa2a`)。**该读数是错的。** 类一直在到达变换链;唯一写斜杠的键正是那条新表项
`com/terraformersmc/modmenu/mixin/MixinTitleScreen`,而链子递进来的是**点分二进制名**,所以
`TABLE.get(...)` 落空、条目是死的;更糟的是审计的守卫 `CONFIGS.containsKey(className)` 用的是同一张斜杠键的表,
于是连一行 AUDIT 都不打——**"没有输出"被读成了"上游缺口",而不是它自己注释里列出的 case(2)"键不匹配"。**

修复两个提交:`0d9f15c8`(键改成点分形态 + 回归测试 + 审计注释)与 `397013bd`(撤回整配置 pin,恢复更窄的
按注入器剪枝)。报告预登记在下面 §6,`W7Harness` 的验证运行读数以那一节为准。

## 1. 字节证据:类确实到达了链子

**链子的入参形态是从源码量出来的,不是推的。**
`forbric-kernel/src/main/java/net/forbric/kernel/classloading/ForbricClassLoader.java:203-223`:

```java
public byte[] getPreMixinClassBytes(String requested) {
    String name = requested.replace('/', '.');          // ← 归一成点分二进制名
    ...
    byte[] transformed = transformer.apply(name, raw);  // ← TransformChain(含 GuestInjectorPruner)拿到的是点分名
```

`KernelBoot.java:1046` 把 `transformer` 接成 `chain.applyBeforeMixin(name, bytes, ctx)`,而 `MixinFit`/
`KernelGuestMixinAdapter` 的字节也走同一条 `getPreMixinClassBytes`(`ForbricMixinService.readAdapterClass`,
其注释原文:"This MUST serve post-transform-chain bytes")。`GuestInjectorPruner` 的其它每一个 `TABLE` 键都
是点分名,`MODMENU_TITLE_MIXIN` 是**唯一**一个斜杠键。

**同一次被读错的审计运行,控制台自己就有一行证明该类被这条读链读到。**
`/Volumes/ORICO/forbric/w7-reports/2026-10-04-client-modmenu-audit2/per-mod/run/000-modmenu__fabric/console.log:302`:

```
[Forbric/Mixin] guest mixin modmenu (mixins.modmenu.json):MixinTitleScreen applies only partially on the merged base
  — 3/4 anchors resolve, missing: @At(INVOKE) net.minecraft.client.gui.GuiGraphics.drawString in TitleScreen.render
  (kept; -Dforbric.mixinFit=strict drops these)
```

这行由 `KernelGuestMixinAdapter.unfitMixins` 发出(`KernelGuestMixinAdapter.java:192`
`resource.apply(pkgPath + "/" + mixin.replace('.', '/') + ".class")` → `ForbricMixinService.readAdapterClass`
→ `getPreMixinClassBytes`),即**同一个 `ForbricMixinAdapter` 通路已经读到了该类**。同一次运行的 AUDIT 只对
"有 TABLE 行或 CONFIGS 行"的类打印(12 行,全是 fabric-api),Mod Menu 因为两张表都查不中而一行都没有——
这正是"静默"(case 2)被误读成"上游缺口"(case 1)的机制。

**真实字节(remapped `modmenu-11.0.5-7296014f78ddc81a.jar`,`javap -p -v`)**:

```
private java.lang.String onRender(java.lang.String);
  descriptor: (Ljava/lang/String;)Ljava/lang/String;
  org.spongepowered.asm.mixin.injection.ModifyArg(
    method=["Lnet/minecraft/client/gui/screens/TitleScreen;render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"]
private int adjustRealmsHeight(int);   descriptor: (I)I
```

即:该条目声明的 handler(`onRender`,`(Ljava/lang/String;)Ljava/lang/String;`,selector
`Lnet/minecraft/client/gui/screens/TitleScreen;render`)与保留者(`adjustRealmsHeight`)都逐字对得上,
**唯一错的就是查找用的类名形态。**

## 2. 修复

- **`0d9f15c8`** — `MODMENU_TITLE_MIXIN` 由 `com/terraformersmc/modmenu/mixin/MixinTitleScreen` 改为
  `com.terraformersmc.modmenu.mixin.MixinTitleScreen`(与其余键、与链子入参同形态);审计注释记录这次的误读
  (case 2 因守卫同键而不可见);新增两条测试(见 §4)。
- **`397013bd`** — 撤回 `MergedBaseMixinCompat.SUPPRESSED_MIXINS` 的 `mixins.modmenu.json:MixinTitleScreen`
  整配置 pin。键修好后按注入器剪枝够得到该类;**pin 的代价更大**(整支 mixin:标题行替换 **和**
  `adjustRealmsHeight`),按注入器剪枝只丢标题行替换。撤回正是 `716caa2a` 自己写下的下一步。

## 3. 影响评估:这不是一个普遍的投递缺口

父派单问"是否**每个**非 fabric-api 的 Fabric mod 的 mixin 都受影响",答案是否定的,且可有字节证据:

- 投递路径是通用的(`KernelGuestMixinAdapter` 按每个已注册配置的 `mixins` 列表逐条向 loader 取字节,
  没有按 mod 分类的分支);Mod Menu 只是没有出现在本次隔离语料里。
- 其它非 fabric-api 客方 mod 的 mixin **确实**被递到剪枝器并在其它运行里留下剪枝行:

```
pruned 2 injector(s) from net.blay09.mods.balm.mixin.FabricCropBlockMixin          (2026-10-03-fabric-cold-fixed/…/001-balm)
pruned 1 injector(s) from dev.architectury.mixin.fabric.MixinPhantomSpawner        (2026-10-04-recheck-walls2/…/001-cobblemon-coop)
pruned 1 injector(s) from dev.architectury.mixin.fabric.MixinServerPlayerGameMode  (同上)
pruned 1 injector(s) from org.krripe.shadowguard.mixin.FireBlockMixin              (2026-10-04-deprule-jdk21/…/000-shadowguard)
```

- 因此:（i）不是内核投递缺陷,是**一条手写表项写错形态**;（ii）fabric-api-only 的语料**并非**结构性地
  看不见一个普遍投递缺陷——但它们**确实**看不见**这一条**(Mod Menu 不在语料里),这就是它只能靠隔离集合
  暴露的原因。**"类投递缺口"这个待办可以关闭;不需要新的投递机制。**

## 4. 红 → 绿(离线单测,`./gradlew test --tests …GuestInjectorPrunerTest --offline`)

新增两条测试,`forbric-kernel/src/test/java/net/forbric/kernel/transform/GuestInjectorPrunerTest.java`:

- `theModMenuEntryMatchesTheNameTheChainHandsOver` — 合成一支内部名为
  `com/terraformersmc/modmenu/mixin/MixinTitleScreen` 的 mixin(`onRender` + `adjustRealmsHeight`),
  按链子的点分名调用 `transform(...)`,断言 `onRender` 被剪、`adjustRealmsHeight` 保留、
  `mixin-injector:mixins.modmenu.json:com.terraformersmc.modmenu.mixin.MixinTitleScreen#onRender(` 记为
  CONFIRMED/required=false、`confirmedRequired` 空。
- `everyPrunerKeyIsTheDottedNameTheChainPasses` — 把"键必须是链子入参形态"变成构建期失败(下一个斜杠键不再静默死)。

| 状态 | tests | failures |
|---|---:|---:|
| 键写回斜杠(修前) | 28 | **2**（两条新测都红） |
| 点分键(修后)   | 28 | **0** |

修前失败原文:

```
FAIL everyPrunerKeyIsTheDottedNameTheChainPasses()
  com/terraformersmc/modmenu/mixin/MixinTitleScreen is keyed with an internal (slashed) name; the chain passes
  the dotted binary name, so this entry can never be looked up ==> expected: <-1> but was: <3>
FAIL theModMenuEntryMatchesTheNameTheChainHandsOver()
  the chain hands over the dotted name; the entry must match it ==> expected: not same but was: <[B@…>
```

## 5. 纪律:`NIGHT_SHIFT` 的冷却规则适用

改动触及**剪枝器**,所以按 `w7/NIGHT_SHIFT.md` "任何触及 `MixinNames` / `InheritedMemberDecls` / 剪枝器的
改动必须至少有一个模块的**冷**重映射"——验证运行用**全新的 remap 缓存**,不沿用任何旧缓存。运行由 `W7Harness`
拥有(本 agent 不自己起游戏 JVM)。

## 6. 预登记读数(运行前登记,禁止事后改判据)

两项都跑,**隔离集合**(主体 `modmenu-11.0.5.jar`,闭包 `fabric-api`/`cloth-config-15.0.140-fabric`/
`placeholder-api`,不带 NeoForge 系)是判别项,**用户真实 12-mod 集合**是验收项。两项都加
`-Dforbric.guestInjectorPrunerAudit=on`(只多打行,不改行为);**冷 remap 缓存**。

**隔离集合(判别项)** —— 下面四条必须同时成立:

1. 剪枝行出现(**逐字来自 `GuestInjectorPruner.java` 的 `ForbricLog.info` 格式串**,非按意图拼写):
   ```
   [Forbric/GuestInjectorPruner] pruned 1 injector(s) from com.terraformersmc.modmenu.mixin.MixinTitleScreen —
   ```
2. AUDIT 行出现(`-Dforbric.guestInjectorPrunerAudit=on`;格式串逐字来自源码):
   ```
   [Forbric/GuestInjectorPruner] AUDIT com.terraformersmc.modmenu.mixin.MixinTitleScreen: 1 prune(s), table row true, switch true, active true
   ```
3. §1 那条 `… MixinTitleScreen applies only partially …` 行**缺席**(onRender 被剪 ⇒ FIT ⇒ 不发该行),
   且没有任何 `suppressed mixin MixinTitleScreen` 行(pin 已撤)。
4. 该 id 记为 `required=false`(或按 §13 的登记"或缺席"):`confirmed_required: 0`,枚举 0;
   `world=true`,`joined world via quick-play` ≥1。

**用户真实 12-mod 集合(验收项)** —— `confirmed_required: 0`、`world=true`、`joined world via quick-play`
(注:上一轮这面集合死在更早的 `ResourcePackLoader` 墙,那是**另一条车道**;若本项仍死在该墙,以隔离集合的
四条为准并把本项标 `world=false, blocked_by=ResourcePackLoader`)。

**若隔离集合的 1/2 不出现,则本文档的判别机制被证伪**——那说明键并非根因,须重开;不得把运行读成成功。

## 7. 验证运行读数(§6 登记之后的实测,`W7Harness` 拥有)

Pin `6d7d32cc`(干净工作树构建的内核 sha `b30955e2…`),隔离集合,**冷缓存**(§5 的纪律),audit 开,
`reports/2026-10-04-guest-class-delivery/`:

```
row:  run=PASS  exit=0  world=true  frames=1  stopped=true  killed=false  strict=TRUE  confirmed_required=0  seconds=320
(a)  [Forbric/GuestInjectorPruner] pruned 1 injector(s) from com.terraformersmc.modmenu.mixin.MixinTitleScreen …      ← 在
(b)  [Forbric/GuestInjectorPruner] AUDIT com.terraformersmc.modmenu.mixin.MixinTitleScreen: 1 prune(s), table row true, switch true, active true  ← 在
(c)  'MixinTitleScreen applies only partially': 0     ← §6 要求缺席,缺席
     'suppressed mixin MixinTitleScreen':        0     ← §6 要求缺席,缺席
confirmedRequired: 0  |  the Mod Menu row: required=False  |  joined world via quick-play: 1
```

§6 的四条**全部**成立,(a)/(b) 同时出现即判别机制的正分支:点分键与链子入参同形态 → 行生效 → 审计(查同一张表)终于报出。

(c) 比"finding 没了"更强:上一轮审计运行的控制台里那行 `…MixinTitleScreen applies only partially …` 现在**缺席**,
因为该注入器是在**应用之前**被剪掉的,而不是在应用时被拒——"主动记录的一处损失"与"事后上报的一次半应用"之差,
这次是按"要求缺席"登记的,所以量得出来。

**12-mod 集合的验收运行仍在 `InertApiFix` 的那一窗之后排队**(一格一窗的纪律);按 §6 的登记,若它仍死在
更早的 `ResourcePackLoader` 墙,该行记 `world=false, blocked_by=ResourcePackLoader`,验收以本节的隔离集合为准——
而它已经通过。`W7Harness` 复核了两点:`MergedBaseMixinCompat` 里 `mixins.modmenu.json:MixinTitleScreen` **0 命中**
(pin 确已撤回),`GuestInjectorPruner` 里是点分键——树与声明一致。
