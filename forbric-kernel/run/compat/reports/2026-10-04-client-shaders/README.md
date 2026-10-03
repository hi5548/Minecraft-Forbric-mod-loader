# 客户端着色器双命名空间（`neoforge:neoforge:shaders/…`）：判决与落地（2026-10-04）

范围：这个缺陷——`RegisterShadersEvent` 期间 `ShaderInstance` 抛
`ResourceLocationException: Non [a-z0-9/._-] character in path of location: neoforge:neoforge:shaders/core/…`，
客户端随即 `Caught error loading resourcepacks, removing all selected resourcepacks`，停在标题界面。

工作树：`实验/forbric/Minecraft-Forbric-mod-loader`（`main`，修复提交 **`d7184178`**，树干净）。
验证运行：`W7Harness` 在 `w7/reports/2026-10-04-client-shaders/`（fabric-api 单装，jdk-21，
`compatibility_policy=continue`，frozen kernel sha256 `12f1bf9d…`，构建自 `eb8aff51`——
与本修复的差别只有 Javadoc 一个措辞，见 §5）。

---

## 1. 判决：哪一侧是错的

题目给的两个候选（合并基底里的 vanilla reader  vs  NeoForge 的着色器数据/补丁）**都不是**。逐个从字节排掉：

- **合并基底没有丢 NeoForge 的 reader 补丁。** 合并基底
  `p0/stage-1.21.1/merged-base/patched-mc-merged-1.21.1.jar` 的
  `net.minecraft.client.renderer.ShaderInstance` 与
  `p0/stage-1.21.1/neoforge-patched/patched-mc-neoforge-1.21.1.jar` 的同名类，**反编译源码逐行相同**；
  原始 class 的字节差只有常量池序号重编号（连带 `ldc`→`ldc_w` 宽度），没有一点语义差。两边的
  `getOrCreate` 都是 NeoForge 形态：`ResourceLocation.parse(vertex)` 取 namespace，再
  `ResourceLocation.fromNamespaceAndPath(ns, "shaders/core/" + loc.getPath() + type.getExtension())`。
  所以"恢复被丢的 reader 行为"这条路不存在——neoforge 的着色器数据要的就是这个 namespaced reader。
- **NeoForge 的数据是它自己的约定、也没有被改写。** `neoforge-runtime.jar` 的
  `assets/neoforge/shaders/core/rendertype_entity_unlit_translucent.json` 里
  `"vertex": "neoforge:rendertype_entity_unlit_translucent"`（`"fragment"` 同）——这个 namespaced 形态正是
  上面那个 reader 要吃的；上游 `neoforge-21.1.252-universal.jar` 同值。
- **实际错的是 guest `fabric-rendering-v1` 的 `ShaderProgramMixin`**：它编译时对着 **vanilla** 的
  `ShaderInstance`（vanilla 用 `ResourceLocation.withDefaultNamespace`，且把 vertex 当**裸路径**）。
  合并基底没有那两个 `withDefaultNamespace` 调用，所以它的两个 `@WrapOperation` 锚点落空被丢；**只剩一条
  `@ModifyVariable(at=STORE, ordinal=1)` 存活**，而这条恰好绑在合并基底
  `getOrCreate` 的 `String s`（刚由 reader 拼出的**裸路径** stage id）上，处理器把它改写成**完整 location**，
  reader 随后再贴一次 namespace → 翻倍。

**要命名的那一个类**：`net.fabricmc.fabric.mixin.client.rendering.shader.ShaderProgramMixin`
（guest）。它嵌进的目标类是 `net.minecraft.client.renderer.ShaderInstance`；NeoForge 补丁的源在
`patched-mc-neoforge-1.21.1.jar` 的同名类里，**没有被合并丢掉**，所以题目里"若补丁被丢，指出其源"这一支
不成立。

## 2. 逐字节证据（都在 `evidence/`）

**崩溃串**（旧运行 `2026-10-04-client-fabricapi-assets/per-mod/run/000-fabric-api__fabric/console.log`
L1327-1364，`evidence/before-console-shader-exception.txt`）：

