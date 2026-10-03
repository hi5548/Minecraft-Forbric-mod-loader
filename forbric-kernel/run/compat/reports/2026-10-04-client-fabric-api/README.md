# 客户端 fabric-api 的 15 条 CONFIRMED required：逐条判决与落地(2026-10-04)

范围：只装 `fabric-api-0.116.17+1.21.1.jar` 的 **1.21.1 客户端**启动，报告里
`confidence==CONFIRMED && required` 的**全集**。用户那份报告已随重装被清掉，所以本报告的一切数字都来自
一次**重新复现**，不是转抄。

工作树：`实验/forbric/Minecraft-Forbric-mod-loader`（`main`，起点 `22398c89`，收尾 `0af17d3d`，树干净）。
所有字节结论都来自两侧实际字节：**冻结的 remap 后 guest**
（`…/000-fabric-api__fabric/.forbric-kernel/remap/*-<hash>.jar`，这正是 Mixin 读的那份）与
**staged merged base**（`p0/stage-1.21.1/merged-base/patched-mc-merged-1.21.1.jar`），工具是 JDK 21 的
`javap -v -p [-c]`。

---

## 1. 复现：一条不能用作证据、但可用作清单的运行

`W7Harness` 用提升过 `kind` 的临时清单（`/tmp/w7-client-fabricapi-corpus`，与已提交清单的差别只有那
一行的 `kind`：`dependency → random`）在**当前内核**上跑了 fabric-api：

```
run=STALL  world=false  frames=0  stopped=false  mod=OK  strict=false
confirmed_required=15  cause=mixin-apply  seconds=800  exit=143  contended=TRUE
java=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin/java — java version "21.0.7" 2025-04-15 LTS
kernel sha256=191273137f60a8503c0450af2179490eb64ede46309854358d633325377e76df  (built from 22398c89, clean tree)
```

**这一行按我们自己的规则不算证据**（`contended=true`、`world=false`），我也明说：
`ClientSmoke] joined world via quick-play` 与 `clean disconnect observed` 都是 **0** 次。它在
`TitleScreen` 上渲染了 200 帧后停在那里直到 800 s 被杀，所以它不是"客户端失败"，也不是一条可引用的
读数。**它的用途只有一个，而且足够**：给我 `compatibility-report.json` 里的全集。我另外独立读过那份文件
（`…/000-fabric-api__fabric/.forbric-kernel/compatibility-report.json`，`confirmedRequired: 15`），与
`W7Harness` 的枚举逐条一致。

### 1.1 为什么简报里是 4 条

用户那份报告是在 **`ASK`** 策略、9 个 mod 下写出的：`policy: ASK`、`confirmedRequired: 4`，因为
**ASK 门在启动中途就停下（exit 78）**，报告只截到了门触发那一刻已经出现的四条。`continue` 会继续加载
那些模块所注入的客户端类，把剩下的暴露出来。所以 **15 是 fabric-api 客户端的诚实全集**，4 是"停机"
造成的截断，不是更小的总体。两条清单并不矛盾：用户那四条里的
`fabric-events-interaction-v0.client…MinecraftClientMixin` 与
`fabric-item-api-v1.client…ClientPlayerInteractionManagerMixin` 在 15 条里，另外两条（
`mixin-target-drift:…ServerPlayNetworkHandlerMixin` 与
`mixin-injector:…RegistryLoaderMixin#enchantmentKey`）在这份 `continue` 报告里是 **SUSPECTED**，
不计入 required 集合。

## 2. 全集（15 条，按站点归并成 9 个根因）

| # | 站点 | 条数 | 判决 | 机制 |
|---|---|---:|---|---|
| A | `fabric-item-api-v1` item api → `ClientPlayerInteractionManagerMixin#fabricItemContinueBlockBreakingInject` | 1 | **重锚** | `FabricMiningMixinAdapter`(世代名 + 世代指纹 + 按真实字节重推的语义) |
| B | `fabric-lifecycle-events-v1` client → `WorldChunkMixin#onRemoveBlockEntity` | 1 | **重锚** | `FabricClientMixinAnchors.removal()`(只差类名) |
| C | `fabric-registry-sync-v0.client` ×3 + `fabric-rendering-v1` ×2 | 5 | 带账退出(整支 pin) | `MergedBaseMixinCompat.SUPPRESSED_MIXINS` |
| D | `fabric-rendering-fluids-v1` → `FluidRendererMixin` ×5 | 5 | 带账退出(整支 pin) | 同上 |
| E | `fabric-particles-v1.client` → `BlockDustParticleMixin#removeUntintableParticles` | 1 | 带账退出(整支 pin) | 同上 |
| F | `fabric-sound-api-v1` → `SoundSystemMixin#getStream` | 1 | 带账退出(整支 pin) | 同上 |
| G | `fabric-events-interaction-v0.client` → `MinecraftClientMixin#injectUseEntityCallback` | 1 | 带账退出(按注入器剪枝) | `GuestInjectorPruner` |

