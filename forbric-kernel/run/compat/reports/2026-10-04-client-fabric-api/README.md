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

## 8. 验收读数（`b9db9617`，已量）

运行由 `W7Harness` 执行、读数逐字回传，报告在 `reports/2026-10-04-client-fabricapi-spelling/`，
本内核 sha256 `1cebf2848d57a15afd0956cc25a83ae69a51838a96cf249fcc920e584873b3a6`：

```
compatibility-report.json   confirmedRequired: 0   枚举: 0
console  WorldChunkMixin    retargeted guest mixin net/fabricmc/fabric/mixin/event/lifecycle/client/
                              WorldChunkMixin:onRemoveBlockEntity onto the blockEntities Map.remove
                              at instruction 20 (factory at 72)
console  'retarget DECLINED'           0 次（全日志）
console  'retargeted guest mixin …'    6 行（上一轮 5 行，第 6 行是 B）
```

**验收判据的第一支因此成立：`CONFIRMED required` 集合为空**，并有一条可逐字引用的控制台行证明最后一个成员是
被重锚掉的，而不是别的什么让它消失。B 照 §7 的预先登记只认这一行，现在这一行在。

**同时把两句话分开，不合并且不夸大：**

1. **"客户端 fabric-api 的 CONFIRMED required 集合为空"**——**已量**（上一段）。
2. **"客户端在默认 `ask` 策略下能起得来"**——**未establish**：本次运行仍然停在 `TitleScreen`
   （`world=false`，console 已 1500+ 行、`EventMux … forwarded 200 frames`，活着并在渲染，但从未进世界），
   卡点是 `ClientShaderFix` 的着色器路径，不在本车道。所以"集合为空"与"启动能过 `ask`"是两件事，
   前者已证，后者待着色器修复后再证。

另外，验收判据的第二支（"每一条剩余项都有具名原因与记录在案的代价"）在本批里也成立，只是用于**已退出**的
13 条：它们的代价逐条写在 `MergedBaseMixinCompat` 的注释与 §3，集合里没有剩余项。

**得到这个结果的过程本身值得留在记录里**：三次测量、零个被接受的推断——`ChunkAccess` owner 假设由常量池
证伪，描述符假设由合并基底证伪，随后是插桩把
`@Redirect method= is [owner 限定] not [裸名]` 打出来；据此落地的修复是**接受面修补**（两种写法指向同一成员，
描述符逐字相同），不是把谓词放宽。我自己推断出来的标记（`retargeted guest mixin fabric-item-api-v1 …`）
在修复前后都不存在，那次"0 命中"没有任何证明力，这条也留在 §7。

## 9. 深度规则（两份读数并列，不互相取消），以及世界深度暴露的第 15+1 条

**同一个内核状态、两次读数、两个深度——两个数都写出来，不让它们看起来互相矛盾：**

| 读数 | 深度 | `confirmedRequired` |
|---|---|---|
| `b9db9617`（sha `1cebf284…`） | 客户端起来了、渲染 200 帧、**从未进世界**（`world=false`，停在 TitleScreen） | **0**（枚举 0） |
| `e22a3d3d` 之后（`world=true`、`frames=1`、`PASS`、`joined world via quick-play`、30 s） | **进了世界**，这就是深度 | **1** |

**这不是两句矛盾的话，是一条深度规则**——与服务端文档里那条同源，这里第一次出现在客户端：
**一个从不加载世界的客户端，报不出只有加载世界之后才出现的东西**。"集合为空"永远要带深度说，
否则后一次更深的读数会被读成前一次的翻案。本战场因此改成：**世界深度的读数才算验收读数**，
浅的那次只作历史。

### 9.1 世界深度暴露的那一条（已落地，`55164ff3`）

```
mixin-injector:fabric-renderer-indigo.mixins.json:
  net.fabricmc.fabric.mixin.client.indigo.renderer.SectionBuilderMixin#hookBuildRenderBlock
```

**判决：重锚（不是带账退出），代价为零。** 字节两侧都读过：

- 合并基底的 `SectionCompiler.compile(SectionPos,RenderChunkRegion,VertexSorting,SectionBufferBuilderPack)`
  只调一处 `renderBatched`，收尾是 `…util/RandomSource;Lnet/neoforged/neoforge/client/model/data/ModelData;
  Lnet/minecraft/client/renderer/RenderType;)V` —— NeoForge **加宽了被调方**；guest 的 `@At(INVOKE)`
  指的是 7 参那一形。7 参方法**仍然声明**（`BlockRenderDispatcher` 上两种都在），只是 `compile` 里不再调它
  ——所以这是**锚点问题而不是缺成员**，也正是它能被重锚的原因。
