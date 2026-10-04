# Fabric 的八个非 strict 主体:逐行判决、一条新内核修复、以及一条藏在行里的 harness 假象(2026-10-04)

范围:用户 ≥95% strict 的 bar 下,fabric 50 样本的**全部**剩余缺口。样本 50,`na`(客户端专属)14,
applicable 36,strict 28 —— 差 **8** 个非 strict 主体。本报告把八个逐个判为 **内核侧 / 主体侧 / harness 假象**,
逐条给字节级证据;把唯一的新内核修复落地(真字节红→绿);其余的内核侧行经复核确认由**中间落地的提交**
修好、只差一次带正确 JDK 的重测。所有重测的证 bootstrap 交 `W7Harness`,预先登记读数写在第 §5 节。

判据来源(当时,用于确定"八个"是哪八个):`python3 harness/bucket_table.py --extra
2026-10-03-recheck-frozen,2026-10-03-cobblemon-ref,2026-10-03-gunpowder-dump,2026-10-04-recheck-walls`
(fabric 行:appl 36 / world 32 / strict 28 / raw 78% / closure 82%;与 Main 给的 cause 计数
`boot-stall 2, nosuchmethod 2, noclassdef 2, registry-load 1, dependency-not-ok 1` 逐字一致)。

**这条命令只用来界定初始的八个,不是复现用的权威命令。** `2026-10-04-recheck-walls` 后来被撤回(它的每一行
都是 JDK 25 量的,§1),而上面那个 78%/82% 也是修复前的数。**复现当前数字请用 §9.3 那条**(去掉
`recheck-walls`、补上修复后的行;fabric 92%/97%)。这两处必须一致,否则读者复现出的是一张已撤回的表。

## 0. 权威的八个(按行来源,不按旧表)

| # | subject | cause(权威行) | 行来源 @ kernel | 判决 |
|---|---|---|---|---|
| 1 | `NoSmithingTemplateReFabriced-1.0.2.1+1.21.1.jar` | nosuchmethod | full-corpus @ 16a95295 | **内核侧 — 已关** (`36e93f82`,boot 已证) |
| 2 | `ShadowGuard-0.0.1.jar` | noclassdef | recheck-walls @ ea196237 | **内核侧 — 本次修复** (`7d21924f`) |
| 3 | `cobblespawnregions-1.0.8.jar` | boot-stall(旧行 registry-load @16a95295) | recheck-walls @ ea196237 | **内核侧 — 已被中间提交修好,只差重测** |
| 4 | `Cobblemon-Auto-Battle-0.1.4.jar` | boot-stall(旧行 registry-load @16a95295) | recheck-walls @ ea196237 | **内核侧 — 同上** |
| 5 | `cobblecoop-1.5.1-fabric.jar` | nosuchmethod(旧行 registry-load @16a95295) | recheck-walls @ ea196237 | **内核侧 — 已被 `36e93f82` 修好,只差重测** |
| 6 | `cobblemon_pufferfish_api_fabric-0.9.1-beta.jar` | noclassdef | recheck-walls @ ea196237 | **主体侧**(未声明的依赖) |
| 7 | `gardnercraft-2.0.0.jar` | registry-load | recheck-frozen @ 53c23314 | **依赖侧**(版本不匹配) |
| 8 | `beilin-data-portability-fabric-1.21.x-1.2.7.jar` | dependency-not-ok | bucket-fabric @ 16a95295 | **harness/环境 假象**(主体自身 PASS/world) |

一个**横切**的 harness 假象(第 §1 节)叠在上面:#2/#3/#4/#5 的 recheck-walls 行是在 **JDK 25** 上量的,
而 Cobblemon 闭包里的 Truffle 需要 JDK 21。这条不是任何一行的"内核缺陷",但会让读的人把内核判错。

## 1. 横切 harness 假象:sweep 跑在 JDK 25 上(不是内核缺陷)

`sweep.py` 的 JVM 取环境里的 `java`:

```
399:        java = os.environ.get("W7_JAVA") or shutil.which("java") or "java"
```

本机 `java -version` = **25.0.1**。Cobblemon 闭包内嵌 `META-INF/jars/truffle-api-22.3.0.jar`(证据
`evidence/jdk-unsafe-traffle.txt`),Truffle 调用 `sun.misc.Unsafe.ensureClassInitialized(Class)`:

```
jdk-21: 1        # present
jdk-22: 0        # removed
jdk-25: 0
```

于是 JDK 25 上 `[Cobblemon Showdown]` 线程抛
`NoSuchMethodError: 'void sun.misc.Unsafe.ensureClassInitialized(java.lang.Class)'`(recheck-walls 的
cobblecoop console,证据同文件)。**旁证**:`ps`/`lsof` 抓正在跑的 recheck-walls2 子 JVM(pid 21020),
其加载的二进制是 `/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin/java`(argv[0] 是
`/usr/bin/java`,JAVA_HOME/W7_JAVA 皆未设)——即本次排障期间 harness 并未真正钉住 21。对照启动台
(`/tmp/w7-fabric-ref`,以及 §4 我重跑的那次)用的是 JDK 21。

