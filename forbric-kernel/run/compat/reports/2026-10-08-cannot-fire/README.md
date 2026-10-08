# 左键"发射不了"：客户端链是完整的，cast 包死在服务端 PLAY 分发（Forge 的无 super override），而内核的修法被第二处缺陷挡在开关后面

**Verdict (EN).** Diagnosed and reproduced end-to-end; **not fired**. Nothing on the live client path is missing or
suppressed: the demo jar's `mc1211` mixin config really is registered, all **12** of its client mixins are applied
(Mixin's own `Mixing MouseHandlerMixin … into net.minecraft.client.MouseHandler` is in the run, and the exported
`MouseHandler.class` carries the injected `shootingStarDemo$whiteSpaceClick`), and the mod's payloads go through the
ordinary NeoForge **21.1** registration (`NetworkRegistry.setup() — 38 payload type(s) registered … NeoForge's own
included: yes`). What is broken is the **server side of the wire**: the merged
`ServerGamePacketListenerImpl.handleCustomPayload` is MinecraftForge's override, whose entire body is
`payload()` → `ForgeHooks.onCustomPayload(payload, connection)` → `POP` → `RETURN` — it never calls `super`, and
NeoForge's dispatcher lives on exactly that super (`ServerCommonPacketListenerImpl.handleCustomPayload` →
`NetworkRegistry.handleModdedPayload`). So the demo's `shooting_star_demo:cast` payload arrives, is offered to
MinecraftForge, is declined, and stops: **no exception, no log, no cast — in singleplayer too**, because the
integrated server takes the same path. Reproduced in-game on the user's 14 jars with the mod's own extension point
(`ShootingStarDemoClient` reflectively calls `<pkg>.devtest.DevAutoTest.register()`), which drives the exact method a
click drives (`ShootingStarDemoClient.onUse`, the `MagicItem.use` → `clientUseHook` target): at the default setting
`RESULT=CAST_DROPPED`; with the kernel's **existing but gated** repair `-Dforbric.playPayloadFallThrough=on` the same
payload **does** reach the server's super — and is then killed by a **second, independent defect**:
`fabric-networking-api-v1`'s mixin into that super assumes it is only ever reached from the **configuration** listener
and throws `IllegalStateException: Unknown addon`, disconnecting the player. The repair is therefore exact but **not
bounded on its own**, and the default stays OFF. The missing
`net.minecraftforge.client.event.RenderGuiEvent$Post` is **not** involved: it is named only by the jar's **dead**
`mc1201` (Forge-1.20.1) half, which the loader never registers.

**答案（中文）。** **已定位并在真机上端到端复现，但没有把它点着。** 客户端这条链一点都不缺：demo 的 `mc1211` mixin 配置
确实注册了，它的 **12 条客户端 mixin 全部装上**（运行里有 Mixin 自己的 `Mixing MouseHandlerMixin … into
net.minecraft.client.MouseHandler`，导出的 `MouseHandler.class` 里带着注入的 `shootingStarDemo$whiteSpaceClick`），
mod 的载荷走的是标准 NeoForge **21.1** 注册（`NetworkRegistry.setup() — 38 payload type(s) registered … NeoForge's own
included: yes`）。坏的是**线的服务端那一头**：合并基底里 `ServerGamePacketListenerImpl.handleCustomPayload` 是
MinecraftForge 的 override，整个方法体就是 `payload()` → `ForgeHooks.onCustomPayload(payload, connection)` → `POP`
→ `RETURN`，**从不调 `super`**，而 NeoForge 的分发恰恰住在那个超类上
（`ServerCommonPacketListenerImpl.handleCustomPayload` → `NetworkRegistry.handleModdedPayload`）。于是 demo 的
`shooting_star_demo:cast` 到了、被交给 MinecraftForge、被拒、然后停住：**没有异常、没有日志、没有施法 —— 单人存档也一样**
（集成服务器走同一条路）。在用户真实的 14 只 mod 上，用 mod 自己的扩展点（`ShootingStarDemoClient` 反射调用
`<pkg>.devtest.DevAutoTest.register()`）驱动**点击真正调用的那个方法**（`ShootingStarDemoClient.onUse`，即
`MagicItem.use` → `clientUseHook` 的目标）：默认设置下读数 `RESULT=CAST_DROPPED`；把内核**已有但被开关关掉**的修法
`-Dforbric.playPayloadFallThrough=on` 打开，同一个包**确实**走到了服务端超类 —— 然后被**第二处独立缺陷**打死：
`fabric-networking-api-v1` 混进那个超类的处理器假定"这个方法只会被 configuration 监听器调到"，于是抛
`IllegalStateException: Unknown addon` 并把玩家踢掉。所以这个修法是精确的，但**单独不封闭**，默认保持关闭。
缺 `net.minecraftforge.client.event.RenderGuiEvent$Post` **与本案无关**：只有 jar 里那只**死掉的** `mc1201`
（Forge 1.20.1）半边引用它，而那个半边加载器根本不注册。