- 结果：redirect 绑不上，Indigo 的包装器从未运行；而包装器对"非 vanilla 适配"的模型是唯一路径。

**改动（既有适配器 `FabricSectionCompilerMixinAdapter` 的 1.21.1 分支；26.2 那支一字未动）**：
包装器按编译原样保留（Indigo 快路径 `TerrainRenderContext.tessellateBlock` 不动，其余样本原样转发），
只把"转发到哪"这一处加宽——注解 target 与 handler 描述符同时多两个参数，并在这条调用前压入
`ALOAD 9`/`ALOAD 10`，把 NeoForge 的 `ModelData` 与 `RenderType` **原样**交给同一个重载：不丢、不加、不换序，
快路径不参与这两个参数。Mixin 绑定时检查的就是注解与 handler 形状一致，两者一起动。

**离证据**（真实字节，不启游戏 JVM：remapped guest × merged base 跑适配器本身）：
`adapt returned 1`；handler 描述符为 10 参（dispatcher + 9）；转发调用描述符为 9 参 NeoForge 形；
调用前确有新压入的 `ALOAD 9`/`ALOAD 10`；注解 target 变为 9 参形；**再跑一次返回 0（幂等）**。
第一次跑返回 0——我的 `TerrainRenderContext.tessellateBlock` 守卫写成了前缀而不是完整描述符，
被这个离线探针挡住并改正：**那是一个真实缺陷，不是假设**。

**验收读数（待运行，预先登记）**：上面那条 id 缺席；`world=true`；`joined world via quick-play` 在；
`confirmed_required: 0`。三者同时成立才算，B 那次只认控制台行的纪律在这里继续适用——
本条的对照行是 `[Forbric/Renderer] retargeted Indigo's per-block redirect onto the merged compile body's
nine-argument renderBatched …`。

## 10. 撤回 55164ff3：加宽本身对，shim 的参数类型错了——而这才量出了真正的形状

世界深度的运行把上一条判决**证伪**了，逐字如下（`crash-reports/crash-2026-10-04_07.26.49-client.txt`）：

```
java.lang.VerifyError: Bad type on operand stack
  Location: net/minecraft/client/renderer/chunk/SectionCompiler.redirect$zjm000$fabric-renderer-indigo$hookBuildRenderBlock(…)
  Reason:   Type 'java/lang/Object' (current frame, stack[8]) is not assignable to
            net/neoforged/neoforge/client/model/data/ModelData
```

标记（`retargeted Indigo's per-block redirect …`）**出现**了，说明重锚确实发射；随后客户端在世界加载时崩。
`a08f43f8` 撤回该 shim：**一个在世界加载路径上崩的 shim 比一条记录在案的损失更糟**，不留半成品。

### 10.1 真正的形状（帧级测量，不是描述符推理）

用 ASM `SimpleVerifier` 在合并基底上把两个 `renderBatched` 调用点的**帧**读出来（classpath 补上游戏
libraries；第一版缺 `it.unimi.dsi.fastutil`，直接抛 `ClassNotFoundException`，我当时把那条失败读成噪声，
那本身就是错的）：

```
compile(SectionPos,RenderChunkRegion,VertexSorting,SectionBufferBuilderPack)   ← guest 的 method= 指的就是这个
  operand[-2] = net/minecraftforge/client/model/data/ModelData                ← Forge 那份
compile(…, SectionBufferBuilderPack, java/util/List)                          ← 另一个重载
  operand[-2] = net/neoforged/neoforge/client/model/data/ModelData            ← NeoForge 那份
```

**同一个类里有两个九参 `renderBatched` 调用点，分属两个 `compile` 重载，拿的是两个不同的 `ModelData` 类。**
我的第一版用"整个 `SectionCompiler` 里 `count(...) == 1`"去卡唯一性——它**在五参那个方法上通过了**，
而 handler 注入的是**四参**那个。注解因此可验、shim 不可验。这不是"再补个 CHECKCAST"能修的：**选错了被调方**，
而 CHECKCAST 到错的那个类正好是运行期 CCE 的配方。

### 10.2 离线探针的缺口（这次要写下来的原因）

