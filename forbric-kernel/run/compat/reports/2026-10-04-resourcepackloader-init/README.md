# `ResourcePackLoader.<clinit>` 的那面墙：Sodium 的 mixin 在 1.21.1 的**空** LoadingModList 上解不出自己(2026-10-04)

**一句话。** `NoClassDefFoundError: Could not initialize class …ResourcePackLoader` 不是 NeoForge 的问题，
是内核在 1.21.1 上把 NeoForge 的 `LoadingModList` 播成了**空表**：Sodium(NeoForge 构建)的
`ResourcePackLoaderMixin` 把一个 `@Unique static final` 字段的初始化
`LoadingModList.get().getModFileById("sodium").getFile()` 并进了目标类的 `<clinit>`，
空表让它返回 null，`<clinit>` 于是 NPE、类被 poison，`PackRepository.rebuildSelected` 再触到就是
`NoClassDefFoundError`。修法用的是内核**已有**的播种器（26.2 路径早在用），只是 1.21.1 路径没接上。
提交 `0c0a2eea`。

---

## 1. 复现:这条墙只对"装了 NeoForge 系 mod 的集合"存在

用户真实 12-mod 集合(modmenu 为主体,其余 11 只作闭包),冻结在 `/tmp/w7-client-usermods/mods/`;
运行证据在 `reports/2026-10-04-client-modmenu-usermods/per-mod/run/000-modmenu__fabric/`。逐字读数:

```
java.lang.NoClassDefFoundError: Could not initialize class net.neoforged.neoforge.resource.ResourcePackLoader
	at net.minecraft.server.packs.repository.PackRepository.rebuildSelected(PackRepository.java:83)
	at net.minecraft.server.packs.repository.PackRepository.reload(PackRepository.java:38)
	at net.minecraft.client.Minecraft.<init>(Minecraft.java:487)
Caused by: java.lang.ExceptionInInitializerError: Exception java.lang.NullPointerException [in thread "Render thread"]
	at net.neoforged.neoforge.resource.ResourcePackLoader.<clinit>(ResourcePackLoader.java:526)
	at net.forbric.kernel.runtime.KernelClientPackSource.readWithTheJarsOwnMeta(KernelClientPackSource.java:282)
	… — Minecraft.<init>(Minecraft.java:486)
```

同一条在更早的 `2026-10-02-client-loaders/…/001-sodium__neoforge` 也出现过——**主体就是 sodium-neoforge**。
fabric-api-only 的十余次运行到不了这里,因为这条 `reader` 只在有 Forge 系 mod 要经 NeoForge 的 pack reader
建包时才被走到;`ResourcePackLoader` 这个类此前从未被初始化过。

**先分离两件事**:`<clinit>` 本身很短（`javap -c` 只有 3 个赋值：`LOGGER`、
`MOD_PACK_SELECTION_CONFIG`、`OPTIONAL_FORMAT`），在隔离的干净 classpath 上**能正常初始化**（已实测：
`java -cp <merged-base:forge-runtime:neoforge-runtime:vanilla libs> Probe` → `INIT OK`）。所以 null 不来自
这三行本身,也不来自内核对该类的改写——`ForbricMergedBaseCompatTransformer` 对
`ResourcePackLoader` 应用后字节**未变**（`changed=false`）。真正把 `null` 塞进 `<clinit>` 的是 **Mixin**。

## 2. 字节证据(两侧都量过)

**客方（Sodium）**。`sodium-neoforge-0.8.13+mc1.21.1.jar` 的 `META-INF/jarjar/…-mod.jar` 里：

```
$ javap -v net/caffeinemc/mods/sodium/mixin/platform/neoforge/ResourcePackLoaderMixin.class
RuntimeInvisibleAnnotations:
  0: org.spongepowered.asm.mixin.Mixin(
       value=[class Lnet/neoforged/neoforge/resource/ResourcePackLoader;]
     )
  private static final net.neoforged.neoforgespi.locating.IModFile SODIUM_FILE;   // @Unique

$ javap -c -l (同一类)
  static {};
    Code:
       0: invokestatic  LoadingModList.get()LoadingModList;
       3: ldc           String sodium
       5: invokevirtual LoadingModList.getModFileById(String)ModFileInfo;
       8: invokevirtual ModFileInfo.getFile()ModFile;
      11: putstatic     SODIUM_FILE:Lnet/neoforged/neoforgespi/locating/IModFile;
      14: return
    LineNumberTable: line 26: 0
```