**结论**:凡闭包触到 Truffle 的主体(目前已知 Cobblemon 系),必须在 `W7_JAVA=…/jdk-21…` 下量;
已在重测前把这条与证据发给 `W7Harness`。这不是"改一行代码"的内核修复,是读数环境。

### 1.1 到底哪些主体带它 —— 扫描(全 166 主体整闭包,`W7Harness`)

只扫主体 jar 得 0(结构性看不见:来源是**依赖**),扫"主体 + 闭包"才得真答案。**5 个主体**带
`sun.misc.Unsafe.ensureClassInitialized`,全部经 `Cobblemon-fabric-1.8.1+1.21.1.jar` 内嵌的
`js-22.3.0.jar` / `regex-22.3.0.jar`(Truffle/GraalJS):

| subject | loader | bucket 行 | 进了分母? |
|---|---|---|---|
| `Cobblemon-Auto-Battle` | fabric | FAIL | **是** |
| `cobblecoop` | fabric | FAIL | **是** |
| `cobblespawnregions` | fabric | FAIL | **是** |
| `veinminer-enchant` | **neoforge** | FAIL | **是** |
| `mod-day-counter` | fabric | FAIL | 否(`na`,客户端专属) |

⇒ 这条假象**不是 fabric 专属**:凡闭包触到 Cobblemon 的 loader 都中招,neoforge 的样本里也有一个
(`veinminer-enchant`),它被计成失败但尚未在 JDK 21 上重测。W7Harness 已把它加进重测;本报告只判 fabric
的八个,但这个跨 loader 事实必须写下来,否则 neoforge 的表会带着同一条缺陷被引用。`W7Harness` 同时给每行加了
`java` 字段(pin → `21.0.7`,ambient → `25.0.1`),让"仪器"在它产出的行上可见。

## 2. 逐行判决(字节级证据)

### 2.1 `NoSmithingTemplateReFabriced` — 内核侧,已关(非本次)
FCAP `ConfigTracker` 桥(`36e93f82` + `d99bd6d9`)在冻结 sha `e74b0274…` 上 boot 证 `PASS/world=true/strict=true`,
报告 `forbric-kernel/run/compat/reports/2026-10-03-fcap-config-bridge/README.md`。本报告不重复。

### 2.2 `ShadowGuard-0.0.1.jar` — **内核侧,本次修复**
- **行内证据**(在 recheck-walls ea196237 的 console,`world=true`):`NoClassDefFoundError: net/fabricmc/loader/launch/common/FabricLauncherBase`,由 LuckPerms 的工作线程抛出。
- **缺的类属于谁**:`net/fabricmc/loader/launch/common/` 是 **fabric-loader 自己** 的包(0.15 之前启动器的位置)。
  真 fabric-loader **0.19.5 仍然发布它**(`evidence/fabric-loader-0.19.5-legacy-facade.txt`,
  `/tmp/w7-fabric-ref/libraries/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar` 内
  `net/fabricmc/loader/launch/common/FabricLauncherBase.class` + `FabricLauncher.class`)。
- **调用形状**(真字节,javap -c `me/lucko/luckperms/fabric/FabricClassPathAppender`,`evidence/luckperms-legacy-launcher-refs.txt`):
  ```
  invokestatic  net/fabricmc/loader/launch/common/FabricLauncherBase.getLauncher:()Lnet/fabricmc/loader/launch/common/FabricLauncher;
  invokeinterface net/fabricmc/loader/launch/common/FabricLauncher.propose:(Ljava/net/URL;)V
  ```
- **声明的闭包该不该带它**:**该主体确实声明了 LuckPerms**(`dep_status` 里 LuckPerms=FAILED),jar 也在了;
  缺的类不是"少下了一个依赖",而是**内核的 loader shim 只发布了 `net.fabricmc.loader.impl.launch` 那一份**,
  没发布真 loader 仍在发布的旧包门面。⇒ 内核侧,不是闭包缺口。
- 修复与红→绿见 §3。

### 2.3 `cobblespawnregions-1.0.8.jar` — 内核侧,已被中间提交修好
- 旧行(`@16a95295`)的 console 逐字:`IllegalStateException: Unknown registry key in
  ResourceKey[minecraft:root / minecraft:worldgen/structure_processor]: cobblemon:height_range`
  (还有 `cobblemon:random_pooled_states`),以及 `Failed to parse cobblemon:worldgen/processor_list/…json`。
- 这是 **Cobblemon 入口点早死**的下游:Cobblemon 的 `CobblemonProcessorTypes.<clinit>` 往
  `BuiltInRegistries.STRUCTURE_PROCESSOR`(`class_7923.field_41161`)注册 `random_pooled_states`/`height_range`
  (`javap -c com/cobblemon/mod/common/world/structureprocessors/CobblemonProcessorTypes.class`),而旧内核里
  Cobblemon 的 entrypoint 在 `EntityDataSerializers.registerSerializer` 的调用者守卫处 abort —— 见
  `reports/2026-10-03-cobblemon-registries/README.md`(修于 `1023cbc8`+`c2b9d90a`)。
