# 颜色修复(两步一体)：基底 getColor 归原始键 + 重绑 fabric-rendering-v1 的两个颜色 mixin；附一次 W7Harness 客户端读数

**一句话。** 合并基底把 `BlockColors`/`ItemColors` 拼成了**两个生态各取一半**：字段初始化与 `register` 是
NeoForge 的（`IdentityHashMap` + **原始 `Block`/`Item` 键**），而 `getColor` 是 MinecraftForge 的（拿
`ForgeRegistries.*.getDelegateOrThrow(...)` 的 `Holder$Reference` 作键）。两个半边永远对不上 ⇒ **每一次颜色查表都
落空、回退 `MapColor`**：原版草/水/树叶/皮革等同色路径全灭（物品栏草方块图标发灰、放置后的草方块顶层失绿），同一个
map 后面的 Fabric `ColorProviderRegistry` 也一并失活。本次落地**两步一体**的修复（提交 `ad9d5615`）：

1. **基底半步**：把三处 `getColor` 的 Holder 键改回原始键（`BlockColors` ×2、`ItemColors` ×1）；
2. **Fabric 半步**：把 `fabric-rendering-v1` 的 `BlockColorsMixin`/`ItemColorsMixin` 重绑到新 map——`@Shadow`
   字段描述符 `IdMapper` → `Map`，`get` 由 `map.byId(BuiltInRegistries.X.getId(k))` 改为 `map.get(k)`——并把
   这两条从 `MergedBaseMixinCompat.SUPPRESSED_MIXINS` 摘除。

任一半步单独落地都会得到"看起来修好了、其实没有"的状态，所以它是一个维修、一次提交、一次读数。

---

## 1. 缺陷：实字节

合并基底 `patched-mc-merged-1.21.1.jar`（sha256 `eb774133…`）逐字节（`javap -p -c`，与
`reports/2026-10-04-inert-apis/evidence/colour-census.txt` 一致）：

```
BlockColors.getColor(BlockState,Level,BlockPos):            BlockColors.getColor(BlockState,BlockAndTintGetter,BlockPos,int):
  1: getfield   blockColors:Ljava/util/Map;                   1: getfield   blockColors:Ljava/util/Map;
  4: getstatic  ForgeRegistries.BLOCKS:IForgeRegistry;        4: getstatic  ForgeRegistries.BLOCKS:IForgeRegistry;
  8: invokevirtual BlockState.getBlock():Block;               8: invokevirtual BlockState.getBlock():Block;
 11: invokeinterface IForgeRegistry.getDelegateOrThrow(...)  11: invokeinterface IForgeRegistry.getDelegateOrThrow(...)
 16: invokeinterface Map.get(Object):Object;                 16: invokeinterface Map.get(Object):Object;

ItemColors.getColor(ItemStack,int):                          register (两侧一致, 原始键):
  1: getfield   itemColors:Ljava/util/Map;                     BlockColors.register →  blockColors.put(rawBlock, provider)
  4: getstatic  ForgeRegistries.ITEMS:IForgeRegistry;          ItemColors.register  →  itemColors.put(itemLike.asItem(), provider)
  8: invokevirtual ItemStack.getItem():Item;                  字段初始化:  new java.util.IdentityHashMap<>()
 11: invokeinterface IForgeRegistry.getDelegateOrThrow(...)
 16: invokeinterface Map.get(Object):Object;
```

| | 字段初始化 | `register` 存的键 | `getColor` 查的键 | 一致? |
|---|---|---|---|---|
| Forge `patched-mc-forge` | `HashMap` | `getDelegateOrThrow(block)` | `getDelegateOrThrow(state.getBlock())` | 是 |
| NeoForge `patched-mc-neoforge` | `IdentityHashMap` | 原始 `Block` | `state.getBlock()`（原始） | 是 |
| **合并基底** | `IdentityHashMap`(N) | 原始 `Block`(N) | `getDelegateOrThrow(...)`(**F**) | **否** |

`IdentityHashMap` 下 `Holder$Reference` 与原始 `Block` 永不相等 ⇒ **查表恒落空**，回退
`state.getMapColor(...).col`（或 `-1`）。这条缺陷与 Fabric 无关，它影响**原版自己的同色路径**。

**Fabric 那一半**：`fabric-rendering-v1` 的两个 mixin 读的是**同一张表**，只是它 `@Shadow` 的字段声明为
`IdMapper`（`net/minecraft/core/IdMapper`，按 `BuiltInRegistries.BLOCK.getId(k)` 索引），而基底声明为
`Map`。Mixin 按**名字 + 描述符**绑定 `@Shadow` ⇒ 绑定失败、**整支 mixin 被丢弃**（含它把
`ColorProviderRegistry.initialize` 挂在 `createDefault` RETURN 上的那个注入器）。所以这两条此前被 pin 在
`SUPPRESSED_MIXINS` 里——这也是"只解 pin 不改字节"会立刻炸的原因。

