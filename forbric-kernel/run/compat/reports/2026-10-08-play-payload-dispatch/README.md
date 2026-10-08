# 服务端 PLAY 载荷到达它的 NeoForge 处理器且不踢人 —— 实施 cannot-fire §4 的候选 (b)

**Verdict (EN).** Implemented and verified end-to-end. Of the two candidates `2026-10-08-cannot-fire` §4 named,
this lane takes **(b) — do not borrow the super**: the play-phase fall-through in
`CommonNetworkInteropInjector.letNeoForgePayloadsThrough` no longer calls
`ServerCommonPacketListenerImpl.handleCustomPayload`; it calls NeoForge's dispatch tail
`NetworkRegistry.handleModdedPayload(ServerCommonPacketListener, ServerboundCustomPayloadPacket)` directly. That is
the method the merged super body itself reaches as its last statement, and the existing gate
(`PayloadInterop.neoForgeWillHandle`, which admits only payload ids with a **registered handler**) already pins the
population to exactly the payloads that tail dispatches — the five payloads the super body handles *before* its tail
(`MinecraftRegister/UnregisterPayload`, `CommonVersion/RegisterPayload`) are codec-only entries of
`NetworkRegistry.BUILTIN_PAYLOADS` and can never be in `PAYLOAD_REGISTRATIONS`. Fabric's invariant — that its shared
handler is only ever entered from a **configuration** listener — is therefore kept instead of broken, and Fabric's own
PLAY receive (its own mixin, at the HEAD of the PLAY override) is not touched at all. The lane also opens the switch's
**default to ON**, because the only reason it was off was the disconnect that (b) removes; `=off` stays as the
falsifier. Measured on the user's real 14 jars: **`RESULT=CAST_ACCEPTED`** (cooldown 1197/1189 ticks came back over
the mod's own payload), **`Unknown addon` = 0**, **no kick**, clean disconnect, and the `shooting_star_demo` row is
field-for-field the 0.3.7 baseline's — while `=off` reproduces `RESULT=CAST_DROPPED` and the unpatched kernel with
`=on` reproduces the `IllegalStateException: Unknown addon` kick. Fabric's own receive is *observed* running in the
same arm: `ServerPlayNetworkAddon <- shooting_star_demo:cast` (declined) and `ServerPlayNetworkAddon <-
minecraft:register`.

**答案（中文）。** **已实施并端到端验证。** 取 §4 的两个候选中的 **(b) —— 不借道超类**：PLAY 阶段的 fall-through 不再
调 `ServerCommonPacketListenerImpl.handleCustomPayload`，而是直接调 NeoForge 的分发尾
`NetworkRegistry.handleModdedPayload(ServerCommonPacketListener, ServerboundCustomPayloadPacket)`（就是合并超类体
最后一条语句到达的那个方法）。既有的门（`PayloadInterop.neoForgeWillHandle`，只放行**有注册 handler** 的 payload id）
已经把种群钉死在这条尾恰好要分发的那些包上——超类体在尾部之前处理的那五个
（`MinecraftRegister/UnregisterPayload`、`CommonVersion/RegisterPayload`）是
`NetworkRegistry.BUILTIN_PAYLOADS` 里只有 codec 的条目，永远进不了 `PAYLOAD_REGISTRATIONS`。于是 fabric 的不变量
（它的共享处理器只被 configuration 监听器进入）被保住而不是被打破，fabric 自己的 PLAY receive（它自己的 mixin，
在 PLAY override 的 HEAD）完全没被碰。同时把开关**默认打开**：它之所以关着，唯一的原因就是 (b) 移除的那次断线；
`=off` 留作证伪臂。在用户真实的 14 只 jar 上读数：**`RESULT=CAST_ACCEPTED`**（冷却 1197/1189 tick 经由 mod 自己的
载荷回来）、**`Unknown addon` = 0**、**没被踢**、干净断开、`shooting_star_demo` 行与 0.3.7 基线逐字段相同；而
`=off` 复现 `RESULT=CAST_DROPPED`，未打此修复的内核配 `=on` 复现 `IllegalStateException: Unknown addon` 断线。
同一臂里**直接观测到** fabric 自己的 receive 在跑：`ServerPlayNetworkAddon <- shooting_star_demo:cast`（拒收）与
`ServerPlayNetworkAddon <- minecraft:register`。

---

## 0. 实施（两次提交，两个决定）

`cannot-fire` §6/D3 写明"实施一个候选"与"是否打开默认"是**两个**决定，不能合成一个提交。这里照办：

| 提交 | 内容 |
|---|---|
| `dbd6a859` | **机制**：fall-through 从 `invokespecial ServerCommonPacketListenerImpl.handleCustomPayload` 改为 `invokestatic NetworkRegistry.handleModdedPayload`；形状断言从"必须调超类"改为"必须不调超类、必须直接调分发尾"，并把该调用的 owner/name/descriptor 钉成合并超类自己用的那条。默认仍 OFF。 |
| 本报告提交 | **默认**：`playFallThroughEnabled()` 默认 `on`（`=off` 仍可关），加"默认开"的测试；报告与证据入档。 |