我那个探针只查**形状**：注解 target、描述符宽度、转发调用描述符、幂等，以及发射出来的指令序列；
它**没有对目标方法做帧分析**——而帧分析需要完整 classpath，第一版抛异常时我没有把它当回事。
**形状探针永远抓不到操作数类型错**：这一次错得恰好是"形状全对、类型错"。
所以下一次加宽必须把"该重载帧里那两个操作数的类型"纳入探针，并按**具体重载**取，不是在整个类里找唯一匹配。

### 10.3 这一条的当前处置与代价

- 状态：`SectionBuilderMixin#hookBuildRenderBlock` 仍是那一条 `CONFIRMED required`；适配器已回到
  `26793e60` 原样（无新分支、不崩）。
- **原因已量清**：被调方被**加宽**（7 参 → 9 参），7 参方法**仍在声明**只是不再被调用，所以锚点不存在——
  这是世代问题的第三种形状：前两条（A/B）是类名与拼写，这条是签名被加宽。
- **代价（玩家可见）**：Indigo 的 per-block 钩子不附着，**非 vanilla-adapter 的模型不会被路由到
  `TerrainRenderContext.tessellateBlock`**，而是走 NeoForge 的 `renderBatched` 路径——Fabric mod 在区块里的
  自定义方块几何可能渲染错误或完全不出现；该 mixin 的循环建立与返回处理器仍照常绑定。
- 下一步（一步的事，不盲写第二版字节）：按"带账退出"给它补一行 `GuestInjectorPruner` 表项，锚点是
  `INDIGO_SECTION_BUILDER_MIXIN = "net.fabricmc.fabric.mixin.client.indigo.renderer.SectionBuilderMixin"`，
  prune 名 `hookBuildRenderBlock`，selector 前缀 `Lnet/minecraft/client/renderer/chunk/SectionCompiler;compile`，
  代价按上面那段写。真正的重锚要先把 shim 的参数类型从**该重载的帧**取出来再生成。

### 10.4 独立复核与三条规则

`W7Harness` 从**常量池**（不是信任我的探针）独立复核了同一件事，两个九参重载只差一个类型：

```
#270  BlockRenderDispatcher.renderBatched:(…RandomSource;Lnet/minecraftforge/client/model/data/ModelData;L…RenderType;)V
#390  BlockRenderDispatcher.renderBatched:(…RandomSource;Lnet/neoforged/neoforge/client/model/data/ModelData;L…RenderType;)V
```
且四参 `compile`（guest 的 `method=` 指的那个）的 model data 来自
`net/minecraftforge/client/model/data/ModelDataManager.getAt(...)`——就是 **MinecraftForge** 那一份。
所以"CHECKCAST 到 NeoForge 那个类"会**通过校验、然后在第一次渲染方块时抛 `ClassCastException`**：
把一次硬崩换成一次静默损坏。这条与 §10.2 同一个根：**按整个类卡唯一性**正是它选错重载的方式。

四条规则留在这里，因为它们不是轶事而是下次可直接引用的判据：

1. **"崩掉的 shim 比记录在案的损失更糟。"** 世界加载路径上的 `VerifyError` 让每个渲染的样本都付出代价；
   一条记录在案的退出只付一条 finding。补不完就必须撤，这是唯一站得住的收尾。
2. **"形状探针抓不到操作数类型错，而这一次恰好是'形状全对、类型错'。"** 注解 target、描述符宽度、
   发射序列、幂等——一个根本不能通过校验的 body 能把这四项全部满足。这条推广到本适配器之外。
3. **"类名之后是拼写，拼写之后是被加宽的签名"**——世代问题的第三种形状，而前两种各自花了一次启动才发现。
   把形状说出来，下一次才是被"找"到而不是被"发现"。
4. **classpath 不完整的探针，是没量到它声称量的东西的探针。** 我第一版探针因缺
   `it.unimi.dsi.fastutil` 抛 `ClassNotFoundException`，我把那条失败读成了噪声——它与"`evidence: []`"、
   "浅层的 `world`"同族：仪器自己的缺口被当成了无关项。

## 11. 验收：世界深度、`strict=true`、集合为空（已量）

`4bf7d90f`（内核 sha256 `9255d22db62f5d35eee67dadb9b3d66ae0d24ed30f87439772d25a7f679480be`），
报告在 `reports/2026-10-04-client-standdown/`：