---

## 2. 修复

`ForbricMergedBaseCompatTransformer` 新增一个维修
`restoreTheRawColourKeysAndRebindTheColourMixins`（登记进 `REPAIRS` 与 `claims()`，`scanned` 声明），两个半边：

### 2.1 基底半步 `stripTheForgeRegistryKey(node, field, expected, keyOwner, keyMethod)`
只对 `BlockColors`(2 处) / `ItemColors`(1 处) 生效。它逐条核对形状——`getDelegateOrThrow` 之前必须**恰好**是
`getstatic ForgeRegistries.<F>` + `aload <state>` + `<BlockState|ItemStack>.getBlock|getItem()`——满足才把
**那两条指令**（`getstatic` 与 `getDelegateOrThrow`）删掉，让原始键直接流入 `Map.get`；命中数不等于 `expected`
或出现任何别的形状就**整类不动**（拒改优于半改）。

### 2.2 Fabric 半步 `rebindTheColourMixinToTheRawMap(node, field)`
只对两个 guest mixin 生效，且只在 `get` 方法**恰好**含一个 `IdMapper.byId`、一个 `DefaultedRegistry.getId`、
一个 `BuiltInRegistries` `getstatic` 时：字段描述符改 `Ljava/util/Map;`，删掉那两个 id 步骤，把 `byId`(INVOKEVIRTUAL)
就地改成 `Map.get`(INVOKEINTERFACE)。幂等（第二遍字段已非 IdMapper，返回 0）。

### 2.3 一起落地的理由
- 基底半步单独落地：原版颜色回来了，但 Fabric 注册面仍死（mod 以为注册成功、画面不变）；
- Fabric 半步单独落地：读一张**没人会填**的表——`ColorProviderRegistry.get` 拿到的还是 `Map`（Fabric 自己
  注册得进去），但**渲染器**仍按 Holder 键查它，也仍然落空。
所以必须同一次落地。

`MergedBaseMixinCompat.SUPPRESSED_MIXINS` 里 `fabric-registry-sync-v0` 的三条**保持 pin**：它们把表交给
`IdListTracker` 做"数字 id ↔ 对象"同步，而基底的 `Map` 是对象键、没有这一维——那是**桥**，不是锚点，本次不碰。

---

## 3. 离线证明（不启游戏 JVM）

`evidence/ColourFixProbe.java` 驱动**真正编译出来的**维修方法跑在**真实字节**上（merged base + remap 后的 guest），
全文 `evidence/colour-fix-probe-output.txt`：

```
== step 2 ==  合并基底 BlockColors 起始 2 处 delegate → 修后 0 处; ItemColors 1 → 0
              getColor 体变为 aload_0; getfield blockColors:Map; aload_1; BlockState.getBlock(); Map.get; checkcast
              两条 getColor 的 StackMapTable 帧逐字不变(未触碰帧; 删的是直线段, 落点峰值栈 2 < 原 maxStack 3)
              幂等(第二遍返回 0)
  NEGATIVE CONTROL: 把 getstatic owner 换掉 → 维修拒改, 类原样不动(2 处 delegate 仍在)
== step 1 ==  guest BlockColorsMixin/ItemColorsMixin 的 @Shadow 由 IdMapper → Map
              get 体变为 (aload_0; getfield Map; [ItemLike.asItem]; Map.get; checkcast; areturn)
              第二遍幂等; 两个 @Shadow 描述符与基底字段逐字相等(Mixin 绑定所需)
== verifier == 四个类(修后的 BlockColors/ItemColors/两个 mixin)全部通过 ASM CheckClassAdapter.verify
ALL CHECKS PASSED
```

即"形状对、类型对、帧对、幂等、负对照可证伪"都量过了。`javap` 复核与帧安全性论证见
`2026-10-04-inert-apis/README.md` §3.2 与 `evidence/colour-census.txt`。

---

## 4. 预登记读数（**运行前登记，禁止事后改判据**）

**被测量的形状**：内核 jar `/tmp/tint-kernel.jar`（sha256 `b50c95b09506f11079f84da8a0ac21f9fec0067951747a45e06998d854abca21`，
= 当前树 `src/main` 编译 + 同级 `src/runtime` 的 runtime 半，见 §6）；集合 = **用户真实十二**
（subject `modmenu-11.0.5.jar` (fabric)，closure = 其余 11：fabric-api 0.116.17 / JEI / Sodium / Lithium /
FerriteCore / ModernFix / EntityCulling / ImmediatelyFast / AppleSkin / Cloth Config / Placeholder API）；JDK 21；
**热** remap 缓存；客户端表面、quick-play 进 `W7Client`、`-Dforbric.compatibilityPolicy=strict`。

只接受：

- **(A) 控制臂**：同一集合/同一装置，内核 = 已发布、**未含**本修复的 `0.3.4-beta` jar
  （已验证其字节**不含** `restoreTheRawColourKeysAndRebindTheColourMixins`），至少 1 张截图；