Mixin 把 mixin 的 `<clinit>` 并入目标 `<clinit>`，于是 `ResourcePackLoader.<clinit>` 的**第一条**就是
`LoadingModList.get()…getModFileById("sodium").getFile()`。（崩溃报告把行号渲染成 526，是 Mixin 合并后
行表偏移的产物；`SourceFile` 仍是目标的 `ResourcePackLoader.java`，所以帧名如此。）

**内核**。`PassiveSeeder.seedNeoForge21Loader`（1.21.1 分支）此前调：

```
private static void seedEmptyNeoForgeLoadingModList(ClassLoader gameLoader, Class<?> fmlLoader) {
    …
    LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of())   // 非空,零 mod
}
```

`LoadingModList.get()` 返回的就是这个空表（`of(...)` 自写 `INSTANCE`；`get()` = `getstatic INSTANCE`，
已 javap 确认），所以 `getModFileById("sodium")` = **null** → `.getFile()` 在 `<clinit>` 里 NPE。

## 3. 修法(既有机制,不新增)

**形态归属**:这和今天其它几条同属一类——**第三方 mixin 建立在"内核本该播好、却播成空/丢失"的结构上**
（今日的 `val$` 捕获名、匿名类编号、重锚适配器都在同一族）。下次的具体结构会不同,形状不会。判别方式也
因此固定:一个「内核播的数据」+ 一个「mod 编译期就假定它非空」的解引用,通常落在某个类的 `<clinit>`,
一旦失败就是 `ExceptionInInitializerError` → 整类 poison → 更晚的 `NoClassDefFoundError`。

26.2 路径早就在用"POPULATED, not empty"的播种器 `seedNeoForgeLoadingModList`，其 javadoc 明写空表正是它
要避免的东西、并点名 Sodium 的 `getModFileById`。1.21.1 路径只是没接上它。改法：

- `seedNeoForge21Loader` 改调 `seedNeoForgeLoadingModList(gameLoader, fmlLoader, null, modsDir,
  KernelBoot.nestedJarJarJars())`；第三个参数传 `null` 表示写 1.21.1 的 **STATIC**
  `private static LoadingModList loadingModList`（`field.get(null)`/`set(null,…)` 即静态寻址）。
- 该播种器已带 1.21.1 的字段形状（`buildModFile`/`fillModFileInfo` 里的 `PORT(1.21.1)`：`jar`/`SecureJar`、
  五参 `LoadingModList.of` 等），并经 `KernelBoot.nestedJarJarJars()` 把 Sodium 的**嵌套 jar** 纳入 presence，
  `getModFileById("sodium")` 因此解得出来。
- 删除因此不再被调用的 `seedEmptyNeoForgeLoadingModList`（它等价于 `seedEmptyLoadingModList(…, null)`）。
- `-Dforbric.seedLoadingModList=off` 仍回退空表（既有开关,未动）。

## 4. 红 → 绿(离线单测,真实 staged neoforge-runtime 字节)

```
./gradlew test --tests 'net.forbric.kernel.boot.PassiveSeederLoadingModListTest' \
  -Pforbric.stagedRoot=/Volumes/ORICO/forbric/p0/p0/run \
  -Pforbric.rebornEnergy=/Volumes/ORICO/forbric/p0/p0/fixtures/energy-4.1.0-named.jar
```

- 默认(无 staged 根)：`7 tests, 6 skipped` —— 真字节用例缺席即跳过。
- 指定 staged 根后：**`7 tests, 0 skipped, 0 failed`**。
- 新增 `the1_21_1StaticShapeSeedsTheSameList`：`loaderInstance=null` 必须写 STATIC 字段，且
  `LoadingModList.get().getModFileById("kerneltestmod")` 必须真解出该 mod——正是 Sodium mixin 在
  `ResourcePackLoader.<clinit>` 里问的那一句。

## 5. 验收读数(运行前登记,禁止事后改判据)

运行由 `W7Harness` 拥有,集合为**用户真实 12-mod**(modmenu + 11 闭包),**冷 remap 缓存**,构建自
`0c0a2eea`。三条判据:

1. `NoClassDefFoundError … ResourcePackLoader` **缺席**（且无点名 `ResourcePackLoader.<clinit>` 的
   `ExceptionInInitializerError`）；
2. `world=true`；
3. `joined world via quick-play` ≥ 1。

**运行读数(`W7Harness`,构建 `0c0a2eea`,sha `648cb6758f85f3af863bf0bde73d5e41564d7333b1c9aa09a4af337aff55670d`,
真实 12-mod,冷 remap 缓存,JDK 21,报告 `reports/2026-10-04-rpl-init/`):**

