# 世界退出里的两件事：Forge 自定义注册表从未被创建（`Failed to apply some object holders`），以及"服务端线程被判成 CLIENT"（`Illegal packet received`）

**Verdict (EN).** Two kernel-side defects sit behind the world-exit noise in the context's log, and both are fixed,
boot-side. **(1)** `KernelForgeBaseline.fireNewRegistryEvent` resolved the traditional-Forge bus's `post` **by name
only** (`KernelForgeModContext.single(bus.getClass(), "post")`). EventBus 6's `EventBus` declares TWO overloads —
`post(Event)` and `post(Event, IEventBusInvokeDispatcher)` — and `Class.getMethods()` order is not specified: measured
on this box, twelve identical probe invocations split 10/2 and 6/6 across the two orders. When the two-argument one
comes up, `Method.invoke(bus, event)` throws `IllegalArgumentException: wrong number of arguments: 1 expected: 2`, the
event never reaches a single `DeferredRegister`, `fill()` builds nothing, and **every Forge custom registry**
(`forge:fluid_type`, `forge:condition_codecs`, `forge:ingredient_serializers`, the modifier serializers,
`forge:holder_set_type`) never exists. Nothing says so until Forge's object-holder pass at world exit:
`net.minecraftforge.registries.ObjectHolderRegistry.applyObjectHolders` → 16 `Unable to find registry with key
forge:*` → `RuntimeException: Failed to apply some object holders`, logged at ERROR by Forge itself. The user's own
log carries the whole chain (the `IllegalArgumentException` at line 1079, `created 0 custom registr(ies)` at 1098, the
holder stack from 1743) and this lane reproduced it **with the user's own jar on this box**
(`before-published` = `16bcd602…`: 1× "could not reach", `created 0`, 1× the holder `RuntimeException`, 26× the
missing-registry `IllegalStateException`; the same 14 jars, same stage, same JDK as two locally-rebuilt arms that drew
the *good* order). The fix names the parameter type — `KernelForgeModContext.eventBusPost(busClass, eventType)`, i.e.
`getMethod("post", Event.class)` — so the selection stops being a lottery. **(2)** `'Illegal packet received'` is
raised by exactly one place in the shipped Forge carrier: `net.minecraftforge.common.ForgeHooks.onCustomPayload
(CustomPayloadEvent)`, which compares `EffectiveSide.get()` with the connection's direction and disconnects the player
on a mismatch. `EffectiveSide.get()` reads the side off the thread's `ThreadGroup`, and the byte merge kept **NeoForge's**
half of both group sites (`merge-conflicts.txt`: `MinecraftServer#spin … (forge hook lost)`,
`ServerConnectionListener#startTcpServerListener … (forge hook lost)`), so the server thread and the Netty loops sit in
`net.neoforged…SidedThreadGroups.SERVER` — not the MinecraftForge class the reader tests for — and Forge answers
**CLIENT** on them. Forge's payload dispatcher is still the surviving body of
`ServerGamePacketListenerImpl.handleCustomPayload` (the merged method is Forge's override, called for NeoForge payloads
too), so the first serverbound play payload a mod sends upward ends the connection. The fix serves the losing *reader*
instead of moving the threads: `ForbricMergedBaseCompatTransformer` rewrites the CLIENT fallback of
`net.minecraftforge.fml.util.thread.EffectiveSide.get()` into `ForgeSidedThreads.groupFor(caller)`, so Forge's side check
answers SERVER on the threads NeoForge built while NeoForge's own reader keeps working. Verified against the real
carrier bytecode off-game: on a thread in NeoForge's SERVER group, before = `CLIENT`, after = `SERVER`; on an unsided
thread both are `CLIENT`. **What was NOT reproduced: the kick itself.** No arm drives the Stellar Remote, and an
unattended client handles no serverbound play custom payload, so `lost connection: Illegal packet received` is 0 in
every arm — and, for the same reason, the `EffectiveSide` transform never fires in a run (the class is never loaded).
Both fixes are boot-side; the run cannot exercise (2), which is why its proof is the real-bytecode probe.

