# 六条失活的 Fabric API:两条按字节重锚落地,颜色族给出形状而非半落地,另有一条与 Fabric 无关的原版缺陷被量到(2026-10-04)

范围:`PORT-1.21.1.md` 与 `run/compat/reports/2026-10-04-client-fabric-api/` 记下的**六条失活 Fabric API**。
本报告只做三件事:把**有界的**两条按真实字节重锚并给出可复现的离线证明;把**不是有界修复**的四条写成形状
而不是半成品;顺带把"颜色族为什么重锚不可行"这条观察**量实**(它暴露出合并基底自身的一条缺陷,且与 Fabric
无关)。

工作树:`/private/tmp/inert-apis-wt`(分支 `inert-apis`,起点 `20e423aa`,源码收尾 `53f101da`(报告提交 `5656a585` 不改源码),树干净)。
所有字节结论来自两侧实字节:**staged merged base**
(`p0/stage-1.21.1/merged-base/patched-mc-merged-1.21.1.jar`)、**Forge/NeoForge 各自的 patched jar**、
**官方 1.21.1 client jar**(`/private/tmp/fapin/src/client-official.jar`)、以及**remap 后的 guest**
(`w7-client-remap-warm/fabric-*-0.116.17-*.jar`,正是 Mixin 读的那份),工具是 JDK 21 的 `javap` 与
ASM 9.10.1 的 `SimpleVerifier`。

---

## 0. 六条一张表

| # | API(PORT 表里的措辞) | 判决 | 依据 |
|---|---|---|---|
| 1 | Indigo 的 per-block 钩子(区块内自定义几何) | **内核侧 — 本次落地**(`64ea43cc`) | §1 |
| 2 | mod 提供自定义 `AudioStream` | **内核侧 — 本次落地**(`53f101da`) | §2 |
| 3 | `ColorProviderRegistry`(方块/物品自定义颜色) | **评估:真修复存在且分两步,本次不半落地**;另量到一条独立的原版同色缺陷 | §3 |
| 4 | `FluidRenderHandlerRegistry` | 不可重锚(桥,不是锚点) | §4.1 |
| 5 | `ParticleRenderEvents.ALLOW_BLOCK_DUST_TINT` | 不可重锚(接缝的问题本身换了) | §4.2 |
| 6 | 客户端 `UseEntityCallback` | 不可重锚(LVT 捕获,已在既有剪枝机制里带账退出) | §4.3 |

两条落地的 API **都是"锚点问题"**:注入点本身还在合并基底上,缺的只是"内核侧的适配器认不认这一代的
名字/被调方/操作数类型"。四条留下的都**不是**锚点问题——要么接缝换了生态系统(流体),要么接缝问的问题
换了(粒子),要么根本不是选择器(捕获),要么需要一个与合并基底结构对应的桥(颜色/ID 同步)。

---

## 1. Indigo 的 per-block 重定向:改绑到"活着"的那次调用

### 1.1 两侧的真形状(逐条量)

guest `net/fabricmc/fabric/mixin/client/indigo/renderer/SectionBuilderMixin`(0.116.17 remap 后)的两个注入器
**都指名四参 `compile`**:

```
hookBuildRenderBlock  @Redirect(method=[SectionCompiler;compile(SectionPos,RenderChunkRegion,VertexSorting,
                        SectionBufferBuilderPack)Results;], require=1,
                        at=@At(value="INVOKE",
                               target="BlockRenderDispatcher;renderBatched(BlockState,BlockPos,BlockAndTintGetter,
                                       PoseStack,VertexConsumer,Z,RandomSource)V"))
hookBuildReturn       @Inject(method=[同一个四参 compile], at=@At("RETURN"))
```

合并基底 `SectionCompiler` 有**两个** `compile` 全实体,且**四参那个是死代码**:

```
$ javap -c patched-mc-merged-1.21.1.jar net/minecraft/client/renderer/chunk/SectionCompiler
  compile(SectionPos,RenderChunkRegion,VertexSorting,SectionBufferBuilderPack):                # 四参
    …405: invokevirtual renderBatched(…, RandomSource, Lnet/minecraftforge/client/model/data/ModelData;,
                                      Lnet/minecraft/client/renderer/RenderType;)V
  compile(SectionPos,RenderChunkRegion,VertexSorting,SectionBufferBuilderPack,java.util.List): # 五参
    …381: invokevirtual renderBatched(…, RandomSource, Lnet/neoforged/neoforge/client/model/data/ModelData;,
                                      Lnet/minecraft/client/renderer/RenderType;)V
```

