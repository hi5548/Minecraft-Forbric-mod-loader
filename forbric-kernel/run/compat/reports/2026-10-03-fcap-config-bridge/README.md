# FCAP 的 `ConfigTracker`:21.1.1 线的四个重载里两条一行改写、两条要造一个真 Forge `ModConfig`

范围:延后条目 K4(`forbric-kernel/run/compat/reports/2026-10-03-bucket-residue/README.md` §1.2),
主体 `no-smithing-template-refabriced`(fabric),存盘 cause `nosuchmethod
ConfigTracker.registerConfig(Type,IConfigSpec,String)`。

载体:`p0/stage-1.21.1/neoforge-runtime/neoforge-runtime.jar`(**NeoForge 21.1.252**,`META-INF/neoforge.mods.toml` 逐字)、
`p0/stage-1.21.1/merged-base/forge-runtime-interop.jar`、`p0/stage-1.21.1/merged-base/patched-mc-merged-1.21.1.jar`。
FCAP:`ForgeConfigAPIPort-v21.1.6-1.21.1-Fabric.jar`,sha256
`2e3a8f0e3bda85a7d722720e7ce879cbd9d028d9395c6fc224d329b3c982d9b1`。
主体 jar:sha256 `e78860bc16118ac0d910b8d352086d0d7ef877b4ebe9f8ff3216a265412b8df5`。

## 1. 形状(全部 javap 真字节,不引源码)

FCAP 自带一整套 `net.neoforged.fml.config.*`(它的 21.1.6 里是 26.2 形状的 NeoForge 副本),
其中两个类与载体**同名**、但形状不同 —— 而 `net.neoforged.` 是 `ALWAYS_GAME`(`DelegationPolicy`),
所以载体的那份赢,FCAP 的副本永不加载:

| | 载体(实跑的那份) | FCAP 自带的副本 |
|---|---|---|
| `ConfigTracker.registerConfig` | `(Type, IConfigSpec, ModContainer)`、`(…, ModContainer, String)` | `(Type, IConfigSpec, String)`、`(…, String, String)` |
| `net.neoforged.fml.config.ModConfig` | `final` 类,没有 Forge 半边 | `public final net.minecraftforge.fml.config.ModConfig modConfig;` 一个字段 |

载体侧还逐字确认了 `registerConfig` 的**内部**动作(这才是"必须修"的原因):

```
public ModConfig registerConfig(Type, IConfigSpec, ModContainer, String);
   … new ModConfig(Type, IConfigSpec, ModContainer, String, ReentrantLock)
   … invokeinterface IConfigSpec.validateSpec(ModConfig)     ← 无条件调用
   … trackConfig(this)
```

**调用点改名了。** 26.2 线把 mod-id-keyed 注册收在一个 `ConfigRegistryImpl` 里;21.1.6 线拆成两个类,
两个名字都不是 `ConfigRegistryImpl` —— 于是"只认一个 26.2 类名"的 shim 在这一线上**一次都没触发**:

| 类(21.1.6) | `register` 重载 | 调用点形状 | 其后 |
|---|---:|---|---|
| `fuzs…impl.core.NeoForgeConfigRegistryImpl` | 2 | `getstatic ConfigTracker.INSTANCE` + `invokevirtual registerConfig(Type,IConfigSpec,String)NeoModConfig` | `areturn`(直接返回 Neo 那份) |
| `fuzs…impl.core.ForgeConfigRegistryImpl` | 4 | 同上(2 处 3 参、2 处 4 参) | 2 处 `pop`;2 处 `getfield net/neoforged/fml/config/ModConfig.modConfig:Lnet/minecraftforge/fml/config/ModConfig;` |

`ForgeConfigRegistryImpl` 的四个重载逐字:

1. `register(String, ForgeType, ForgeSpec) → Forge ModConfig` —— 3 参调用 + `getfield modConfig`
2. `register(String, NeoType, ForgeSpec) → void` —— 3 参调用 + `pop`
3. `register(String, ForgeType, ForgeSpec, String) → Forge ModConfig` —— 4 参调用 + `getfield modConfig`
4. `register(String, NeoType, ForgeSpec, String) → void` —— 4 参调用 + `pop`

**主体走的是哪一条**:主体字节码是
`invokeinterface ForgeConfigRegistry.register:(Ljava/lang/String;Lnet/neoforged/fml/config/ModConfig$Type;Lnet/minecraftforge/fml/config/IConfigSpec;)V`
⇒ 第 2 条;**栈帧 `ForgeConfigRegistryImpl.register(…:24)` + 该方法的 LineNumberTable(`line 24: 0`,偏移 13 就是那句
`invokevirtual registerConfig`)逐字印证**——即四条里的**两个 `void` 重载之一**。

## 2. 哪两条能一行改写,另两条要什么

- **两个 `void` 重载(第 2、4 条):一行改写。** 它们只是"注册然后 `pop` 掉返回值",而 bridge 的
  `registerConfig(ConfigTracker, Type, IConfigSpec, String[, String])` 返回的正是**载体自己的** `ModConfig`
  ——描述符逐字相等。`invokevirtual` → `invokestatic`,接收者原地成为第 0 个实参:指令数、`maxStack`、
  `maxLocals`、局部变量表都不动。
- **`NeoForgeConfigRegistryImpl` 的两条:同样一行改写。** 它们返回 Neo `ModConfig`、后面直接 `areturn`,
  栈形状与 bridge 的返回值逐字相等,没有别的读。
- **两个返回 Forge `ModConfig` 的重载(第 1、3 条):需要"造一个真 Forge `ModConfig`"。** 它们在调用之后
  **无条件**读 `net/neoforged/fml/config/ModConfig.modConfig`——载体那个类是单类、没有 Forge 半边,
  所以只把方法桥掉只是把失败往右挪一条指令(`NoSuchMethodError` → `NoSuchFieldError`)。
  这个字段的值在 FCAP 自己的副本里由它自己的 `ConfigTracker` 填;载体没有。

`getfield` → 桥的 `forgeHandle(NeoModConfig) → Forge ModConfig` 是**另一个一行改写**:都是弹 1 推 1,
指令数/`maxStack` 不变(字段读换成静态调用,节点必须换成 `MethodInsnNode`,不是改 opcode)。

## 3. 桥