**答案（中文）。** 语境日志里世界退出的两件事都在内核半边，已修，且只动 boot 半边。**(1)**
`KernelForgeBaseline.fireNewRegistryEvent` 只按**名字**取总线上的 `post`（`KernelForgeModContext.single(bus.getClass(),
"post")`）。EventBus 6 的 `EventBus` 有**两个** `post` 重载：`post(Event)` 与 `post(Event, IEventBusInvokeDispatcher)`；
而 `Class.getMethods()` 的顺序是未定义的 —— 本机实测：同一条探针命令跑 12 次，两批分别是 10/2 与 6/6 落在两种顺序上。抽到
两参那个时，`Method.invoke(bus, event)` 抛 `IllegalArgumentException: wrong number of arguments: 1 expected: 2`，事件到不了
任何一个 `DeferredRegister`，`fill()` 什么都建不出来，于是**所有 Forge 自定义注册表**
（`forge:fluid_type`、`forge:condition_codecs`、`forge:ingredient_serializers`、两个 modifier serializer、
`forge:holder_set_type`）从来不存在。这件事一直到世界退出才说话：Forge 自己的
`ObjectHolderRegistry.applyObjectHolders` → 16 条 `Unable to find registry with key forge:*` → `RuntimeException:
Failed to apply some object holders`（由 Forge 自己以 ERROR 打印）。用户日志里有整条链（1079 行的
`IllegalArgumentException`、1098 行 `created 0 custom registr(ies)`、1743 行起的 holder 栈），本车道**用用户那个 jar
在本机复现了**（`before-published` = `16bcd602…`：1 次 could-not-reach、`created 0`、1 次 holder `RuntimeException`、
26 次缺键 `IllegalStateException`；同样 14 只 jar、同 stage、同 JDK，而两个本地重建臂抽到的是**好**顺序）。修法是按参数类型取
—— `eventBusPost(busClass, eventType)`，即 `getMethod("post", Event.class)`。**(2)** `'Illegal packet received'`
在 Forge 载体里只有一处会产生：`ForgeHooks.onCustomPayload(CustomPayloadEvent)` 拿 `EffectiveSide.get()` 与连接方向比对，
不一致就把玩家踢掉。`EffectiveSide.get()` 从线程的 `ThreadGroup` 读 side，而字节合并把两个 group 站点都留了
**NeoForge** 的那半（`merge-conflicts.txt`：`MinecraftServer#spin … (forge hook lost)`、
`ServerConnectionListener#startTcpServerListener … (forge hook lost)`），于是服务器线程与 Netty 线程都落在
`net.neoforged…SidedThreadGroups.SERVER` 里 —— 不是 Forge 读取时 `instanceof` 的那个类 —— Forge 判成 **CLIENT**。
合并后的 `ServerGamePacketListenerImpl.handleCustomPayload` 仍是 Forge 的那个 override（对 NeoForge 载荷也照调），
所以 mod 往上发的第一个 play 阶段自定义包就会断线。修法是**服务丢掉的读者**而不是搬线程：`ForbricMergedBaseCompatTransformer`
把 `EffectiveSide.get()` 的 CLIENT 兜底改写为 `ForgeSidedThreads.groupFor(caller)`，Forge 的 side 检查在 NeoForge 建的线程上
答 SERVER，NeoForge 自己的读者不受影响。已用真载体字节离线验证：在 NeoForge SERVER group 的线程上，改前 `CLIENT`、改后
`SERVER`；无 side 的线程两者都是 `CLIENT`。**没有复现的是"踢人"本身**：四臂都没有驱动 Stellar Remote，无人值守的客户端也不会处理
任何 serverbound play 自定义包，所以 `Illegal packet received` 四臂都是 0 —— 同理 `EffectiveSide` 的改写在一次运行里也从未触发
（该类从未被加载）。两处改动都在 boot 半边；(2) 无法由这次运行证明，所以它的证据是真载体探针。

---

## 0. The window the lane was asked to read (log lines 1690-1760)

`/Applications/.minecraft/versions/1.21.1-forbric/logs/latest.log` (2026-10-08, kernel `0.3.6-beta`). Verbatim in
`evidence/user-log-1690-1760.txt`; the anchors:

