# 预登记 —— 2026-10-08 `cannot-fire` 车道（左键点不着：装置与判据，先写后跑）

## 0. 问题

用户语境：Shooting Star demo 装得上、进得了世界（`shooting_star.log` 里有 "The Stellar Remote answers
to its wielder." / "armed with 3 skills" / 14 条 "Compiled spell shader"），但**点击不触发施法**（左键"发射不了"）。
三条只读切片（`SSFireMixin` / `SSFirePayload` / `SSFireLog`）先取证；本车道负责**复现 + 定位 + 定性**。

## 1. 被测量的形状（用户真实 14 只，逐字节）

- 集合：`/private/tmp/rel037/corpus14`（subject `the-shooting-star-demo-1.3.1-neoforge.jar`，closure = 12 只 W7 冻结包
  + `shooting_star_addition-1.1.0.jar`；两只 SS jar 与用户实例逐字节相同：demo `8b972bce…`、addition `9b2ce1b1…`）。
- 内核：已发布 `0.3.7-beta` 客户端 boot jar，**钉住** `--kernel-jar`
  `/private/tmp/rel037/artifacts/release-kernel.jar`，sha256
  `48df3d2b90dfec3926fbfcee92dc6bd8a441760f27560fd7d168169d81e2fa97`（= 0.3.7 gate artifact，未重建）。
- 装置：`W7Harness` 客户端面 `w7/harness/sweep_client.py`，quick-play 进 `W7Client`，`--ready-ticks 200`，
  `-Dforbric.compatibilityPolicy=strict`、`-Dforbric.mixinFit=default`，`W7_JAVA` 钉 JDK 21.0.7，
  stage `stubtable/stage`，mc 根 `stubtable/mc`，热 remap 缓存 `/private/tmp/rel037/remap`，一次一臂。

## 2. 装置的两个已知边界（先写在明面上）

1. **输入无法脚本化**：`sweep_client.py` 只有 quick-play + 世界内驻留 200 tick + 截图 + 干净断开；
   没有鼠标/键盘注入（无 `Robot`、无 `--click`、无输入回放）。所以"点击"这一动作**不能由 W7Harness 行使**。
   本车道据此把点击路径的证据放在**离线**（Mixin 是否装、payload 是否注册），运行只证明"进世界、无崩溃、日志形状"。
2. **插装在 artifact 之外**：为了看到 Mixin 是否真的织入，本臂额外开
   `-Dmixin.debug.verbose=true`（Mixin 自己的 `Mixing X from Y into Z` INFO 行）
   与 `-Dmixin.debug.export=true`（把改写后的目标类写进 `<rundir>/.mixin.out/class/…`）。
   这两个开关**只改日志与导出**，不改判定语义；`MouseHandler` 的导出字节是"左键回调在不在"的直接证据。

## 3. 判据（跑之前写下，只可"达成 / 被证伪"）

- **C1 boot gate**：`run=PASS`、`exit=0`、`world=true`、`frames>=1`、`stopped=true`、`killed=false`、
  `confirmed_required=0`、`catalog_failures=[]`（arm C 既有形状）、0 份 crash-report。
- **C2 主体自己的日志**：`shooting_star.log` 至少含 `The Stellar Remote answers to its wielder.` 与
  `The Shooting Star demo is armed with 3 skills`（skills 面）；若运行到达着色器预热，还应有 `Compiled spell shader` /
  `Warmed N spell shaders`（shaders 面）。
- **C3 Mixin 装置正面**：console 里出现 12 条
  `Mixing <mixin> from shooting_star_demo.mc1211.mixins.json into <target>`，其中必须有一条把
  `MouseHandlerMixin` 织进 `net.minecraft.client.MouseHandler`。**这是"左键点不着"是否由"mixin 没装"造成的判定。**
- **C4 导出字节**：`<rundir>/.mixin.out/class/net/minecraft/client/MouseHandler.class` 存在，且其常量池/方法表里有
  `shootingStarDemo$whiteSpaceClick`。
- **C5 加载报告**：`shooting_star_demo` 那一行**不劣于**既有形状（DEGRADED / `RenderGuiEvent$Post` /
  `Addiction 说需要它`）；即"不变或更好"。
- **F1（证伪条件）**：若 C3 的 12 条一行都不出现（或只在 `<target>` 处报 `not found`/`suppressed`），则
  "mixin 没装"成立 —— 左键没反应由装置侧造成；若 C3 全中且 C4 命中，则装置侧清白，问题在别处（payload / 菜单状态）。

## 4. 事后 §Adjudication

（跑完在此追加；判据原文不改，被证伪就写明被证伪。）

---

## §Adjudication（跑完追加；上文判据一字未改）

### 装置臂 V（`gate1`，44s，CPU 328% / load 3.64 —— 带 rule 3 的机器负载提示）