---

## 0. 语境：这里的"左键"到底是哪一下

demo 自己写了操作说明（`assets/shooting_star_demo/lang/en_us.json`，逐字）：
`item.shooting_star_demo.magic_item.hint = "Right click: cast the selected skill · %s: skill menu · Sneak + right click: next skill"`，
`gui.shooting_star_demo.void.hint = "Aim with the crosshair · Click to choose · Right-click for details · %s or Esc to leave"`。
逐字节看：**发射 = 手持 Stellar Remote 右键**（`MagicItem.use` → `clientUseHook` → `ShootingStarDemoClient.onUse`），
**左键 = 在 WhiteSpace 轮盘里选技能**（`MouseHandlerMixin` → `WhiteSpace.click` → `choose` → `ClientSkills.select`）。
`CastSkillPayload` 在全 jar 里**只有一处构造点**：`ShootingStarDemoClient.onUse`。所以无论用户说的是哪一下，
"发射"这一条链只有这一条：`onUse` → `Network.toServer(new CastSkillPayload(idx))`。

本车道因此不去猜是哪个键，而是**驱动 `onUse` 本身**（§3）。

## 1. 复现（W7Harness，用户真实 14 只，钉住 0.3.7 内核）

装置：`w7/harness/sweep_client.py`，quick-play 进 `W7Client`，`--ready-ticks 200`，
`-Dforbric.compatibilityPolicy=strict`，JDK 21.0.7，三次运行各自一臂；集合与 sha 见 `evidence/mod-set.txt`。
判据先写在 `evidence/preregistration.md`（含事后 §Adjudication，判据原文未改）。逐字读数见 `evidence/arm-v.txt`。

**先说装置边界：输入动作无法脚本化。** `sweep_client.py` 只有 quick-play + 世界内驻留 + 截图 + 干净断开，
**没有**鼠标/键盘注入（无 `Robot`、无 `--click`、无输入回放）。所以"点一下"这个动作**不能由 W7Harness 行使**；
本车道据此把"点击之后会发生什么"搬到 §3 的离线装置（mod 自己的 dev 钩子），运行只证明"进世界、无崩溃、日志形状"。

| 读数 | 值 |
|---|---|
| `run` / `exit` / `world` / `frames` / `stopped` / `killed` | `PASS` / `0` / `true` / `1` / `true` / `false` |
| `strict` / `confirmed_required` / crash-report | `FALSE` / `0` / **0 份** |
| `mod` / `cause` / `catalog_failures` | `DEGRADED` / `mod-degraded` / `['shooting_star_demo']`（= arm C 既有形状，见 §5） |
| 主体自己的日志 | `The Stellar Remote answers to its wielder.` / `armed with 3 skills` |

一条预登记写错了，如实记：我在 C1 里写了 `catalog_failures=[]（arm C 既有形状）` —— 同一句自相矛盾，
arm C 的既有形状本来就是 `['shooting_star_demo']`。按字面该条被证伪，"不劣于既有形状"（C5）成立；判据不改写。
另一条：demo 的着色器预热挂在**标题屏**，quick-play 不过标题屏，所以本装置 0 条 `Compiled spell shader`
（用户手跑那次 14 条）；这是装置不可达，不是回归。

## 2. 不是它：mixin 装置、注册、以及那条 `DEGRADED`

1. **12 条 mc1211 客户端 mixin 全部装上**（判定这案子的关键）：`-Dmixin.debug.verbose=true` 让 Mixin 自己打印
   `Mixing <X>Mixin from shooting_star_demo.mc1211.mixins.json into <target>` 共 12 条，含
   `MouseHandlerMixin → net.minecraft.client.MouseHandler`；`-Dmixin.debug.export=true` 导出的
   `net/minecraft/client/MouseHandler.class` 里就是注入后的方法表：
   `handler$boe000$shooting_star_demo$shootingStarDemo$whiteSpaceClick` / `…whiteSpaceScroll`，
   外加 `MouseTurnMixin` 的 `modify$bof000$…whiteSpaceYaw/Pitch`。逐字见 `evidence/mixin-install.txt`。
   ⇒ **"左键点不着"不是 mixin 没装。**