```
run=PASS  exit=0  world=true  frames=1  stopped=true  killed=false  mod=OK
strict=TRUE   confirmed_required=0   seconds=29   java=jdk-21
joined world via quick-play: W7Client
clean disconnect observed; stopping client
```

四条预先登记全部成立：①那一条 id 只在记录里出现，形如 `required=False, confidence=CONFIRMED`；
②`confirmedRequired: 0`、枚举 0；③`world=true` 且 `joined world via quick-play: W7Client`；
④`pruned 1 injector(s) from …indigo.renderer.SectionBuilderMixin` ×1，且 `retargeted Indigo` ×0
（撤回的机制在控制台里彻底消失）。

**这次启动本身就是七张表的证明**：一条缺 COSTS 行的 prune 会让 `declaredAnchors` 直接把启动打死，
所以一个带着这一行、并且真的进了世界的运行，等于 `EXTRA_TABLE/CONFIGS/ACTIVE/COSTS/REASONS/DRIFT/LOSSES`
七张都齐——比逐张 grep 更硬的仪器。

**证伪条款的落地读数（这一步不能只看计数）**：控制台里确有 `VerifyError` ×1 与 `ClassCastException` ×4，
**没有一条是"撤回没生效"**。那条 `VerifyError` 是剪枝行自己的原因文本；四条 CCE 里两条是 `MergedBaseCompat`
的路由提示，第四条是配置库里被捕获的非致命转换（`SimpleCommentedConfig → CommentedFileConfig`），
**没有 crash report、运行照常走到干净停机**。所以撤回生效，这里没有我该修的东西。

**验收结论，按两支分别说：**

1. **"fabric-api 客户端的 `CONFIRMED required` 集合为空"——已量，且是世界深度**：`strict=true`、
   `world=true`、`PASS`、`confirmed_required=0`，控制台逐字如上。这是本次战役第一条 strict 客户端行。
2. **"默认 `ask` 策略下客户端能起得来"——仍未直接测**：这一行跑在 `continue` 下。策略门的规则是
   以 `confirmedRequired` 为输入（>0 则停），本行给它的输入是 0，所以按已记录的规则它不该停；
   但**"不该停"是推断，不是读数**——要把它变成读数，需要一条默认策略的启动。这一条留给下一次，
   不改写成本次已成立的结论。

**四条世代形状**（同一缺陷类的四种脸，每一种都是靠一次启动而不是靠 diff 看到的）：
类名（`LevelChunkMixin → WorldChunkMixin`，A/B 之前那一类）、拼写（owner 限定 vs 裸名，B）、
**被加宽的签名**（7 参 → 9 参，Indigo）、以及字段被换类型（`IdMapper → Map`，颜色族，带账退出）。
把它们写下来，是为了下一次是被"找"到而不是被"发现"。

另：本行 `killed=false`/29 s，对照崩溃那次 `killed=true`/18 s 与之前三个停在 TitleScreen 的窗口——
harness 现在能区分"跑完"与"停到被回收"，这正是测量与一团乱麻之间的差别。







## 12. Mod Menu 那一行：真实 12-mod 集合下未验证，而且暴露了一条深度规则

`W7Harness` 用**用户自己的 12 个 jar**（modmenu 作主体，其余十一个从其实例取作闭包）跑到
`3cced286`（内核 sha `359c9426…`，`reports/2026-10-04-client-modmenu-usermods/`）：

```
run=CRASH exit=255 world=false frames=0 stopped=false killed=true seconds=181 contended=false
confirmedRequired: 1      Mod Menu 那条仍是 required=TRUE
pruned 1 injector(s) from …terraformersmc.modmenu.mixin.MixinTitleScreen: 0
```

崩溃是**另一个更早的墙、且不属于本车道**：
`NoClassDefFoundError: Could not initialize class net.neoforged.neoforge.resource.ResourcePackLoader`
（`PackRepository.rebuildSelected ← reload ← Minecraft.<init>`），即游戏初始化阶段就死，早于任何世界工作。
它只有在**真实 mod 集合**下才出现——fabric-api-only 的十余次运行都到不了这里，所以是真实集合一直在掩盖的墙。

