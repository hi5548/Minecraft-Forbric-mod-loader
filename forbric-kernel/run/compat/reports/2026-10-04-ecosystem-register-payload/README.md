# 生态载荷路由:`minecraft:register` 的类不匹配(2026-10-04)

范围:**客户端 1.21.1 + `fabric-api-0.116.17+1.21.1.jar`(只此一个 mod)的真实启动**。判据是
`[Forbric/ClientSmoke] joined world via quick-play` 在真实客户端启动里出现;否则给出下一条阻塞行及其证据。

工作树:`实验/forbric/Minecraft-Forbric-mod-loader`(`main`,起点 `d7184178`,修复 `e22a3d3d`)。
所有字节结论都来自两侧实际字节:remap 后的 guest 模块(内核加载的那份)与 staged merged base,
工具是 JDK 21 的 `javap -p -c`。

**§4 的读数在运行前登记;§5 是 W7Harness 回填的实测结果。**

---

## 1. 现象(控制台逐字)

来源 `reports/2026-10-04-client-shaders-d7184178`(修复前,提交 `d7184178`;着色器双命名空间已修,
于是暴露到下一条)。integrated server 的配置阶段,服务端**发出自己的 `minecraft:register`** 时崩:

```
[Server thread/INFO]: com.mojang.authlib.GameProfile@42bd61c[id=…,name=ForbricKernel,…] lost connection:
  Internal Exception: io.netty.handler.codec.EncoderException:
  Failed to encode packet 'clientbound/minecraft:custom_payload' (minecraft:register)

Caused by: java.lang.RuntimeException: Failed encoding custom payload minecraft:register:
    java.lang.reflect.UndeclaredThrowableException
	at CustomPacketPayload$1$forbricneo.writeCap(CustomPacketPayload.java:42)
	at CustomPacketPayload$1$forbricneo.encode(CustomPacketPayload.java:47)
	at CustomPacketPayload$1$forbricneo.encode(CustomPacketPayload.java:29)
	at StreamCodec$4.encode(StreamCodec.java:81)
	…
Caused by: java.lang.ClassCastException: class net.fabricmc.fabric.impl.networking.RegistrationPayload
  cannot be cast to class net.neoforged.neoforge.network.payload.MinecraftRegisterPayload
  (… are in unnamed module of loader 'forbric' @1abbc1d4)
	at net.minecraft.network.codec.StreamCodec$4.encode(StreamCodec.java:81)
	… DirectMethodHandleAccessor.invoke / Method.invoke …
	at net.forbric.kernel.interop.PayloadInterop$CodecInvocationHandler.invoke(PayloadInterop.java:585)
	at jdk.proxy3/jdk.proxy3.$Proxy62.encode(Unknown Source)
	at CustomPacketPayload$1$forbricneo.writeCap(CustomPacketPayload.java:40)
	…
```

**触发调用点(栈逐字,Fabric 自己的服务端 addon 是发出方):**

```
ServerConfigurationNetworkAddon.startConfiguration(ServerConfigurationNetworkAddon.java:73)
  -> AbstractChanneledNetworkAddon.sendInitialChannelRegistrationPacket(…:112)
  -> PacketSender.sendPacket(…:46/54)
  -> ServerConfigurationNetworkAddon.sendPacket(…:169)
  -> ServerCommonPacketListenerImpl.send(…:187)
  -> Connection.send/doSendPacket → netty → PacketEncoder → IdDispatchCodec.encode
  -> CustomPacketPayload$1$forbricneo.encode → PayloadInterop$CodecInvocationHandler → 选中的 codec
```

即:Fabric 发出的是 **`net.fabricmc…RegistrationPayload`**,而内核的 codec 代理把 **NeoForge 的
`MinecraftRegisterPayload` codec** 交给它去编码。

## 2. 归因(字节,不是推断)

`PayloadInterop.findCodec(...)` 会为 `minecraft:register` 装配一个 `StreamCodec` 代理,
`CandidateSet.selectEncode(payload)` 按运行时载荷类分流:

```java
if (payloadClass.startsWith("net.fabricmc.")) return firstNonNull(fabric, local, neo, fallback);
```

`payloadClass` 确实以 `net.fabricmc.` 开头,所以它拿到的是 `fabric` —— 除非 `fabric == null`。
`fabric` 来自 `fabricTypeAndCodec(id, protocol, packetFlow)` → `fabricRegistryField(protocol, packetFlow)`
→ `staticField(PayloadTypeRegistryImpl, "CLIENTBOUND_CONFIGURATION")`。**该字段在 1.21.1 的模块里不存在:**