```
at net.minecraft.client.renderer.ShaderInstance.<init>(ShaderInstance.java:157)
at net.neoforged.neoforge.client.ClientHooks$ClientEvents.registerShaders(ClientHooks.java:788)
Caused by: net.minecraft.ResourceLocationException: Non [a-z0-9/._-] character in path of location:
  neoforge:neoforge:shaders/core/rendertype_entity_unlit_translucent.vsh
	at ResourceLocation.fromNamespaceAndPath(ResourceLocation.java:52)
	at ShaderInstance.getOrCreate(ShaderInstance.java:186)
	at ShaderInstance.<init>(ShaderInstance.java:144)
```

**reader 两侧同一**（`evidence/merged-vs-neoforge-ShaderInstance.txt`）：
`ShaderInstance.class` sha256 merged `e7b0a73e…` / neoforge `672dd396…`，两者反编译源码 diff 为空；
空白归一后的 `javap -c -p` 行差只有 `ldc`↔`ldc_w`。

**guest 处理器**（remapped guest
`net/fabricmc/fabric/mixin/client/rendering/shader/ShaderProgramMixin.class`，sha256 `a4c87349…`）：
`modifyStageId(Ljava/lang/String;ResourceProvider;Program$Type;Ljava/lang/String;)Ljava/lang/String;`
在 `name` 含 `:` 时返回 `FabricShaderProgram.rewriteAsId(id,name).toString()`——完整 location。

**两份 Reader 的对齐点**：该 mixin 的另外两个锚点是
`@At(INVOKE) ResourceLocation.withDefaultNamespace in ShaderInstance.<init>` 与 `… in getOrCreate`
（旧运行 L233 逐字），合并基底没有这两个调用，所以只有上面那一条注入器存活（预检 `4/6`）。

**崩溃复算**（用合并基底自己的 `ResourceLocation`，`evidence/location-before-after.txt`）：
id=`shaders/core/rendertype_entity_unlit_translucent.vsh`，name=`neoforge:rendertype_entity_unlit_translucent`

```
修复前 handler → neoforge:shaders/core/rendertype_entity_unlit_translucent.vsh
       location(fromNamespaceAndPath("neoforge", …)) THROWS:
       ResourceLocationException: … location: neoforge:neoforge:shaders/core/rendertype_entity_unlit_translucent.vsh
修复后 handler → shaders/core/rendertype_entity_unlit_translucent.vsh
       location = neoforge:shaders/core/rendertype_entity_unlit_translucent.vsh   (合法)
```

## 3. 改法（既有机制，未新增机制）

`net.forbric.kernel.mixin.FabricClientMixinAnchors`（既有的 Fabric 客户端锚点适配器，已有
`removal()`/`render()`/`screenExtract()` 三个分支）新增 `shaderStage()`：对
`…/client/rendering/shader/ShaderProgramMixin` 只把 `modifyStageId` 里那一条
`ResourceLocation.toString()Ljava/lang/String;` 改成 `ResourceLocation.getPath()Ljava/lang/String;`——
把交给合并 reader 的产物从"完整 location"归一到"**路径**"，namespace 由 reader 自己贴。这正是"在该边界
归一位置"：注入器与注解原样保留，所以

- mixin 维持原 **PARTIAL（4/6）** 判定，`confirmed_required` 不因此新增；
- NeoForge reader 本就处理 `ns:` 前缀的 vertex，`CoreShaderRegistrationCallback` 语义不变；
- 一条 `-Dforbric.fabricClientAnchors=off` 可整体关掉，回到改动前。

**代价：零。** 这条注入器在合并基底上本来就是错位的（它的两个兄弟锚点已经落空），归一后它做的是恒等；
没有记录任何 CONFIRMED 损失，也没有把整支 mixin 钉掉（那才会把 `fabric-rendering-v1.mixins.json` 的
`required:true` 变成一条 required 损失）。

**适配器单测**（对 remapped guest 的真实 class 字节跑 `adapt`，`evidence/adapter-on-remapped-guest.txt`）：

```
adapt returned 1
handler modifyStageId(...):
  … FabricShaderProgram.rewriteAsId(String,String)ResourceLocation
  … net/minecraft/resources/ResourceLocation.getPath()Ljava/lang/String;     <- 原 toString
annotation: @ModifyVariable 仍在
```

## 4. 验证状态（`W7Harness` 读数，逐字）