- **(B) 主读数（像素）**：用 `evidence/sample-tint.py` 采样截图。
  - 修复臂：至少一张截图的 `grass_green_frac >= 0.03`，且其 `mean_green_excess_over_grass >= 15`；
  - 控制臂：对应截图的 `grass_green_frac <= 0.01`；
  - **方向判据**：修复臂 `grass_green_frac` ≥ 控制臂的 2 倍。若两臂草绿都≈0（例如出生视角对着天空/石头），
    本条**判为不可判**并如实写，而不是用"没看到灰"冒充通过。
- **(C) 控制台落地点（证明确实生效，而非碰巧）**：
  - `BlockColors looked its colours up through a Forge registry delegate ... (2 site(s) re-keyed to getBlock)`
  - `ItemColors looked its colours up through a Forge registry delegate ... (1 site(s) re-keyed to getItem)`
  - `BlockColorsMixin's @Shadow blockColors is an IdMapper this base no longer has ...`
  - `ItemColorsMixin's @Shadow itemColors is an IdMapper this base no longer has ...`
- **(D) 不回归**：修复臂 `run=PASS`、`world=true`、`confirmed_required: 0`、
  `[Forbric/ClientSmoke] joined world via quick-play` ≥ 1、0 份 crash-report；`fabric-rendering-v1` 那条不再因
  `BlockColorsMixin/ItemColorsMixin` 记 DEGRADED（其余抑制项照旧）。
- **(E) 证伪**：(C) 缺失即"修复未在客户端生效"；(D) 出现 `confirmed_required>0` 或 crash 即逐字列出——**崩掉的
  修复比记录在案的缺陷更糟**，本车道会撤回而不是保留。

---

## 5. 验证运行读数（登记后回填）

_（待运行；本节在 §4 登记之后追加，不改 §4。）_

---

## 6. 环境与偏离（必须写在明面上）

- **`/Volumes/ORICO` 未挂载**（用户拔盘），`/private/tmp` 亦被清理：p0 的 staged 产物与其上的 remap 缓存、
  `w7-reports/` 报告树、两个构建夹具（`fabric-api-*-named.jar`、`energy-4.1.0-named.jar`）全部不可用。
  本车道改用内部盘：`/Applications/.minecraft/.forbric-build/out`（patched merged base / forge-runtime /
  neoforge-runtime，sha 与已安装副本一致）与已安装实例
  `/Applications/.minecraft/versions/1.21.1-forbric/`（用户真实 12 mod + 热 remap 缓存）。
- **本次 jar 的组装方式（因此可复核）**：`energy-4.1.0-named.jar` 在内部盘上**确实不存在**（`find /` 无果），
  故 `gradlew jar` 的 game-side 不能重建。实际做法：
  1. `./gradlew compileJava`（当前树，含本修复）；
  2. `./gradlew jar`（无 staged game jars 时 gradle 只发 boot 半，不嵌 runtime，正是设计）；
  3. runtime 半由**当前** `build/classes/java/runtime` + `build/resources/runtime` 重新打包
     （含 7 个 transfer 类、不含 team/reborn），作为 `META-INF/jars/forbric-kernel-runtime.jar` 注入 boot jar。
  三者同源（同一 checkout、同一编译），boot 半 = 本修复后的 `src/main`。
- **移动了 3 个 macOS Finder 重复源码**（未跟踪、内容较旧、会导致 `javac` 重复类失败）：`KernelLoadReport 2.java`、
  `KernelModListScreen 2.java`、`KernelLoadReportTest 2.java` 已移至 `/tmp/tint-strays/`（未删，可复原）；
  `build/**` 下同源的 `* 2.class` 亦已清出打包。这是复现 `build-kernel.sh` 头注里记的"stale jar"事故形状。
- **未做**：未改 `fabric-registry-sync-v0` 的三条 pin（见 §2.3）；未触碰两个 mixin 的 `create` 注入器（锚点本来就在）。

---

## 7. 证据清单（`evidence/`）

| 文件 | 内容 |
|---|---|
| `ColourFixProbe.java` | 驱动真实维修方法的三腿探针（形状/帧/幂等/负对照 + ASM verifier） |
| `colour-fix-probe-output.txt` | 上述探针的完整输出（ALL CHECKS PASSED） |
| `sample-tint.py` | 纯 stdlib 的 PNG 像素采样器（grass_green / grey_tex / sky 分类 + 模式 RGB） |
| `colour-census.txt` | （引用自 `2026-10-04-inert-apis/evidence/`）合并基底/Forge/NeoForge/guest 的字段与键路径逐条 javap |

## 8. 提交

| 提交 | 覆盖 |
|---|---|
| `ad9d5615` | 两步一体：基底 `getColor` 原始键 + 重绑两个 guest 颜色 mixin + 摘两条 pin |
| _（本节报告与读数回填的提交）_ | |