改动只在一个类里：`CommonNetworkInteropInjector.letNeoForgePayloadsThrough`（splice 两条指令的 target）与
`playFallThroughEnabled`（默认值）。门、`PayloadInterop`、fabric-api、任何 guest mixin 都没有被改。

**被验证的产物不是工作树里的 jar**：它是发布过的 0.3.7-beta 内核，只把里面同一个类换成这次的重编译。
`git diff --stat 4f4935b9 HEAD -- forbric-kernel/src/main` **只有本车这一个文件**（62+/22-）；两份 boot 半边逐条目
比 sha，差的类也只有 `net/forbric/kernel/transform/CommonNetworkInteropInjector.class` 一个，
所以 FIX 与 OLD-ON 两臂**只差这一个类**。产物 sha 见 `evidence/mod-set.txt`。

## 1. 预登记与它的裁决

判据在跑之前写在 `evidence/preregistration.md`（原文未改），裁决追加在同一文件末尾。汇总：

| 判据 | 读数 | 判定 |
|---|---|---|
| **R1 cast accepted** | `RESULT=CAST_ACCEPTED`，冷却 1197 tick（FIX）、1189 tick（FIX-DEBUG） | **达成** |
| **R2 no kick** | `lost connection` = 0；`ClientSmoke] clean disconnect observed` 在；行 `run=PASS exit=0 world=true stopped=true killed=false` | **达成** |
| **R3 `Unknown addon` = 0** | 0 行（FIX、FIX-DEBUG、OFF 三臂都是 0） | **达成** |
| **R4 standard row green** | 与 0.3.7 gate14 基线逐字段相同（`DEGRADED` / `['shooting_star_demo']` / `mod-degraded` / `loaded=false / strict=false`），只有 cpu/load/seconds 变 | **达成** |
| **F1 repair off 复现原缺陷** | `=off`：`RESULT=CAST_DROPPED`，`remaining` 全程 0，0 条 fall-through 生效行 | **达成（证伪臂如实复现）** |
| **F2 不装修复则复现断线** | release 内核 + `=on`：3 行 `Unknown addon` + `ForbricKernel lost connection: Internal Exception: … Unknown addon` + `DisconnectedScreen`；行 `STALL frames=0` | **达成** |
| **F3 fabric 自己的 PLAY receive 仍在工作** | 见 §5：PLAY addon 实际收到了 `shooting_star_demo:cast`（拒收）与 `minecraft:register`（处理） | **达成（功能读数，不是推断）** |

## 2. arm FIX（主臂）

`python3 /private/tmp/cannotfire/probe/run_with_probe.py … --only shooting_star_demo`，内核 = FIX jar，**不加**
`-Dforbric.playPayloadFallThrough`（走新默认）。

```
[CANNOTFIRE/PROBE] tick 72: called ShootingStarDemoClient.onUse — the cast the click makes (… remaining before = 0)
[CANNOTFIRE/PROBE] tick+12: ClientSkills.remaining(SS-01 · Railgun)=1197
[CANNOTFIRE/PROBE] RESULT=CAST_ACCEPTED — the server took the cast; cooldown 1197 ticks came back over the mod's own payload
…
[Forbric/ClientSmoke] clean disconnect observed; stopping client
[client-sweep] ----   PASS     world=True frames=1 mod=DEGRADED   36s cause=mod-degraded
```

mod 自己的日志（`shooting_star.log`）也齐：`The Stellar Remote answers to its wielder.` / `armed with 3 skills` /
`Compiled spell shader …`（这一臂过了着色器预热，`cannot-fire` 的 arm V 因为 quick-play 不过标题屏而是 0 条）。
逐字读数：`evidence/arm-fix.txt`。

## 3. 证伪臂

- **F1 `=off`**（同 jar）：`RESULT=CAST_DROPPED`，`remaining` 始终 0，0 条 fall-through 生效日志，`Unknown addon` 0。
  `evidence/arm-off.txt`。
- **F2 release 内核 + `=on`**（即 `cannot-fire` §3 的 probe-ON 臂，这里重跑，好让 before/after 只差一个类）：

```
java.lang.IllegalStateException: Unknown addon
    at …ServerCommonPacketListenerImpl.handler$zhd000$fabric-networking-api-v1$handleCustomPayloadReceivedAsync(…:544)
    at …ServerCommonPacketListenerImpl.handleCustomPayload(…)
ForbricKernel lost connection: Internal Exception: java.lang.IllegalStateException: Unknown addon
Client disconnected with reason: Internal Exception: java.lang.IllegalStateException: Unknown addon
```

  该臂永远等不到 clean disconnect（行 `STALL`、`frames=0`、`killed=true`），与 `cannot-fire` 记录的"probe-ON 无
  results 行"逐字一致——只不过那次是人工 stop，这次是 harness 到预算后自己收掉。`evidence/arm-old-on.txt`。