**唯一调用者只调五参那个**(全 jar 扫常量池只有三个类提到 `SectionCompiler`):

```
$ javap -c SectionRenderDispatcher$RenderSection$RebuildTask
  131: invokevirtual SectionCompiler.compile:(…SectionBufferBuilderPack;Ljava/util/List;)L…$Results;V
```

⇒ guest 的七参 `renderBatched` 锚在合并基底上**一处都不存在**;四参 body 里那处九参调用的第 9 个操作数是
**Forge 的** `ModelData`,五参 body 里那处才是 **NeoForge 的** `ModelData`。逐操作数的帧级读数(FrameProbe,
见 §7)验证了这一点,不是从描述符推断的:

```
compile(4-arg) @249  operand[8] = net/minecraftforge/client/model/data/ModelData
compile(5-arg) @239  operand[8] = net/neoforged/neoforge/client/model/data/ModelData
```

### 1.2 上一版为什么崩(以及本报告点名的"那个探针缺口")

`55164ff3` 把 `@At.target` 与 handler 一起加宽成 NeoForge 形状是正确的加宽,但它**没有动 `@Redirect.method`**
——两个注入器仍绑在四参(死)body 上,而四参 body 交的是 Forge 的 `ModelData`。世界加载时:

```
java.lang.VerifyError: Bad type on operand stack
  Location: net/minecraft/client/renderer/chunk/SectionCompiler.redirect$zjm000$fabric-renderer-indigo$hookBuildRenderBlock(…)
  Reason:   Type 'java/lang/Object' (current frame, stack[8]) is not assignable to
            net/neoforged/neoforge/client/model/data/ModelData
```

`a08f43f8` 撤回它的标准("崩掉的 shim 比记录在案的损失更糟")成立,`931c0468`/`5488ef08` 也把缺口写成了方法
判据。**本次把那条判据实现成了可运行的东西**(§1.4),而不是再写一遍"下次要注意"。

### 1.3 本次改法(既有适配器新增 1.21.1 分支,26.2 那支一字未动)

`FabricSectionCompilerMixinAdapter.renderBlockRedirect`:

1. **两个注入器的 `method` 选择器一起**从四参 `compile` 挪到五参 `compile`(`@Redirect` 与 `@Inject` 是成对的,
   只挪一个等于把"每块几何进哪个 buffer"和"什么时候 release"拆到两个方法上);
2. `@At.target` 改成五参 body 真正调用的九参 `renderBatched`(NeoForge `ModelData`);
3. handler 描述符同步加宽(`(BlockRenderDispatcher, BlockState, BlockPos, BlockAndTintGetter, PoseStack,
   VertexConsumer, Z, RandomSource, ModelData, RenderType)V`),在**同一条**转发调用前压入 `ALOAD 9`/`ALOAD 10`
   ——不丢、不加、不换序;
4. **guest 的暂存局部 slot 9 让位**:原 handler 把 `getBlockModel` 的结果 `astore 9`。加宽后 slot 9/10 是新形参
   (`ModelData`/`RenderType`),所以那个暂存搬到 slot 11;不改的话等于把 `BakedModel` 存进 `ModelData` 形参,
   VerifyError。这是本轮的第四个坑,只有帧级/类型级检查能看见,形状检查看不见。
5. `hookBuildReturn` 一并搬过去并声明第五个实参(它的 body 只读 slot 2,不读 callback)。

守卫全部量自实字节,任一不成立就**不动字节**:handler 指纹、两个选择器、`@At` 旧锚、guest body 里恰好一处
`tessellateBlock` 与一处七参转发、四参 body **不得**含 NeoForge 形状的九参调用、五参 body 恰好一处、
`RebuildTask` 只调五参(且旧四参 0 次)、`BlockRenderDispatcher` 仍声明七参形。

### 1.4 "能抓住 Indigo 那个 VerifyError"的检查 —— 定义与负对照

判据(实现:`evidence/IndigoRetargetProbe.java`,运行:`evidence/run-probes.sh`):

> 对一次候选重锚,取注解选中的目标方法,用 `SimpleVerifier` 在**完整 classpath**(merged base + 两个 runtime +
> 1.21.1 全部 libraries + guest 模块)上算帧;在按新 `@At.target` 匹配到的**唯一**调用点,逐操作数(接收者在先)
> 要求:帧里的静态类型既是期望的那个类,又对 handler 声明的对应形参**可赋值**;再把适配后的 handler **拷进**
> 目标方法、把调用点换成同描述符的转发器,用同一个 verifier 验整条方法。**负对照**:同一检查对四参(死)body
> 的那处调用**必须失败**。