1. **成立(逐字)**。`NoClassDefFoundError … Could not initialize class …ResourcePackLoader`：**0 次**；
   点名 `ResourcePackLoader.<clinit>` 的 `ExceptionInInitializerError`：**0 次**。该类正常初始化。
2. **未成立** —— `world=false`。3. **未成立** —— `joined world via quick-play: 0`。
   该行 `run=CRASH exit=255 world=false frames=0 cause=mixin-apply seconds=828`,`confirmed_required: 1`
   （`initialization:constructor`,内核为该崩溃记的 finding）。

**正面标记(运行前请求的那一行,逐字):**
```
[Forbric/Seed] seeded NeoForge LoadingModList with 64 mod(s) (16 Forge-family, 48 Fabric for presence)
  — mods that resolve themselves through FMLLoader.getLoadingModList() (Iris' version pro…
[Forbric/Seed] LoadingModList.getModFileById also answers for 47 underscored mod id(s)
```
空表路径的静默消失,共 13 行 `[Forbric/Seed]`。

**证伪"只是巧合"的最强一条**:启动现在**跑进了 Sodium 的 mixin handler** ——
`Minecraft.handler$cbm001$sodium$loadConfig(Minecraft.java:13517)` at `<init>:488`。该 handler 只在
`ResourcePackLoaderMixin` 的 `<clinit>` 合并**成功**后才存在;修复前那个类被 poison,其后什么都跑不到。
墙不是移开了,是消失了。

### 5.1 因此暴露的**下一面墙**(不是本车道,也不是本次改动的产物)

```
Description: Initializing game
java.lang.RuntimeException: Sodium's config could not be found; the game is in a broken state most likely
  caused by an earlier error from another mod. Please check the game log (latest.log) for any errors…
	at net.caffeinemc.mods.sodium.client.config.ConfigManager.registerConfigs(ConfigManager.java:119)
	at net.caffeinemc.mods.sodium.client.config.ConfigManager.registerConfigsEarly(ConfigManager.java:72)
	at net.minecraft.client.Minecraft.handler$cbm001$sodium$loadConfig(Minecraft.java:13517)
	at net.minecraft.client.Minecraft.<init>(Minecraft.java:488)
```

判据:这条用的是 **NeoForge 的 `ModList`**（`ConfigLoaderForge.collectConfigEntryPoints` 迭代
`ModList.get().getMods()` / `getModContainerById`),**不是** 本次播的 `LoadingModList`;本次改动只动
`LoadingModList`,故这是**暴露**而非**引入**。`W7Harness` 另给一条线索(未作诊断):
`[Forbric/Load] 12 mod(s) did not finish loading: fabric-content-registries-v0, fabric-events-interaction-v0,
fabric-item-api-v1, fabric-loot-api-v3, fabric-object-b…`。归谁判、怎么修不在本报告范围。

**结论(严格按本车道契约)**:`ResourcePackLoader.<clinit>` 的 NPE 已修且已被运行证伪重现;预登记第 1 条成立。
第 2/3 条被一个**更晚、另一处**的崩溃挡住,不由本改动负责,也未被读成本字段的成功。

### 5.2 收口(§5.1 那面墙也被修掉后,同一集合的合成读数)

`W7Harness` 后续在 `ReviewFixes` 的批次(**`e11e4fcb`**,sha `451c6780…`,已 rebase 到 main)上复用**同一
十二集合**重跑:

```
PASS  world=true  frames=1  strict=TRUE  confirmed_required: 0  seconds=102  崩溃报告 0 份
`Sodium's config could not be found`：0 次
```

即 §5.1 那面墙由 `ReviewFixes` 的 **P5 `dbc4d3de`**("FRAPI evidence check misses Sodium's real entry
point")负责;本次改动**先把 `ResourcePackLoader` 解 poison,才第一次让启动走到 Sodium 的 config
registration**,两步串联、各自暴露下一面墙。`ConfigLoaderForge` 在通过的那次日志里仍出现一次——Sodium 的
Forge 侧 config 路径是**被走到并满足**,而不是被跳过(墙"消失"与"移走"的区别)。

**因此:同一十二集合的预登记第 2 条(`world=true`)在合成构建上成立;第 3 条(`joined world via
quick-play`)本报告未取得逐字引用,不作断言。** 本车道的 `ResourcePackLoader` 墙已关。
