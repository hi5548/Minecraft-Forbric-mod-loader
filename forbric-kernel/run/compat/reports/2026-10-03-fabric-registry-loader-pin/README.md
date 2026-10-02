# fabric-registry-sync 的注册装载回调：pin 与 adapter 指向的类在 0.116.17 不存在

**结论**：`registry-load`（每个 fabric 主体在 `RegistryDataLoader.lambda$load$6` 上以
`IllegalStateException: Registry must be non-empty` 死在 world load）这一条上的四处处长名错误已修：
kernel 的 pin/adapter/服务谓词/审计串都写 `RegistryDataLoaderMixin`（fabric-api 0.154.0 世代的类），
而本分支实际装载的 **fabric-api 0.116.17 里那个类叫 `RegistryLoaderMixin`**。修法是把四处改成
按世代的精确名（两条都列，装哪代只有哪条生效），并让「摘 pin」以 adapter 能否真正保留回调为前提。
**这四处的名字修好了；它是否就是 world load 那个空注册表的成因，由修正后的 fabric 切片判定——尚未跑。**

## 1. 症状与已排除项（都是字节，不是推断）

- 死在 `RegistryDataLoader.lambda$load$6`（合并基底第 140–151 行）的「声明非空却空了」断言；
  只有两个注册表带 `requiredNonEmpty`：`wolf_variant` 与 `painting_variant`（静态初始化里 3 参构造各用两次）。
- 无 per-element 错误 ⇒ 不是解析失败，是**资源列举为空**（`loadContentsFromManager` 的
  `FileToIdConverter.json(elementsDirPath).listMatchingResources(rm)` 没拿到文件）。
- 排除「包/数据路径」：合并基底 jar 里 50 个 `painting_variant`、9 个 `wolf_variant` 齐全；vanilla pack 的
  `namespaces == {minecraft}`、根路径来自 classpath；`configurePackRepository` 的 enabled 列表含 `vanilla`
  且无 `Missing data pack`；fabric 的 `refreshAutoEnabledPacks` 在健康实例上 `removeIf(fabric_isHidden)` 为空操作
  （`fabric_isHidden()` = `parentsPredicate != DEFAULT_PARENT_PREDICATE`，构造器赋了初值）。
- **A/B（W7Harness，`reports/2026-10-03-forge-registry-ab/`）**：同一天的内核与基底上，
  `modelfix` 与 `athena-ctm`（neoforge）都 **PASS**（`Done (4.184s)` / `Done (6.565s)`，
  `Registry loading errors` = 0）⇒ 故障是 **fabric 闭包特定**，数据/包路径无罪。
- fabric 闭包里唯一能*静默*丢弃元素的是 `fabric-resource-conditions` 的 `checkResourceCondition`
  （`applyResourceConditions` 对无 `fabric:load_conditions` 的 JSON 返回 true，parse 失败也返回 true）⇒ 已读字节排除。

## 2. 名字错误的证据链

```
unzip -p <remapped fabric-registry-sync-v0-0.116.17 jar> fabric-registry-sync-v0.mixins.json
  → mixins: [BootstrapMixin, ChunkSerializerMixin, DebugChunkGeneratorAccessor,
             ExperimentalRegistriesValidatorMixin, IdListMixin, MainMixin, RegistriesAccessor,
             RegistriesMixin, RegistryKeysMixin, RegistryLoaderMixin, RegistryMixin,
             SaveLoadingMixin, SerializableRegistriesMixin, SimpleRegistryAccessor, SimpleRegistryMixin]
unzip -l <同样的 jar> → 含 RegistryLoaderMixin.class，无 RegistryDataLoaderMixin.class
```

四处（连同两处测试）此前都指向后者：

|file:line|修前|
|---|---|
|`FabricRegistryLoaderMixinAdapter.java` `PIN` / `MIXIN`|`...:RegistryDataLoaderMixin` / `...sync.RegistryDataLoaderMixin`|
|`MergedBaseMixinCompat.java` `SUPPRESSED_MIXINS`|`fabric-registry-sync-v0.mixins.json:RegistryDataLoaderMixin`|
|`ForbricMixinService.suppressedMixinsFor`|`out.remove("RegistryDataLoaderMixin")`|
|`FabricApiModuleLossAudit`|`DynamicRegistrySetupCallback never fires (RegistryDataLoaderMixin is pinned)`|
|`ForbricMixinServiceTest` / `FabricRegistryLoaderMixinAdapterTest`|同名 fixture 与断言|

后果：**pin 抑制了一个不存在的类**（`MergedBaseMixinCompat` 的条目永不命中），而真正会应用的
`RegistryLoaderMixin` 未被 pin、也未被适配地应用了——`wrapOperation$zgp000$fabric-registry-sync-v0$wrapIsServerCall`
出现在每条失败栈里（`RegistryDataLoader.java:1056`）就是这个。