## 4. 行对比（R4）

FIX / OFF / OLD-ON / 0.3.7 gate14 基线逐字段并排：`evidence/row-compare.txt`。FIX 与基线在每一个判定字段上相同
（`run/exit/stopped/killed/world/frames/mod/status_source/confirmed_required/catalog_failures/loaded/strict/cause/
load_report`），差的只有 `cpu_busy_pct/load_1m/seconds`——三臂都带 rule 3 的"负载偏高"标记（300%+ CPU）。

## 5. fabric 自己的 PLAY 路径（F3）

这一臂开 `-Dforbric.debug=true` 与 `-Dmixin.debug.export=true`（两者都只加输出/导出，不改判定）。三条读数：

1. **fabric 的 PLAY receive 真的跑了**（`PayloadInterop.probe`，从 `handleFabricChannelRegistrationAddon` 里打，
   即 fabric addon 自己的体内）：
   ```
   addon ServerPlayNetworkAddon <- minecraft:register [MinecraftRegisterPayload]        (处理)
   addon ServerPlayNetworkAddon <- shooting_star_demo:cast [CastSkillPayload]           (拒收)
   ```
   第一条是 fabric 自己的 PLAY 通道登记收发，第二条就是本次要送达的 cast——它**先被 fabric 看到、且被 fabric 拒收**，
   然后才轮到 fall-through。这正是候选 (b) 想保住的东西：fall-through 只在 fabric 拒绝之后运行。
2. **门在 cast 上点火**：`neo owns shooting_star_demo:cast — handing the play payload to its dispatcher`。
3. **运行期字节**（Mixin 导出的 `ServerGamePacketListenerImpl.class`，已是 post-mixin + post-injector）：
   ```
    0: … CallbackInfo("handleCustomPayload", cancellable)
   15: invokespecial handler$zhg001$fabric-networking-api-v1$handleCustomPayloadReceivedAsync(packet, ci)
   22: ifeq 26   ; 26 = 未被 fabric 取消
   25: return
   34: invokestatic ForgeHooks.onCustomPayload(payload, connection)Z
   37: ifne 55   ; 55 = Forge 收下了
   44: invokestatic PayloadInterop.neoForgeWillHandle(payload)Z
   47: ifeq 55   ; 55 = NeoForge 不认这个包
   52: invokestatic NetworkRegistry.handleModdedPayload(this, packet)
   55: return
   ```
   没有 `invokespecial ServerCommonPacketListenerImpl.handleCustomPayload`；fabric 的 PLAY mixin 仍在、其
   handler 仍调 `ServerPlayNetworkAddon.handle`。`evidence/arm-fix-debug-fabric.txt`（addon 读数）与
   `evidence/arm-fix-debug.txt`（字节）。

## 6. 机制在字节上的两面

- **离线**（`DispatchProbe`，跑在**真**合并类 `ServerGamePacketListenerImpl` 上，`evidence/payload-path.txt`）：
  `off` → 与输入逐字节相同（12 条 INSN，sha `27ccfef8…`，与 `cannot-fire/evidence/payload-path.txt` 的 off 读数
  同值）；`on` → 20 条 INSN，`ForgeHooks.onCustomPayload` → `PayloadInterop.neoForgeWillHandle` →
  `invokestatic NetworkRegistry.handleModdedPayload(ServerCommonPacketListener, ServerboundCustomPayloadPacket)V`，
  **没有** `invokespecial …handleCustomPayload`，且该 descriptor 逐字等于合并超类体自己调用的那一条；
  `BasicVerifier` 通过。
- **运行期**：见 §5 第 3 条。

## 7. 明写的空白与损失

- **超类体的五条前置分支在 PLAY 阶段仍然不可达。** 这不是本车引入的：它们在那道门里不可能出现（见 §0），而且
  在**修复前**它们同样从未在 PLAY 跑过（载荷被 Forge 的 `POP` 丢掉）。要不要把 `MinecraftRegisterPayload` 等
  在 PLAY 阶段也接回来，是 `cannot-fire` §4(b) 已经点名的**另一个问题**，本车不动它。功能上，PLAY 的通道登记由
  fabric 的 addon（`ServerPlayNetworkAddon <- minecraft:register`）与 NeoForge 的 `onMinecraftRegister`（它在
  `ServerCommonPacketListenerImpl.handleCustomPayload` 的第一分支，由**配置**监听器正常到达）各走各的，本车没有
  观察到一个因此丢失的功能。
