# 四份 campaign 报告的"非冻结失败"分类:逐条真字节判读、内核侧三条落地、一条显式延后

范围:`w7/reports/2026-10-03-{full-corpus,bucket-fabric,bucket-neoforge,bucket-forge}`(`per-mod/results.jsonl`)。
口径:`strict=false` 且 `na=false` 的 **20 行**——`na=true` 的 17 行是服务端上的客户端 mod,不计。
载体内核:`16a95295…`(full-corpus/forge/fabric 冻结 jar 同名)/ 各 bucket 自己的 `frozen-kernel-sha256.txt`。

## 0. 20 行里已归入"冻结的 12 条 id"的 8 个主体(不计入本轮 residue)

`harness/confirmed_ids.py` 在 166 行上给出 12 条 CONFIRMED-required id、8 个主体。这 8 个主体的
非 strict **完全由已登记的冻结 id 解释**,本报告只列名、不重判:

| 主体 | loader | 承载的冻结 id | 该行 cause |
|---|---|---|---|
| shadowguard | fabric | 7、8、12(FireBlockMixin) | noclassdef |
| open-parties-and-claims | forge | 6(ExperienceOrb#onScanForEntities) | noclassdef |
| cobblemon-coop | fabric | 1(architectury#onBreak)、9(Polymer/`require=0`) | registry-load |
| gardnercraft-mod | fabric | 10、11(polymer 匿名类) | registry-load |
| sun-fade | neoforge | 5(connector ServerMainMixin#earlyInit) | noclassdef |
| better-teleport | neoforge | 1、3(architectury) | compat-required-loss |
| bettergrassify | neoforge | 2(fabric-screen-handler) | compat-required-loss |
| bonfires-ex | fabric | 4(bonfires ItemStackMixin#getOrDefaultRedirect) | noclassdef |

注意 `bonfires-ex` / `open-parties-and-claims` / `sun-fade` 同一行里还有**第二条、与冻结 id 无关**的失败
(见下表 §1.2、§2),因此它们不是"整行冻结"。

## 1. 非冻结 residue:逐条

`#` 中 ✅ = 已用真字节判定;**⚑ = 判据已给出但尚未逐字节复核**,表内写明"决定它的那一步"。
不用 "unknown":每条都写了读法与决定方法,未复核的明确标 ⚑。

### 1.1 内核侧(本轮已落地,3 条)

| # | 主体 | loader | 行(run/mod/world) | 存盘 cause | 分类 | 真字节证据 | 动作 / commit |
|---|---|---|---|---|---|---|---|
| K1 | uhc-gapples | forge | FAIL/OK/false | boot-illegal-argument-exception | **内核侧** ✅ | `CheaperGapples.jar` 内 `__MACOSX/com/modrinth/_6sSDO6Y/._ModrinthWrapper.class` 的字节是 `00 05 16 07 00 02 00 00`(AppleDouble resource fork),非类文件;`MixinShadowMembers.scan` 第 133 行把每个 `.class` 后缀条目交给 `new ClassReader(bytes)` ⇒ `IllegalArgumentException`(无消息),启动在**任何客方重映射之前**就死 | 落地 `f3e58371`:ByteScan.isClass + mapping 四扫描器与 ModAnnotationScanner 过魔数 |
| K2 | bonfires-ex | fabric | FAIL/FAILED/false | noclassdef `net/minecraft/class_2960` | **内核侧** ✅ | `cardinal-components-base-6.1.3` 的 `CcaAsmHelper.<clinit>`:`getMappingResolver().mapClassName("intermediary","net.minecraft.class_2960")` 后 `replace('.','/')`;内核 `KernelMappingResolver` 的索引按斜杠建,点号查询永远 miss ⇒ 返回原样 ⇒ 生成的 component type 引用 `net/minecraft/class_2960` ⇒ `Class.getDeclaredConstructors0` 抛 `NoClassDefFoundError` | 落地 `e61eaf19`:查询归一化、答案按调用者拼法回 |
| K3 | simple-hats-collection | fabric | PASS/OK/true | nosuchmethod `DataComponentType$Builder.endec(Endec,SerializationContext)` | **内核侧** ✅ | 控制台 `duplicate mod id 'owo' (owo-sentinel … and owo (owo-lib …)) — keeping the first`;`accessories` 内嵌 `owo-sentinel`(id `owo-sentinel`,`provides ["owo","owo-lib"]`)先占别名 `owo`;真 `owo-lib`(id `owo`)被判重复**丢弃**,其 `ComponentTypeBuilderMixin` 从不施加 ⇒ `endec` 不存在 | 落地 `040d8300`:id 与 provides 别名分开仲裁,真重复仍保留第一个 |

### 1.2 内核侧但**显式延后**(1 条,理由写明)

| # | 主体 | loader | 行 | 存盘 cause | 分类 | 真字节证据 | 动作 |
|---|---|---|---|---|---|---|---|
| K4 | no-smithing-template-refabriced | fabric | PASS/FAILED/true | nosuchmethod `ConfigTracker.registerConfig(Type,IConfigSpec,String)` | **内核侧(互操作 lane 的缺口)** ✅ 判据 / ⚑ 未落地 | FCAP 21.1.6 自带 `net/neoforged/fml/config/ConfigTracker.class`,javap 出 `registerConfig(Type,IConfigSpec,String)` 与 `(…,String,String)`;合并基底的 `ConfigTracker`(**neoforge-runtime.jar**)只有 `(…,ModContainer)` 与 `(…,ModContainer,String)`。内核的 `PortingLayerAbiInjector` 只认 26.2 的类名 `ConfigRegistryImpl`,而 1.21.1 的调用点在 `ForgeConfigRegistryImpl`(javap:4 个 mod-id-keyed 调用点) | **延后,不半修**:4 个重载里 2 个 `void` 形状可一行改写到 `KernelConfigPortBridge`;另 2 个**返回 Forge `ModConfig`** 的重载随后读 `getfield net/neoforged/fml/config/ModConfig.modConfig`,该字段只有 FCAP 自带的 `ModConfig` 有(合并基底的没有),而 FCAP 自己的 `adapt()` 又用合并基底不存在的 5 参构造器 —— 完整修复需要内核新增"用 Forge ModContainer 造一个真 Forge ModConfig"的桥,无法在无启动的前提下验证。故按纪律 **stand-down + 记账**,不写半个修。代价:凡经 FCAP 注册配置的 Fabric 主体在该点仍失败 |

### 1.3 主体侧(与内核无关,点名以便终表分开)

| # | 主体 | loader | 行 | 存盘 cause | 分类 | 证据 |
|---|---|---|---|---|---|---|
| S1 | no-cooldown-enchantment | forge | FAIL/OK/false | registry-load | **主体侧** ✅ | `Failed to parse no_cooldown:enchantment/instant.json … Unknown registry key … minecraft:attack_speed`:该 mod **自己的 datapack** 写了 1.21.2+ 的属性 id;1.21.1 是 `minecraft:generic.attack_speed`。写错版本的数据,不由内核产生 |
| S2 | servereconomy | fabric | PASS/OK/true | dependency-not-ok | **主体侧** ✅ | 行内 `dep_status`:其声明必需依赖 `placeholder-api-2.4.2+1.21.jar` = **ABSENT**——闭包里根本没解析到这个 jar。主体自己的依赖不可解析 |
| S3 | beilin-data-portability | fabric | PASS/OK/true | dependency-not-ok | **主体侧** ✅ | `dep_status`:`beilin-entry-control-fabric-1.21.x-1.2.7.jar` = **FAILED**——失败的字节是它的依赖,不是它自己 |
| S4 | cobblemon_skills_api | fabric | PASS/FAILED/true | noclassdef `net/puffish/skillsmod/api/reward/Reward` | **主体侧** ✅(控制台) | 控制台点名 Puffish Skills 的 API 类;而闭包只有 fabric-api,`puffish_skills` 未被解析进来——主体声明的依赖解析不到 |
| S5 | cobblemon-auto-battle / cobblespawnregions / cobblemon-coop | fabric | FAIL/…/false | registry-load | **内核侧**(由 W7Harness 的对照启动结案,见 §1.4 P2) | 失败数据属依赖 Cobblemon,但**根因在核**:真 Fabric 1.21.1 上 Cobblemon 注册 **43/43** 自定义注册表并干净启动;Forbric(`03c1f88a`)上注册 **0/43**,`registry errors: 5`、`world=false`、`cr=0`。此前我认为"数据属 Cobblemon ⇒ 主体侧"的读法**已被这次对照否掉** |
| S6 | gardnercraft-mod | fabric | FAIL/FAILED/false | registry-load | **主体侧(数据面)** ✅(控制台) | `Failed to parse gardnercraft:trim_pattern/gardnercraft.json` → `Failed to get element gardnercraft:gardnercraft_armor_trim_smithing_template`:主体自己的 trim_pattern 引用自己没注册成功的条目(该行另有冻结 id 10/11,见 §0) |

### 1.4 收尾(P1、P2、P3 均已结案;无遗留的"未定")

P2 的答案由 W7Harness 的对照启动给出,并且**推翻了本节先前"依赖侧数据"的读法**——这正是"决定步骤"存在的意义:

| # | 主体 | loader | 行 | 存盘 cause | 现状与决定步骤 |
|---|---|---|---|---|---|
| P1 | more-gunpowder-creeper | forge | STALL/OK/true | boot-stall | 控制台 1011 行:Worker-Main 线程在 `LinearPalette.valueFor` 抛 `MissingPaletteEntryException: Missing Palette entry for index 10`(`ThreadedLevelLightEngine.runUpdate` → `runLightUpdates`),之后 spawn-area 停在 `18%`,没有 `Done (`、没有 `Stopping the server` ⇒ 真挂起。**已由夜间档的单独复跑结案**:在 `604a557d` 上用 600 s 窗口重跑 → `PASS / world=true / 172 s / cr=0`,中段线程转储 30 条、`main` RUNNABLE、无死锁(`/tmp/gunpowder-dump-1.txt`)。即 `boot-stall` **不再复现**,是其间某个提交修掉的;`boot-stall` 这个标签只是 harness 的 verdict 回退,不是证据 |
| P2 | veinminer-enchantment | neoforge | FAIL/OK/false | registry-load | **内核侧 ✅ 已结案(W7Harness 对照启动)**:真 Fabric 1.21.1 上 Cobblemon 1.8.1 打印 **43 条** `Registered the cobblemon:* registry` 并 `Done (1.065s)!`;Forbric **`03c1f88a`**(sha `5aaad474…`,`reports/2026-10-03-cobblemon-forbric/`)上 **0 条**、`registry errors: 5`、`run=FAIL world=false cr=0`。同一 mod 集(Cobblemon 1.8.1 + fabric-api 0.116.17)、同为 JDK 21、同一条 mod 自打的信号 ⇒ 不是 mod、不是数据。**最窄的下一步**:错误链从 `minecraft:root` 的嵌套 walk 起,点名 `cobblemon:medicinal_leek`(`minecraft:worldgen/configured_feature`)在 freeze 时 unbound;先确认 Forbric 到底**有没有**把 Cobblemon 的 datapack 扫进那个注册表,再谈 freeze。**与 `no-cooldown-enchantment` 同一张面**(datapack→registry 装载路径),可能一修两治 |
| P3 | superb-warfare-perimeter | forge | FAIL/OK/true | noclassdef `com/atsuishio/superbwarfare/entity/vehicle/DroneEntity` | **主体/依赖侧** ✅:该 run 的 `mods/` 里**只有** `sbw-Perimeter-ops-1.6.0.jar`;它的 `META-INF/neoforge.mods.toml` 声明 `[[dependencies.sbwswarm]] modId = "superbwarfare"`,而宿主 mod superbwarfare 不在闭包里 ⇒ `ClassNotFoundException` 指向的是**未解析的声明依赖**,与 servereconomy 同一类 |

### 1.5 与夜间档 forge/fabric triage 的**一处实质分歧**(必须写在明面上)

`w7/NIGHT_SHIFT.md` 的 "Update, same evening" 把 `simple-hats-collection` 的 `endec` NoSuchMethodError 记为
"26.2-era API reached from a jar labelled 1.21.1 ⇒ subject/version mismatch,outside the kernel"。本轮**不同意**,
且已按分歧落地了 K3:

- 失败的 entrypoint 不是被测主体,是**依赖** `accessories`(`main entrypoint of accessories failed`);
- `endec` 不是外来的 26.2 API,它就在闭包里的 `owo-lib-0.13.0-alpha.15`:javap 出
  `OwoComponentTypeBuilder.endec(Endec)` 与 `endec(Endec,SerializationContext)` 两个 default 方法,由
  `owo.mixins.json` 里的 `ComponentTypeBuilderMixin` 施加到 `DataComponentType$Builder`;
- 内核**明说**它把真的 owo-lib 丢掉了:`duplicate mod id 'owo' (owo-sentinel … and owo (owo-lib …)) — keeping the first`。
  `owo-sentinel` 的 `fabric.mod.json` 里 `id` 是 `owo-sentinel`,只是 `provides ["owo","owo-lib"]`;
- 因此该行是内核仲裁把 id 和别名混为一谈,不是主体版本错配。测试 `KernelFabricLoaderPresenceAliasTest`
  2/2 钉住了两条语义(别名让位 / 真重复保留第一个)。

若终表要采纳夜间档的口径,应当先否掉上面四条中的任意一条;在此之前**不应**把该主体记成主体侧。



## 2. 待复核/口径注记

- **行与 run 目录不是一一对应**:`full-corpus` 有 101 个 run 目录对 100 行,`bucket-fabric` 14 对 13——
  都是早先 arm 的残留(如 `000-simple-hats-collection` 与真正的 `008-…`)。按 `(slug,loader)` 取名次会取错,
  本轮的每条读法都标了实际使用的目录。
- **`cause` 是派生标签,不是证据**:`harness/recause.py --dry-run --force` 显示 full-corpus 有 8 行、
  bucket-forge 有 1 行的 cause 会变(其中 4 行是 strict=true 的通过行——recause 未复刻"仅非 strict 才算 cause"
  的守卫,那 4 处是派生器的假阳性,不是报告错)。`boot-stall`/`boot-illegal-argument-exception` 来自
  `sweep.py` 的 `"boot-" + verdict` 回退,只说明没匹配到别的签名。
- **本轮派出的 3 个 scout 全部在 provider 处 401 INVALID_API_KEY**,零产出(与 `2026-10-03-frozen-twelve-ids`
  记的同一现象)。§1 的全部读法为本轮独力完成,未用任何子代理结论。

## 3. 已落地的三个提交(每个决定一个提交)与套件状态

| commit | 决定 | 真字节红→绿 |
|---|---|---|
| `f3e58371` | 非类文件不许进 ASM(AppleDouble 形状) | `MixinShadowMembersTest` 3/3 **0 skip**(含新增副档名用例) |
| `e61eaf19` | MappingResolver 保留调用者拼法 | `KernelMappingResolverMappingsTest` 5/5 **0 skip**(含新增 dotted 用例) |
| `040d8300` | id 与 provides 别名分开仲裁 | `KernelFabricLoaderPresenceAliasTest` 2/2 **0 skip** |

套件命令(与 `run/test-staged-root.sh` 同一套 staged-root 口径;映射 fixture 走
`<mcDir>/.forbric/mappings/`,本机已就位):

```bash
cd forbric-kernel
./gradlew --offline cleanTest test \
  --tests net.forbric.kernel.mapping.MixinShadowMembersTest \
  --tests net.forbric.kernel.mapping.KernelMappingResolverMappingsTest \
  --tests net.forbric.kernel.fabric.KernelFabricLoaderPresenceAliasTest
# 10 tests, 0 failures, 0 errors, 0 skipped
```

`-Pforbric.stagedRoot=<run/>` 一档在本机走不通:`compileRuntimeJava` 需要 source-namespace(intermediary)
的 MC 类与本机未备的 `-Pforbric.fabricApi`/`-Pforbric.rebornEnergy`,故本次按"无 staged 游戏档"档位运行
(`test` 任务在该档位只编译 main+test,跳过的只是游戏侧编译),三个用例读的是真实映射数据,不是合成对。

## 5. 后续:AppleDouble 之后暴露的第二处——重打包不能死在重复条目名上(独立提交)

K1 的守卫让 uhc-gapples 往前走了一步,随即死在**重打包**:

```
java.util.zip.ZipException: duplicate entry: META-INF/mods.toml
```

`CheaperGapples.jar` 里**真的**有两个 `META-INF/mods.toml`、两个 `META-INF/neoforge.mods.toml`
(以及成对的 `__MACOSX/.../._<name>.class`)。zip 允许重名,Java 的 `ZipFile`/`JarFile` 按中央目录顺序解析、
**后一条覆盖前一条**——所以"取最后一条"不是偏好,而是与原 jar 的读者看到的一致。`ZipOutputStream`
则对已持有的名字拒绝第二次 `putNextEntry`,于是"要清洗的 jar"恰好清洗不了。

- **策略:保留每个名字的最后一条**,理由如上;测试里把这条前提**在输入 jar 上**断言掉
  (`assertEquals("last", ZipFile.getEntry(DUP))`),以免策略悄悄漂离它。
- **红→绿**:`ReadableClassEntriesTest` 1/1——修前 `ZipException: duplicate entry: META-INF/mods.toml`,
  修后通过;同批 4 个套件 **13/13,0 skip**。
- **其它重打包点是否同形**:`MixinNames.translate`、`InheritedMemberRefs.translate`、
  `InheritedMemberDecls.translate`、`AccessWidenerRemapper.remap` 都是**按条目名建 map 再重写**
  (`LinkedHashMap<String,byte[]>`),重名在 map 里塌成一条(后写覆盖,同样是"最后一条胜"),因此不会出现
  第二次 `putNextEntry` —— **同形不存在**。`ForgeModRemapper.remapJar` 把 jar 交给 tiny-remapper 的输出侧,
  它现在只拿到已清洗的 jar,重复条目已不在其输入里(记录,未证)。
- **附带**:`MixinShadowMembers.scan` 里我先前那道 `ByteScan.isClass(bytes) continue` 已删除——它是个
  **静默**短路,会让条目进不了 `unreadable` 列表;可读性判定收归 `ReadableClassEntries.parse` 一处,
  它**点名**跳过了谁。这同时修绿了同一批里 `MixinShadowMembersTest.anUnreadableClassEntryIsSkipped…`
  (修前红:`the skipped entry must be named, not silently ignored`)。



## 6. 新增:一条内核侧(未修,已定位到最窄一步),与两条复核环境注记

**新增内核侧 K5(未修,已把范围收窄到三条真字节线索)**:Cobblemon 1.8.1 的自定义注册表在 Forbric 上
**一个都没注册**(0/43;W7Harness 对照:真 Fabric 1.21.1 上 43/43 且 `Done (1.065s)!`),错误链从
`minecraft:root` 的嵌套 walk 起,`cobblemon:medicinal_leek`(`minecraft:worldgen/configured_feature`)在
freeze 时 unbound —— 该 JSON 引用的 `cobblemon:biome` 这类由 Cobblemon 自己注册的 predicate/placement 类型
没进注册表,于是 entry 解析失败、freeze 报 unbound。

从 `reports/2026-10-03-cobblemon-forbric/per-mod/run/000-cobblemon__fabric/console.log` 读出的三条线索
(都指向"注册这一步",不是 freeze 机制):

1. **不是"entrypoint 没跑"**:`1386:[12:52:19] Launching Cobblemon 1.8.1` —— Cobblemon 自己的初始化到了;
   它之后没有任何一条 `Registered the cobblemon:* registry`(参考启动 43 条)。所以断点在**它的注册循环**
   或它之前,不在 mod 装载。
2. `1481:[Forbric/DatapackRegistries] fired MinecraftForge's declaration event and mirrored **0 custom registry
   codec(s)** into the active loader list: []` —— 内核自己报"镜像了 0 个自定义注册表 codec";紧接着
   `1482/1483` 只有 NeoForge/Forge 的 `neoforge:biome_modifier`/`forge:biome_modifier` 等 **2+2** 条进去。
   这就是与参考启动的**直接不对称**:参考那边存在 43 个,这边声明事件后是空表。
3. 同一份日志里内核有一条专门的修复说明,形状与本例一致:
   `900:[Forbric/ResourceLoader] … every pack read as hidden, and **pack selection came back empty, which
   empties every datapack registry** (only requiredNonEmpty ones report it)` —— "pack 选空 ⇒ 每个 datapack
   注册表被清空"是内核已知的一种失败形状;K5 是否是同一处的**另一条分支**(只清空非 requiredNonEmpty 的),
   下一次开工的第一件事就是对着这条读。

**未修的理由**:剩余预算已尽,而下一步必须是**读真字节**(Cobblemon 走哪条注册路径、内核在哪一步把它的
codec 挡在列表外),按纪律不猜着改注册表机制。**它与 `no-cooldown-enchantment` 是同一张面**(datapack→registry
装载路径),可能一修两治:后者是主体数据写错版本(§1.3 S1),但两行都是从同一条
`Freeze`/`RegistryDataLoader` 路径报出来的,别当两个 bug 分开修。

**证明它的启动**:一个只装 Cobblemon(+fabric-api)的 subject run,由 W7Harness 跑;判据是
`Registered the cobblemon:* registry` 计数回到 43 且 `world=true`。

**W7Harness 提供的两条复核环境事实**(省下一次复跑的钱):

1. `sweep.py` 会**静默忽略** `--only cobblemon`:Cobblemon 是 `kind: dependency` 行,不是可启动主体。
   现已改为打印一行点名该行与原因;需要它当主体时得在一次性 corpus manifest 里提升它。
2. Cobblemon 硬性 `requires java @ [21]`,本机默认 JDK 25 会在 mod 解析阶段就拒绝它;对照启动跑在
   `/Library/Java/JavaVirtualMachines/jdk-21.jdk`。**任何 Cobblemon 对照实验都要显式 JDK 21**,否则
   得到的"失败"与内核无关。



| slug | loader | 为什么 | 预期 |
|---|---|---|---|
| `uhc-gapples` | forge | K1 的直接受力点 | `boot-illegal-argument-exception` 消失,应到 world |
| `bonfires-ex` | fabric | K2 的直接受力点 | `net/minecraft/class_2960` 消失;但该主体仍带冻结 id 4,`cr` 未必归零,`world` 应可到 |
| `simple-hats-collection` | fabric | K3 的直接受力点 | 依赖 `accessories` 不再 FAILED ⇒ `loaded/strict` 应转真 |

`no-smithing-template-refabriced`(K4)本轮**不动**,复核无意义;`cobblemon*`/`serverbunch` 属主体侧,不该移动。
