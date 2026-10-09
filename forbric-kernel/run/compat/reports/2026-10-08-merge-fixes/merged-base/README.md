# merged-base 修复：`ItemBlockRenderTypes` 的 Forge 渲染层键（#1 / A3），以及 `RegistrySynchronization.<clinit>` 的 Forge 路由（#4 / B1）

**Verdict (EN).** Two of the 2026-10-08 merge-convention census's owner lanes are closed here.

**(#1 / audit A3) `ItemBlockRenderTypes` — FIXED.** In the merged base `BLOCK_RENDER_TYPES` is read by the raw
`Block` (`getRenderLayers`: `getstatic BLOCK_RENDER_TYPES; aload_1; Map.get`) and written by the raw `Block` in
NeoForge's own `setRenderLayer(Block, ChunkRenderTypeSet)`, but MinecraftForge's surviving overload
`setRenderLayer(Block, net.minecraftforge.client.ChunkRenderTypeSet)` keys it through
`ForgeRegistries.BLOCKS.getDelegateOrThrow` — a `Holder.Reference` that never equals the raw `Block`. Every render
layer a Forge mod registers through that overload is therefore stored where nothing reads it and the block draws
with the default layer. `ForbricMergedBaseCompatTransformer#rekeyTheForgeRenderLayerRegistration` removes the two
Forge key instructions (key = raw `Block`) **and** converts the value in the same pass,
`NeoChunkRenderTypeSet.of(forgeSet.asList())` — because the raw-key strip alone would be a *regression*: the reader
`checkcast`s to `net.neoforged.neoforge.client.ChunkRenderTypeSet`, and Forge's `ChunkRenderTypeSet` is a different
class, so the fixed key would hand the reader a `ClassCastException` mid-frame. Proven by a real-byte probe (shape,
idempotence, negative control, ASM `BasicVerifier`) **and** by a `W7Harness` client run: the repair's own log line
fires and the run is `STRICT PASS / world=true / frames=1 / confirmed_required=0`, no crash-report. Honest scope: no
corpus subject registers through the Forge overload (0 of 228 jars reference `net/minecraftforge/client/ChunkRenderTypeSet`),
so the run shows the repair **applies and links**; the *behavioural* half (opaque → cutout) is proven off-game.

**(#4 / audit B1) `RegistrySynchronization.<clinit>` — RECORDED, not repaired; not a loss.** The merged class is
MinecraftForge's whole: `NETWORKABLE_REGISTRIES = DataPackRegistriesHooks.grabNetworkableRegistries(supplier)`,
NeoForge's direct derivation discarded. But the supplier the merge kept is `RegistrySynchronization.lambda$static$0`,
whose body is byte-for-byte NeoForge's discarded `<clinit>` expression
(`RegistryDataLoader.SYNCHRONIZED_REGISTRIES → map(key) → toUnmodifiableSet`), and Forge's hook returns
`unmodifiableSet(ForgeHooks.NETWORKABLE_REGISTRIES ∪ supplier.get())` — a **union**. So the merged set is a strict
**superset** of NeoForge's, the only additions being Forge's custom synced-registry keys, and the set is consumed only
as a membership filter (`ownedNetworkableRegistries`). Rewriting the clinit to NeoForge's route would **drop** Forge's
custom synced registries — a regression for Forge mods, not a repair. Recorded as a benign divergence (§4).

**答案（中文）。** 2026-10-08 合并约定审计的两条 owner 车道在此收口。

**(#1 / A3) `ItemBlockRenderTypes` —— 已修。** 合并基底里 `BLOCK_RENDER_TYPES` 读侧用原始 `Block`（`getRenderLayers`：
`getstatic BLOCK_RENDER_TYPES; aload_1; Map.get`），NeoForge 自己的 `setRenderLayer(Block, ChunkRenderTypeSet)` 也存原始
`Block`；但 MinecraftForge 幸存下来的重载 `setRenderLayer(Block, net.minecraftforge.client.ChunkRenderTypeSet)` 用
`ForgeRegistries.BLOCKS.getDelegateOrThrow` 的 `Holder.Reference` 作键 —— 在 map 的 identity 语义下它永不等于原始
`Block`。于是**经该重载注册的渲染层全部存在没人读的地方**，方块按默认层绘制。`ForbricMergedBaseCompatTransformer#
rekeyTheForgeRenderLayerRegistration` 同趟做两件事：删掉那两条 Forge 键指令（键=原始 `Block`），并把值转换
`NeoChunkRenderTypeSet.of(forgeSet.asList())` —— **只做 raw-key 是 REGRESSION**：读侧 `getRenderLayers` 会把 `Map.get`
的结果 `checkcast` 到 `net.neoforged.neoforge.client.ChunkRenderTypeSet`，而 Forge 的 `ChunkRenderTypeSet` 是另一个类，
修好键反而会在渲染帧里抛 `ClassCastException`。证据 = 真字节探针（形状/幂等/负对照/ASM `BasicVerifier` 全过）**加**一次
`W7Harness` 客户端臂：修复自己的日志行出现，`STRICT PASS / world=true / frames=1 / confirmed_required=0`，0 份 crash-report。
如实写明边界：语料里没有任何 mod 走这个 Forge 重载（228 个 jar 中 0 个引用 `net/minecraftforge/client/ChunkRenderTypeSet`），
所以运行证明的是**修复已生效且能链接**；行为那一半（不透明→cutout）由离线证明。

**(#4 / B1) `RegistrySynchronization.<clinit>` —— 记录，不修；不是损失。** 合并类整支是 MinecraftForge 的：
`NETWORKABLE_REGISTRIES = DataPackRegistriesHooks.grabNetworkableRegistries(supplier)`，NeoForge 的直接推导被丢弃。
但合并保留的 supplier 是 `RegistrySynchronization.lambda$static$0`，其字节**逐条等于** NeoForge 被丢弃的 `<clinit>` 表达式
（`RegistryDataLoader.SYNCHRONIZED_REGISTRIES → map(key) → toUnmodifiableSet`）；而 Forge 的钩子返回
`unmodifiableSet(ForgeHooks.NETWORKABLE_REGISTRIES ∪ supplier.get())`，是**并集**。所以合并结果是 NeoForge 的**严格超集**，
多出来的是 Forge 自己自定义同步注册表的键；该集合只被当成员过滤器用（`ownedNetworkableRegistries`）。把 clinit 改回
NeoForge 的路由会**丢掉** Forge 的自定义同步注册表 —— 对 Forge mod 是回归，不是修复。如实记为良性分歧（§4）。

---

## 1. 基线（bytes）

三支 staged jar 的类字节（`ItemBlockRenderTypes` 的类字节在 `/Applications/.minecraft/.forbric-build/out/` 与已安装
`libraries/net/forbric/patched-mc-merged/1.21.1/` 以及 w7 `.stage-scratch` 三处**完全相同**，sha256 `3c1e0921…`；
jar 整体 sha 差异只是 zip 元数据）：

| jar | sha256 |
|---|---|
| `patched-mc-merged-1.21.1.jar` | `46af05299bafd3140321b25246138def88dd4f938b9ae9451f976b57bd1c587f` |
| `ItemBlockRenderTypes.class` | `3c1e092119da0452e71fa099763f5997d538ebbe6b67dd7748248b5578cfc88c` |
| `RegistrySynchronization.class`（merged） | merged `11653` B == forge `11653` B；neo `11544` B 被弃 |

## 2. #1 —— 缺陷的实字节与修法

合并类 `setRenderLayer(Block;Lnet/minecraftforge/client/ChunkRenderTypeSet;)V`（`javap -c`，全文见
`evidence/itemblockrendertypes-before.javap.txt`）：

```
 0: invokestatic  ItemBlockRenderTypes.checkClientLoading()V
 3: getstatic     BLOCK_RENDER_TYPES:Ljava/util/Map;
 6: getstatic     ForgeRegistries.BLOCKS:Lnet/minecraftforge/registries/IForgeRegistry;   <-- 键走 Forge 注册表
 9: aload_0
10: invokeinterface IForgeRegistry.getDelegateOrThrow:(Ljava/lang/Object;)Lnet/minecraft/core/Holder$Reference;
15: aload_1
16: invokeinterface java/util/Map.put:(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
21: pop
22: return
```

读侧 `getRenderLayers(BlockState)`（offset 28–40）：`getstatic BLOCK_RENDER_TYPES; aload_1(=BlockState.getBlock()); Map.get`。
`Holder$Reference` 与原始 `Block` 永不相等 ⇒ 经该重载注册的 layer 被静默忽略。同类的另外 4 处（实为 3 处）Forge 代理
（`setRenderLayer(Fluid,…)`、`getRenderLayer(FluidState)`、fluid filler `lambda$static$3`）是**一致的 Holder 形状**（A4，
由 `ItemBlockRenderTypesFluidMapRepair` 负责），本次**不动**。

修后 body（探针输出逐条）：

```
INVOKESTATIC  ItemBlockRenderTypes.checkClientLoading()V
GETSTATIC     BLOCK_RENDER_TYPES:Ljava/util/Map;
ALOAD 0                                            <-- 键 = 原始 Block
ALOAD 1
INVOKEVIRTUAL net/minecraftforge/client/ChunkRenderTypeSet.asList()Ljava/util/List;
INVOKESTATIC  net/neoforged/neoforge/client/ChunkRenderTypeSet.of(Ljava/util/Collection;)Lnet/neoforged/neoforge/client/ChunkRenderTypeSet;
INVOKEINTERFACE java/util/Map.put:(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
POP ; RETURN
```

三条设计约束：**(a)** 值必须转换（见上，纯 raw-key 会 CCE）；**(b)** 守卫要求整段 body 逐条等于实测生成，否则整类拒改
（拒改优于半改）；**(c)** 幂等 —— 第二趟已无 `getstatic ForgeRegistries.BLOCKS`，返回 0。

### 2.1 离线证明（不启游戏 JVM）
`evidence/ForgeRenderLayerKeyProbe.java` 驱动**真正编译出的** `ForbricMergedBaseCompatTransformer#
rekeyTheForgeRenderLayerRegistration` 跑在**真字节**上，全文 `evidence/forge-render-layer-key-probe-output.txt`：

```
== 1. premise ==      块重载 delegate 站点 = 1；整类 Forge delegate 站点 = 4
== 2. repair ==       修后 writer 0 delegate；无 ForgeRegistries.BLOCKS；含 Forge.asList + NeoChunkRenderTypeSet.of；
                      9 条指令不变；key = aload_0
== 3. fluid pair ==   余 3 处 fluid delegate 幸存；恰移除 1 处
== 4. verifier ==     ASM 结构检查静默；BasicVerifier 每个方法 clean
== 5. idempotence ==  第二趟被拒
== 6. negative ctrl ==把 ForgeRegistries owner 改坏 → 拒改，类逐字节原样交回
ALL CHECKS PASSED
```

同一批断言也落成常驻单测 `MergedBaseForgeRenderLayerKeyTest`（3/3，`evidence/render-layer-key-test-output.txt`），
它按本仓惯例用 `TestFixtures.mergedBase()` 读真基底；在没有 staged 基底的 checkout 上它 `assumeTrue` 跳过（本机 CI 行为）。

### 2.2 运行读数（预登记见 `PREREGISTRATION.md`，运行前写定）
装置：W7Harness `sweep_client.py`，surface=client，`--corpus corpus-user12 --only modmenu`（用户真实 12 mod：
modmenu + ImmediatelyFast/appleskin/cloth-config/entityculling/fabric-api/ferritecore/JEI/lithium/modernfix/
placeholder-api/sodium 11 个 dep），quick-play `W7Client`，隐藏窗口 agent 开，`-Dforbric.compatibilityPolicy=continue`，
冻结内核 sha256 `5944d167a110ed00d325c667778821c09c1fc31731701bdab18c213a9365ea7e`（= 本树 `src/main` 编出的 boot 半 +
HEAD 的 runtime 半）。

| 判据 | 读数 | 结论 |
|---|---|---|
| (R1) 修复在真客户端执行 | 日志出现本修复行（class load 时 1 次，渲染线程再次），见 `run-user12/per-mod/run/…/console.log:110,1557` | ✅ |
| (R2) 无回归 | `run=PASS exit=0 world=True frames=1 mod=OK strict=True confirmed_required=0`；日志有 `joined world via quick-play` / `client-ready after 200 world tick(s)` / `clean disconnect observed`；`crash-reports/` 为空 | ✅ |
| (R3) 其它 4 处 Forge 代理不动 | 由离线探针固定 | ✅（离线） |
| (R4) 边界 | 语料无 mod 走该重载（228 jar 扫描 = 0 命中 `net/minecraftforge/client/ChunkRenderTypeSet`），运行不证明"不透明→cutout" | 如实记录 |

`results.jsonl` 全行与 `boot-deps.txt` 见 `evidence/run-user12/`；`frozen-kernel-sha256.txt` 绑定冻结字节。

**一次被丢弃的臂（如实记录，不是选择性呈现）。** 先用 `--corpus corpus`（该语料给 modmenu 的 closure 只有
fabric-api + placeholder-api 两个 dep）跑，得到 `CRASH world=True frames=0`，崩溃在
`fabric-renderer-indigo` 的 `TerrainRenderContext.tessellateBlock`（`AccessChunkRendererRegion.fabric_getRenderer()`
为 null，"Batching sections"）——那是 **fabric-renderer-indigo 在没有 Sodium 接管渲染时的已知合并基底问题**，与本次改动无关
（本次新增的 Forge 重载在该崩溃栈里不出现；本修复行同样出现）。换成用户真实 12 mod 闭包（Sodium 在场）即 `STRICT PASS`。
两支原始证据都留在 `evidence/run/`（crash 臂）与 `evidence/run-user12/`（PASS 臂）。

## 3. #4 —— 实字节、结论、为什么不修

merged `<clinit>`（= Forge 的）：

```
0: invokedynamic  #9:get:()Ljava/util/function/Supplier;
5: invokestatic   net/minecraftforge/registries/DataPackRegistriesHooks.grabNetworkableRegistries:(Ljava/util/function/Supplier;)Ljava/util/Set;
8: putstatic      NETWORKABLE_REGISTRIES:Ljava/util/Set;
```

NeoForge 被丢弃的 `<clinit>`（`javap -c`，全文 `evidence/registrysynchronization-neo.javap.txt`）与合并类自己的
`lambda$static$0`（Forge 这一侧也有同名 lambda，body 相同）都是：

```
0: getstatic  RegistryDataLoader.SYNCHRONIZED_REGISTRIES:Ljava/util/List;
3: stream(); 8: map(RegistryData::key); 18: Collectors.toUnmodifiableSet(); 21: collect(); 26: checkcast Set
   neo: putstatic NETWORKABLE_REGISTRIES ; return     merged lambda$static$0: areturn
```

Forge 的钩子（`evidence/datapackregistrieshooks-forge.javap.txt`）：先做 caller 身份守卫
（`StackWalker.getCallerClass().equals(RegistrySynchronization.class)`，本合并类的 caller 正是该类 ⇒ 通过），再
`NETWORKABLE_REGISTRIES.addAll(supplier.get())` 并 `Collections.unmodifiableSet(...)` 返回 —— **并集**。

而 `RegistryDataLoader.<clinit>`（合并，B2）收尾是
`SYNCHRONIZED_REGISTRIES = DataPackRegistriesHooks.grabNetworkableRegistries(List.of(46 项))`，调的是 **NeoForge 的**钩子
（`evidence/registrysynchronization-…` 之外见 `javap` 748–681 段），所以 supplier 读到的字段本身就已是
`vanilla ∪ NeoForge 自定义`。Forge 的 `addRegistryCodec` 只在 `networkCodec != null` 时把键加进它的
`NETWORKABLE_REGISTRIES`（`evidence/datapackregistrieshooks-forge.javap.txt`），而本地基确实会触发 Forge 的
`DataPackRegistryEvent`（`ForgeDatapackDeclarations.declare`），故并集里可能多出 `forge:*` 自定义同步注册表的键。

**因此 merged set = NeoForge 的集合 ∪ Forge 自定义同步集合 = 严格超集。** 该集合只被
`ownedNetworkableRegistries`（`NETWORKABLE_REGISTRIES.contains(entry.key())`）当**成员过滤器**用：多出来的键只有在注册表
真的被加载时才起作用，而那时把它同步给客户端正是期望行为（Forge mod 的 `forge:*` 注册表）；没被加载则完全惰性。
**没有任何 NeoForge 会同步的注册表被丢掉。**

- **诚实的有界修复？** 若把 clinit 改成 NeoForge 的直接表达式（= 去掉 Forge 钩子），会**移除** Forge 自定义同步注册表的键
  —— 对 Forge mod 是回归。因此**不修**，记为良性分歧（不是损失）：见
  `evidence/RegistrySyncRouteProbe.java` / `evidence/registry-sync-route-probe-output.txt`（`ALL CHECKS PASSED`，
  机械核对"supplier 逐条等于 NeoForge 的表达式"+"Forge 钩子是并集"）。
- 运行侧的旁证：本车道的客户端臂与用户 12 mod 集合都到达世界（integrated server 会跑 `RegistrySynchronization`
  的 pack 路径），Forge 钩子的 caller 守卫未抛（`world=true`）。

## 4. 环境与偏离（写在明面上）

- 分支/工作树：`merge-fixes-merged-base`，worktree `../mf-wt`（四个车道共用一个 checkout，本车道不动共享 index）。
- 内核 jar 的组装：本机 **energy-4.1.0-named.jar 不存在**（`find /` 无果），game-side 不能编译，故
  `./gradlew --offline jar` 只出 boot 半；runtime 半从已安装 `forbric-kernel-0.3.8-beta` 的
  `META-INF/jars/forbric-kernel-runtime.jar` 取出注入 —— 该 release 与 HEAD 的 `src/runtime` **逐字节相同**
  （`git diff v0.3.8-beta-1.21.1 61b93a04 -- forbric-kernel/src/runtime` 为空；`src/main` 亦为空，HEAD 只在标签后多一个纯报告提交）。
- 全量单测：`./gradlew --offline test` = **2959 tests, 31 failed, 1020 skipped**；31 个失败**全部**是"没有 staged 游戏
  jar / 没有编译 game-side runtime"的环境失败（`TestFixtures.mergedBase()` 为 null、`build/classes/java/runtime/**` 缺席），
  与本次改动无关：把本修复 revert 后重跑其中的代表类（`ModsButtonRedirectorTest`/`SpawnerFinalizeInjectorTest`/
  `TransferTransactionHooksTest` = 16 个用例）**同样 16 失败**，`KernelRuntimeClassesTest` 亦同样失败（其点名
  `KernelEntityDataSerializers`/`KernelClientLifecycle` 是既有源码状态，本 diff 未引入）。本修复自己的
  `TransformerAnchorCensusTest`（REPAIRS/claims/transform 三处同序，6/6）与 `MergedBaseForgeRenderLayerKeyTest`（3/3）全过。
- 客户端运行在 load≈54–72 的高负载下进行（rule-3 timing-suspect），但**判定不看时间**，PASS 臂 49 s 到世界。

## 5. 证据清单（`evidence/`）

| 文件 | 内容 |
|---|---|
| `PREREGISTRATION.md`（上一级） | 运行前写定的读数判据（R1–R4） |
| `ForgeRenderLayerKeyProbe.java` / `forge-render-layer-key-probe-output.txt` | #1 真字节探针 + 输出（ALL CHECKS PASSED） |
| `RegistrySyncRouteProbe.java` / `registry-sync-route-probe-output.txt` | #4 路由并集探针 + 输出（ALL CHECKS PASSED） |
| `MergedBaseForgeRenderLayerKeyTest.java`（`src/test/…`） / `render-layer-key-test-output.txt` | 常驻形状单测（3/3） |
| `itemblockrendertypes-before.javap.txt` | 合并类 Forge writer / 读侧 / Forge 参考的 `javap -c` |
| `registrysynchronization-{merged,forge,neo}.javap.txt` | 三支 `RegistrySynchronization` 的 `javap -c` |
| `datapackregistrieshooks-{forge,neo}.javap.txt` | 两个钩子的 `javap -c`（并集 / caller 守卫 / addRegistryCodec） |
| `run-user12/` | PASS 臂：`results.jsonl`、`boot-deps.txt`、`frozen-kernel-sha256.txt`、`per-mod/run/…/console.log`、`screenshots/` |
| `run/` | 被丢弃的 crash 臂（`--corpus corpus`，indigo 崩）——为诚实一并留存 |

## 6. 提交

| 提交 | 覆盖 |
|---|---|
| `1013f775` | #1/A3：`ForbricMergedBaseCompatTransformer#rekeyTheForgeRenderLayerRegistration` + `MergedBaseForgeRenderLayerKeyTest` |
| `725ef198` | #1 证据与读数回填 + #4 的字节判定与"记录不修"结论 + 探针/证据（本报告） |