```
$ javap -p remap/fabric-networking-api-v1-0.116.17-…jar:PayloadTypeRegistryImpl
public static final PayloadTypeRegistryImpl<FriendlyByteBuf> CONFIGURATION_C2S;
public static final PayloadTypeRegistryImpl<FriendlyByteBuf> CONFIGURATION_S2C;
public static final PayloadTypeRegistryImpl<RegistryFriendlyByteBuf> PLAY_C2S;
public static final PayloadTypeRegistryImpl<RegistryFriendlyByteBuf> PLAY_S2C;
```

`NetworkingImpl.init()` 把 `RegistrationPayload.REGISTER`/`UNREGISTER` 逐条注册进这四个注册表(javap -c),
所以 `CONFIGURATION_S2C.get(minecraft:register)` 本就是 Fabric 的 `REGISTER_CODEC`。内核却只按 26.2 的名字
(`CLIENTBOUND_CONFIGURATION`/`CLIENTBOUND_PLAY`/…)去取,反射落空:

- 编码侧:`selectEncode` 退化为 `firstNonNull(local, neo, fallback)`,取到合并基底里 NeoForge 为同一 id
  注册的 codec ⇒ 写 Fabric 载荷时 CCE(§1)。
- 镜像面:`reflectFabricRegistrations()` 用同一批名字,因此在 1.21.1 上**从未**收到 Fabric 的注册,
  `mirrorMergedPayloadRegistries` 静默地什么都没镜像。

这与本项目里一串"内核只认 26.2 世代名字"的移植遗留是同一类(`WorldChunkMixin`/`LevelChunkMixin`、
`MultiPlayerGameModeMixin`/`ClientPlayerInteractionManagerMixin`、refmap 拼写),不是新的问题类。

## 3. 改法(既有约定)

**既有约定:一个名称适配器同时接受两代名字**(见 `MixinNames`、`FabricClientMixinAnchors.removal()`)。
本次只在 `PayloadInterop` 内落地,不新增机制:

- 新增 `FABRIC_REGISTRY_FIELD_NAMES`(两代共 8 个名字)与 `fabricRegistry(registryClass, protocol, packetFlow)`,
  按 (protocol, flow) 取"模块实际声明的那个注册表";
- `fabricTypeAndCodec` 与 `mirrorNeoPayloadIntoFabricRegistry` 共用它;`reflectFabricRegistrations` 遍历两代名字。
- 26.2 的名字全部保留为别名,一代模块不需要改。

代价:**零**。没有放宽任何谓词,没有新增分支之外的机制;唯一"变动"是被静默跳过的镜像面在 1.21.1 上恢复运行
(这是原本的设计,不是新增行为)。前一代 loader 的 `ForbricCustomPayloadInterop` 副本是差分 oracle 的产物、
在内核路径上是死代码(合并基底烘焙的 owner 名已被 `MergedBaseCompatTransformer` 重指到内核),本次不动它。

修复前的定向复现(临时探针,真实 remap 模块 + staged merged base,直接调 `fabricRegistry`):

```
pre-fix spelled field CLIENTBOUND_CONFIGURATION = ABSENT (NoSuchFieldException)
CONFIGURATION_S2C: resolved == CONFIGURATION_S2C
CONFIGURATION_C2S: resolved == CONFIGURATION_C2S
PLAY_S2C:          resolved == PLAY_S2C
PLAY_C2S:          resolved == PLAY_C2S
```

## 4. 预登记读数(运行前,W7Harness 执行)

- 目标:提交 **`e22a3d3d`**,clean worktree;冻结内核即它的 `gradlew … jar` 产物(client surface,JDK 21,单独端口)。
- 主体:`fabric-api-0.116.17+1.21.1.jar`(`--only fabric-api`,沿用既有清单,kind 不改)。
- **判据(逐条,逐字):**
  1. `ClientSmoke] joined world via quick-play` **≥ 1**(本车道目标);
  2. `RegistrationPayload cannot be cast to MinecraftRegisterPayload` = **0**;
  3. `Failed to encode packet 'clientbound/minecraft:custom_payload' (minecraft:register)` = **0**。
- **若仍无 world:** 不推断,逐字给出控制台里下一条阻塞行(含其栈/文件名),并登记为剩余 blocker。
- 已知会仍在、且**不是**本车道阻塞的一条告警(登记以免误读):`[Forbric/Anchor]
  forbric-common-network-interop#fabricAddonHandle was handed …ServerConfigurationNetworkAddon and made no edit
  — its anchor is gone`。1.21.1 的 `ServerConfigurationNetworkAddon` 不覆写 `handle`,该方法继承自
  `AbstractChanneledNetworkAddon`(已注入),所以功能上仍被覆盖;这是 claim 的按类 REQUIRED 判定,不是载荷 CCE。