**我的那一行为什么没生效——这是深度问题，不是匹配问题。** `GuestInjectorPruner` 是一个按**类读取**起作用的
`ClassTransformer`：它在某个 mixin 类第一次被读取（即将被应用时）改写它的字节，并把那条 finding 记成
`required=false`。而报告里的 `required=TRUE` 行来自**配置预检**（`KernelGuestMixinAdapter` 在准备混入配置时写），
早于任何类加载。所以：**启动在 `Minecraft.<init>` 就死 ⇒ `TitleScreen` 从未被加载 ⇒ MixinTitleScreen 从未被
读取 ⇒ 剪枝从未发生 ⇒ 预检那条 required 原样留在报告里。**

这给出一条新的深度规则，和"没有世界就没有世界之后的 finding"同族但更严格：
**"带账退出"这类判决要生效，启动必须至少走到该 mixin 的*目标类被加载*那一步。**
`MixinTitleScreen` 的目标是 `TitleScreen`，它要等资源加载之后才被加载——所以这一行比"到世界"更早、
但比"到 `Minecraft.<init>`"更晚。我在预先登记里没有写这条，是我漏的：我当时把"剪枝会生效"当成了与深度无关，
而它与深度一样敏感。

**结论**：Mod Menu 那一行在 fabric-api-only 语料上**测不到**（那里没有 Mod Menu），在真实集合上也**测不到**
（死在更早的墙上）；它需要一次**隔离运行**：主体 `modmenu-11.0.5.jar`，闭包取它自己声明的那几个
（`fabric-api`、`cloth-config`、`placeholder-api`），不带 NeoForge 系 mod——这样既避开
`ResourcePackLoader` 那面墙，又能走到 `TitleScreen`。预先登记读数随之改成：剪枝行在、该 id 记为
`required=false`（或缺席）、`confirmed_required: 0`、`world=true`。

## 13. Mod Menu 结案：机制换成 pin 后 strict=true，以及一条关于"预先登记字符串"的教训

`716caa2a`（内核 sha `869ae4671bb0fb5f703abe66d120d2e9cece5f85ba69403bd458b2d010a2cda8`），隔离集合
（modmenu + fabric-api + cloth-config + placeholder-api），`reports/2026-10-04-client-modmenu-pin/`：

```
run=PASS exit=0 world=true frames=1 stopped=true killed=false mod=OK
strict=TRUE  confirmed_required=0  seconds=32  crash reports: 0
joined world via quick-play: 1
```

四条读数：①pin 标记在（措辞更正见下）；②类级行 `mixin:mixins.modmenu.json:…MixinTitleScreen` 记为
`required=False`，注入器行**完全缺席**（§12 的登记允许"或缺席"）；③`confirmedRequired: 0`、枚举 0；
④`world=true` 且 `joined world via quick-play: 1`。

**设计点是被量到的、不是被论证的**：pin 在一次**该类从未被递进变换链**的启动上就关掉了那条 finding——
这正是按类剪枝欠缺的性质，也是机制从"剪枝"换成"pin"的原因。剪枝行保留为更窄的形式（Mod Menu 可保住
`adjustRealmsHeight`），等链子开始把该类递进来时再由它接管。pin 的代价是整支 mixin（含
`adjustRealmsHeight`）——这就是"不依赖一个永远不到来的类"的价格。

### 13.1 我预先登记的标记是错的，而它错的方式值得记下来

我登记的是 `suppressed mixin MixinTitleScreen from mixins.modmenu.json`，grep **0 命中**；内核实际发的是

```
[Forbric/Mixin] suppressed mixin MixinTitleScreen from modmenu (mixins.modmenu.json)
```

即 `from <mod> (<config>)` 而不是 `from <config>`。机制完全按预期工作（标记出现、且**不需要该类被读取**），
错的是我那条字符串：**它是照"机制应该怎么说"写出来的，不是从发出它的那行源码里抄下来的。**

这与今天更早那次（`retargeted guest mixin fabric-item-api-v1 …`，一个我在内核里根本不存在的标记）同族，
而且是同一个错误更隐蔽的版本：那次的字符串是凭空推断的，这次的字符串**方向对、细节错**——一次照字面
执行的 grep 会把成功读成我自己写下的证伪分支。结论写在这里：**预先登记的标记必须从发射它的那行代码里逐字
抄出来；由意图拼出来的字符串，即使机制是对的，也会把读数读反。**

另：`W7Harness` 的纪律是"每条读数都构建自**该提交的干净工作树**"，所以同树里别的车道未提交的改动
（`FabricSectionCompilerMixinAdapter.java` 当前处于修改态，不是我的）不会渗进任何 pin 的 `kernel_sha256`
——上面这个 sha 只属于 `716caa2a`。