- 现在行(boot-stall @ea196237)只是 **550 s 的 harness 超时**(remap 在启动窗口内跑),不是主体/内核读数。
- 对照启动台见 §4。
- 判决:**内核侧,由中间提交修好;只差一次 `HEAD + JDK 21 + --boot-timeout 2400` 的重测。**

### 2.4 `Cobblemon-Auto-Battle-0.1.4.jar` — 同上
旧行 `@16a95295` 的 console 里每条 `processor_list` 叶因同样逐字
`Unknown registry key … structure_processor]: cobblemon:height_range`;现行为 boot-stall。判 **内核侧,
同一簇,只差重测**。它比 #2.3 还多一层:主体自己在 `[Cobblemon Showdown]` 里用 Truffle(§1)。

### 2.5 `cobblecoop-1.5.1-fabric.jar` — 内核侧,已被 FCAP 桥修好
- 现(recheck-walls ea196237)逐字:
  ```
  java.lang.NoSuchMethodError: 'net.neoforged.fml.config.ModConfig net.neoforged.fml.config.ConfigTracker.registerConfig(
      net.neoforged.fml.config.ModConfig$Type, net.neoforged.fml.config.IConfigSpec, java.lang.String, java.lang.String)'
      at fuzs.forgeconfigapiport.fabric.impl.core.NeoForgeConfigRegistryImpl.register(NeoForgeConfigRegistryImpl.java:17)
      at com.ghostrick.cobblecoop.fabric.FabricCoopPlatform.registerConfig(FabricCoopPlatform.java:75)
      at com.ghostrick.cobblecoop.config.CobbleCoopServerConfig.register(CobbleCoopServerConfig.java:79)
      at com.ghostrick.cobblecoop.CobbleCoop.initialize(CobbleCoop.java:24)
  ```
- 调用点正是 `NeoForgeConfigRegistryImpl` —— `PortingLayerAbiInjector.CONFIG_REGISTRIES` 在 `36e93f82`
  里按名字盯上的两个类之一(2 个调用点)。该行是在 **ea196237** 上量的,而 `36e93f82` 在它**之后**落地
  (NIGHT_SHIFT:FCAP 桥 2026-10-04 ~07:47;recheck-walls 06:51 冻结内核)。这与 FCAP 报告 §5.4 的"cobblecoop
  走 NeoForge 那半、闭包要 Cobblemon+architectury+rctapi"逐字对上。
- 判决:**内核侧,已被 `36e93f82` 修好;只差重测**(且重测必须在 JDK 21 上,否则会读到 §1 的 Truffle 假象)。

### 2.6 `cobblemon_pufferfish_api_fabric-0.9.1-beta.jar` — **主体侧**
- 行内(世界已到):`NoClassDefFoundError: net/puffish/skillsmod/api/reward/Reward`。
- **缺的类属于谁**:`net.puffish.skillsmod` = **Puffish's Skills**(另一个 mod),不是本主体、不是内核。
- **声明的闭包该不该带它**:不该。主体 `fabric.mod.json` 的 `depends` 只有
  `fabricloader / minecraft=1.21.1 / fabric`(`evidence/pufferfish-undeclared-dependency.txt`),
  没有 puffish;而它的 entrypoint `CobblemonSkillsBridgeFabric` 第一句就
  `invokestatic net/puffish/skillsmod/api/SkillsAPI.registerExperienceSource(...)`(逐字,javap)。
  ⇒ **未声明的硬依赖**。按判据(缺类属于谁、声明的闭包本应否携带):缺类属于**第三方 mod** Puffish's Skills,
  主体的 `depends` 从未声明它,所以**声明的闭包本就不该携带** ⇒ 主体侧,不是内核、也不是"闭包漏解析"。

### 2.7 `gardnercraft-2.0.0.jar` — **依赖侧(版本不匹配)**
- 行内:`[Forbric/Fabric] main entrypoint of gardnercraft failed` →
  `java.lang.NoSuchMethodError: 'net.minecraft.sounds.SoundEvent eu.pb4.polymer.core.api.other.PolymerSoundEvent.registerOverlay(net.minecraft.sounds.SoundEvent, net.minecraft.core.Holder, java.util.UUID)'`,
  栈 `com.gardnercraft.ModSounds.registerReference(ModSounds.java:38)`;随后 registry-load(trim_pattern 找不到
  它自己本该注册的 item)。
- 此后 `NoSuchMethodError` 并不是内核重映射错:把闭包实际带的 `polymer-core-0.9.19` 交给 `javap`,
  `PolymerSoundEvent` **根本没有 `registerOverlay`**(`evidence/gardnercraft-polymer-mismatch.txt`)。