15 = 2 重锚 + 13 带账退出。**A、B 这两条的代价是零**：它们的注入点都在合并基底上真实存在，缺的只是
内核侧适配器认不认这一代的名字/字节。

## 3. 逐条字节证据与代价

### A `#{fabricItemContinueBlockBreakingInject}`（重锚，代价零）
- guest 的 `@Redirect` 盯 `ItemStack.isSameItemSameComponents(ItemStack,ItemStack)` 在
  `MultiPlayerGameMode.sameDestroyTarget` 里；合并基底该方法收
  `ItemStack.shouldCauseBlockBreakReset(ItemStack)`（javap -c，且全类无 `isSameItemSameComponents` 调用）。
- `IItemExtension.shouldCauseBlockBreakReset(old,new)` 的默认体就是 `!isSameItemSameComponents`
  （javap -c `forge-runtime-interop.jar`），所以 `a.shouldCauseBlockBreakReset(b)` 正是原谓词的替换。
- 适配器此前只认 26.2 的类名 `MultiPlayerGameModeMixin` 与那一代的 handler 指纹 `fc6337bc…`；
  0.116.17 的类是 `ClientPlayerInteractionManagerMixin`，同一 handler 指纹 `d9cd559d…`。
- 改法：两个类名都接受、两个指纹都接受并按指纹分流；1.21.1 那一支的重写体是 guest 原语义的逐句镜像
  （`!shouldCauseBlockBreakReset` 取代 `isSameItemSameComponents`，Fabric 自己的
  `Item.allowContinuingBlockBreaking` 那一半原样保留）。26.2 那一支一分未动。

### B `#{onRemoveBlockEntity(Map,Object)Object}`（重锚，代价零）
- `FabricClientMixinAnchors.removal()` 的判决条件在 0.116.17 guest 上逐条成立：handler 形状、
  `@Redirect(method=LevelChunk.getBlockEntity(BlockPos,EntityCreationType))`、
  `@Slice(from=L…LevelChunk;createBlockEntity(BlockPos;)…)`。
- 合并基底侧：`LevelChunk.getBlockEntity(BlockPos,EntityCreationType)` 在 **+30** 有一处来自
  `blockEntities` 字段的 `Map.remove`（**+47** 那处来自 `pendingBlockEntities`，被判据的接收者字段名排除），
  `createBlockEntity` 在 **+92**，排在选中点之后——正是该适配器要重锚成 ordinal 的那一个。
- 改法只有类名：接受 `…/client/WorldChunkMixin` 与 `…/server/WorldChunkMixin`，两个 `…LevelChunkMixin`
  一并保留（26.2 世代模块不需要改）。恢复 `ServerBlockEntityEvents` 的卸载事件。

### C 颜色族 5 条（重锚不可行）
五个 mixin 都 `@Shadow` 一个"字段还在、名字没变、描述符被换掉"的目标字段。合并基底实测：
`Map<Block,BlockColor> blockColors`、`Map<Item,ItemColor> itemColors`、
`Map<ResourceLocation,ParticleProvider<?>> providers`（javap）。Mixin 按**名字 + 描述符**绑定，
五条都抛 `InvalidMixinException: @Shadow field X was not located in the target class`，**整支 mixin 被
丢弃**（连同锚点在的那些 handler）。
- `ShadowFieldAliases` 帮不上：它的规则是"同描述符的唯一字段"，而描述符正是被改掉的那一环，没有候选。
- 反向重锚也不成立：两个消费者要的就是 `IdMapper`（registry-sync 的
  `IdListTracker.register(Registry,String,IdMapper)`；rendering-v1 的
  `ColorMapperHolder.get` 用 `BuiltInRegistries.BLOCK.getId` 索引），而合并基底既没有这个成员、取值路径
  也换了钥匙——javap：`getColor` 先 `ForgeRegistries.BLOCKS.getDelegateOrThrow(state.getBlock())` 再
  `Map.get`，而它自己的 `register` 仍然存**原始 Block**。