## 5. 结果(W7Harness 实测,已回填)

运行由 `W7Harness` 执行、读数逐字回传。内核 sha256
`99d074757f9ef68d4076a9ac53ab1c104e71147125e92c709a2996dd685f04b6` —— 它自己的 clean-worktree 构建
(`e22a3d3d`,`PayloadInterop.java` 是唯一改动文件)复现了这个 sha。报告在
`w7/reports/2026-10-04-ecosystem-register-payload/`。

(本报告作者在 `e22a3d3d` 上另做的一次 `gradlew clean` + 同参数 `jar` 也产出同一 sha256
`99d07475…`,即冻结的那份字节确实来自该提交,两边独立构建逐字节一致。)

**验收判据成立:`joined world via quick-play` 在真实客户端启动里出现。** 这是本轮 campaign 的**第一次完整客户端启动**:

```json
{"subject": "fabric-api-0.116.17+1.21.1.jar", "slug": "fabric-api", "kind": "random",
 "kernel_sha256": "99d074757f9ef68d4076a9ac53ab1c104e71147125e92c709a2996dd685f04b6",
 "java": "/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin/java — java version \"21.0.7\" 2025-04-15 LTS",
 "compatibility_policy": "continue", "run": "PASS", "exit": 0, "stopped": true, "killed": false,
 "world": true, "frames": 1, "mod": "OK", "confirmed_required": 1, "seconds": 30, "contended": false}
```

逐行结果文件(`per-mod/results.jsonl`)里 `"run":"PASS","exit":0,"stopped":true,"killed":false,"world":true,
"frames":1,"seconds":30,"contended":false`。

**三条预登记断言(逐条计数,取自该 run 的 console.log,2566 行):**

```
1) ClientSmoke] joined world via quick-play                                1   <- MET
2) RegistrationPayload cannot be cast to …MinecraftRegisterPayload        0   <- MET
3) Failed to encode … (minecraft:register)                               0   <- MET
```

控制台逐字(证明是真实启动、不是侥幸行):

```
[07:16:25] [Render thread/INFO]: [Forbric/ClientSmoke] joined world via quick-play: W7Client
[07:16:29] [Render thread/INFO]: [Forbric/ClientSmoke] screenshot written — 2026-10-04_07.16.29.png
[07:16:34] [Render thread/INFO]: [Forbric/ClientSmoke] client-ready after 200 world tick(s)
[07:16:35] [Render thread/INFO]: [Forbric/ClientSmoke] requesting clean disconnect after 220 world tick(s)
[07:16:37] [Render thread/INFO]: [Forbric/ClientSmoke] clean disconnect observed; stopping client
```

`contended=false`、30 秒结束端到端(对照:今天早些时候同样是 fabric-api 的两次运行分别停在 620 s/800 s
的 `STALL`)。

**一句话的诚实边界:进世界把审计带到了更深处,于是暴露出 1 条新的 `CONFIRMED required`(此前看不到):**

```
mixin-injector:fabric-renderer-indigo.mixins.json:
  net.fabricmc.fabric.mixin.client.indigo.renderer.SectionBuilderMixin#hookBuildRenderBlock
detail: A required injector has no attachment in the actual defined class
```

这是 **fabric-renderer-indigo** 车道的另一件事,不是 `minecraft:register` 载荷 CCE(后者计数为 0);
内核已按既有机制把它的 `require 1 → 0` 软化(console 第 315 行逐字:`…hookBuildRenderBlock: require 1 → 0 …`),
所以它没有杀掉这次启动。要说的实话:早先的 `confirmed_required: 0` 是**世界之前**量的,那是"启动停在哪"
的计数,不是清白证明;这条只在进世界后才可见。**它作为独立车道登记,不作为本车道的阻塞。**

**预登记 §4 里那条已知告警逐字确认、不计为阻塞:**

```
forbric-common-network-interop#fabricAddonHandle was handed
  net.fabricmc.fabric.impl.networking.server.ServerConfigurationNetworkAddon and made no edit — its anchor is gone.
```

1.21.1 的 `ServerConfigurationNetworkAddon` 不覆写 `handle`(javap),该方法继承自已注入的
`AbstractChanneledNetworkAddon`,所以功能上仍被覆盖;这是 claim 的按类 REQUIRED 判定,不是载荷 CCE。