2. **载荷注册正常**：mod 用 `RegisterPayloadHandlersEvent` + `PayloadRegistrar`
   （`CastSkillPayload` playToServer、`SpellFxPayload`/`CooldownPayload` playToClient），
   运行里 `NetworkRegistry.setup() — payload handlers registered; 38 payload type(s) registered {CONFIGURATION=11,
   PLAY=27}`。`PayloadInterop` 不在这条路上（它只做 Fabric↔Neo 的镜像与 `minecraft:register` 记账）。
3. **`RenderGuiEvent$Post` 不是原因，也不需要载体**（回答任务书里那一条条件分支）：全 jar 逐字节扫描，
   引用 `net.minecraftforge.client.event.RenderGuiEvent*` 的**只有一个类**：
   `dev/aek/shootingstardemo/mc1201/client/ShootingStarDemoClient.class` —— 那是 jar 的 **Forge-1.20.1（mc1201）半边**。
   加载器把这只 dual jar 判给 NEOFORGE（`[Forbric/MultiLoader] … declares 2 loaders — loading it as NEOFORGE only,
   suppressing [FORGE]`），构造的是 `dev.aek.shootingstardemo.mc1211.ShootingStarDemo`，
   注册的 mixin 配置是 `shooting_star_demo.mc1211.mixins.json`（MANIFEST 里那条 `mc1201.mixins.json` 从不注册）。
   **live 的 mc1211 半边一个 `net/minecraftforge/` 引用都没有**（逐类扫描 0 命中），它只引用
   `net.neoforged.neoforge.*`。所以 `AbiLinkAudit` 那条 `DEGRADED` 是**整包扫描扫到了死半边**造成的误报
   （它扫 jar 里全部 class，不按 loader 归属过滤），既不是缺口，也不需要走 `ForeignType`/carrier 惯例去承载 ——
   **该机制是否存在、是否 bounded 这一问，答案是不需要它**。证据：`evidence/dead-half.txt`。

## 3. 是它：服务端 PLAY 分发是死的（字节 + 真机 A/B）

**字节（`evidence/broken-link-bytes.txt`，`javap -c` 原文）**：合并基底
`patched-mc-merged-1.21.1.jar` 的 `ServerGamePacketListenerImpl.handleCustomPayload` 全长就这几条：

```
0  ALOAD 1 ; ServerboundCustomPayloadPacket.payload()
4  ALOAD 0 ; GETFIELD connection
8  INVOKESTATIC net/minecraftforge/common/ForgeHooks.onCustomPayload(payload, connection)Z
11 POP
12 RETURN
```

而 NeoForge 的分发在超类 `ServerCommonPacketListenerImpl.handleCustomPayload` 的尾部：
`… instanceof MinecraftRegisterPayload → NetworkRegistry.onMinecraftRegister; … CommonRegisterPayload →
NetworkRegistry.onCommonRegister; … NetworkRegistry.isModdedPayload(payload) → NetworkRegistry.handleModdedPayload(this, packet)`。
Forge 的 override 不调 `super`，所以这一整段在 PLAY 阶段**不可达**。

**离线**：把内核**出厂**的 `CommonNetworkInteropInjector` 跑在**真**合并类上（`evidence/payload-path.txt`）：
默认 `-Dforbric.playPayloadFallThrough=off` 时**输出与输入逐字节相同**（`105420 B`，sha `27ccfef8…`）——
修法确实没生效；`=on` 时 `+114 B`，恰好插入 `PayloadInterop.neoForgeWillHandle(payload)` 的门与
`invokespecial ServerCommonPacketListenerImpl.handleCustomPayload`。

**真机 A/B**（`evidence/probe-ab.txt`）：用 demo **自己**的 dev 钩子驱动 `onUse`（装置在 artifact 之外，源码
`evidence/instrument-DevAutoTest.java`）：
- `-Dforbric.playPayloadFallThrough=off`（默认）：`tick 87: called ShootingStarDemoClient.onUse` →
  `tick+12: ClientSkills.remaining(SS-01 · Railgun)=0` → **`RESULT=CAST_DROPPED`**。