- 而主体自己的元数据要求 `"polymer-core": ">=0.15.2+1.21.11"`、`"packet_tweaker": ">=0.6.0"`、
  `"minecraft": "~1.21"`(逐字,同文件)。闭包解析出的却是 `polymer-bundled-0.9.19+1.21.1` ——
  在 1.21.1 上**无法满足**该声明。⇒ 依赖解析/版本不匹配,判 **依赖侧**,非内核缺陷。

### 2.8 `beilin-data-portability-fabric-1.21.x-1.2.7.jar` — **harness/环境 假象**
- 主体自身行:`run=PASS exit=0 stopped=true world=true mod=OK`;console 里主体真的跑起来了
  (`Beilin Data Portability opened cuboid region index ./world/./beilin-data-portability/index.db`)。
- 之所以 strict=false:唯一原因 `dep_status: beilin-entry-control=FAILED`,而它 FAILED 的原因逐字:
  ```
  java.lang.IllegalStateException: Beilin Entry Control requires an apiKey. Please configure
      …/config/beilin-entry-control.json
      at us.beiyue.beilinentrycontrol.platform.fabric121x.BeilinEntryControl121x.onInitialize(BeilinEntryControl121x.java:42)
  ```
- 依赖需要一个操作者提供的密钥,自动 sweep 无法提供;`strict` 谓词把 `bad_deps` 算进去(`sweep.py`
  `loaded = … and not bad_deps`),于是整行被判非 strict。⇒ **harness/环境假象**,内核零字节在路径上。

## 3. 唯一的新内核修复:`net.fabricmc.loader.launch.common` 门面

提交 `7d21924f`(branch `main`)。改动:

| 文件 | 改动 |
|---|---|
| `src/main/java/net/fabricmc/loader/launch/common/FabricLauncher.java` | 新增。旧包门面接口,**只声明观察到的那一个成员** `void propose(URL)`(与同仓 `impl` 包兄弟同一条"按需生长"约定) |
| `src/main/java/net/fabricmc/loader/launch/common/FabricLauncherBase.java` | 新增。`static getLauncher()/setLauncher(...)`,与 `impl` 包兄弟同形 |
| `src/main/java/net/forbric/kernel/fabric/KernelFabricLauncher.java` | 同一个 launcher 视图同时实现两个接口;`install(…)` 把实例同时装进两个 base;新增 `propose(URL)` → 转 `Path` 后委托 `addToClassPath`(族记录与日志一并复用) |
| `src/main/java/net/forbric/kernel/classloading/FabricLoaderInternals.java` | 两个门面类进 `ALWAYS`:**钉到父层、开关不收回**。理由:内核启动时把视图填进这个静态,mod 若自带一份 child-first 会拿到第二个、空的静态,`getLauncher()` 就会对内核刚装好的调用回 null |
| `src/test/java/net/fabricmc/loader/launch/common/LegacyFabricLauncherFacadeTest.java` | 新增,两条(见下) |

**真字节红→绿**(干净 worktree `/tmp/w7-gap-wt` = `f4123526` + 仅拷测试):

- **红**(仅测试、无修复):`2 tests, 1 failed, 1 skipped`,失败逐字
  `java.lang.ClassNotFoundException: net.fabricmc.loader.launch.common.FabricLauncherBase`
  (`LegacyFabricLauncherFacadeTest.java:75`)。
- **绿**(修复 + 真 jar 夹具 `run/client-kernel/mods/LuckPerms-Fabric-5.4.140.jar`):`2 tests, 0 failed, 0 skipped`。
  第二条测试对**真 LuckPerms jar** 逐类扫描 ASM,断言它从旧包只命名
  `FabricLauncherBase.getLauncher()Lnet/fabricmc/loader/launch/common/FabricLauncher;` 与
  `FabricLauncher.propose(Ljava/net/URL;)V` 两个成员,且 shim 以逐字相同描述符声明它们(jar 一旦改形状即失败)。
- **全量**:`2930 tests, 982 skipped, 30 failed`;与**未改动基线**(`/tmp/w7-gap-base` @ `f4123526`,
  `2928 tests, 982 skipped, 30 failed`)用 XML 解析器逐条对比,**失败集合逐条相同** —— 这 30 条是
  夹具缺席的既有失败,与本改动无关。中途一次误读:第一次 pinning 前 `everyCompiledInternalIsPinned` 会红,
  已按 §3 第 4 行修好(这正是该 guard 存在的意义)。

**构建**:`w7/harness/build-kernel.sh /tmp/w7-gap-build/forbric-kernel /tmp/w7-gap-kernel.jar`(干净 worktree @ `7d21924f`,
`-Pforbric.stagedRoot/mcLibraries/fabricApi/rebornEnergy` 与 FCAP 报告同),冻结 jar
sha256 **`e61560cfe979a29233f2982b1842d255bd8cbd23ef35bfc0b2fddbe7818bf279`**,内含
`net/fabricmc/loader/launch/common/{FabricLauncher,FabricLauncherBase}.class`(逐字校验)。