本次运行结果(全文 `evidence/output.txt`):

```
== Indigo ==
  ok   adapt returned 2 (both paired injectors moved)
  ok   @Redirect.method -> the LIVE five-argument compile
  ok   @At.target -> nine-argument renderBatched with NeoForge ModelData
  ok   guest scratch local moved off the new parameter slots
  ok   LIVE operand[8] frame type is net/neoforged/neoforge/client/model/data/ModelData
  ok   LIVE every operand matches the handler parameters
  ok   the dead four-argument body makes the same nine-argument call with FORGE's ModelData
  ok   DEAD (negative control) the frame check REJECTS this binding (negative control)
  ok   woven LIVE compile frame-verifies (the Indigo check)
  ok   adapted handler frame-verifies
  ok   second pass is idempotent (0)
```

负对照是这条判据可证伪的地方:把 `@Redirect.method` 换回四参(即 `55164ff3` 的状态)后,同一探针在
`operand[8] = forge/ModelData` 处判 FAIL —— 与真实崩溃的 `Reason` 逐字同源。**形状全对、类型错**这一类错误
因此在下一次启动之前就被挡住。

---

## 2. 声音流:按 1.21.1 的形状重绑并摘掉那条 pin

### 2.1 两侧的真形状(逐条量)

guest `net/fabricmc/fabric/mixin/client/sound/SoundSystemMixin`(0.116.17 remap 后):

```
@Redirect(method=["SoundEngine;play(SoundInstance)V"],
          at=@At(value="INVOKE",
                 target="SoundBufferLibrary;getStream(ResourceLocation,Z)CompletableFuture;"))
private CompletableFuture<?> getStream(SoundBufferLibrary loader, ResourceLocation id, boolean looping,
                                       SoundInstance sound) {
    return sound.getAudioStream(loader, id, looping);   // 字节逐字:aload 4;aload 1;aload 2;iload 3;invokeinterface
}
```

合并基底 `SoundEngine.play(SoundInstance)V` **不做这个调用**;它做的是接口调用(唯一一处):

```
587: invokeinterface SoundInstance.getStream:(SoundBufferLibrary;Lnet/minecraft/client/resources/sounds/Sound;Z)CompletableFuture;
```

而 `SoundInstance.getStream(SoundBufferLibrary,Sound,boolean)` 的默认体正是 `library.getStream(sound.getPath(),
loop)` —— 即 Forge/NeoForge 把流创建挪到了接口默认后面。内核里两条既有机制(`FabricSoundMixinAdapter` /
`FabricSoundContractTransformer`)都是 26.2 世代:类名 `SoundEngineMixin`、签名里的
`net/minecraft/resources/Identifier`,对 0.116.17 一个都不匹配。

### 2.2 本次改法(26.2 那支一字未动)

1. `@At.target` 改绑到 `SoundEngine.play` 里那唯一一处 `SoundInstance.getStream` 接口调用;
2. handler 描述符改成该调用的**接收者 + 参数**:`(SoundInstance, SoundBufferLibrary, Sound, boolean)`;
3. body 照 guest 自己的语义落字:`aload 1; checkcast FabricSoundInstance; aload 2; aload 3;
   invokevirtual Sound.getPath(); iload 4; invokeinterface FabricSoundInstance.getAudioStream(
   SoundBufferLibrary,ResourceLocation,Z)CompletableFuture; areturn`。这正是"mod 为自己的 `SoundInstance`
   提供 `AudioStream`"这条 API 本身;
4. 不覆盖 `getAudioStream` 的 `SoundInstance` 走 Fabric 默认(`library.getStream(path,loop)`),与合并基底的
   原路径**等价**——所以这不是新增行为,是把 guest 编译的语义搬到被改写的调用点上;
5. 从 `MergedBaseMixinCompat.SUPPRESSED_MIXINS` 删掉 `fabric-sound-api-v1.mixins.json:SoundSystemMixin`,
   那段"代价"文字随之作废。