| line | content |
|---|---|
| 1702 | `[Render thread/WARN] [Forbric/Load] 10 mod(s) partly did not run: … shooting_star_demo — details in .forbric-kernel/load-report.txt` |
| 1723 | `[10:35:31] [Server thread/INFO]: Charles_cai_5332 lost connection: Illegal packet received, terminating connection` |
| 1743 | `[10:35:31] [Server thread/ERROR]:` → `java.lang.RuntimeException: Failed to apply some object holders, see suppressed exceptions for details` (the ERROR block runs to 2849) |
| 2850 | `[Server thread/WARN] [Forbric/EventMux] handleServerStopped forward failed; …` → `ClassCastException: SimpleCommentedConfig cannot be cast to CommentedFileConfig` at `ModConfig.save(ModConfig.java:80)` |

The full object-holder stack (1107 lines: the `RuntimeException`, then **16** `Suppressed:` `IllegalStateException`s
each carrying a `Caused by: java.lang.Throwable: Calling Site from mod: forge` back into `ForgeMod.<clinit>`) is
`evidence/user-log-object-holder-stack.txt`. Its head, verbatim:

```
[10:35:31] [Server thread/ERROR]:
java.lang.RuntimeException: Failed to apply some object holders, see suppressed exceptions for details
	at forbric/net.minecraftforge.registries.ObjectHolderRegistry.applyObjectHolders(ObjectHolderRegistry.java:226) ~[forge-runtime-1.21.1.jar:52.1.16]
	at forbric/net.minecraftforge.registries.ObjectHolderRegistry.applyObjectHolders(ObjectHolderRegistry.java:215) ~[forge-runtime-1.21.1.jar:52.1.16]
	at forbric/net.minecraftforge.registries.GameData.revertTo(GameData.java:304) ~[forge-runtime-1.21.1.jar:52.1.16]
	at forbric/net.minecraftforge.registries.GameData.revertToFrozen(GameData.java:283) ~[forge-runtime-1.21.1.jar:52.1.16]
	at forbric/net.minecraftforge.server.ServerLifecycleHooks.handleServerStopped(ServerLifecycleHooks.java:101) ~[forge-runtime-1.21.1.jar:52.1.16]
	at forbric/net.forbric.kernel.runtime.KernelGameServerLifecycle.lambda$installStopped$4(KernelGameServerLifecycle.java:134) ~[forbric-kernel-runtime.jar:?]
	at forbric/net.forbric.kernel.runtime.KernelGameServerLifecycle.lambda$subscribe$5(KernelGameServerLifecycle.java:162) ~[forbric-kernel-runtime.jar:?]
	at forbric/net.neoforged.bus.ConsumerEventHandler.invoke(ConsumerEventHandler.java:27) ~[neoforge-runtime-1.21.1.jar:21.1.252]
	… (EventBus.post ×2, NeoForge ServerLifecycleHooks.handleServerStopped:125, MinecraftServer.runServer:750) …
	Suppressed: java.lang.IllegalStateException: Unable to find registry with key forge:condition_codecs for mod "forge". Check the 'caused by' to see further stack.
		at forbric/net.minecraftforge.registries.RegistryObject$1.accept(RegistryObject.java:166) …
		at forbric/net.minecraftforge.registries.ObjectHolderRegistry.applyObjectHolders(ObjectHolderRegistry.java:229) …
	Caused by: java.lang.Throwable: Calling Site from mod: forge
		at forbric/net.minecraftforge.registries.RegistryObject.<init>(RegistryObject.java:153)
		at forbric/net.minecraftforge.registries.DeferredRegister.register(DeferredRegister.java:194)
		at forbric/net.minecraftforge.common.ForgeMod.<clinit>(ForgeMod.java:326)
```

The 16 suppressed exceptions are 4× `forge:condition_codecs`, 3× `forge:ingredient_serializers`, 3×
`forge:fluid_type for mod "minecraft"`, 3× `forge:biome_modifier_serializers`, 2× `forge:holder_set_type`, 1×
`forge:structure_modifier_serializers` — all `for mod "forge"` except `forge:fluid_type`.

## 1. The machinery, the holder, the mod — and the merged-base divergence

**Machinery.** MinecraftForge's object-holder machinery: `net.minecraftforge.registries.ObjectHolderRegistry`, whose
`applyObjectHolders(Predicate)` walks every registered `RegistryObject` consumer, letting each one re-bind or throw,
collecting failures into one `RuntimeException` (bytecode in `evidence/object-holder-bytecode.txt`). Its driver here
is `GameData.revertToFrozen()`, which genuine Forge's `ServerLifecycleHooks.handleServerStopped` calls on any
non-dedicated server (offset 4–7, `isDedicatedServer`; then `unloadConfigs` at 111). The kernel forwards
`ServerStoppedEvent` to that hook from `KernelGameServerLifecycle.installStopped` — the two `KernelGameServerLifecycle`
frames in the stack are the kernel doing its job, not the defect.