运行：`w7/reports/2026-10-04-client-shaders/`，fabric-api 单装，jdk-21，warm cache，`continue`；
row：`run=FAIL exit=143 stopped=false world=false frames=0 mod=OK confirmed_required=0 seconds=339 contended=true`。

**题目要求的四项读数，逐条**（`evidence/after-console-markers.txt`）：

| # | 预登记读数 | 实读 | 结果 |
|---|---|---|---|
| 1 | `ShaderInstance`/`reloadShaders` 的 `ResourceLocationException` 不出现 | 计数 `ResourceLocationException=0`、`neoforge:neoforge=0`、`reloadShaders=0` | **达成** |
| 2 | `world=true` | `world=false` | 未达成（见下，非本车道） |
| 3 | `frames>=1` | `frames=0` | 未达成（同上） |
| 4 | `joined world via quick-play` 出现 | 无此行 | 未达成（同上） |

落地标记（证明是本修复生效，而非碰巧缺失，新运行 L317 逐字）：

```
[Forbric/Mixin] normalised net/fabricmc/fabric/mixin/client/rendering/shader/ShaderProgramMixin:modifyStageId
  — the stage id it hands ShaderInstance.getOrCreate is the path, not a full location; the merged NeoForge
  reader attaches the namespace itself
```

且判定与上报量**未动**（新运行 L233，与旧运行逐字同）：

```
[Forbric/Mixin] guest mixin fabric-rendering-v1 (fabric-rendering-v1.mixins.json):shader.ShaderProgramMixin
  applies only partially on the merged base — 4/6 anchors resolve, missing: @At(INVOKE) …withDefaultNamespace in
  ShaderInstance.<init>, @At(INVOKE) …withDefaultNamespace in ShaderInstance.getOrCreate (kept; …)
```

**结论：着色器墙已翻过。** 那三项未达成的读数是**另一道墙**、不属于本车道，具名如下（新运行 L1336/L1482/
L1617-1621）：客户端这次**起了集成服务器**（`Starting integrated minecraft server`），玩家
`ForbricKernel` 也**连上了**，然后在编码 `minecraft:register` 自定义负载时：

```
Caused by: java.lang.ClassCastException: class net.fabricmc.fabric.impl.networking.RegistrationPayload
  cannot be cast to class net.neoforged.neoforge.network.payload.MinecraftRegisterPayload
   (… PayloadInterop$CodecInvocationHandler.invoke(PayloadInterop.java:585) …)
→ Failed to encode packet 'clientbound/minecraft:custom_payload' (minecraft:register)
→ lost connection / DisconnectedScreen → Stopping server
```

这是 `PayloadInterop` 的 Fabric↔NeoForge `minecraft:register` 负载互操作缺陷，与着色器无关；旧报告 §7 那条
"客户端至今没有一次读到 world，卡点是 ClientShaderFix 的着色器路径缺陷"的结论随本次修复作废，新的卡点
是上面这条。`frames=0`/`world=false` 是夹具把这次启动记成 `exit=143`/`contended=true` 的结果，不作为本
报告的主张。

## 5. 关于内核 sha：`eb8aff51` vs `d7184178`

`W7Harness` 在我把提交信息与一处 Javadoc 措辞修正（`eb8aff51`→`d7184178`）之前就构建了 frozen kernel。
两提交的源码差**只有 `FabricClientMixinAnchors` 里一段注释**（把"逐字节等价"改成准确的"语义等价"）；
注释不进字节码，`compileJava` 在 `d7184178` 上退出 0。因此那次读数对 `d7184178` 有效，
未为其再开一次客户端窗口（用户显示器上残留窗口的问题由 `W7Harness` 另修）。若日后要严格按 sha 复跑，
重跑即可，预期读数不变。

## 6. 证据文件

- `evidence/merged-vs-neoforge-ShaderInstance.txt` — 两侧 sha256、反编译源码 diff（空）、`javap` 归一差。
- `evidence/before-console-shader-exception.txt` — 旧运行的 `ResourceLocationException` 全栈（L1323-1365）。
- `evidence/adapter-on-remapped-guest.txt` — 适配器对 remapped guest class 的运行结果。
- `evidence/location-before-after.txt` — 用合并基底 `ResourceLocation` 复核的 before/after 位置。
- `evidence/after-console-markers.txt` — 新运行的关键行（L233/L317/L1336/L1482/L1617/L1621）与异常计数。