守卫:handler 指纹 `e1920e2f…`、`@Redirect.method = SoundEngine;play(SoundInstance)V`、`@At.target` 是那条
旧锚、guest body 恰好一处 `getAudioStream`、`SoundInstance` 声明 `getStream(SoundBufferLibrary,Sound,Z)`
默认方法、`SoundEngine.play` 里接口 `getStream` 恰好一处、`Sound.getPath()ResourceLocation` 存在。

### 2.3 离线探针(同一套三腿)

`evidence/SoundRetargetProbe.java`:`adapt=1`;`@At.target` 与 handler 描述符同步;帧读数逐项等于 handler
形参;**负对照**——旧锚 `SoundBufferLibrary.getStream` 在合并基底 `play` 里 **0 次**(所以重绑不是装饰);
handler body **不递归**(不调被它替换掉的那个接口方法);仿真 Mixin 织入后 `SimpleVerifier` 通过;二次调用
返回 0。全文见 `evidence/output.txt`。

---

## 3. 颜色族:真修复存在且分两步,本次**不半落地**

### 3.1 五条 mixin 为什么绑不上:字段名还在,描述符换了

合并基底(`javap -p`,全文 `evidence/colour-census.txt`):

```
BlockColors : private final java.util.Map<Block,BlockColor> blockColors        # vanilla 是 IdMapper<BlockColor>
ItemColors  : private final java.util.Map<Item,ItemColor>   itemColors         # vanilla 是 IdMapper<ItemColor>
ParticleEngine: private final java.util.Map<ResourceLocation,ParticleProvider<?>> providers   # vanilla 是 Int2ObjectMap
```

五个 guest 混入**都 `@Shadow` 一个 `IdMapper`/`Int2ObjectMap` 同名字段**,Mixin 按"名字 + 描述符"绑定,
于是五条都抛 `InvalidMixinException: @Shadow field X was not located in the target class`,**整支 mixin 被丢弃**。
`ShadowFieldAliases` 的规则是"同描述符的唯一字段",而描述符正是被换掉的那一环 → 没有候选。

**关键量测:锚点本身是好的。** rendering-v1 的两个 `@Inject(method=…createDefault()…)` / `@At("RETURN")` 在合并
基底上逐字存在(`evidence/colour-census.txt` 末段)。所以这不是"接缝没了",**唯一**的阻塞就是那个 `@Shadow`
字段的类型。

### 3.2 `ColorProviderRegistry` 的修复形状:两步,缺一步都不算修好

**第一步(有界,2 个类)**:把 rendering-v1 的 `BlockColorsMixin`/`ItemColorsMixin` 重绑到新 map——
`@Shadow IdMapper blockColors` → `@Shadow Map blockColors`(只改描述符),并把两个 `get` 方法体从
`map.byId(BuiltInRegistries.X.getId(key))` 改成 `map.get(key)`。注册侧**本来就不用改**:

```
ColorProviderRegistryImpl$1.registerUnderlying  →  BlockColors.register(provider, block)
```

而合并基底的 `register` 正是 `blockColors.put(rawBlock, provider)`(与它自己的 map 同为原始 Block 键)。

**第二步(有界,3 个方法体)**:合并基底**自己的取值路径**与它自己的 `register` **不闭钥匙**:

```
register : blockColors.put(block, color)                              # NeoForge 的形状:原始 Block 键
getColor : blockColors.get(ForgeRegistries.BLOCKS.getDelegateOrThrow(state.getBlock()))  # Forge 的形状:Holder 键
field    : new java.util.IdentityHashMap<>()                          # NeoForge 的初始化
```

三者是**两个生态的一致对**被逐方法拼在了一起:Forge 的 getColor + NeoForge 的 register/init。
`IdentityHashMap` + `Holder$Reference` 键对 `Block` 键 ⇒ **永远取不到**。逐方法对照(全文在
`evidence/colour-census.txt`):

| | 字段初始化 | `register` 存的键 | `getColor` 查的键 | 一致? |
|---|---|---|---|---|
| Forge `patched-mc-forge` | `HashMap` | `getDelegateOrThrow(block)` | `getDelegateOrThrow(state.getBlock())` | 是 |
| NeoForge `patched-mc-neoforge` | `IdentityHashMap` | 原始 `Block` | `state.getBlock()`(原始) | 是 |
| **合并基底** | `IdentityHashMap`(N) | 原始 `Block`(N) | `getDelegateOrThrow(...)`(**F**) | **否** |