**已知未覆盖面(写明,不假装)**:第二个真实消费者是 Sinytra Connector(它也参考旧包,还参考
`launch/common/MappingConfiguration`),但它是 hybrid loader、表面远超一个 `propose`,**不在本修复范围**。
门面也只声明 `propose`;若将来某 mod 从旧包问别的成员,会按名链接失败(与 `impl` 包约定一致),而不是静默假通过。

## 4. 对照启动台(Cobblemon 形状),同一批 jar、同一 JDK

方法按既定纪律:同一批 jar 在真 Fabric 上启得来、在 Forbric 上启不来 ⇒ 内核侧。用 `#2.3` 的闭包照做
(rig = `/tmp/w7-fabric-ref` 的副本 + 那 5 个 jar,`fabric-loader 0.19.5`,**JDK 21**):

```
[08:24:37] [main/INFO]: Registered the cobblemon:molang registry
…                                                          (43 条 Registered the cobblemon:*)
[08:24:38] [main/INFO]: ║ Environment   :: Java: 21.0.7 | Mem: 537MB/2048MB | Time: 08:24:38
[08:24:39] [Worker-Main-3/INFO]: Registering commands for cobblespawnregions
[08:24:45] [Server thread/INFO]: Done (0.970s)! For help, type "help"
```

`grep -c 'Registered the cobblemon:'` = **43**;`grep -c 'Failed to parse\|Unknown registry key'` = **0**。
全日志:`ref-cobblespawnregions-jdk21.txt`(同目录),参数 `ref-rig-server.properties`。

对照:同一 Cobblemon 入口点,Forbric `@16a95295` 上是 0/43 且 91 个 `Failed to parse cobblemon:`(§2.3 的
`cobblemon:height_range`);Forbric `@ea196237` 上已注册成功但启动窗口内 remap 超时(§2.3 的 boot-stall)。
⇒ 这一簇是**内核侧**,由 `1023cbc8`+`c2b9d90a` 关闭;残余只是"用对 JDK、给足预算"的重测。

## 5. 交给 `W7Harness` 的证 boot(预先登记读数,已发 IRC)

冻结字节:`/tmp/w7-gap-kernel.jar` sha256 `e61560cf…`,提交 `7d21924f`。环境:**JDK 21**
(`W7_JAVA=…/jdk-21.jdk/…/bin/java`),冷/空 remap 缓存。

| slug | subject | 预先登记的可接受读数 |
|---|---|---|
| `shadowguard` | ShadowGuard-0.0.1.jar | (A) console 全文无 `NoClassDefFoundError: net/fabricmc/loader/launch/common/FabricLauncherBase`;`world=true`(`Done (`+`world/level.dat`)、`mod=OK`、`dep_status[LuckPerms]=OK`、`strict=true`;(B) 否则点名下一条确切故障 |
| `cobblespawnregions` | cobblespawnregions-1.0.8.jar | 无 `Unknown registry key … structure_processor]`;`world=true`;`--boot-timeout 2400` |
| `cobblemon-auto-battle` | Cobblemon-Auto-Battle-0.1.4.jar | 同上(再叠 JDK 21 以越过 Truffle) |
| `cobblemon-coop` | cobblecoop-1.5.1-fabric.jar | 无 `NoSuchMethodError …ConfigTracker.registerConfig`,无 registry-load;`world=true` |

我自己也已用 `harness/sweep.py --only ShadowGuard-0.0.1.jar --port-base 27100`(JDK 21、新缓存、
`--boot-timeout 1200`)在同一冻结 jar 上启动一次该证 boot。读数见 §5.1。

### 5.1 我这一次的读数 —— **A(修复成立)**

命令(逐字,独立端口 27100 以避开并行跑的 W7Harness):

```bash
cd w7 && W7_JAVA=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin/java \
  python3 harness/sweep.py --corpus corpus --out reports/2026-10-04-fabric-gap-shadowguard \
    --stage ../p0/stage-1.21.1 --mc ../p0/mc-1.21.1 \
    --kernel ../Minecraft-Forbric-mod-loader/forbric-kernel --kernel-jar /tmp/w7-gap-kernel.jar \
    --jvm=-Dforbric.compatibilityPolicy=continue --boot-timeout 1200 --boot-stall 600 \
    --stop-timeout 60 --remap-cache /tmp/w7-gap-cache --port-base 27100 --only ShadowGuard-0.0.1.jar
```

冻结 jar sha256 逐字等于 §3 登记的 `e61560cf…`。`per-mod/results.jsonl` 该行(逐字字段,全文见
`boot-shadowguard-reading.txt`):

```
subject=ShadowGuard-0.0.1.jar  run=PASS  exit=0  stopped=true  world=true  mod=OK
status_source=console:constructed-or-entrypoint
dep_status: LuckPerms-Fabric-5.4.140.jar=OK, fabric-api-0.116.17+1.21.1.jar=OK, fabric-language-kotlin-1.14.1+kotlin.2.4.20.jar=OK
confirmed_required=0  findings_required=14  strict=true  na=false  seconds=246  cause=null
```