- `PortingLayerAbiInjector.CONFIG_REGISTRIES`:三个类名 + 各自的调用点数(`ConfigRegistryImpl`=4、
  `NeoForgeConfigRegistryImpl`=2、`ForgeConfigRegistryImpl`=4)。数不对 ⇒ **整类拒改**(与原来"第五个调用点就
  全部站下"同一判据);`modConfig` 读只要有一处不紧跟调用点 ⇒ 同样拒改。
- `KernelConfigPortBridge.forgeHandle`:用**载体的** `net.minecraftforge.fml.config.ModConfig` 公开构造器
  `(ForgeType, ForgeSpec, ModContainer[, String])` 造,Forge 容器来自 `KernelForgeContainers.create(modId)`
  (与 Neo 侧 `containerFor` 同形的"只作身份"的容器),Forge spec 从 FCAP 适配器的 `spec()` 反射取出
  (适配器是 record,分量就是那个 spec);取不到就**响亮失败**,不给半个对象。
  一个 Neo config 缓存一个 handle,一个 modId 缓存一个容器。

### 代价(记账,不藏)

载体 Forge `ModConfig` 的构造器**自己**会 `ConfigTracker.INSTANCE.trackConfig(this)`(javap 逐字),
所以这个"视角对象"同时进了 MinecraftForge 自己的 tracker:内核的 Forge 配置遍历
(`KernelForgeConfigLoad.openLate`,`COMMON`)会**再开一次同一个文件**——一个配置文件两份 reader、两个
file watcher,盘上一次编辑两边都触发。两边读的是同一个文件、值不可能不一致;这是"一份配置真的同时是两家的"
的价钱(FCAP 自己注册进 **NeoForge** 的 tracker,也就是真正承载 mod 读到的值的那份)。
**没有**采用"绕过构造器分配"的做法:那会让 `getHandler()` 为 null、`getConfigData()` 永远为 null,
换来一个会撒谎的 handle,而代价只是多一次读。

## 4. 测试(真字节,红→绿)

`PortingLayerAbiInjectorTest` 扩到两条线,并新增 21.1.6 的四个用例;夹具是**真 jar**
(`run/client-kernel/mods/ForgeConfigAPIPort-v21.1.6-1.21.1-Fabric.jar`,与仓库里 26.2 夹具同一约定,
未入仓,缺席则 skip):

- `everyModIdKeyedRegistrationOfThe2116PortGoesThroughTheBridge`(2+4 个调用点全部改道,且不得残留
  `ConfigTracker.registerConfig`),
- `theForgeFlavouredOverloadsHandBackARealMinecraftForgeConfig`(不得残留 `modConfig` 读;
  恰好 2 处 `forgeHandle` 且描述符逐字 `(Lnet/neoforged/fml/config/ModConfig;)Lnet/minecraftforge/fml/config/ModConfig;`),
- `nothingAboutTheFramesOrTheStackMovedInThe2116Port`(逐个方法:指令数/`maxStack`/`maxLocals` 不动),
- `a2116PortClassWhoseCallSiteCountMovedIsRefusedWhole` 与
  `aForgeHalfReadThatCameLooseFromItsCallIsRefusedWhole`(两条拒改判据)。

**红(修前,`0f590f1f` 的内核源码 + 新测试)**:`13 tests, 6 skipped, 2 failed` —— 恰是上面第一、二条。
**绿(修后)**:`13 tests, 6 skipped, 0 failed`。

全量:`./gradlew --offline test` → **2928 tests, 2 failed, 661 skipped**;这 2 条
(`ForeignTypeTest.noConceptIsStillWrittenOutUnderBothFamiliesOutsideThisEnum`、
`KernelRuntimeClassesTest.everyGameSideClassTheBootSideNamesIsInTheRegistry`)在**未改动的基线**上同样失败
(逐条 git stash 前后复跑,同 2 条),与本改动无关。

构建/跑测的真参数(新工作树必须给全):

```
-Pforbric.stagedRoot=$B/p0/run -Pforbric.mcLibraries=$B/p0/mc-1.21.1/libraries \
-Pforbric.fabricApi=$B/p0/fixtures/fabric-api-0.116.17+1.21.1-named.jar \
-Pforbric.rebornEnergy=$B/p0/fixtures/energy-4.1.0-named.jar
```

## 5. 启动验证(W7Harness)

### 5.1 预先登记(跑机之前写下)

输入(固定):内核提交 `0f590f1f` + 本报告的改动,内核 jar sha256
`e74b02748c49ad0f9864eccd44124df61edb9aff00a409d707ba4c7bc4c158f2`;
主体 `no-smithing-template-refabriced` 单独跑(依赖闭包 = FCAP 21.1.6 + fabric-api 0.116.17),
冷 remap 缓存,`--boot-timeout 600`。只有两种可接受读数:

- **A(修复成立)**:console 不再出现 `NoSuchMethodError … ConfigTracker.registerConfig(…)`;
  该行 `world=true`(出现 `Done (`,`world/level.dat` 落盘)且 `cr=0`(compatibility-report.json 里
  该主体没有 `required && CONFIRMED` 的 finding),`mod=OK`;
- **B(仍有故障)**:上面任一条不成立 ⇒ 必须**点名下一条确切的故障**(逐字异常与栈帧)。

第三种读数不接受:没有 `world=true` 的 `cr=0` 不是证据(纪律原文)。

### 5.2 读数

PENDING —— 本节在 W7Harness 给出启动日志后填写。
