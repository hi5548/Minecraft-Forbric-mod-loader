# 能不能把 Create（机械动力）加进用户的 12-mod 集合？—— 预登记、一次 strict 客户端 boot、字节级定位、以及一处有界内核修复

**Verdict (EN).** **YES — after one small, bounded kernel fix.** Create was fetched with its required
deps, the set was booted `strict` on the frozen kernel, and the first boot **failed** on a named
kernel-side blocker; with that one defect fixed Create boots `strict` alongside the 12 mods.
`run=PASS world=true frames=1 confirmed_required=0 strict=TRUE exit=0`. The blocker was
**kernel-side**: the kernel's client mod-loading window never published
`ClientModLoader.isLoading()==true`, and NeoForge gates `ItemBlockRenderTypes.setRenderLayer` on it.

**答案（中文）。** **可以。** Create 6.0.10（1.21.1 NeoForge）+ 用户真实 12-mod 集合，在**冻结内核
`a56bf626`** 上第一次 `strict` 客户端 boot **不通过**（`mod=DEGRADED`、`cause=mixin-apply`、
`catalog_failures=['create']`）；字节级定位到**内核侧**一处有界缺陷（客户端 mod-loading window 没有
把 `ClientModLoader.isLoading()` 置真），修好后同集合 **`strict` 一遍通过**。

| | 集合 | 内核 | 逐字读数 |
|---|---|---|---|
| 控制臂 | 用户 12 + Create 6.0.10 | `a56bf626`（= 已发布 `0.3.5-beta`，未改） | `run=PASS world=true frames=1 exit=0 mod=DEGRADED catalog_failures=['create'] confirmed_required=0 loaded=false strict=FALSE cause=mixin-apply` |
| 修复臂 | 同上 | `988357cb`（本车道 boot 半 + 已发布 runtime 半） | `run=PASS world=true frames=1 exit=0 mod=OK catalog_failures=[] confirmed_required=0 loaded=TRUE strict=TRUE cause=None` |

判据（`evidence/preregistration.md` §Pre-committed reading 运行前写下）：**证伪条件没有触发** ——
第一次 boot 不是 `strict`；命中的是我预登记的第 1 号假设（guest mixin / 设配期），但其具体落点比我预想的
更靠"内核自己的生命周期窗口"，不是 Create 的 mixin 去不到锚点。

---

## 1. 集合与版本（逐字节冻结，`evidence/mod-set.txt`）

用户真实 12 mod（`/Applications/.minecraft/versions/1.21.1-forbric/mods/`，原样复制，sha256 未变）：

| mod | 文件 | 版本 |
|---|---|---|
| Mod Menu | `modmenu-11.0.5.jar` | 11.0.5 (fabric) |
| Fabric API | `fabric-api-0.116.17+1.21.1.jar` | 0.116.17+1.21.1 (fabric) |
| JEI | `jei-1.21.1-neoforge-19.57.0.450.jar` | 19.57.0.450 |
| Sodium | `sodium-neoforge-0.8.13+mc1.21.1.jar` | 0.8.13+mc1.21.1 |
| Lithium | `lithium-neoforge-0.15.4+mc1.21.1.jar` | 0.15.4+mc1.21.1 |
| FerriteCore | `ferritecore-7.0.3-neoforge.jar` | 7.0.3 |
| ModernFix | `modernfix-neoforge-5.27.24+mc1.21.1.jar` | 5.27.24+mc1.21.1 |
| EntityCulling | `entityculling-neoforge-1.11.2-mc1.21.1.jar` | 1.11.2 |
| ImmediatelyFast | `ImmediatelyFast-NeoForge-1.6.14+1.21.1.jar` | 1.6.14+1.21.1 |
| AppleSkin | `appleskin-neoforge-mc1.21-3.0.9.jar` | 3.0.9 |
| Cloth Config | `cloth-config-15.0.140-neoforge.jar` | 15.0.140 |
| Placeholder API | `placeholder-api-2.4.2+1.21.jar` | 2.4.2+1.21 (fabric) |