**这条缺陷与 Fabric 无关,它影响原版自己的同色路径**(草/水/树叶/物品等一切 `createDefault` 注册过的颜色
查表都落空,回退到 `MapColor`)。把 `getColor` ×2(Block)与 ×1(Item)改回原始键即可修好,并同时让**第一步之后**
的 Fabric 注册真能进渲染。`KernelForgeBlockColors`/`ForgeBlockTintInjector` 走的也是同一个 `register`
(原始 Block),所以这条对 Forge 侧注册同样是"静默失效"。

**为什么不本次落地**:它是**两步一体**的修复,任一步单独落地都会得到一个"看起来修好了、其实没有"的状态——
只落第一步,`ColorProviderRegistry.register/get` 能用了,但渲染器仍然查不到(mod 以为注册成功、画面不变);
只落第二步,原版同色恢复,但 Fabric 的注册面仍然是死的。而第二步改的是**游戏自己的取值路径**,需要一个独立的
客户端读数(连同视觉确认),不在本次已排的启动预算里;它的自然归属是
`ForbricMergedBaseCompatTransformer`(本车道已与 `ReviewFixes` 约定为该文件的所有者,不在此处抢改)。
所以本车道把**形状、字节证据与两条代价**留在这里,而不是半落地。

### 3.3 registry-sync 的三条(颜色/粒子的 ID 同步)不属于有界修复

`fabric-registry-sync-v0.client` 的 `BlockColorsMixin`/`ItemColorsMixin`/`ParticleManagerMixin` 同样 `@Shadow`
`IdMapper`/`Int2ObjectMap`,但它们把这些表交给

```
IdListTracker.register(Registry, String, IdMapper)          # BlockColors.providers / ItemColors.providers
Int2ObjectMapTracker.register(Registry, String, Int2ObjectMap)  # ParticleManager.factories
```

做**数字 id ↔ 对象**的同步。合并基底的表是对象键的 `Map`,里面没有 id→对象这一维;要恢复它就得维护一张与
合并表并行的 `IdMapper` 并保持同步——那是**桥**,不是锚点修复。判决:带账退出(维持 pin),代价文字照旧。

---

## 4. 其余三条:为什么不是有界修复

### 4.1 `FluidRenderHandlerRegistry`(流体外观)
合并基底的 `LiquidBlockRenderer.tesselate` 是 NeoForge 自己的实现(`FluidSpriteCache.getFluidSprites`、
`IClientFluidTypeExtensions.of(...).getTintColor`、一处 `BlockState.shouldDisplayFluidOverlay`),它**声明**
`isNeighborSameFluid` 却不再调用,也不读 `waterOverlay`/`waterIcons`/`lavaIcons`。五个注入器锚的正是这些点。
`FluidRenderHandler` 在合并基底上**没有可绑的成员**;把它重实现到 `IClientFluidTypeExtensions` 上是一座桥。
判决不变:带账退出。

### 4.2 `ParticleRenderEvents.ALLOW_BLOCK_DUST_TINT`(粒子染色否决)
`BlockDustParticleMixin` 的 `@Slice` 从 `TerrainParticle.bCol` 读到 `BlockState.is(Block)` 为止;合并基底把染色
判定换成了 `IClientBlockExtensions.of(state).areBreakingParticlesTinted(state, level, pos)` → 切片终点不存在。
更深一层:handler 返回的是一个 `BlockState`,其 `is(...)` 的答案才是原来的门;合并基底的门是一次**对原始 state
的活扩展调用**——接缝问的问题换了。不是选择器问题,判决不变。

### 4.3 客户端 `UseEntityCallback`(按注入器剪枝)
注入点在(`Minecraft.startUseItem` 的 `MultiPlayerGameMode.interactAt` 调用在合并基底 +225),失败的是**局部变量
捕获**:NeoForge 在 handler 写的那圈 `InteractionHand` 循环之前插了自己的
`InteractionKeyMappingTriggered` 局部变量(`LVT … has incompatible changes at opcode 143`)。捕获**不是选择器**,
没有可改写的锚。既有 `GuestInjectorPruner` 已按注入器剪除并补齐六张表,判决不变。

---

## 5. 验证状态(按字面读)

**已证(离线,不启游戏 JVM)**:§1.4 / §2.3 的全部检查,含两条负对照。复现:
```bash
run/compat/reports/2026-10-04-inert-apis/evidence/run-probes.sh \
  <forbric-root> <clean-kernel-worktree> <a-harness-remap-cache>
```
内核侧:改动的三个文件 `compileJava` 退出 0;`FabricSoundContractsTest`/`FabricSectionCompilerMixinAdapterTest`/
`MergedBaseMixinCompatPinnedContractsTest` 在本检出下 4/4、4/4、3/3 全部 **skip**(夹具是 26.2 的
`fabric-api-0.155.2`,本检出没有,`assumeTrue` 跳过),无失败——本改动不破坏既有契约。