**Holder and mod.** The failing holders are the static `RegistryObject`s of `net.minecraftforge.common.ForgeMod`
(`Caused by: Calling Site from mod: forge`, `ForgeMod.<clinit>` at the line of each `DeferredRegister.register`), and
the mod is **`forge`** — the MinecraftForge baseline itself, not either Shooting Star jar. `RegistryObject$1.accept`
throws `Unable to find registry with key <key> for mod <modid>` (source line 166, bytecode offset 40) when
`RegistryObject.registryExists(key)` is false, and `registryExists` is exactly
`RegistryManager.ACTIVE.getRegistry(key) != null || BuiltInRegistries.REGISTRY.containsKey(key)` — so the keys are
missing from Forge's ACTIVE **and** are not builtin registries.

**Where those registries are supposed to come from.** `RegistryManager.postNewRegistryEvent` (bytecode in
`evidence/registry-declaration-bytecode.txt`) posts `NewRegistryEvent`, then `DataPackRegistryEvent.NewRegistry`, then
`NewRegistryEvent.fill()`s — and `fill()` is what builds and registers the registries the listeners collected.
`ForgeMod`'s `DeferredRegister`s are those listeners; the keys are `ForgeRegistries$Keys`'
`forge:fluid_type`, `forge:condition_codecs`, `forge:ingredient_serializers`, `forge:biome_modifier_serializers`,
`forge:structure_modifier_serializers`, `forge:holder_set_type`, `forge:global_loot_modifier_serializers`,
`forge:entity_data_serializers`, `forge:display_contexts`.

**The divergence — the kernel never ran that post.** `KernelForgeBaseline.fireNewRegistryEvent` is the kernel's own
substitute for `postNewRegistryEvent`, and its post was `KernelForgeModContext.single(bus.getClass(), "post")`:

```java
static Method single(Class<?> cls, String name) {
    for (Method m : cls.getMethods()) if (m.getName().equals(name)) return m;
    throw new IllegalStateException("no method " + name + " on " + cls);
}
```

`net.minecraftforge.eventbus.EventBus` declares both overloads, and `Class.getMethods()` order is unspecified —
**measured**: twelve identical probe invocations on this box, same classpath, same JDK 21.0.7, split **10/2** and
**6/6** between the two orders (`evidence/post-order-probe.txt`). Drawing the two-argument overload makes
`invoke(bus, event)` throw `IllegalArgumentException: wrong number of arguments: 1 expected: 2` (JDK 21's
`DirectMethodHandleAccessor.checkArgumentCount(paramCount=2, args.length=1)`) — the user's log line 1079 and this
lane's `before-published` console line 1089, both at `KernelForgeBaseline.java:142`. `fill()` then has nothing to
build, `created 0 custom registr(ies)` (log line 1098), and the failure stays invisible for the whole session because
a `RegistryObject` whose registry is absent binds to nothing and nobody dereferences it — until Forge's own
world-exit revert applies the holders. The sibling lane's report read the same log and stopped at "not a merge gap";
this lane's answer is narrower and darker: the *declaration* machinery (`forge:biome_modifier` /
`forge:structure_modifier` as datapack registries) was already repaired there, while the nine **custom** registries
come from this post and the post was a coin toss.

## 2. What raises `Illegal packet received`

Exactly one site in the shipped Forge carrier contains that string:
`net.minecraftforge.common.ForgeHooks.onCustomPayload(CustomPayloadEvent)` (bytecode in
`evidence/illegal-packet-bytecode.txt`):

```
side = conn.getReceiving() == PacketFlow.CLIENTBOUND ? LogicalSide.CLIENT : LogicalSide.SERVER;
if (side != EffectiveSide.get()) {
    conn.disconnect(Component.literal("Illegal packet received, terminating connection"));
    return false;
}
```

and `EffectiveSide.get()` is four instructions: read `Thread.currentThread().getThreadGroup()`, take the side off it
when it is a **MinecraftForge** `SidedThreadGroup`, answer `CLIENT` for everything else.