新增：**Create `6.0.10` for mc1.21.1 (NeoForge)** —— `create-1.21.1-6.0.10.jar`,
sha256 `ef87fe5709f1ba1f5b8bb20a2925b5afb4669e178fd6d8bf10c167759eefe37a`,取自 Modrinth
`LNytGWDc/versions/UjX6dr61`。

**required deps**：Create 的 `neoforge.mods.toml` 把 `flywheel` 与 `ponder` 声明为 `required`，两者
（以及 `Registrate`）都通过 `META-INF/jarjar/` **内置在同一只 jar 里**（JiJ），`jar_ids` 读到的
`provides = {create, flywheel, ponder}`，所以闭包不额外添文件；内核的 JiJ 抽取把它当独立 mod 加载
（证据：`[Forbric/Seed] … 9 of the Forge-family ones came out of another mod's jar`，且
`flywheel` / `ponder` 在 `compatibility-report.json` 里 `status=OK`）。声明需要
NeoForge `[21.1.219,)`；载体是 **21.1.252**，满足。

装置：`w7/harness/sweep_client.py`（客户端面，quick-play 进 `W7Client`，`-Dforbric.compatibilityPolicy=strict`），
`W7_JAVA` 钉 **JDK 21.0.7**；stage 由 `/Applications/.minecraft/.forbric-build/out/` 三个 jar 组装，
mc root 用安装实例的 `libraries/ assets/ .forbric/mappings/ versions/1.21.1` 覆盖，世界夹具
`w7/corpus/client-world/W7Client`。跑法与 `2026-10-04-sodium-cutout` / `2026-10-06-release-0.3.5-beta` 同一车道。

## 2. 第一次 boot：命名阻塞点（内核侧，`evidence/gate-control-reading.txt` / `console-markers.txt`）

客户端**进得了世界**（`joined world via quick-play: W7Client` → `client-ready after 200 world tick(s)`
→ `clean disconnect observed`，exit 0，0 份 crash-report），但 **Create 自己是 DEGRADED**：

```
[Render thread/WARN]: [Forbric/Lifecycle] create failed during client setup
java.lang.IllegalStateException: Render layers can only be set during client loading!
        This might ideally be done from `FMLClientSetupEvent`.
    at net.minecraft.client.renderer.ItemBlockRenderTypes.checkClientLoading(ItemBlockRenderTypes.java:474)
    at net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(ItemBlockRenderTypes.java:460)
    at com.tterrag.registrate.builders.BlockBuilder.lambda$registerLayers$5(BlockBuilder.java:163)
    at com.tterrag.registrate.util.OneTimeEventReceiver.accept(OneTimeEventReceiver.java:82)
    … EventBus.post … KernelNeoSetup.post/firePhase
    at net.forbric.kernel.boot.KernelLifecycle.fireClientSetupLifecycle(KernelLifecycle.java:1820)
    at net.minecraft.client.Minecraft.<init>(Minecraft.java:486)
```

load-report 头：`这一次启动，10 个 mod 有一部分没有跑起来。` 第一条即
`Create (create) … 有一部分没有跑起来 — it threw during client setup`；
`compatibility-report.json`：`catalogFailures=[{modId:create, status:DEGRADED, detail:"it threw during client setup"}]`，
`confirmedRequired=0`（11 只依赖全 `OK`）。

**为什么（字节级）。** 合并基底的 `ItemBlockRenderTypes.checkClientLoading()` 不是 vanilla 的静态开关：

```
private static void checkClientLoading();
   0: invokestatic net/neoforged/neoforge/client/loading/ClientModLoader.isLoading:()Z
   3: ldc "Render layers can only be set during client loading! …"
   5: invokestatic com/google/common/base/Preconditions.checkState
```