| 判据 | 读数 | 判定 |
|---|---|---|
| C1 `run/exit/world/frames/stopped/killed/confirmed_required` | `PASS / 0 / true / 1 / true / false / 0` | **达成** |
| C1 `catalog_failures=[]` | `['shooting_star_demo']` | **被证伪（预登记写错）** |
| C2 skills 两行 | `shooting_star.log` 逐字有 `The Stellar Remote answers to its wielder.` 与 `armed with 3 skills` | **达成** |
| C2 shaders 两条 | console 里 `Compiled spell shader` = **0**、`Warmed` = **0** | **本装置未达（非 mod 侧）** |
| C3 12 条 `Mixing` + `MouseHandlerMixin→MouseHandler` | 12/12 条齐，`Mixing MouseHandlerMixin … into net.minecraft.client.MouseHandler` 在 | **达成** |
| C4 导出 `MouseHandler.class` 含 `whiteSpaceClick` | 命中（见 `mixin-install.txt`） | **达成** |
| C5 Shooting Star 行不劣于既有形状 | 与 gate14/0.3.6 逐字同（DEGRADED / `RenderGuiEvent$Post` / Addition 依赖行） | **达成** |
| F1（mixin 未装） | 未触发 | 装置侧清白 |

**C1 的 `catalog_failures=[]` 是我写错的一条**：arm C 的**既有**形状本来就是
`catalog_failures=['shooting_star_demo']`（0.3.7 发布报告 arm C 那行逐字如此）。同一句括号里我又写了"（arm C 既有形状）"，
即那句预登记自身矛盾。按纪律记：**这一条按字面被证伪**，而"不劣于既有形状"（C5）成立。判据不改写。

**C2 的 shader 两条在本装置里不可达，不是回归**：demo 的着色器预热挂在标题屏
（`ShootingStarDemoClient` 的 `ScreenEvent$Init$Post`），而 W7Harness 是 `--quickPlaySingleplayer` 直接进世界、不经过标题屏；
用户手跑的那次有 14 条 `Compiled spell shader`（09:20:06–08），本臂 0 条。两条读数互不矛盾，如实记。

### 追加的探针两臂（**先写读数，后跑**；是 C3/C4 判定之后的装置扩展，不在 §3 预登记内）

装置：demo 自己的 dev 钩子 —— `ShootingStarDemoClient.init` 反射调用
`<pkg>.devtest.DevAutoTest.register()`（类名配方常量 `\u0001.devtest.DevAutoTest`），只吞 `ClassNotFoundException`。
探针类就落在这个名字上，经 `--libraryPath` 进游戏加载器（非 owned mod、不进语料），调用**点击真正调用的那个方法**
`ShootingStarDemoClient.onUse(Player, MagicItem)`（`MagicItem.use` → `clientUseHook` 就是它，BootstrapMethod #7），
再轮询 `ClientSkills.remaining(skill)`（只有 `CooldownPayload` 到达才会写）。

- **臂 probe-OFF**（`-Dforbric.playPayloadFallThrough=off`，默认）：预期 `onUse` 被调用、`remaining` 始终 0
  ⇒ `RESULT=CAST_DROPPED`。
- **臂 probe-ON**（`=on`）：预期 `onUse` 被调用后，载荷**到达服务端超类**（`ServerGamePacketListenerImpl:1817` → `super`），
  并按 `CommonNetworkInteropInjector` 的 javadoc **精确复现** fabric 那条 `IllegalStateException: Unknown addon` 断线；
  若能穿过 fabric，才应读到 `RESULT=CAST_ACCEPTED`。

读数：
- probe-OFF：`tick 87: called ShootingStarDemoClient.onUse …` → `tick+12: remaining=0` → **`RESULT=CAST_DROPPED`**（判据达成）。
- probe-ON：`tick 79: called …onUse` → **0.03 s 后** Netty 线程
  `java.lang.IllegalStateException: Unknown addon @ ServerCommonPacketListenerImpl.handler$zhd000$fabric-networking-api-v1$handleCustomPayloadReceivedAsync(:544)`
  ← `ServerCommonPacketListenerImpl.handleCustomPayload` ← **`ServerGamePacketListenerImpl.handleCustomPayload(:1817)`**，
  玩家被踢（`ForbricKernel lost connection: Internal Exception: java.lang.IllegalStateException: Unknown addon`）。
  ⇒ 载荷**确实**穿过了 Forge 的 override 走到超类（这正是修法生效），唯一挡住它的是 fabric 的混入（判据达成）。
  该臂永不干净断开，故没有 `results.jsonl` 行；5m24s 时**由我主动 stop**（不占机器，一行读数也不改），
  这一臂的证据是 console，行本身不存在，如实记。