**陷阱**：`RegistryDataLoaderMixinEarly` 是 Wover 的类（`de.ambertation.wover.core.mixin.registry`），
另一 mod 的另一 mixin，`RegistryDirectoryOwnerInjectorTest` 故意对它断言。改名一律按「精确名 / `:后缀`」判定，
不做子串替换；期望计数是 6 production + 2 test → 0，不是 15 → 0。

## 3. 逐字节的形状差（W7Harness 要求的「adapter 假设 vs 真类」）

真类（0.116.17，remapped，`javap -v`）三个 handler：

```java
@Mixin(RegistryDataLoader)
wrapIsServerCall(Object, RegistryAccess, List<RegistryData>, Operation<Frozen>)
    @WrapOperation(method = load(ResourceManager, RegistryAccess, List),
                   at = @At(INVOKE, target = load(LoadingFunction, RegistryAccess, List)))    // 已是活的私有重载
beforeLoad(Object, RegistryAccess, List<RegistryData>, CallbackInfoReturnable<Frozen>, List<Loader<?>>)
    @Inject(method = load(LoadingFunction, RegistryAccess, List),
            at = @At(INVOKE, target = List.forEach), ordinal = 0)                          // 与 ordinal = 1
    // 捕获的 List<Loader<?>> 铸成 DynamicRegistryView，发 DynamicRegistrySetupCallback（pending tags 面）
prependDirectoryWithNamespace(ResourceKey, Operation<String>)
    @WrapOperation(method = {loadContentsFromNetwork, loadContentsFromManager},
                   at = @At(INVOKE, target = Registries.elementsDirPath(ResourceKey)))
```

- 它绑的是 **`ThreadLocal`**，不是 26.2 世代的 `ScopedValue`，也没有异步重绑；
- **没有 `supplyAsync` handler**，注解也**已经**指向活的私有 `load` ⇒ adapter 的 `adapt()`（为
  `load(LoaderFactory, List, List, Executor, List) → CompletableFuture` 那对加宽重载而写）在 1.21.1 上
  无事可做，返回 0。因此这里的机制只能是 **fallback pin 生效**，而此前 pin 指错了类 ⇒ 等于没生效。

## 4. 改动

- `FabricRegistryLoaderMixinAdapter`：认识两代类名（`named()`/`knownNames()`），`PIN` = 本世代
  （`...:RegistryLoaderMixin`，审计行问的就是它），`PINS` 两条都列；`adapt()` 的前置条件提成共用谓词
  `widenedShape(...)`（加宽重载对 + public→private 恰一次调用）；新增 `retainsOnBase()`（读同一份合并基底
  `RegistryDataLoader`，失败即 false = 保守方向）与 `resetForTests(...)`（同
  `KernelRegistryDirectories.resetForTests` 的形状）。
- `ForbricMixinService`：暴露静态 `mergedBaseNodeFor(internalName, flags)`（与实例读数同一 reader，供 config 改写
  阶段提问）；`suppressedMixinsFor` 的摘 pin 条件 = 配置名命中 ∧ adapter 启用 ∧ `retainsOnBase()`，命中则摘
  `PINS` 里实际在役的那条。
- `MergedBaseMixinCompat`：条目名 + 该条 javadoc 重写为 PORT(1.21.1)（ThreadLocal、三个 handler 的实际形状、
  代价 = registry-sync 的 server/client 上下文与 `DynamicRegistrySetupCallback`，由审计行照报）。
- `FabricApiModuleLossAudit`：用户可见串改为 `RegistryLoaderMixin is pinned`。

## 5. 测试（红 → 绿）

- 新增 `ForbricMixinServiceTest.theRegistryLoaderPinHoldsUntilTheAdapterCanRetainTheCallback`：
  `retainsOnBase()` false ⇒ 回退 pin 必须命中，且**必须命中模块真正声明的那个类**；true ⇒ 两条 pin 一起被摘。
  **红**：把常量改回旧名后该测试 `FAILED`（pin 名 ≠ 模块声明的类）⇒ 改回后 0 failed。
- 新增 `FabricRegistryLoaderMixinAdapterTest.everyRegistryLoaderNameTheStagedModuleDeclaresIsPinned`：
  从 **已装载模块的 mixin 配置**读类表，adapter 认识且模块声明的每个名字都必须有同名 pin。
  （本 checkout 未 staged fabric-api fixture ⇒ 该测试 skip；0.116.17 fixture 下即红。）
- 实测：上述两类 16 tests / 3 skipped / 0 failed；`mixin` + `boot` + `transform` 三包与基线**逐一对齐**
  ——改动前后各 29 个失败且集合完全相同（`comm` 双向为空，全是本 checkout 缺 staged 游戏 jar 的既失败）。

## 6. 切片读数（W7Harness，`reports/2026-10-03-registryloader-pin/`，内核 `16e70adb` sha `2e1183e9`）

**pin 现在真的生效了**（本改动唯一的可观测行为差异，逐字）：

```
[Forbric/Mixin] suppressed mixin RegistryLoaderMixin from fabric-registry-sync-v0 (fabric-registry-sync-v0.mixins.json)
```