- **代价（写进 pin 注释）**：registry-sync 客户端的颜色/粒子 ID 追踪失活；rendering-v1 的整个
  `ColorProviderRegistry` 失活——`initialize` 由同一支 mixin 的 `createDefault` 注入器触发，所以 Fabric
  mod 的方块/物品颜色注册不到任何地方、也读不到。原版自己的颜色路径不受影响。

### D 流体渲染 5 条（重锚不可行）
合并基底的 `LiquidBlockRenderer.tesselate` 是 **NeoForge 自己的实现**：javap 里是
`FluidSpriteCache.getFluidSprites`、`IClientFluidTypeExtensions.of(...).getTintColor`、一处
`BlockState.shouldDisplayFluidOverlay`；它**声明 `isNeighborSameFluid` 却不再调用**（唯一调用者在
`shouldRenderFace`），也不读 `waterOverlay` 与 `waterIcons`/`lavaIcons` 数组。五个注入器锚的正是这些点：
两个 `@ModifyVariable` on `isNeighborSameFluid`、一个 `@ModifyVariable(CONSTANT)` 染常量、一个 overlay 的
`BlockState.getBlock()`、一个 `waterOverlay` 字段读。
- **代价**：客户端的 `FluidRenderHandlerRegistry` 失效——Fabric mod 自定义流体外观（染色/贴图/覆盖层）
  不生效，渲染走 NeoForge 的 `FluidType` 路径。不是重锚问题：`FluidRenderHandler` 在合并基底上没有可绑的
  成员，把它重实现到 `IClientFluidTypeExtensions` 上是一座桥，不是锚点修复。

### E 粒子 1 条（重锚不可行）
`BlockDustParticleMixin` 的 `@Slice` **终点不存在**：切片从 `TerrainParticle.bCol` 读到
`BlockState.is(Block)` 为止，而合并基底把染色判定换成了
`IClientBlockExtensions.of(state).areBreakingParticlesTinted(state, level, pos)`（javap：`bCol=0.6f`
之后直接是这条），`BlockState.is` 不再出现 → 切片为空。
- **代价**：`ParticleRenderEvents.ALLOW_BLOCK_DUST_TINT` 不再被询问——Fabric mod 不能否决方块破坏尘的
  染色；由 NeoForge 的 `areBreakingParticlesTinted` 决定。

### F 声音 1 条（重锚不可行，但两项既有机制已在位、只是 26.2 世代）
`SoundSystemMixin` 的 `@Redirect` 盯 `SoundBufferLibrary.getStream(ResourceLocation,Z)`，
而合并基底的 `SoundEngine.play` 不调它：javap 显示 **+587** 处是
`SoundInstance.getStream(SoundBufferLibrary, Sound, boolean)`（接口默认体，库调用在它自己体内）。
- 内核**本来就有**这条形状的两个机制：`FabricSoundMixinAdapter`（把 redirect 重绑到接口调用并重写
  handler）与 `FabricSoundContractTransformer`（改 `SoundInstance.getStream` 默认体去派发
  `getAudioStream`）。**两个都是 26.2 世代**：类名 `SoundEngineMixin`、签名里的
  `net/minecraft/resources/Identifier`、以及按那一代字节算出的指令指纹，对 0.116.17 一个都不匹配，所以
  两者在本基底上都是**死代码**。这条本次**没有**盲改：重绑需要改 handler 形状（redirect 的
  receiver+args 从 `(library,id,loop)` 变成 `(sound,library,Sound,loop)`）并加一次
  `FabricSoundInstance` 强转，写错就是播放路径里的 `VerifyError`。1.21.1 版本的正确写法已写进 pin 注释，
  列为后续项。
- **代价**：`FabricSoundInstance.getAudioStream` 不经 `SoundEngine.play` 被咨询——Fabric mod 不能为自己的
  `SoundInstance` 提供 `AudioStream`；走 NeoForge 自己的流路径。

### G `#{injectUseEntityCallback}`（按注入器剪枝）
- 注入点**在**：`Minecraft.startUseItem` 的 `MultiPlayerGameMode.interactAt` 调用在合并基底 **+225**
  处（javap）。失败的是**局部变量捕获**：Mixin 自己的告警逐字是
  `Injection warning: LVT in net/minecraft/client/Minecraft::startUseItem()V has incompatible changes at opcode 143 in callback fabric-events-interaction-v0.client.mixins.json:MinecraftClientMixin from mod fabric-events-interaction-v0->@Inject::injectUseEntityCallback(…)`，
  因为 NeoForge 在 handler 写的那圈 `InteractionHand` 循环之前插入了自己的
  `InteractionKeyMappingTriggered` 局部变量。handler 的七个额外参数是模块自己的代码、靠捕获到的 LVT 解析；
  **捕获不是选择器**，没有可改写的锚。