而 `isLoading()` 只读载体自己的静态字段（javap `neoforge-runtime.jar` 21.1.252）：
`isLoading() { getstatic loading; ireturn }`。genuine NeoForge 在
`ClientModLoader.begin(Minecraft, PackRepository, ReloadableResourceManager)` 里 `loading=true`、
在 `finishModLoading`（setup 阶段之后）里 `loading=false`。**内核把这处 `begin` 的调用点整体重定向**
到 `KernelClientLifecycle.onClientModLoadingWithPacks`（`LifecycleHookInjector` 的
`CLIENT_INIT_TRIGGERS`），genuine body 不再执行 —— 那次写入随之消失，`loading` 从头到尾是 `false`，
于是内核自己 fire `FMLClientSetupEvent` 时 `checkClientLoading()` 抛。Registrate 的
`BlockBuilder.registerLayers` 正是在 `FMLClientSetupEvent` 里给 Create 的每一个方块设 render layer，
所以 Create 整个被标 DEGRADED。

> 补充：控制台另有一条 `[Mixin/mixin] @Mixin target type mismatch: … ParticleEngine is not an interface`
> 的 ERROR。**与 Create 无关**，也不是本车道的阻塞点：它是内核自己把 fabric-api 的
> `ParticleManagerAccessor.getFactories()` 从 `@Accessor` 改成 default method 之后，Mixin 把该接口
> mixin 判为 `SubType.Interface` 而拒绝整个 `fabric-particles-v1.client.mixins.json`（详见 §4）。它
> 不阻断 boot（`ParticleEngine` 的 target 与 Create 无关），两臂都在，按"记录形状与代价"处理。

## 3. 修复（1 处，全在 boot 半，`evidence/diff.patch`）

`KernelLifecycle.java`：在客户端生命周期窗口前后发布/收回载体自己的 `loading` 字段，与 genuine
NeoForge 的窗口一致（`begin` 置真、setup 阶段之后清假）：

- 新增 `setClientModLoadingState(ClassLoader, boolean)`：反射写 **载体自己的** `loading` 字段
  （NeoForge 与 Forge 两个 `ClientModLoader` 都试，缺的那个跳过），只写字段、不替换 getter —— 与既有
  `setForgeLoadingState` 同一规矩；带 `-Dforbric.clientModLoadingState=off` 开关可回到旧行为（做对照臂）。
- `onClientModLoading()` 把 `driveNativeRegistration(CLIENT)` 包进 `setClientModLoadingState(cl,true) …
  finally setClientModLoadingState(cl,false)`。窗口之外的行为与被发布内核**逐字节同**（旧内核那里恒为
  false），所以"窗口外不回归"不是推断而是保持。

**修复臂读数**（`evidence/gate-fix-reading.txt`，内核 `988357cb`）：

```
run=PASS  exit=0  world=true  frames=1  stopped=true  killed=false  loaded=true  strict=TRUE
mod=OK  catalog_failures=[]  confirmed_required=0  seconds=41  cause=None
joined world via quick-play: W7Client
client-ready after 200 world tick(s)
clean disconnect observed; stopping client
```

- `grep -c "create failed during client setup"` = **0**；`grep -c "Render layers can only be set"` = **0**。
- load-report 头回到 **`9 个 mod 有一部分没有跑起来`**，Create **不在**其中（`evidence/load-report-create-fix.txt`）——
  这正是 `0.3.5-beta` 发布说明里记的十二 mod 常态（"启动日志仍见 … 9 mod(s) 有一部分没有跑起来"），
  说明修复只是把 Create 收进这个干净集合，没有动别的 mod。
- 12 只依赖全 `OK`，0 份 crash-report，`confirmed_required=0`。

**装配（明写）**：`/Volumes/ORICO` 未挂载、`energy-4.1.0-named.jar` 本机不存在 ⇒ 带 staged 属性的整体
构建跑不了。按 `2026-10-04` 车道同一方式：boot 半 `./gradlew --offline -q -Pforbric.stagedRoot=<空目录> jar`
（无 staged 游戏 jar ⇒ 只出 boot 半），runtime 半由**已发布 `0.3.5-beta` jar 里的
`META-INF/jars/forbric-kernel-runtime.jar` 逐字节注入**（本车道未动任何 `src/runtime/java` 文件）。
`kernel-fix.jar` sha256 `988357cbb676e1097c375aedfe4fc6a8c47669e79e7279d37d5ca76dde54c5b4`，
`assert_game_side` 通过（`evidence/build-provenance.txt`）。

## 4. 记录（形状与代价，未修，不在本判决路径上）