console 逐字:`Done (8.360s)! For help, type "help"`,`luckperms-worker` 线程出现 9 次(LuckPerms 的工作线程
确实跑起来了),全 console `grep -c NoClassDefFoundError` = **0**、`grep -c 'launch/common'` = **0** ——
修前那条 `NoClassDefFoundError: net/fabricmc/loader/launch/common/FabricLauncherBase` 已消失。
`dep_status` 里 LuckPerms 由 FAILED 转 OK、fabric-language-kotlin 由 ABSENT 转 OK,`strict` 由 false 转 true。

(注:这一次是我为了 red→green 自己跑的;§5 表里另外三个 slug 的重测仍归 `W7Harness`。)

## 6. 最终表要把这些分开

- **fabric 内核侧(现表可见)**:`shadowguard`(本修复)、`cobblespawnregions`、`cobblemon-auto-battle`、
  `cobblecoop`(后三者待重测;`no-smithing-template-refabriced` 已绿)。
- **主体侧**:`cobblemon_pufferfish_api`(未声明 Puffish)。
- **依赖侧**:`gardnercraft`(声明的 polymer 版本在 1.21.1 上无法满足)。
- **harness/环境**:`beilin-data-portability`(依赖要 apiKey);外加横切的 **JDK** 读数环境(§1)。
- 一个不改变样本、但要写进方法的量:**本样本的 8 行里有 3 行(Cobblemon 闭包的 `cobblespawnregions`、
  `Cobblemon-Auto-Battle`、`cobblecoop`)的权威行来自已有缺陷的读数环境**(JDK 25 的 Truffle 假象 + 550 s 预算);
  另有 1 行(`ShadowGuard`)虽在同一环境量得,但其故障(noclassdef)与 JDK 无关,已在 §5.1 用 JDK 21 复核为绿。
  把视野放到全部 loader:§1.1 的扫描显示 4 行被"带 artefact 却计失败"(fabric 3 + neoforge 1),所以"8 个非 strict"
  里真正指向 fabric 内核的只有 4 个,且其中 3 个已被更早的提交修好。
- **一步重述**:权威命令行与最终数字见 §9.3(extras 不含 `recheck-walls`,它已撤回)。

## 7. 账(成本与未证,不藏)

- 新修复的成本:两个门面类 + 一次 `install` 双发布 + 一个 guard 名单;无运行时分配(`propose` 只在被调用时
  转一次 URL→Path)。**未证**面:只声明 `propose`;Connector 的旧包表面未覆盖(§3 末)。
- 三个"已被中间提交修好"的行是**归因**,不是重跑:它们的旧行在 `16a95295`/`53c23314`/`ea196237`,
  修复提交在这之后;重测由 §5 覆盖,未回前**不引用为绿**。
- JDK 假象的账:本次排障期间至少一轮全量 fabric 判读(4 行)在 JDK 25 上生出,须以 JDK 21 重跑;
  `W7Harness` 已在改。
- 复现命令:
  ```bash
  # 单主体证 boot(固定 jar、JDK 21、独立端口)
  cd w7 && W7_JAVA=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin/java \
    python3 harness/sweep.py --corpus corpus --out reports/<stamp> \
      --stage ../p0/stage-1.21.1 --mc ../p0/mc-1.21.1 \
      --kernel ../Minecraft-Forbric-mod-loader/forbric-kernel --kernel-jar /tmp/w7-gap-kernel.jar \
      --jvm=-Dforbric.compatibilityPolicy=continue --boot-timeout 1200 --boot-stall 600 \
      --stop-timeout 60 --remap-cache /tmp/<fresh> --port-base 27100 --only ShadowGuard-0.0.1.jar
  # 对照启动台(Cobblemon 形状)
  cd /tmp/w7-ref-gap && /Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin/java -jar fabric-server-launch.jar nogui
  ```

## 9. 后续裁定(2026-10-04 下午):ShadowGuard 两 rig 分歧、三条残余、以及 gardnercraft 的分母

### 9.1 `ShadowGuard`:`strict=true` 站得住,错的是 rig 的状态谓词
两个 rig 报出不同 `dep_status`(我的 boot `LuckPerms=OK, FLK=OK`;`gap-jdk21` 的 `LuckPerms=OK, FLK=ABSENT`
→ `dependency-not-ok`)。**closure 是同一个**:

- `closure.json['ShadowGuard-0.0.1.jar']` = `[LuckPerms-Fabric-5.4.140.jar, fabric-api-0.116.17+1.21.1.jar,
  fabric-language-kotlin-1.14.1+kotlin.2.4.20.jar]`;两次 run 的 `mods/` 目录 `diff` 为空(同一个 jar 集)。