- 与表里 architectury 的两条（`MixinServerPlayerGameMode`/`MixinPhantomSpawner`）同一形状、同一根因、
  同一杠杆，所以走同一条既有机制：`GuestInjectorPruner` 按注入器剪除（并补齐
  CONFIGS/ACTIVE/COSTS/REASONS/DRIFT/LOSSES 六行——`declaredAnchors` 缺 cost 行会让启动直接死掉）。
- **代价**：客户端的 `UseEntityCallback` 不再触发（模组用它取消/观察对实体的使用动作）；同一支 mixin 的
  另外六个注入器照常绑定。剪除与否运行时行为相同，变的只是报告不再把一条必然跳过的注入器记成必需损失。

## 4. 落地（一次决定一个提交，四个提交覆盖 15 条）

| 提交 | 覆盖 | 决定 |
|---|---|---|
| `f2d1518d` | B（1 条） | 重锚：接受 1.21.1 世代的 `WorldChunkMixin` |
| `4c1317ab` | A（1 条） | 重锚：接受 1.21.1 世代类名 + 指纹，并按真实字节重推该代语义 |
| `1e6bbc36` | C+D+E+F（12 条） | 带账退出：六条整支 pin，四条字节证据 + 代价写进列表注释；提交信息逐条列 id |
| `0af17d3d` | G（1 条） | 带账退出：按注入器剪枝 + 六张表补行 |

粒度说明：`1e6bbc36` 一次编辑同时落了 12 条，因为它们是**同一份 pin 列表的一次编辑**；12 条的 id、
证据与代价都逐条写在该提交的信息与 `MergedBaseMixinCompat` 的注释里。四个改动的文件都能编译通过
（javac 对 `build/classes/java/main` + ASM + sponge-mixin，退出 0）。没有新增机制：A/B 用已有的两个
适配器，C/D/E/F 用已有的 `SUPPRESSED_MIXINS`，G 用已有的 `GuestInjectorPruner`。

## 5. 验证状态——**尚未跑过**，这一节必须按字面读

**收尾时这四条改动还没有在客户端上跑过一次。** 复现那一次用的是改动之前的内核，我手上也只有那一次。
所以：

- **不能说"CONFIRMED required 集合已经为空"**。能说的是：15 条里 2 条（A、B）按字节判决应恢复注入，
  13 条（C–G）按既有机制会变成 `CONFIRMED required=false`（`reportNamedSuppressions` /
  `recordRemovedInjector` 都记 `required=false`），所以**预期**为 0——但预期不是读数。
- 下一次运行的**预先登记读数**（已发 `W7Harness`）：
  1. `confirmed_required == 0`，枚举 0 条；
  2. 报告里**不出现** A 与 B 的两个 id（注入器已附着）；
  3. 若 C–G 的 id 仍出现，只允许是 `CONFIRMED required=false`；
  4. `world=true`、`run=PASS`、`contended=false`、`java=jdk-21`；控制台有
     `ClientSmoke] joined world via quick-play`。
- **控制台可判定的落地标记**（用来证明改动真的生效，而不是碰巧）：
  - `retargeted guest mixin fabric-item-api-v1 … ClientPlayerInteractionManagerMixin`（A）；
  - `retargeted guest mixin fabric-lifecycle-events-v1 … WorldChunkMixin`（B）；
  - `suppressed mixin <名字> from fabric-registry-sync-v0.client.mixins.json` /
    `… fabric-rendering-v1.mixins.json` / `… fabric-rendering-fluids-v1.mixins.json` /
    `… fabric-particles-v1.client.mixins.json` / `… fabric-sound-api-v1.mixins.json`（C–F）；
  - `[Forbric/GuestInjectorPruner] pruned 1 injector(s) from net.fabricmc.fabric.mixin.event.interaction.client.MinecraftClientMixin`（G）。
- **证伪条件**：`confirmed_required > 0` 时把每一条剩余 id 逐字列出来，不许改写。
- 复现那一次还暴露了一条与本次改动无关的**夹具问题**：客户端在 `TitleScreen` 上停到被杀
  （quick-play 没有进世界）。`W7Harness` 已核过世界夹具本身（`saves/W7Client/level.dat` 在、
  quick-play 数据确实下发过），并新加了 `--remap-cache` 让冷 remap 不再吃掉启动预算。清点前必须先有
  一次 `world=true` 的干净读数，否则"集合为空"这句话没有落脚点。

## 6. 未做与未证（不藏）

