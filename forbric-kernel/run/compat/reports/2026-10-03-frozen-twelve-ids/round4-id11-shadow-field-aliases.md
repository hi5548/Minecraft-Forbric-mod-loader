# 第四轮补充:id 11 的 shadow 残差落地(独立文件,不与 README 争写者)

id:`mixin:polymer-core.mixins.json:eu.pb4.polymer.core.mixin.other.PacketCodecsRegistryMixin`

## 规则(只表达一次,两侧同消费)

被合并改名的 `@Shadow` 字段:声明的名字在目标整条继承链上不存在、且**恰好一个**同描述符的字段留在原地时,
给它补 `aliases`。这一条写在 `net.forbric.kernel.mixin.ShadowFieldAliases.aliasFor(...)`:

- **改写**方 `ShadowFieldAliases.apply(ClassNode, resolver)` 调它,把答案写进 `@Shadow` 的 `aliases`
  (在 `ForbricMixinService.getClassNode` 里、`MixinAnonymousRetarget` **之后**执行,所以 `targets` 已经是搬过去的新家);
- **判定**方 `MixinFit.anchorsOf` 的 `@Shadow field` 锚点调**同一个方法**(`ShadowFieldAliases.binds`),
  以同一个目标集合求值。

两侧取同一目标集合(`MixinFit.homes`,即把 `@Mixin` 按匿名类普查搬过之后的名单),所以"判定说 resolved"必然
"改写写了别名"。`-Dforbric.shadowFieldAliases=off` 两侧一起关。

判据是保守的:名字只要在链上出现就**不**别名(描述符错配是 Mixin 自己的报错,别名治不了);同描述符候选 ≥2 个
就不猜;目标读不到的跳过(`MixinFit` 对不可见目标本来就是"假定可 fits")。目标侧字段若被 Mixin 视为可别名,
`val$…` 捕获字段是 `ACC_SYNTHETIC`,恰好过得了 `MixinPreProcessorStandard.attachFields` 的"非 private 不可别名"检查。

## 真字节:红 → 绿

材料(全部是流水线真实产物):

- remapped guest:`polymer-core-0.9.19+1.21.1-113f70d9ec4019eb.jar`(内核 remap 缓存输出)sha256 `863a0a74…`
- 合并基底:`patched-mc-merged-1.21.1.jar` sha256 `639dab05…`(`p0/mc-1.21.1/.forbric-build/out/…`)

离线驱动(`MixinFit.evaluate` 对 remapped mixin + 真实基底):

| | verdict | 说明 |
|---|---|---|
| 改前 | **PARTIAL (1/2)** | `missing: @Shadow field ByteBufCodecs$25.val$registryKey` |
| 改后 | **FIT (2/2)** | 同一批字节,影子锚点由本规则判 resolved |
| 改写 | — | `@Shadow field val$registryKey`(desc `Lnet/minecraft/resources/ResourceKey;`)→ `aliases=[val$p_319942_]` |

描述符匹配与唯一候选:`ByteBufCodecs$25`(即 `$23` 经匿名类普查搬到的家)**只有一个** `ResourceKey` 字段
`val$p_319942_`(另一个字段是 `val$p_320353_:Function`)⇒ 唯一,补别名。

## id 11 重派生:消失

用本轮内核 jar(`/tmp/id11-kernel.jar`,sha256 `2e1bc958…`)真实启动承载 polymer 的主体
`gardnercraft-2.0.0.jar`(+ fabric-api、polymer-bundled 闭包),读其 `compatibility-report.json`:

- 控制台实证改写真的发生:
  `[Forbric/Mixin] …PacketCodecsRegistryMixin: @Shadow field val$registryKey no longer exists in its target; aliased to val$p_319942_, the one field of the same descriptor the merged base declares`
- findings 里 **`mixin:polymer-core.mixins.json:…PacketCodecsRegistryMixin` 不在了** ⇒ id 11 掉出派生集合。

**如实记口径**:该主体的这次启动 `world=false`(`cause=registry-load`),按本清单的纪律,`cr` 数字本身
**不能**当世界级证据;这里成立的是**类级**观测——mixin 应用发生在入世之前,而同一份 findings 里
`PacketCodecsEntriesMixin` 的 `InvalidInjectionException` 被照常报出,说明该类级审计确实跑到了这些 mixin。
RegistryMixin 没有对应的 `did not fit` / `InvalidInjectionException` 行。

**同轮旁证(不是本 id)**:`fabric-events-interaction-v0` 的 `ServerPlayNetworkHandlerMixin` 也吃到同一条规则
(`@Shadow field val$target` → `val$entity`),其 `mixin:` finding 由 "3/4 anchors … missing @Shadow field" 变为
只剩匿名类 `@Mixin target` 漂移那条软锚点。`PacketCodecsEntriesMixin`(id 10 的 id 串)**仍在**,但原因已从
"未 fit 被 suppress" 变成应用期 `InvalidInjectionException`(`@ModifyVariable` 的 `method` 选择器仍钉着旧 owner
`ByteBufCodecs$22`);该 mixin **无任何字段**,与本规则无关,是匿名类重定向未覆盖注入器 `method` 选择器 owner 的
既存缺口,不在本轮范围。

## 同轮并入:remap 不再因一个读不了的 `.class` 条目杀死整个主体

`CheaperGapples.jar` 在 `MixinShadowMembers.scan` 里触发 `ClassReader.<init>` 的 `IllegalArgumentException: null`。
修法与证据:

- `ReadableClassEntries.parse/readable`:读前先验证,读不了就跳过并**计数、一次性点名**(`MixinShadowMembers.scan` 也改用它,判据只有一处);
- 关键发现:**只补 scan 不够**——同一条空 `.class` 条目会让 tiny-remapper 自己
  `error analyzing <entry> from <jar>` 再死一次(实测),所以 `FabricGuestRemapper.remapAll` 在把 jar 交给引擎前先
  清洗(去掉读不了的条目,写临时副本,`finally` 删除),后面的 `MixinNames`/`InheritedMemberRefs`/`InheritedMemberDecls`
  也只见得到可读类。干净 jar 原样返回,不复制。

## 测试

- `ShadowFieldAliasesTest`(5):唯一候选→别名 + 判定 FIT;两个同描述符候选不猜(UNFIT、0 别名);
  名字仍在→不别名;名字在但描述符不同→不别名;开关两侧一致。
- `MixinShadowMembersTest`(4):新增"空 `.class` 条目被跳过、可读类照常处理、坏条目被点名";
  新增"引擎只见可读类"(`ReadableClassEntries` 清洗后 `remapJar` 不抛,坏条目被丢、好类仍在)。
- 顺手修复既存红:`ForbricCacheTest.resolveAndIsCached` 在 `fd8110a4` 把 `isCached` 收紧为"非空 zip"后仍写
  字符串 `"x"` 期待命中;改为写一个真 jar,并新增"非 jar 桩不算命中"这一边界断言。

聚焦套件(staged-root 旗标:`FORBRIC_OLD=/tmp/w7-stage`、`-Pforbric.stagedRoot=/tmp/w7-stage/run`、
`-Pforbric.rebornEnergy`/`fabricApi`/`mcLibraries`)在 `net.forbric.kernel.mapping.*` 与
`MixinFit*`/`ShadowFieldAliasesTest`/`MixinAnonymousRetarget*` 上全绿。