**fabric-api 的 `ParticleManagerAccessor` 接口 mixin 被 Mixin 拒绝。** 内核的合并基底修复把
`net.fabricmc.fabric.mixin.client.particle.ParticleManagerAccessor.getFactories()` 从 `@Accessor` 改写为
**default method**（`ForbricMergedBaseCompatTransformer.readTheFactoriesThroughThatBridge`，因为该 base 只有一个
`ResourceLocation` 键的 `providers` 字段，旧 `@Accessor` 绑不上），并移除了它的 `@Accessor` 注解。Mixin 的
`MixinInfo.Variant` 判定一条"接口 mixin 里只要有一个**非 synthetic、非 accessor** 的方法就是 INTERFACE 变体"，
于是这个接口 mixin 变体会被要求 target 是接口 —— 而 target 是类 `ParticleEngine`，
`MixinConfig.prepareMixins` 抛 `@Mixin target type mismatch: … ParticleEngine is not an interface`，
整个 `fabric-particles-v1.client.mixins.json` 的 mixin 不再施加。

- 代价：`fabric-particles-v1` 的 `ParticleManagerAccessor`（以及同 config 的 `BlockDustParticleMixin`）
  不生效；`fabric-api` 的粒子工厂注册读不到那张表。
- 是否本车道引入：**否**。因果与 Create 无关（无 Create 时同样成立），两臂逐字都在（控制 2 行、修复 2 行）。
- 可能的修法（未做，需另开车道）：在改写时把该 default method 标 `ACC_SYNTHETIC`（Mixin 的 variant 判定
  明确跳过 synthetic），或把该 mixin 从接口改造成 kernel 自己的 mixin。**不过** accessor 变体能不能接受一个
  无注解的 default 方法需要先探针验证，所以不在这条"快问快答"车道里顺手改。

## 5. 证据清单（`evidence/`）

| 文件 | 内容 |
|---|---|
| `preregistration.md` | **运行前**写下的集合、装置、判据与证伪条件（事后未改） |
| `mod-set.txt` | 13 只 jar 的 sha256 + 两臂内核 sha + JVM/stage/mc/世界夹具 |
| `gate-control-reading.txt` | 控制臂 `results.jsonl` 逐字 + boot gate 行 + 命名阻塞点行 |
| `gate-fix-reading.txt` | 修复臂 `results.jsonl` 逐字 + boot gate 行 |
| `console-markers.txt` | 两臂的阻塞 marker 对照（控制有 / 修复 0） |
| `load-report-create-control.txt` / `-fix.txt` | 两臂 `load-report.txt` 头部（10 → 9，Create 消失） |
| `diff.patch` | `KernelLifecycle.java` 的全部改动 |
| `build-provenance.txt` | 修复臂 jar 的构建方式与 sha |

运行目录（可复核）：控制臂 `/private/tmp/create-bughunt/gate`，修复臂 `/private/tmp/create-bughunt/gate-fix`，
scratch 语料 `/private/tmp/create-bughunt/scratch/corpus`，stage `/private/tmp/create-bughunt/stage`，
mc overlay `/private/tmp/create-bughunt/mc`，修复臂 jar `/private/tmp/create-bughunt/kernel-fix.jar`。

## 6. 偏离与未做（明写）

- **未改用户安装实例**、未动 `w7/reports/**`、未留诊断代码；内核源码只改了 `KernelLifecycle.java` 一处。
- `gradlew test`/完整游戏侧构建在本机跑不了（`energy-4.1.0-named.jar` 缺失，与 `2026-10-04` 车道同因），
  故改动未跑单测套件；判定靠真实客户端 boot 的两臂读数 + `javap` 字节证据。
- 本车道**只跑了一次**控制 boot 与一次修复 boot（各自 `--only create`）。未做：更深的 in-world 行为、
  机械动力配方/渲染的像素读数、以及 §4 那条 fabric 访问器缺陷的修复。
- 为把 §4 归因到"是否与 Create 有关"，本可再跑一次"无 Create 的十二 mod"控制臂；按"先给判决"的要求
  该次运行已中止，§4 的归因由字节因果链给出（见上），如实标注未实测。