- `=on`：`tick 79: called …onUse` → **0.03 s 后** Netty 线程
  `java.lang.IllegalStateException: Unknown addon` `@ ServerCommonPacketListenerImpl.handler$zhd000$fabric-networking-api-v1$handleCustomPayloadReceivedAsync(…:544)`
  ← `ServerCommonPacketListenerImpl.handleCustomPayload` ← **`ServerGamePacketListenerImpl.handleCustomPayload(…:1817)`**；
  玩家被踢（`ForbricKernel lost connection: Internal Exception: java.lang.IllegalStateException: Unknown addon`）。
  这一条栈**同时证明两件事**：载荷确实穿过了 Forge 的 override 走到超类（修法生效），以及挡住它的是 fabric 的混入。

## 4. 为什么这个修法单独不封闭：第二处缺陷（fabric 半边）

内核早就知道这件事，且**故意把开关留在 off**：`CommonNetworkInteropInjector.playFallThroughEnabled()`
默认 `"off"`，其 javadoc 写的就是本节复现的现象；引入开关的提交 `5b59c3a2` 说得更直白：
"fabric-api mixes into the SUPER that the fall-through reaches … it threw `IllegalStateException: Unknown addon` and
ended the connection … Until then the default is the old behaviour, because silently dropping a NeoForge mod's
upward packet is smaller than disconnecting the player."

**这次的复现在真机上把那条现象逐字重现了，并给出了它的字节原因**（`evidence/fabric-half.txt`）：
fabric-networking-api-v1 的 `net/fabricmc/fabric/mixin/networking/ServerCommonNetworkHandlerMixin`
把 `handleCustomPayloadReceivedAsync` 注入 `ServerCommonPacketListenerImpl`（即那个超类）的 HEAD：

```
getAddon() instanceof ServerConfigurationNetworkAddon  → handle(payload)
否则 → new IllegalStateException("Unknown addon"); athrow
```

在 configuration 监听器上 `getAddon()` 是 config addon，一切正常；在 **PLAY** 监听器上它是
`ServerPlayNetworkAddon`（同 jar 的 `ServerPlayNetworkHandlerMixin` 提供的 `getAddon()`），于是走 else 分支**抛异常**。
也就是说：fabric 的这条"服务端接收"从来没在这块基底上跑过（它被同一个 Forge override 遮住了），
fall-through 是它**第一次**被调到 —— 第一次就抛。这是与本案并列的第二处缺陷，不是本修法写错。

两个候选修法（**本车道不实施**，见 §6 决策）：
- **(a) 服务丢掉的那一半读者**：像 `MergedBaseMixinCompat`/`PostMixinFixups` 对待别的 guest mixin 那样，把这条
  `ServerCommonNetworkHandlerMixin` 的 else 分支（`new IllegalStateException("Unknown addon"); athrow`）改成"不处理"，
  留出 fall-through 走进 NeoForge 的体。它只在"从 PLAY 到达超类"这条新路上有影响
  （从 configuration 到达时走的是 if 分支），但它是**改第三方 mod 的 mixin 语义**，要按内核已有的 guest-mixin 修复惯例
  单独立案、单独取证。
- **(b) 不借道 super**：fall-through 直接调 NeoForge 的分发尾（`NetworkRegistry.handleModdedPayload`），
  绕开超类里别人的混入。代价是复制 NeoForge 的分发契约，并且会跳过超类体里那几个 NeoForge 自己的
  special payload（`MinecraftRegisterPayload` 等）—— 那几个在 PLAY 阶段同样是被别的 override 遮住的，要不要一起接回来是另一个问题。

两条都要**各自**的预登记与证据；而且它们与"是否把默认打开"是**两个**决定，不能合成一个提交。

## 5. 明写的空白、损失、与"不是本车道"的部分

- **没有观测到 `RESULT=CAST_ACCEPTED`**：两个臂里服务端都没走到 `Casting.request`，因为 (b) 那一半在 fabric 抛异常时
  根本还没轮上。要读到"服务端真的施法了"，必须先有 §4 的 fabric 修复；本车道到此为止，如实记，不假装。
- **`probe-ON` 臂没有 `results.jsonl` 行**：载荷到服务端后玩家立刻被踢，harness 的 `clean disconnect` 永远等不到，
  JVM 会一直停在世界断开屏；我在 **5m24s 主动 stop**（不占机器、不改任何一行读数）。该臂的证据只有 console，行不存在。
- **`strict=FALSE` 是 arm C 的既有形状**，不是本车道引入：`mod=DEGRADED cause=mod-degraded`，
  与 `2026-10-06-shooting-star` / `-disconnect` / `0.3.7` 发布 gate 的 arm C 逐字段相同。