- 差的是**状态谓词的输入**:专用服务端路径的 catalogue `mods[]` 为空,`dep_status` 落到 `console_status`。
  `fabric-language-kotlin` 是纯库(`fabric.mod.json` 只有 `languageAdapters`,**无 `entrypoints`、无 mixins,
  jar 内 0 个 `*.mixins.json`**),console 里唯一会点它名的行就是 remap 行
  `[Forbric/Mapping] remapped fabric-language-kotlin-…jar`。
  - 我的 boot 是**空缓存**,启动窗口内真的 remap 了 FLK → 该行出现 → `console:declared-only` → OK。
  - `gap-jdk21` 是**暖缓存**(`/tmp/w7-cache-gap`),FLK 已缓存 → 无名可循 → 无 OK 模式 → `ABSENT` →
    `bad_deps` → `dependency-not-ok`。
- 同 jar、同内核、同 JDK,只差缓存冷暖 ⇒ **该用的数是 `strict=true`;要修的是 corpus rig 的状态谓词**。
  `W7Harness` 已在 `sweep.py` 里给"无入口点、无 mixin 的依赖"补上和主体同一条库豁免(工作树里带着这条
  测量的注释),并在 `reports/2026-10-04-deprule-jdk21` 复验。

### 9.2 三条残余的归属
- **`nosuchmethod ×1` = `NoSmithingTemplateReFabriced` —— 不是活故障,是陈行。** 池里的行是
  `2026-10-03-full-corpus@16a95295`;它的 FCAP 证 boot(内核 `e74b0274…`,`PASS/world=true/strict=true/cr=0/283 s`)
  跑在 `/tmp/fcap-boot-36e93f82-v3`,不在 `w7/reports` 下,所以表看不见、一直引用旧行。现把它
  **落成 `w7/reports/2026-10-04-fcap-config-proof`**(指向外置卷的软链,run 逐字节拷入;
  sha 见 `frozen-kernel-sha256.txt`);作为 extra 折进去即翻,不需要再 boot。判决:**内核侧,已修(`36e93f82`),
  证 boot 已有**。
- **`registry-load ×1` = `gardnercraft` —— 按本 campaign 自己的规则,应在 closure-complete 分母之外;判决依赖侧。**
  它声明 `polymer-core >=0.15.2+1.21.11`、`polymer-resource-pack >=0.15.2+1.21.11`、`packet_tweaker >=0.6.0`;
  实际闭包只有 `[fabric-api, polymer-bundled-0.9.19+1.21.1]`。`javap` 0.9.19 的 `PolymerSoundEvent`
  **没有 `registerOverlay`**,版本约束被违反;而 `packet_tweaker` 在语料里**没有任何 provider**
  (`grep -c` 在 manifest/graph/closure/closure-missing 上各为 0)。⇒ 声明的闭包在 1.21.1 上没解析齐。
  两个 rig 缺口随之暴露:`bucket_table.closure_complete()` 只看解析器**产出**的依赖(声明了但没找到的不可见),
  且 `closure-missing.json` 里没有 gardnercraft 这一行。把谓词改成看**声明的**集合(材料 `closure-missing.json`
  已有),gardnercraft 即离开分母。
- **`noclassdef ×1` = `cobblemon_pufferfish_api` —— 主体侧。** entrypoint 调
  `net/puffish/skillsmod/api/SkillsAPI.registerExperienceSource(...)`,而自身 `depends` 只有
  `fabricloader/minecraft/fabric`。未声明的硬依赖;其声明闭包是解析齐的,所以留在分母内,就是一个装不起来的
  主体,不是内核。

### 9.3 用最新行重述(权威命令行,与 `W7Harness` 的一致)
```
python3 harness/bucket_table.py --extra 2026-10-03-recheck-frozen,2026-10-03-cobblemon-ref,\
    2026-10-03-gunpowder-dump,2026-10-04-gap-jdk21,2026-10-04-veinminer-jdk21,\
    2026-10-04-deprule-jdk21,2026-10-04-deprule2-jdk21,2026-10-04-fcap-config-proof
⇒ fabric: sample 50 / na 14 / appl 36 / world 35 / strict 33 / raw 92%
          closure-complete 34 / closure-strict 33 / 97%
   非 strict 剩 3:gardnercraft(registry-load)、cobblemon_pufferfish_api(noclassdef)、beilin(dependency-not-ok)
   >=95% closure-complete: MEETS (97%) raw 92%
⇒ neoforge 49/49 raw 100% / 49/49 closure 100%;forge 50/48 raw 96% / 50/48 closure 96%
```
**`2026-10-04-recheck-walls` 已撤回,不得出现在命令行里**:它的每一行都是 JDK 25 量的(§1),其 `boot-stall`
是仪器不是主体;上面这条命令与我先前那条(含 `recheck-walls` + `fabric-gap-shadowguard`)产出**逐字相同**
的数字——但命令行是可复现性契约,一个已撤回的目录留在里面,读者明天可能复现出一张不同的表(override 顺序一变
就会)。`deprule-jdk21`/`deprule2-jdk21` 是 `W7Harness` 复核 dep-status 规则与 `CheaperGapples`/`economymod`
的报告,已用它取代我自己的 `fabric-gap-shadowguard` 行(两者对该主体读 `strict=true`,一致)。