- **`CompatProtocolTest::protocolNamesEveryShippedScript` 在本机是红的，且与本车无关。** 它要求 `run/compat` 下每个
  `.sh`/`.py` 都在 `PROTOCOL.md` 里有记录；它点名的第一个未记录文件是**别的车道**的
  `reports/2026-10-08-release-0.3.7-beta/evidence/sampler.py`。本车因此**不**把 `.py` 驱动复制进报告目录
  （只放 `.java` 探针 + 绝对路径/sha 引用），以免再加一条未记录项。
- **`CommonNetworkInteropInjectorTest` 的"真字节"断言在本机被 skip**：它钉的合并基底是 26.2
  （`patched-mc-merged-26.2.jar`），本机只有 1.21.1。等价的形状检查由 §6 的离线探针在**真 1.21.1 合并类**上
  做了，并在 §5 的运行期字节里被再次看到。全量测试的影响见 §8。
- **探针仍靠 mod 自己的 dev 钩子驱动**：没有任何鼠标/键盘注入，"点一下"这个动作仍不能由 harness 行使——这与
  `cannot-fire` §1 的装置边界相同，本车沿用。
- **`-Dmixin.debug.export=true`/`-Dforbric.debug=true` 只加输出与导出**，不改判定；FIX-DEBUG 一臂的 `run=PASS`
  与 FIX 相同，`Unknown addon` 也是 0。

## 8. 决定与提交

1. **D1 —— 取候选 (b)，不借道超类。** 理由：它不触碰任何 guest mixin 的语义（(a) 要按 guest-mixin 修复惯例改
   fabric 的 `ServerCommonNetworkHandlerMixin`），且门已经把种群钉成"有注册 handler 的 payload"，直接调尾恰好就是
   那个方法在那里的动作。
2. **D2 —— 默认打开。** 关着的唯一理由是那次断线，而 (b) 移除的正是它；不打开的话"mod 的服务端 PLAY 载荷到达它的
   NeoForge 处理器"对真实用户仍然不成立。`=off` 保留为证伪臂。按 `cannot-fire` D3 的要求，它是**独立**的一次提交。
3. **D3 —— fabric 半边不"修"。** 不需要改 `ServerCommonNetworkHandlerMixin`：把共享体留给 configuration
   监听器（它在本基底上本来就只被 configuration 监听器进入）就恢复了它的前提。
4. **D4 —— 报告与证据入档**（本目录），预登记原文不改，裁决追加。

**测试影响**：`./gradlew --offline test` 在本机 29 红 / 924 skip。在 HEAD 的干净 worktree 上跑同一套是 **31 红 /
同集合**（多出的两条是 worktree 缺 `../forbric-loader` 兄弟目录造成的另一类环境失败）；本车的失败集合是基线的
**子集**，没有一条失败可归因于本车。`CommonNetworkInteropInjectorTest` 12→13 条、0 失败、7 skip（skip 的是上面
说的 26.2 真字节断言）。

## 9. 证据清单（`evidence/`）

| 文件 | 内容 |
|---|---|
| `preregistration.md` | **跑之前**写下的形状、装置、判据、证伪条件 + 事后 §Adjudication（判据原文未改） |
| `mod-set.txt` | 14 只 jar 的 sha256、FIX/基线内核 sha 与"只差一个类"的来源、合并基底与 runtime sha、装置参数、四臂 |
| `payload-path.txt` | `DispatchProbe` 在**真**合并类上的 off/on 原文（长度、sha、逐条 INSN、`BasicVerifier`、超类自己的尾部调用） |
| `dispatch-probe.java` | 上面那件离线探针的源码 |
| `arm-fix.txt` | arm FIX：探针逐字、clean disconnect、fall-through 生效行、kick/`Unknown addon` 计数、`results.jsonl` 行 |
| `arm-off.txt` | F1：`RESULT=CAST_DROPPED`、0 条生效行、行 |
| `arm-old-on.txt` | F2：`Unknown addon` 栈、kick、`STALL` 行 |
| `arm-fix-debug-fabric.txt` | F3：fabric 各 addon 实际收到的 (addon ← payload) 计数，PLAY 那几条在最前 |
| `arm-fix-debug.txt` | FIX-DEBUG：`RESULT`、`neo owns` 行、运行期 `handleCustomPayload` 与 fabric PLAY handler 的 `javap -c` |
| `row-compare.txt` | FIX / OFF / OLD-ON / 0.3.7 gate14 基线逐字段并排 |
| `instrument-DevAutoTest.java` | 装置（mod 自己的 dev 钩子）源码；jar 见 `mod-set.txt` |
| `mixin-half.txt` | 既有合并基底两只 `handleCustomPayload` 与 fabric `ServerCommonNetworkHandlerMixin` 的 `javap -c`（承接 `cannot-fire/evidence/fabric-half.txt`，供本报告自洽读） |