- **F（声音）**：正确写法已记录、没有盲改，原因见 §3/F；两个既有机制在本基底上仍是 26.2 世代的死代码，
  这是本报告点名的后续项。
- **C（颜色族）**：javap 顺带量到合并基底 `BlockColors`/`ItemColors` 的取值路径与它自己的 `register`
  **不闭钥匙**（`getColor` 用 Forge 注册表代理取键，`register` 存原始 Block）。这是本报告的一条观察，
  不是断言：它是否影响原版方块/物品染色**没有量过**，也不在本次范围内；但它正是"Fabric 的颜色映射无法
  在 1.21.1 上重锚"的直接原因，值得单独一轮排查。
- **D/E/F/C 的代价**都是真实玩家可见的（流体渲染、粒子染色、声音流、Fabric 颜色注册），已逐条写进
  `MergedBaseMixinCompat` 的注释与上面 §3；本次没有把它们做成"消失"，而是做成"记录在案的缺失"。
- **没有跑过的运行**：A、B 的重锚效果、C–G 的降级效果，都还没有客户端读数。

## 7. 追加（`9746f217`/`b9db9617` 之后）：两条重锚的原因都量到了，B 的原因是一个拼写

§5 说"未验证"是对的，随后两轮运行把它收口如下（`W7Harness` 跑，读数逐字回传）：

**13 条带账退出：已验证。** `d293d776` 上 `confirmed_required` **15 → 1**，十二行
`suppressed mixin`、一行 `pruned 1 injector(s) from …MinecraftClientMixin`；两次启动读数一致。
再加上 `9746f217` 的第三次启动（同为 1），所以 15→1 不是一次性现象。

**A（fabric-item-api 的挖矿重定向）：已归因。** `9746f217` 的控制台逐字有
`retargeted guest mixin net/fabricmc/fabric/mixin/item/client/ClientPlayerInteractionManagerMixin:fabricItemContinueBlockBreakingInject onto ItemStack.shouldCauseBlockBreakReset (1.21.1 generation)`，
所以它那条 CONFIRMED required 的消失是重锚生效，不是无法归因。**上一轮我登记的
`retargeted guest mixin fabric-item-api-v1 …` 标记是我从别的适配器推断出来的，内核里从来不存在**，
那次"0 命中"什么都没证明；`9746f217` 把这类标记变成真实打点，这条教训留在提交信息里。

**B（fabric-lifecycle-events 的 WorldChunkMixin）：原因量到了，且与锚点无关。** `9746f217` 的
DECLINED 行（逐字）：

```
net/fabricmc/fabric/mixin/event/lifecycle/client/WorldChunkMixin:onRemoveBlockEntity retarget DECLINED —
  @Redirect method= is
    [Lnet/minecraft/world/level/chunk/LevelChunk;getBlockEntity(Lnet/minecraft/core/BlockPos;
      Lnet/minecraft/world/level/chunk/LevelChunk$EntityCreationType;)Lnet/minecraft/world/level/block/entity/BlockEntity;]
  not
    getBlockEntity(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/LevelChunk$EntityCreationType;)
      Lnet/minecraft/world/level/block/entity/BlockEntity;
```

拒绝它的那条守卫是 `@Redirect method=` 的**拼写**：0.116.17 写 owner 限定形，适配器比的是裸名，
而两边描述符逐字相同。`b9db9617` 接受两种写法（Mixin 两种都接受），属于接受面修补、不是谓词放宽；
与 `MixinNames` 的 refmap 拼写修复同类。**整个控制台里 B 是唯一的 DECLINED 行**（另有 5 条 retargeted
成功），所以这不是系统性谓词问题。

顺带纠正我自己的两处推断：`blockEntities` 的 `Fieldref` owner 是 `LevelChunk`（常量池 `#701`）而不是
`ChunkAccess`，所以我提的"放宽 owner 比较"是错的、已撤回；host 描述符也逐字匹配，"描述符不符"同样被证伪。
这两条都是**测量推翻推断**，不是推断自我修正。

**验收状态：仍未验证。** 15 条现在预期为 0——13 条已验证的降级 + A 已验证的生效 + B 的拼写修复
（`b9db9617` 尚未跑过）。B 是否真的生效，只认下一次运行的控制台：出现
`retargeted guest mixin …client/WorldChunkMixin:onRemoveBlockEntity onto the blockEntities Map.remove at instruction <N>`
才算，不再接受任何推断性标记。另外，客户端至今**没有一次读到 world**，卡点是
`ClientShaderFix` 的着色器路径缺陷（`neoforge:neoforge:shaders/…` 的双命名空间），不是本车道。