- **`AbiLinkAudit` 的误报本身没有修**：它按 jar 全量 class 判 ABI，不看仲裁归属，所以双包 jar 的**死半边**会把它标成
  `DEGRADED`。本报告只是把它的真因写清楚；要不要让它只看归属半边是**另一个**决定。
- **反射式 StarBridge 安装是好的**（任务书给的另一条离线替身，用来对照"不是这条）：addition jar 的
  `StarBridge.install()` 在真机上成功 —— `[modloading-sync-worker/INFO]: SS-05 Halley joined the Stellar Remote as
  skill #4 (key slot 3)`，本车道 arm V（`console.log:1970`）与用户自己那两次（`latest.log:1056/4192`）逐字相同；
  安装成功即 `problem()` 为空，登录时的红字提示不会出现。所以"第 4 个技能"这条反射桥不是断点，断点只在 §3 那条线上。
- **两名片**：`SSFireMixin`（逐 mixin 装/丢状态）与 `SSFirePayload`（cast 网络路径）的结论与本车道一致，
  本车道对第 (2) 条（分发死在服务端）做了**独立复算**：合体字节、出厂注入器的离线跑、以及真机 A/B。
  `SSFireLog` 的取证（点击时刻全静默）与 §3 一致。
- **没有在安装实例上再启动一次**：本车道的所有运行都用 stage + `--kernel-jar` 钉住 0.3.7 gate 那只内核
  （sha `48df3d2b…`），与安装实例装的逐字节相同（0.3.7 发布报告已验）。
- 机器负载：三臂都在 300%+ CPU / load 3.6–6.7 下跑（rule 3 的提示原样带在行里），本车道**没有任何时限判据**。

## 6. 决策与提交（一次提交一个决定）

1. **D1 —"点不着"不在客户端**：12 条 mixin 装上、载荷注册正常、`RenderGuiEvent$Post` 只被死半边引用。
   决定：`DEGRADED` 行照旧（不劣化），不改 mixin 装置，不为 `RenderGuiEvent$Post` 做任何载体。
2. **D2 — 断点在服务端 PLAY 分发**：Forge 的无 `super` override 让 NeoForge 的分发不可达；
   内核**已有**精确修法且**默认关**。决定：记字节证据 + 真机 A/B，不动开关。
3. **D3 — 打开开关不封闭**：真机复现了 fabric 那条 `Unknown addon` 断线并给出字节原因；
   修法要有第二个决定（§4 的 (a)/(b)）。决定：**本车道不实施**，把两处候选修法与它们的预登记要求写清楚。
4. **D4 — 装置留档**：`DevAutoTest` 探针（mod 自己的 dev 钩子）是"点击→发射"的忠实驱动，源码与 sha 入 `evidence/`。

## 7. 证据清单（`evidence/`）

| 文件 | 内容 |
|---|---|
| `preregistration.md` | **跑之前**写下的集合、装置、判据、证伪条件 + 事后 §Adjudication（判据原文未改） |
| `mod-set.txt` | 14 只 jar 的 sha256、内核 sha、两份合并基底 sha 与"内容相同"的核对、装置参数、三臂 |
| `arm-v.txt` | arm V 的 `results.jsonl` 逐字、boot gate 行、主体自己的日志、load-report 行、AbiAudit 行、crash 数 |
| `mixin-install.txt` | 12 条 `Mixing` 逐字、导出 `MouseHandler.class` 的 `javap -p` 与方法表、`MixinGate` 的 `getMixins()` |
| `broken-link-bytes.txt` | 合并基底两只 `handleCustomPayload` 的 `javap -c` 原文、合并 jar sha |
| `payload-path.txt` | 出厂注入器在真合并类上 off/on 两次跑的原始输出（长度、sha、逐条 INSN）、开关源码 |
| `probe-ab.txt` | 两臂 console 里的探针行、kick 栈、`falls through` 行 |
| `fabric-half.txt` | `ServerCommonNetworkHandlerMixin.handleCustomPayloadReceivedAsync` 的 `javap -c`（含 `Unknown addon` 的 athrow）、注解、同 jar 的 PLAY mixin |
| `dead-half.txt` | 引用 `RenderGuiEvent*` 的唯一类、两只入口点/两份 mixin 配置、仲裁行、构造行、mc1211 的注册与分发字节 |
| `instrument-DevAutoTest.java` / `instrument-run_with_probe.py` / `instrument-FallThroughProbe.java` | 三件装置（在 artifact 之外）的源码 |
