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

**运行状态:已交给 `W7Harness`（请求见对话），读数待回填。** 若三者同时成立,则这面墙关；若 1 仍不成立,
本报告的机制即被证伪,须重开（不得读成成功）。