**The divergence (forge hook lost, twice).** Both ecosystems patch the same two places to put their threads in their
own group, and the merge kept NeoForge's half of both — its own ledger says so
(`/Applications/.minecraft/.forbric-build/out/merge-conflicts.txt`):

```
net/minecraft/server/MinecraftServer#spin(Ljava/util/function/Function;)Lnet/minecraft/server/MinecraftServer; (forge hook lost)
net/minecraft/server/network/ServerConnectionListener#startTcpServerListener(Ljava/net/InetAddress;I)V (forge hook lost)
net/minecraft/server/network/ServerConnectionListener#lambda$static$1()…EpollEventLoopGroup; (forge hook lost)
```

Measured against the unmerged bases (`evidence/thread-group-divergence.txt`): `MinecraftServer.spin` gets
`net.minecraftforge…SidedThreadGroups.SERVER` in `patched-mc-forge-1.21.1.jar`, NeoForge's in
`patched-mc-neoforge-1.21.1.jar`, and **NeoForge's in `patched-mc-merged-1.21.1.jar`** (`fbd531b0…`) — same for
`ServerConnectionListener`'s two Netty loops. Forge's third group site, the login thread in
`ServerLoginPacketListenerImpl`, **survived** the merge (it still names Forge's class), which is the proof this is a
merge artefact and not either ecosystem's behaviour. Consequence: on the merged base the "Server thread" and every
Netty thread carry NeoForge's group, Forge's `EffectiveSide.get()` answers `CLIENT` on them, and any Forge side check
taken there is wrong.

**Reached, because the merged listener is Forge's.** The byte merge kept MinecraftForge's body for
`ServerGamePacketListenerImpl.handleCustomPayload` (genuine NeoForge's delegates to `super`; the merged one is
Forge's override, `ForgeHooks.onCustomPayload(payload, connection); POP; RETURN`, no `super`, no
`ensureRunningOnSameThread`). So **every** serverbound play-phase custom payload — NeoForge's included, which is what
the Shooting Star demo's `playToServer` `CastSkillPayload` is — is offered to Forge's dispatcher on a Netty or server
thread, whose group is NeoForge's, and the side check disconnects the player. The kick in the context's log is
therefore not caused by the missing registries; it is the *second* kernel-side defect at world exit, and the one this
report's title names.

## 3. Reproduction — what ran, and what did not reproduce

One client run at a time, `w7/harness/sweep_client.py`, the user's 14 jars, subject
`the-shooting-star-demo-1.3.1-neoforge.jar` (its closure is the other thirteen), window hiding **on** (the hiding A/B's
`COMPUTE_FRAMES` agent; no VerifyError anywhere), JDK 21.0.7. Instrument and rows:
`evidence/preregistration.md` (written before the first run), `evidence/mod-set.txt`, `evidence/harness-stdout.txt`,
`evidence/gate-reading.txt`, `evidence/console-markers.txt`.

| arm | kernel sha256 | `created N custom registr(ies)` | "could not reach" | holder `RuntimeException` | missing-key `ISE`s | run / world / frames |
|---|---|---|---|---|---|---|
| `before-published` (the user's jar) | `16bcd602…` | **0** | **1** | **1** | **26** | PASS / true / 1 |
| `before` (HEAD rebuilt) | `2f561650…` | 9 | 0 | 0 | 0 | PASS / true / 1 |
| `before` (repeat, same jar) | `2f561650…` | 9 | 0 | 0 | 0 | PASS / true / 1 |
| `after` (HEAD + both fixes) | `48df3d2b…` | 9 | 0 | 0 | 0 | PASS / true / 1 |

**Reproduced:** the user's `IllegalArgumentException: wrong number of arguments: 1 expected: 2` at
`KernelForgeBaseline.java:142`, `created 0 custom registr(ies)`, the object-holder `RuntimeException`, and the 26
missing-registry `IllegalStateException`s — all with the user's own jar bytes, on the harness surface.
**Not reproduced:** `lost connection: Illegal packet received` — 0 in all four arms, exactly as the pre-registration
predicted for an unattended client. No arm drives the demo's `playToServer` payload, so Forge's dispatcher is never
handed a serverbound play payload; and for the same reason the `EffectiveSide` repair never even runs in a run (the
class is not loaded, so `MinecraftForge's EffectiveSide now reads the merged base's thread groups again` is 0 in
`after` — pre-registered as 1, and the miss is adjudicated in §5). The run therefore reproduces the *object-holder*
half of the log and cannot exercise the *disconnect* half.

**Deviation the pre-registration did not anticipate:** reading 1 ("before 0, after > 0") is a lottery, not a
deterministic `before`. The published jar drew the two-argument overload; the locally-rebuilt `before` jar drew the
one-argument overload twice. Both orders were measured from identical probe invocations (10/2 and 6/6), which is what
makes the two-argument draw a real risk rather than an environment quirk. The pre-registered criteria are unchanged;
this is recorded as an adjudication, not a re-write.

## 4. The fixes (boot-side only)

| # | file | change |
|---|---|---|
| A | `KernelForgeModContext.java` | `single(Class,String)` → `eventBusPost(Class<?> busClass, Class<?> eventType)` = `getMethod("post", eventType)`, failing loud and naming the type when the bus changes shape. |
| A | `KernelForgeBaseline.java` | resolves `net.minecraftforge.eventbus.api.Event` once and posts through `eventBusPost(bus.getClass(), eventType)`. |
| B | `ForgeSidedThreads.java` (new) | boot-side answer for `EffectiveSide`: `groupFor(Class<?> caller)` returns MinecraftForge's SERVER `SidedThreadGroup` when the calling thread is in NeoForge's SERVER group, else Forge's CLIENT group. Resolved reflectively per caller loader; no game type named. |
| B | `ForbricMergedBaseCompatTransformer.java` | new repair `letMinecraftForgeReadTheMergedThreadGroups`: replaces the CLIENT fallback of `EffectiveSide.get()` with `ForgeSidedThreads.groupFor(EffectiveSide.class)` + `checkcast`/`getSide()`. One stack slot, no new branch, no frame or max-stack change. |
| B | `ForeignType.java` | `SIDED_THREAD_GROUPS` row — `ForeignTypeTest`'s scanning test demanded it once both families' class names appeared in one file (and the helper uses the row). |
| — | `KernelRegistrationIsolationTest.java` | its post-vs-fill isolation assertion located the seam by name (`single`); it now locates `eventBusPost`. Same contract, new seam. |

Fix B deliberately does **not** move the threads into Forge's group: NeoForge keeps them in its own (its own
`EffectiveSide` reads them) and only one ecosystem can own a thread group — the same "serve the losing reader"
choice `serveDefaultAttributesBothEcosystems` makes. Bytecode delta and rationale:
`evidence/diff.patch`, `evidence/build-provenance.txt`.

## 5. Verification against the pre-registered reading

- **Fix A.** The published arm and the two `before` arms establish the defect and its two outcomes; the `after` arm
  (same build command, same environment, one class added and six edited relative to `before`:
  `evidence/build-provenance.txt`) prints `created 9 custom registr(ies)`, 0 "could not reach", 0 holder
  exceptions, 0 missing-key ISEs. The `KernelForgeModContextPostTest` unit test pins the *contract* — with a bus
  declaring both overloads, the one-argument one is chosen and is invocable as `invoke(bus, event)`; with no such
  method it fails loudly naming the type.
- **Fix B.** `evidence/effective-side-probe.txt`: the kernel's transformer is run on the **real**
  `net/minecraftforge/fml/util/thread/EffectiveSide` from the shipped carrier, both classes are loaded, and
  `get()` is called from a thread built in `net.neoforged.fml.util.thread.SidedThreadGroups.SERVER`:
  `before=CLIENT`, `after=SERVER`; on an unsided thread both `CLIENT`. The `GETSTATIC LogicalSide.CLIENT` constant is
  gone from the transformed method and the class verifies and executes.
- **Adjudication (pre-registered reading 4 missed).** `after` prints the repair's log line **0** times, not 1. The
  repair is claimed per class load, and the run never loads `EffectiveSide` because no serverbound play payload is
  handled (§3). This is the same condition that stops the kick from reproducing, and it is the reason fix B's
  evidence is the off-game probe rather than a row.
- **Suite.** `./gradlew --offline test` → `2955 tests, 924 skipped, 29 failed`. 24 of the 29 fail before any
  transformer: no staged root on this box (`Files.isRegularFile(null)`, "staged merged base absent"). The five that
  do run are pre-existing and were confirmed on HEAD with this lane's sources reverted (5 failed in the same
  selection): `KernelRuntimeClassesTest` (KernelEntityDataSerializers, KernelClientLifecycle),
  `ForeignTypeTest` (ClientModLoader, Event, EventPriority — this lane's SidedThreadGroups pair is gone),
  `CompatProtocolTest` (a sibling report lacks PROTOCOL.md rows), `KernelForgeGatherStatesTest` and
  `FinalMixinApplicationsTest` (absent staged fixtures). This lane's four test classes: `TransformerAnchorCensusTest`
  6/6, `KernelForgeModContextPostTest` 3/3, `KernelRegistrationIsolationTest` 3/3, `MergedBaseEffectiveSideTest` 2
  skipped here (no `run/forge-runtime/forge-runtime.jar`) and exercised for real by the probe.

## 6. Written-down blanks, and what is not this lane's

- **The kick is diagnosed, not reproduced** (§3). Its raise site, the divergence and the surviving dispatch path are
  all bytecode-level, and the user's log shows the kick with a NeoForge `playToServer` payload in the same instance.
  A run that presses the remote would settle it; none here did.
- **The `ClassCastException` at 2850 is a third, separate defect.** `ServerLifecycleHooks.handleServerStopped:111 →
  ConfigTracker.unloadConfigs → ModConfig.save` casts a `SimpleCommentedConfig` to `CommentedFileConfig`. It is
  present in **every** arm (before/before-2/published/after all print exactly one
  `handleServerStopped forward failed`), is unrelated to both fixes, and is reported here because the kernel's own
  WARN in the context's log is the first line after the holder stack — it is not this lane's to fix and was not
  pre-registered.
- **The demo mod's own `DEGRADED` status is pre-existing** (all arms `mod=DEGRADED cause=mod-degraded`) — the same
  state the sibling lane recorded for this jar.
- **The window-hiding agent's OS-level half still needs the accessibility permission** (`os-level hide FAILED … System
  Events … -10006` in every arm). The javaagent half is installed and clean in all four runs — no `VerifyError`, no
  stack-map error, no GLFW error — which is the hiding A/B's fix holding.

## 7. Evidence index (`evidence/`)

| file | content |
|---|---|
| `preregistration.md` | the set, instrument, arms, per-reading predictions and falsification, written **before** the first run |
| `mod-set.txt` | the 14 jars' sha256, the four arm shas, stage/mc/remap/timeouts |
| `user-log-1690-1760.txt` / `user-log-object-holder-stack.txt` | the requested window, verbatim, and the full 1107-line holder ERROR block |
| `object-holder-bytecode.txt` | `ObjectHolderRegistry.applyObjectHolders` (both overloads), `RegistryObject$1.accept`, `RegistryObject.registryExists` |
| `registry-declaration-bytecode.txt` | `ForgeRegistries$Keys`, `ForgeMod.<clinit>`'s `DeferredRegister`s, `RegistryManager.postNewRegistryEvent` |
| `illegal-packet-bytecode.txt` | `ForgeHooks.onCustomPayload` (both entry points, the string and the side check) and `EffectiveSide.get()` |
| `thread-group-divergence.txt` | `merge-conflicts.txt`'s "forge hook lost" rows; `spin`/`ServerConnectionListener`/`ServerLoginPacketListenerImpl` in the unmerged and merged bases; the merged `handleCustomPayload` vs NeoForge's |
| `post-order-probe.txt` | why the same `post` resolution is not stable: the probes and their 12-run tallies |
| `effective-side-probe.txt` | the off-game verification of fix B against the real carrier bytecode, source and output |
| `gate-reading.txt` / `console-markers.txt` / `harness-stdout.txt` | the four arms' rows, marker counts, verbatim marker lines, sweep stdout |
| `build-provenance.txt` | the arms, the entry-set comparison, reproducibility (the rebuild re-packs to the same sha), artifact hygiene |
| `unit-test-reading.txt` / `unit-test-failures.txt` | the suite reading, every failure and its reason, and the HEAD baseline for the five non-fixture ones |
| `diff.patch` | this lane's whole source change (5 files edited, 3 added) |