**待启动证 boot(预先登记,交给 `W7Harness`)**:冻结内核 jar `/tmp/inert-apis-kernel.jar`,
sha256 `0a819eec759853495ecbb3a7b02e4c7a921481549a7bc3aa675b9774c9418256`(=`53f101da`),JDK 21,
客户端表面、quick-play、冷 remap 缓存。只接受:

- (A) **该 finding 缺席**:`compatibility-report.json confirmedRequired == 0`,且枚举里没有含
  `SectionBuilderMixin#hookBuildRenderBlock` 或 `fabric-sound-api-v1` `SoundSystemMixin` 的 id;
- (B) `world=true`、`run=PASS`、`contended=false`、`java=jdk-21`,console 有 `ClientSmoke] joined world via quick-play`;
- (C) 两条**控制台落地点**(证明确实生效,而不是碰巧没出现):
  `[Forbric/Renderer] retargeted Indigo's per-block redirect onto the LIVE merged compile body's nine-argument renderBatched`
  `[Forbric/Sound] retargeted Fabric's stream redirect onto the merged SoundEngine.play's SoundInstance.getStream call (1.21.1 generation)`;
- (D) **证伪**:若 `confirmedRequired > 0`,逐字列出每条剩余 id;若 CRASH,逐字给出 `VerifyError` 的 Location
  与 Reason —— 崩掉的 shim 比记录在案的损失更糟,本车道会撤回而不是保留。

**未测**:Indigo 的区块内几何在游戏里**画得对不对**(只证了绑定与校验通过)、声音流在真播放路径上的行为
(只证了类型与调用形状)。两者都需要一次世界深度读数;读数到位前,"API 恢复"只算**离线成立**。

---

## 6. 账(成本与未证,不藏)

- 两条落地的成本都是**常量级、无运行时分配**:改的是注解与 handler 字节,播放/渲染路径各多一次既有的接口调用。
- **新发现的坑**(写在 §1.3.4):guest 的暂存局部会与新形参撞槽。任何"给 handler 加参数"的重锚都必须检查原
  handler 的局部变量表,否则得到的是类型正确、语义错误,或 VerifyError。形状探针抓不到。
- `FabricSoundContractTransformer`(改 `SoundInstance.getStream` 默认体去派发)在本基底上是**死代码**:它
  注册在 `COREMOD`,而 `MIXIN` 是终态(`getPreMixinClassBytes` 就是链的输出),所以它看到的
  `SoundInstance.interfaces` 里没有 `FabricSoundInstance`(那个接口由 `SoundInstanceMixin` 在 Mixin 阶段才加上)。
  本次**没有**动它:1.21.1 分支直接派发 `getAudioStream`,不依赖它。这条是观察,留给"26.2 那支到底靠什么生效"
  这个独立问题。
- **颜色族**的两条代价与两条未测量(原版同色是否真在合并基底上失活:**由字节判定,未在运行中看到**;
  修好后视觉确认)都留在 §3。

## 7. 证据清单(`evidence/`)

| 文件 | 内容 |
|---|---|
| `IndigoRetargetProbe.java` | Indigo 重锚的三腿探针,含四参(死)重载的负对照 |
| `SoundRetargetProbe.java` | 声音重绑的三腿探针,含"旧锚 0 次"与"不递归"对照 |
| `run-probes.sh` | 组装完整 classpath 并跑两个探针(不启游戏 JVM) |
| `output.txt` | 上述脚本的一次完整输出(两份 ALL CHECKS PASSED) |
| `colour-census.txt` | 合并基底 / Forge / NeoForge / 官方 client / 五个 guest 的字段与键路径逐条 javap |

## 8. 提交

| 提交 | 覆盖 | 决定 |
|---|---|---|
| `64ea43cc` | Indigo per-block(1 条) | 重锚:注入器迁到活着的五参 `compile`,目标换九参 `renderBatched`(NeoForge ModelData),暂存局部让位 |
| `53f101da` | 声音流(1 条) | 重锚:`@At` 改绑接口 `getStream`,handler 重写为派发 `getAudioStream`;摘掉 `SoundSystemMixin` 的 pin |