**而 world load 的失败逐字节不变**：两个主体（cristellib / balm）仍是 `registry-load`，
`>> Errors in element minecraft:painting_variant: IllegalStateException: Registry must be non-empty`，
`cr`/`req` 不变 ⇒ **名字错误是必要但不充分**。

附带发现（已修，见 `6cb5cf19`）：第一版实现的两个世代 pin 列表让抑制日志为**配置里不存在的那个类**也打印了一行
"已抑制"——与刚从审计文案里删掉的同一种假陈述；现只报本配置真有的名字。

## 7. §6 那个读点已被字节清掉（Q1/Q2 的答案都是"否"）

1. **模块自己的判定不会丢无条件的元素**：`ResourceConditionsImpl.applyResourceConditions(JsonObject, String,
   ResourceLocation, HolderLookup$Provider)` 里 `if (!obj.has("fabric:load_conditions"))` 跳到 **156: `iconst_1;
   ireturn`**（保留）；condition codec 解析失败也落到同一处（先打 `LOGGER.error(… skipping …)`，仍保留）。
   只有"键在、解析成功、`test()` 返回 false"才是 FALSE。`RegistryLoaderMixin.checkResourceCondition` 仅在 FALSE 时
   `reader.close(); ci.cancel()`。vanilla 的 painting/wolf 文件没有任何条件键。
2. **kernel 的 `KernelFabricConditions` 在缺席/未知时答"保留"**：`alsoAskFabric` 先判
   `input instanceof JsonObject json && json.has(KEY)`（无键的根本不问），`ask(...)` 在两条失败路径（句柄缺失、invoke 抛）
   都返回 `null`，而丢弃分支是 `Boolean.FALSE.equals(keep)`；代码里写明理由（"failing closed here would delete content
   over a reflection problem"）。

⇒ 条件路径不是 dropper，§6 作废。当前首选假设：**`configurePackRepository` 之后包 SELECTION 被改写，ResourceManager
根本拿不到 vanilla pack**——那会静默清空**每一个** vanilla datapack registry，而只有两个 `requiredNonEmpty` 的上报；
fabric 闭包里唯一会改写选中列表的是 `fabric-resource-loader-v0` 的 `ResourcePackManagerMixin`（26.2 世代的
`PackRepositoryMixin`）经 `ModResourcePackUtil.refreshAutoEnabledPacks`。该假设由 W7Harness 用单旋钮矩阵验证（步骤 1 =
`-Dforbric.disableMixinConfigs=fabric-resource-loader-v0.mixins.json`；全红则改用 `-Dforbric.mixinDiagnostics` 让
静默软跳过的注入现形）。

## 8. 机制与修法（bisect-1 之后确认）

bisect-1（`-Dforbric.disableMixinConfigs=fabric-resource-loader-v0.mixins.json`）把 dropper 锁在这个 config 内，
字节链随后闭合：

1. 合并基底的 `PackRepository` 是 **26.2 形状**（有 `rebuildSelected(Collection)List`、`addPack`、`removePack`，
   且 `rebuildSelected` 内确实调 `ImmutableList.copyOf`）⇒ fabric `ResourcePackManagerMixin.handleAutoEnableDisable`
   的 `@At(INVOKE, ImmutableList.copyOf)` 锚能落，并在返回前把正在构造的选中列表交给
   `ModResourcePackUtil.refreshAutoEnabledPacks(list, available)`。
2. 该方法首句 `enabled.removeIf(p -> ((FabricResourcePackProfile) p).fabric_isHidden())`；而
   `ResourcePackProfileMixin.fabric_isHidden()` 是 `parentsPredicate != DEFAULT_PARENT_PREDICATE` 的同一性判定，
   `parentsPredicate` **只在那个 mixin 自己的无参构造里赋值一次**（类上无 `Initialiser`）。合并基底的 `Pack`
   构造是 merge 的、NeoForge 改过形 ⇒ 该构造没进去时字段停在 JVM 默认 `null` ⇒ **每个包都读成 hidden ⇒ 选中列表被清空**。
3. 清空**无异常、无 INFO**：重新加回的循环嵌在被过滤的列表上（`enabled.listIterator()`），列表一空循环体零次执行，
   `fabric_parentsEnabled` 也不会被调用（null 不 NPE）⇒ `createResourceManager()` 拿到零个 pack ⇒
   **每个 datapack registry 从空装载**，只有两个 `requiredNonEmpty` 的上报。

修法：`FabricResourcePackProfileMixinAdapter`（同一约定：adapter + 开关 `-Dforbric.fabricResourcePackProfile=off`）
在 `fabric_isHidden()` 的同一性判定前补一条 `parentsPredicate == null` ⇒ 跳到**既有 false 分支**；
只补「未设过」这一种情形，显式设过 predicate 的包照旧 hidden。

## 9. 尚未做

- 游戏 JVM/切片不在本 agent lane（共享机器；由 W7Harness 跑）。
- 0.116.17 模块 jar 未 staged 成测试 fixture ⇒ §5 第二条生成测试在本 checkout 只 skip；要它本地也由红到绿需那一步。
- 未把抑制日志行写成测试（断言日志串不是行为契约）。