`gardnercraft` 按 §9.4 的规则移出分母 ⇒ **33/34 = 97%,越过 bar**。
把 fabric 从 §0 的 28 推到 33 的三步(`ShadowGuard` 的 rig 谓词、`no-smithing` 的证 boot 行落盘、
Cobblemon 闭包的四行 JDK 21 重测)都不含新内核代码。

### 9.4 最终规则:声明的依赖 **被触达** 才能移出分母(declared-and-reached)

先落地的"只要声明没被满足就移出"被自己的反例推翻了:`mealmastery`、`ghoulcraft`、`miguelfaction`、
`CloserPillagerOutpost` 四个主体**都**声明了语料里没有 provider 的依赖,却都 `world=true / strict=true`。
⇒ 声明不等于 boot,不能单独动分母。

可用的规则因此有两轴,实现是 `harness/declared_reached.py`(唯一实现;输出 `corpus/closure-reached.json`,
`bucket_table.closure_complete()` 读它):

1. **声明未满足**:主体自己 `depends`/`[[dependencies]]` 里的某个 id,要么闭包里没有任何 provider,
   要么所有 provider 的版本都不满足声明的范围。版本从**每个 jar 自己的元数据**读(递归进嵌套 JiJ,不看文件名);
   范围求值器支持 `>= > <= < = ~ ^ *`;不认识的运算符一律按**满足**处理(解析缺口只能让主体留在分母里,
   不可能移出——抗通胀方向)。
2. **该失败被触达**:主体自己的 console 里出现 linkage 错误(`NoClassDefFoundError`/`NoSuchMethodError`/
   `NoSuchFieldError`/`AbstractMethodError`/`IncompatibleClassChangeError`),且它点名的类由闭包里**某个版本违反
   声明范围的 jar** 承载(类→jar 是真查表,含嵌套)。

**移动(两个方向,逐 loader;extras 同 §9.3 + 本规则的记录):**

| loader | 旧分母 | 旧 strict | 旧 % | 新分母 | 新 strict | 新 % | 移出 | 移入 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| fabric | 35 | 33 | 94.3% | 34 | 33 | **97.1%** | 1 | 0 |
| neoforge | 49 | 49 | 100% | 49 | 49 | 100% | 0 | 0 |
| forge | 50 | 48 | 96.0% | 50 | 48 | 96.0% | 0 | 0 |

唯一移出者及其记录原文:
```
gardnercraft-2.0.0.jar
  polymer-core: NoSuchMethodError eu.pb4.polymer.core.api.other.PolymerSoundEvent
                (shipped 0.9.19+1.21.1, declared >=0.15.2+1.21.11)
```
四个反例**一个都没动**(它们的 console 里没有任何 linkage 错误,第二轴不触发);`cobblemon_pufferfish_api`
也留在分母内(它失败点名的 `net.puffish.skillsmod...` 不由闭包里任何 jar 承载,归不到任何**声明**的 id 上)。
规则按构造只减不增,而它之所以不是"图省事"的版本,是因为**每一次移出都要求主体的 boot 真的死在那个未满足的声明上**,
这一点可被四条命令证伪——这也是它值得进最终表的原因。

同时收紧了 `bucket_table.closure_complete()` 的第二处缺口:它原来只看解析器**产出**的依赖,声明了但解析不到的
不可见(这正是 `closure-missing.json` 里没有 gardnercraft 的原因);现在它读上面那条记录。

## 10. 交付状态

- [x] 八个逐个判决,零 "unknown"。
- [x] 内核侧:1 个新修复落地(`7d21924f`,真字节红→绿 + 全量不回归);3 个归因到中间提交,重测已 hand off。
- [x] 报告 + pointer(`w7/NIGHT_SHIFT.md`)。
- [x] 树干净(内核仓只提交了本修复的 5 个路径;`* 2.java` 等他人未跟踪文件未动)。
- [x] §5.1 我这一次的 ShadowGuard boot 读数:**A(修复成立)** —— PASS/world=true/strict=true/cr=0/246 s,
      `NoClassDefFoundError` 与 `launch/common` 在 console 里各 0 次。
- [x] 三个 Cobblemon 重测:`W7Harness` 在 JDK 21 上全部转绿(`cobblecoop` 836 s、`cobblespawnregions` 113 s、
      `Cobblemon-Auto-Battle` 53 s,`reports/2026-10-04-gap-jdk21`),`veinminer-enchant` 亦然
      (`2026-10-04-veinminer-jdk21`)。
- [x] 证 boot 行落盘:`2026-10-04-fcap-config-proof`(原来写在 `w7/reports` 之外的证据对表不可见——过程
      教训一行:`run` 输出按构造就该落在 reports 根下,`harness/reports.py` 现已强制)。
- [x] 最终规则(§9.4)落地:`harness/declared_reached.py` + `corpus/closure-reached.json`,移动
      fabric 1 出 0 入、neoforge/forge 0/0;fabric **33/34 = 97%**。
