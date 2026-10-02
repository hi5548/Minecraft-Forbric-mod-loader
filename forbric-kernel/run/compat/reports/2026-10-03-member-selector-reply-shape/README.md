# 成员选择器的"同形回复"剥掉了描述符:L 与末尾分号——冷缓存下整个 fabric 闭包一起坏

`a08a03cd`(为 `@At(NEW)` 的**类**选择器做同形规范化)顺手套到了**成员**选择器的成功路径上,产出畸形字符串。
热缓存里是修复前的 jar,所以这条回归躲过了当时所有验证;`-11` 提版强迫重派生,**重派生出来的就是坏的**。

## 症状(冷缓存,`/tmp/w7-remap-cache-11` 建为空;W7Harness 跑三主体后主动停)

|subject|cr|req|world|cause|
|---|---:|---:|---|---|
|cristellib|**88**|118|no|**mixin-apply**|
|balm|**88**|119|no|**mixin-apply**|
|chipped|**90**|120|no|**mixin-apply**|

对照:一小时前**热**缓存同一批主体 `cr=2`——即那次读的是**修复前**的 remap 阶段产物。

控制台(逐字):

```
InvalidInjectionException: @Inject annotation on startResourceReload, has invalid target descriptor:
  Invalid owner: net/minecraft/server/MinecraftServer;reloadResources. Using refmap fabric-lifecycle-events-v1-refmap.json
```

owner 里混进了成员名:`net/minecraft/server/MinecraftServer;reloadResources` 被整体当成 owner。

## 根因与逐字节证据

真字节、真映射(`p0/mc-1.21.1/.forbric/mappings`),对**同一份原始模块**跑本阶段:

| | `MinecraftServerMixin#startResourceReload` 的 `method=` |
|---|---|
| 修复前(冷) | `net/minecraft/server/MinecraftServer;reloadResources(Ljava/util/Collection;)Ljava/util/concurrent/CompletableFuture` |
| 修复后 | `Lnet/minecraft/server/MinecraftServer;reloadResources(Ljava/util/Collection;)Ljava/util/concurrent/CompletableFuture;` |
| **热缓存(修复前内核,能进世界)** | `Lnet/minecraft/server/MinecraftServer;reloadResources(Ljava/util/Collection;)Ljava/util/concurrent/CompletableFuture;` |

修复后与"能进世界"的那份**逐字节相同**。坏形状的来源是 `MixinNames.sameShape` 的第二支:

```java
if (!wrapped && answerWrapped) return answer.substring(1, answer.length() - 1);   // 对整个字符串去掉 L 和 ;
```

**类名有两种拼法**(`L…;` 与裸类名),所以对类做同形规范化是对的;成员选择器没有第二种拼法:对
`Lowner;member(desc)ret;` 做 `substring(1, len-1)` 得到 `owner;member(desc)ret`。修前这些成员选择器
**查不到、原样放过**(包裹完好、Mixin 可用);拼写修复让查找成功之后,回复形状错了。

独立佐证(W7Harness,常数池 diff,同一模块 stale `8827` vs fresh `11`,仅 7 个类不同,全是 mixin):
类那一半正确(`SynchronizeRecipesS2CPacket;<init>(…)V` → `ClientboundUpdateRecipesPacket;<init>(…)V`),成员那一半
每个类都掉了 `L`/`;`(`LivingEntityMixin`、`MinecraftServerMixin`、`ClientChunkManagerMixin`、`server/WorldChunkMixin`)。

## 红→绿

- **单模块**:上表;另 `failOnNullItem`(refmap 键为**点号**拼法)同属坏形状,修复后回到合法描述符。
- **类那一半不受影响**:`@At.target=net/minecraft/world/item/trading/MerchantOffer`(裸类名,`a08a03cd` 的本意)保持不变。
- **闭包级扫描**(一个真实主体的 **51** 个 guest jar、**625** 条注入选择器;坏形状判据 = 含 `;` 且不在描述符内、
  又不以 `L` 开头):修复前 **119 条**,修复后 **0 条**。(同一扫描亦确认:返回类型为基本类型的选择器本就不以 `;` 结尾,
  不是坏形状——第一版判据因此误报,已收紧。)
- **与修复前产物的全量对拍**(lifecycle 模块 77 条选择器):差异只有三处**类**重定位
  (`SynchronizeRecipesS2CPacket→ClientboundUpdateRecipesPacket`、`WorldChunk→LevelChunk`、`ClientWorld→ClientLevel`)
  与两处 `lambda$…` 补齐为完整描述符;没有任何成员选择器回到坏形状。
- **单元测试**:`MixinNamesTest.aBareMemberKeyKeepsItsDescriptorAndIsNotStripped`(真实裸键 `"reloadResources"`)。
  带固定夹具跑(`MC_DIR=<p0/mc-1.21.1>`):**6 tests / 0 skipped / 0 failed**;把守卫拿掉后**只有它一条失败**。

## 修法与提交

`e4afa796`:`sameShape` 只服务类选择器;回复里带描述符的成员选择器原样返回。回归测试同上。

## 验证(冷缓存切片,判据预先写定)

`553ecf7a` / 干净 worktree / 全新空缓存 ⇒ **10/10 主体 `cr=0`、9/10 进世界、8/10 STRICT PASS**
(此前同一 10 主体在冷缓存上 `cr=88–90`、`cause=mixin-apply`,每个都到不了世界)。两条残留是主体侧闭包问题
(`cobblemon_skills_api` 未声明的 Pufferfish 依赖、`cobblemon-auto-battle` 自身数据):**没有新 id 顶上来**。
报告目录 `2026-10-03-fabric-cold-fixed/`。

## 无关项(排除)

`InheritedMemberDecls` 与此无关,两条独立证据:该 pass 只改**声明**(`node.methods[].name`)且对 `@Mixin` 类 stand-down,
四个模块 jar 78 个类 **0 处**声明改动、refmap 值逐字节相同(FabricBootFails2);且**声明重命名不可能产出畸形的选择器字符串**。
