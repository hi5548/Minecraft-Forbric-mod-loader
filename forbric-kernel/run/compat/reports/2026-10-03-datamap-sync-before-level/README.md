# `handleDataMapSync` arrives before `ClientLevel` exists — sender-side ordering (2026-10-03)

Scope: the client-side ordering question blocking `joined world via quick-play`. The client reaches
`LevelLoadingScreen`, the confirmation is answered, the handshake packet is clean (`val$idToType` gone), and then
NeoForge's data-map sync payload is handled while `Minecraft.getInstance().level` is still null →
`NullPointerException` at `ClientRegistryManager` → `Network Protocol Error` → `DisconnectedScreen`.

Everything below is `javap`/jar reads against the staged tree and HEAD `88278053`. No game JVM was started; the
box was carrying an arm. Code-level only.

## 1. There is no guard on the receiving side

`net/neoforged/neoforge/registries/ClientRegistryManager.handleDataMapSync` (neoforge-runtime.jar 21.1.252):

```java
public static <R> void handleDataMapSync(RegistryDataMapSyncPayload<R> payload, IPayloadContext context) {
    context.enqueueWork(() -> {                                                   // lambda$handleDataMapSync$1
        RegistryAccess access = Minecraft.getInstance().level.registryAccess();    // dereferences level directly
```

The lambda dereferences `Minecraft.getInstance().level` with no null check. NeoForge therefore *depends* on the
level existing when the work runs; the guarantee cannot be added on the receive side without inventing a deferral
NeoForge does not have.

## 2. The sender, and what orders it

`net/neoforged/neoforge/common/NeoForgeEventHandler.onDpSync(OnDatapackSyncEvent)` constructs
`RegistryDataMapSyncPayload` and sends it to `event.getRelevantPlayers()` (`OnDatapackSyncEvent.getPlayerList()` on
the `/reload` path, the single joining player on the join path).

`RegistryDataMapNegotiation` is the *configuration*-phase task (`ICustomConfigurationTask`) that negotiates which
maps will sync — it is not the sender of this payload, so the configuration/play phase split is not the fault line.

Firing site, from a jar scan of the merged base: **exactly one class references `OnDatapackSyncEvent`** —
`net/minecraft/server/players/PlayerList`. The NeoForge patch that posts it from the player-join path survived the
merge, so the payload is sent where vanilla/NeoForge would send it, after the login packet.

Client side, `net/minecraft/client/multiplayer/ClientPacketListener.handleLogin` (merged) constructs
`ClientLevel` and calls `Minecraft.setLevel(level, reason)` inside that method (offsets 118 → 181), so by the end of
`handleLogin` the level exists. Both ends are vanilla-correct in isolation.

## 3. What is therefore ruled out

- **Not the kernel.** `grep -rn 'RegistryDataMapSync\|DataMapSync\|handleDataMapSync'` over
  `forbric-kernel/src` and `forbric-loader/src` returns nothing: the kernel neither sends nor handles this payload.
- **Not `OnDatapackSyncEvent` never firing / firing from the wrong place.** The only firing site is vanilla's
  `PlayerList`, i.e. the join path the payload needs.
- **Not a missing client `setLevel`.** The merged `handleLogin` does it.
- **Not the configuration-phase negotiation.** It is a separate task and a separate payload.

## 4. The remaining candidates, and the read that separates them

Ordered by how cheap the discrimination is:

1. **The payload is dispatched on the network thread rather than the main thread's play queue.** Vanilla play
   packets go through `PacketUtils.ensureRunningOnSameThread` before the listener sees them, so `handleLogin` and
   any later payload are executed as main-thread tasks *in arrival order* — login first. If our network bridge (or
   `setupNeoForgeNetwork(CLIENT)`'s registration path, or `CommonNetworkInteropInjector`) delivers the payload to
   `IPayloadContext` without that step, `enqueueWork` can run before `handleLogin`'s main-thread task does, which
   is exactly the observed NPE. **Read**: the client-side dispatch path for a play-phase custom payload — where
   `handleCustomPayload` is invoked from, and on which thread/queue its work lands.
2. **The payload is handled *inside* `handleLogin`'s window.** If the sync arrives between the login packet and
   `setLevel` (offset 181), the NPE is expected even with correct dispatch. **Read**: whether the join-path send
   happens after the client acknowledges login completion, or immediately after `ClientboundLoginPacket`.
3. **Player placement happens earlier than vanilla on our integrated-server path.** The client's own dedicated
   server is driven by the kernel's lifecycle; if `placeNewPlayer` (and therefore the sync) runs before the client
   has finished login handling, candidate 2 collapses into this. **Read**: the kernel's integrated-server start
   order versus the client's login handling.

Candidates 1 and 2/3 have opposite fixes — a dispatch/queue fix on our bridge versus a join-sequence ordering fix —
so the next step is the candidate-1 read, which is a single call-path question and no window.

### 4a. Candidate 1's first test was run, and it FAILED — recording it so nobody repeats it

The obvious probe for candidate 1 is whether `handleCustomPayload` still hands off to the main thread:
`PacketUtils.ensureRunningOnSameThread` appears **105 times** in the merged `ClientPacketListener` (once at the top
of most play-packet handlers) and **zero times** inside `handleCustomPayload` (318 lines, method range measured, not
eyeballed). That looks like a textbook merge casualty.

It is not. The same measurement on **both** inputs — `patched-mc-forge-1.21.1.jar` and
`patched-mc-neoforge-1.21.1.jar` — gives the identical result: 318-line handler, 0 `ensureRunningOnSameThread`,
105 in the class. The absence is upstream, present in both families before the merge, and therefore neither a merge
casualty nor evidence about our divergence.

So candidate 1 currently has **no** supporting evidence; the read that would support it is the harder one (where
`ClientPacketListener.handleCustomPayload` is *called from*, and whether the connection layer's queue already
guarantees main-thread, in-order execution for play-phase payloads — in which case the absence is correct and the
payload's arrival order relative to `handleLogin` is determined elsewhere). Candidate 2/3 inherit the same
requirement. This is the second time in this pass that checking the inputs overturned a confident reading of the
merged class; the lesson is the same one the campaign has been running on — the merged artifact alone cannot tell
you what the merge did.

## 5. Options and costs (no fix landed)

- **If candidate 1 holds** (dispatch without the main-thread hand-off): the fix is local to the client dispatch
  path and testable without a game — a unit test over the bridge that asserts a payload's work lands on the same
  queue `handleLogin` runs on, in arrival order. Cost: one commit, no harness decision. This is the only one of the
  three that is safe to land on present evidence.
- **If candidate 2 or 3 holds** (the send genuinely precedes the client's level): the honest fix is a deferral, and
  inventing one is a behaviour change NeoForge does not have — it needs a decision about whether the kernel's
  client join sequence should be reordered to match vanilla's, which is a harness/ordering call rather than a
  local repair. Cost: a client arm per attempt, plus the risk of masking a real ordering defect. **Recommendation:
  do not guess here.**
- **Regardless of which**: the confirmation that any fix worked is `joined world via quick-play` on a client arm
  pinned to the commit that carries it, with `compatibility_policy` and `mixin_fit` recorded on the row.

## 6. What this report does not claim

The discrimination in §4 is **not done**: candidates 1, 2 and 3 are all still open, and the reads that separate
them are named above rather than performed. No fix is landed. The claim here is only the negative half, which is
worth having on its own: the kernel does not touch this payload, the send site is vanilla's, and the receive side
has no guard — so the divergence is in the client's dispatch/join ordering, not in the payload or registration
path.

## 7. The separating read — performed; verdict is candidate 2's mechanism, enabled by an inline hand-off

Both receipts Main asked for, from bytecode:

**Thread hand-off (receipt 1).** `ClientboundCustomPayloadPacket.handle(ClientCommonPacketListener)` is a
one-instruction body — `listener.handleCustomPayload(packet)` — with **0** `ensureRunningOnSameThread` calls, and
`ClientPacketListener.handleCustomPayload` has **0** as well (105 elsewhere in that class, including
`handleLogin`). So the login packet is marshalled to the main thread and the custom payload is **not**: the payload
is dispatched wherever the connection's thread happens to be.

**Where the payload's work lands (receipt 2).** `ClientPayloadContext.enqueueWork(Runnable)`
(neoforge-runtime.jar):

```java
if (listener.getMainThreadEventLoop().isSameThread()) {
    runnable.run();                                    // INLINE — runs immediately, right here
    return CompletableFuture.completedFuture(null);
}
return NetworkRegistry.guard(listener.getMainThreadEventLoop().submit(runnable), payloadId);
```

**Verdict.** The hand-off is *conditional*. When the payload is handled on a network thread, the work is submitted
to the main-thread queue and therefore runs **after** `handleLogin`'s marshalled task — the level exists and
NeoForge's unguarded dereference is safe, which is why vanilla/NeoForge never sees this. When the payload is handled
**on the main thread**, `isSameThread()` is true and the work runs **inline, synchronously, at that moment** — so if
that moment is inside the client's login/level-loading window, `Minecraft.level` is still null and
`ClientRegistryManager` NPEs. The observed failure is therefore candidate 2 (arrival inside `handleLogin`'s
window), reached through the inline branch — not a missing hand-off of the kind candidate 1 described, and not an
earlier `placeNewPlayer` on our server side.

**Fix layer.** The send side is vanilla-correct and cannot be moved without inventing a deferral NeoForge does not
have. What differs is that our client path delivers this payload synchronously, on the main thread, before the level
exists. Two shapes, with costs:

- *Keep the delivery, defer the work*: make the kernel's synchronous delivery not run payload work inline ahead of
  the level — narrow, but it is a behaviour change in the delivery path and needs a client arm to show it does not
  perturb other payloads.
- *Reorder the client join sequence* so the payload is handled after the level exists, mirroring what the queue
  does for us on a real network thread: correct in principle, but a join-sequence change, which is a harness
  decision rather than a local repair, and it can mask a genuine ordering defect elsewhere.

Neither is a guess-safe local fix; the decision belongs to whoever owns the client join sequence, per the brief.

## 8. Option 2's general form, located but NOT landed — and why

Main chose the join-sequence ordering (option 2). Searched for the kernel hook it would live in: there is none.
`KernelLifecycle.setupNeoForgeNetwork(CLIENT)` only *invokes* NeoForge's own registration
(`NetworkRegistry.setup()` then `ClientNetworkRegistry.setup()`); it registers payload types and client handlers and
has no say in when a payload's work runs. Delivery is entirely NeoForge's:
packet → `handleCustomPayload` → handler → `ClientPayloadContext.enqueueWork`.

So option 2 **is expressible, but only as a bytecode repair**, in the same family as `ModLauncherClaimRewriter`:
transform `ClientPayloadContext.enqueueWork` so the `isSameThread()` shortcut is gone and the work always goes
through `getMainThreadEventLoop().submit(...)`. That mirrors exactly what the queue does for a real network thread,
applies to every payload rather than special-casing this one, and is a deleted branch rather than new logic.

Why it is not landed here, stated plainly rather than hidden in a commit:

- It changes NeoForge's semantics for **every** payload, not just this one. Today a payload handled on the main
  thread has its work run *inline*; afterwards it would run at the next queue drain — the same thread, the same
  tick, a later point in it. Any handler that relies on immediacy when already on the main thread would change
  behaviour, and nothing in the artifacts says whether any does.
- The required test — "the same-thread branch must not run payload work ahead of the level-existing point" — is a
  test of the *transformed* class, and it can be written without a JVM (transform the real class, assert the
  `isSameThread` branch is gone and the `submit` path is unconditional). But a test of the shape is not a test of
  the semantics, and I would be landing an unverified behavioural change in the client's payload path on the last
  of my budget, with the arm that could falsify it deliberately deferred.

**Recommendation instead**: land it with a fresh budget *and* the arm, or take the narrower honest form — keep the
inline branch but make the one thing it runs depend on the level existing, which is option 1's shape and which Main
has already rejected on the grounds of matching the platform's guarantee. I am recording both so the choice is
made on the receipts rather than on my last-minute judgement; the transform is small enough that whoever takes it
can land and test it inside one budget, and the confirmation is unchanged: `joined world via quick-play` on an arm
pinned to the commit that carries it, with `compatibility_policy` and `mixin_fit` on the row.

## 9. Landed: the branch removed, with its shape test and its falsification

Landed the transform written out in `ordering-transform.md`: new
`forbric-kernel/src/main/java/net/forbric/kernel/transform/PayloadWorkOrderingTransformer.java`, plus one
`chain.register(TransformPhase.COREMOD, new net.forbric.kernel.transform.PayloadWorkOrderingTransformer());`
beside the other COREMOD registrations in `KernelBoot` (next to `SplitterPacketContextInjector`, the other
network-path registration). Nothing existing changed.

What it does: in `ClientPayloadContext.enqueueWork(Runnable)` the `INVOKEVIRTUAL isSameThread` + `IFEQ` pair
becomes `POP` + a `GOTO` to the same label, so the `submit` path is entered unconditionally and the work is
queued instead of run inline. It is general — every payload that enqueues a `Runnable` — not a special case for
the data-map sync. Kill switch, in the family's style: `-Dforbric.payloadWorkOrdering=off` leaves the class
untouched (read inside `transform`, so the registration stays a single unguarded line, as
`SplitterPacketContextInjector` does).

Deliberately not touched: the `enqueueWork(Supplier)` overload carries the same shortcut, but the failed join
enqueued a `Runnable`, and widening the repair to the returning form is a separate change with its own arm.

**Shape test** (`forbric-kernel/src/test/java/net/forbric/kernel/transform/PayloadWorkOrderingTransformerTest.java`,
no JVM): it reads the real `net/neoforged/neoforge/network/handling/ClientPayloadContext.class` out of
`neoforge-runtime.jar`, runs the transformer over it, and asserts (a) the transformed `enqueueWork(Runnable)`
carries **no** `INVOKEVIRTUAL isSameThread` followed by a conditional jump, and (b) the `GOTO` that replaced the
`IFEQ` lands on the same block the `IFEQ` targeted — the `submit` + `NetworkRegistry.guard` path. The label's
block is compared rather than label object identity, because identity does not survive serialization. Its javadoc
states plainly what it does not cover: **it proves the branch is gone, not that removing it is safe for handlers
that currently rely on the inline form** — which is why the falsification is an arm, not a test.

Red/green, run in this session:
- **red without the transform**: with the shortcut-removal disabled, the test fails at "the transform must edit
  the real class, not hand it back" (`expected: not same but was: [B@…`) — 1 failed, 1 passed.
- **green with it**: `2 tests, 0 skipped, 0 failed`; the four anchor/chain tests that police Transformer
  declarations (`TransformerAnchorCensusTest`, `TransformChainAnchorAccountingTest`, `AnchorLedgerTest`,
  `TransformChainTest`) also pass beside it (30 tests, 0 failed, run together).

**Falsification criterion** (unchanged from `ordering-transform.md`): a client arm pinned to the commit carrying
the transform, `compatibility_policy` and `mixin_fit` recorded on the row. **`joined world via quick-play`** is
the confirmation; its absence, with the same `Network Protocol Error`, means the hand-off was not the ordering's
cause and candidate 2's mechanism needs re-reading. The `build-kernel.sh` sha and the arm outcome are in §10.

## 10. The arm, run against the landed commit — `joined world via quick-play` does NOT appear

**Build.** `build-kernel.sh` on a clean worktree at the landed commit `51f03efb` printed:

```
[build] kernel dir: /tmp/forbric-wt/forbric-kernel
[build] commit:    51f03efb
[build] /tmp/w7-kernel-build/forbric-kernel.jar
[build] sha256: 32bfa8e612abbb71fb6be7a238c634d9663b7c7d2a362afa185900bf5a750b47
[build] verified: zip readable, game side present, mtime Oct  3 07:21:26 2026
```

W7Harness's own clean-worktree build of the same commit reproduced the sha exactly, and the client arm was run
twice with this frozen kernel (`frozen-kernel-sha256.txt` = `32bfa8e6…`): `reports/2026-10-03-client-ordering2/`
(the first run, `…-client-ordering`, is identical line for line). Subject `sound-physics-remastered__fabric`, row:

```
run=STALL  exit=143  world=false  frames=0  strict=false  na=false  cause=crash  seconds=217
mod=OK  status_source=console:constructed-or-entrypoint  confirmed_required=0
compatibility_policy=continue   mixin_fit=default   catalog_mods_rows=0
kernel_sha256=32bfa8e6…
```

**`joined world via quick-play` does not appear** — the run's console contains zero occurrences of it. What appears
instead, verbatim from `console.log`, is the transform firing and then the class it produced being refused by the
verifier at load:

```
[07:28:38] [Netty Local Client IO #0/INFO]: [Forbric/PayloadOrdering] net.neoforged.neoforge.network.handling.ClientPayloadContext.enqueueWork now submits its work unconditionally — the inline same-thread path ran payload work before the client level existed, which is how the data-map sync died with "Network Protocol Error"
[07:28:38] [Netty Local Client IO #0/ERROR]: Exception caught in connection
java.lang.VerifyError: Expecting a stack map frame
Exception Details:
  Location:
    net/neoforged/neoforge/network/handling/ClientPayloadContext.enqueueWork(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture; @16: aload_1
  Reason:
    Expected stackmap frame at this location.
  Bytecode:
    0000000: 2ab4 0022 b900 3a01 00b6 0040 57a7 000e
    0000010: 2bb9 0045 0100 01b8 004b b02a b400 22b9
    0000020: 003a 0100 2bb6 004e 2ab4 0024 b800 54b0
    0000030:
  Stackmap Table:
    same_frame(@27)
```

and the disconnect it causes:

```
[07:28:38] [Render thread/WARN]: Client disconnected with reason: Internal Exception: java.lang.VerifyError: Expecting a stack map frame
Exception Details:
  Location:
    net/neoforged/neoforge/network/handling/ClientPayloadContext.enqueueWork(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture; @16: aload_1
  Reason:
    Expected stackmap frame at this location.
  Bytecode:
    0000000: 2ab4 0022 b900 3a01 00b6 0040 57a7 000e
    0000010: 2bb9 0045 0100 01b8 004b b02a b400 22b9
    0000020: 003a 0100 2bb6 004e 2ab4 0024 b800 54b0
    0000030:
  Stackmap Table:
    same_frame(@27)
```

The screens the run does reach, in order, verbatim: `GenericMessageScreen` → `BackupConfirmScreen` →
`LevelLoadingScreen` → `DisconnectedScreen`; it never announces `joined world via quick-play`, writes no frame
(`frames=0`) and never reaches a world. The class is loaded from `NetworkRegistry.handleModdedPayload` on the
configuration-phase payload path, so the client dies at the first modded payload, before the data-map sync of §7
is even reached.

**The falsification criterion was not exercised.** The artifact's criterion reads: absence of `joined world via
quick-play` **with the same `Network Protocol Error`** means the hand-off was not the ordering's cause. Here the
absence is not that: the client never attempted the join, because the transformed `enqueueWork` fails verification
at `@16` (the instruction immediately after the `pop`+`goto` this transform writes — `57 a7 000e` in the byte
dump above), and the JVM refuses the class before any of its code runs. So the arm did not test whether removing
the inline branch orders the data-map work after the level; it stopped on the transform's own output. Per the
criterion's own terms, that outcome is not a verdict on candidate 2, and nothing here is claimed as one.

Two harness facts, recorded because they bound the run: both runs were flagged `contended: true` (67.5% and 120%
CPU busy), and W7Harness closed a gap in its own client sweep — the client rows now carry `compatibility_policy`
and `mixin_fit` (the row above is from the re-run with both live), matching the server rows' axes.

**For the record — shape is not verification.** This was the first pass in this campaign where the *shape* test
was green while the artifact was still wrong: the branch was gone exactly as asserted, but the class no longer
verified. A test that reads instruction order and jump targets cannot see a `StackMapTable` the rewrite
invalidated. §11 adds the gate that can.

## 11. Fix: the branch rewrite has to recompute the frames, and now a gate holds it

Committed as `9bfc1e1b` (on top of `51f03efb`). One production change: the class is written with
`ClassWriter(COMPUTE_FRAMES)` instead of preserving the carrier's `StackMapTable`. The transform replaces a
conditional branch with `POP`+`GOTO`, so the instruction after the GOTO starts a new basic block and the carrier's
single `same_frame(@27)` no longer covers it — that is the `VerifyError` §10 quotes. `COMPUTE_FRAMES` recomputes
the whole method's frames; for the now-dead inline block ASM emits `NOP…ATHROW` under a self-consistent synthetic
frame, which is the standard shape a recomputed class carries. The method has no merge of two reference types (the
only branch left is the GOTO), so ASM's frame computation never calls `getCommonSuperClass` and never has to
resolve — let alone load — a game class.

The artifact's "leave the dead block in place — removing it would move frame offsets for no gain" was the wrong
call: leaving it is fine, but only once the frames are recomputed, and the artifact preserved them. That sentence
is superseded.

**Build.** `build-kernel.sh` on a clean worktree at `9bfc1e1b` printed
`sha256: eb8f8c5d4832083e451122526263afc904575f9332615cf9b804ab540692636a`.

**The gate.** The shape test now also carries `theTransformedClassLinksUnderTheRealVerifier`: it builds a
`URLClassLoader` over the staged `neoforge-runtime.jar`, merged base and forge runtime, defines the transformed
`ClientPayloadContext` through a child loader, and calls `Class.forName(OWNER, true, …)` so the JVM links and
verifies every method. The test JVM's own verifier is the check; no game is launched.

**Is a JVM-free form available? Not one with fidelity, and that is stated rather than papered over.** A
`CheckClassAdapter.verify` pass is what the artifact originally reached for, but its `SimpleVerifier` resolves
every referenced type through a `ClassLoader` and the game classes are not on the test classpath (the same reason
`MergedBasePipBridgeTest` and `PackMetadataFailSoftInjectorTest` use `BasicVerifier` instead). A `BasicVerifier`
analysis checks stack depth and locals at merges but does **not** compare against the declared frames, so it does
not catch this defect. A hand-written frame-coverage check would be re-implementing the verifier. So the honest
position: the frame/link check must be a JVM gate (the test JVM over staged jars, or the client arm), and the
JVM-free assertions remain the instruction-shape ones.

Verified here: with `COMPUTE_FRAMES` the class defines, links and initialises cleanly; reverting the writer to
`ClassWriter(0)` makes the new gate fail with the **same** `VerifyError: Expecting a stack map frame … @16:
aload_1 … same_frame(@27)` the client arm hit. The gate reproduces the field failure exactly, so it is the check
that would have caught it before the arm.

## 12. The re-run after the fix — the criterion IS met: the hand-off is not the cause

Re-run by W7Harness against the fixed commit `9bfc1e1b`, frozen kernel
`eb8f8c5d4832083e451122526263afc904575f9332615cf9b804ab540692636a` (W7Harness's own clean-worktree build reproduced
the sha exactly). Two runs, same subject and pin discipline: the transform **on**
(`reports/2026-10-03-client-frames/`) and the transform **off**, `-Dforbric.payloadWorkOrdering=off`
(`reports/2026-10-03-client-ordering-off/`).

**The fix took.** `VerifyError` count is **0** in both runs (was the sole failure in §10), and the transform's own
line appears exactly once in the ON run — `[Forbric/PayloadOrdering] …enqueueWork now submits its work
unconditionally…`.

**`joined world via quick-play` is absent in both runs** (`grep -c` = 0). The rows are otherwise identical:

```
ON   run=STALL exit=143 world=false frames=0 strict=false cause=crash seconds=218 contended=false
OFF  run=STALL exit=143 world=false frames=0 strict=false cause=crash seconds=217 contended=false
     compatibility_policy=continue   mixin_fit=default   (both)
```

Screens, both runs, in order: `GenericMessageScreen → BackupConfirmScreen → LevelLoadingScreen →
DisconnectedScreen`, disconnect reason `Network Protocol Error`.

**ON**, verbatim — the NPE is reached through the main-thread queue, which is the transform doing its job:

```
[Render thread/ERROR]: Failed to handle registry data map sync:
java.lang.NullPointerException: Cannot invoke "net.minecraft.client.multiplayer.ClientLevel.registryAccess()" because "net.minecraft.client.Minecraft.getInstance().level" is null
	at forbric/net.neoforged.neoforge.registries.ClientRegistryManager.lambda$handleDataMapSync$1(ClientRegistryManager.java:41)
	at forbric/net.minecraft.util.thread.BlockableEventLoop.lambda$submitAsync$0(BlockableEventLoop.java:60)
	at java.base/java.util.concurrent.CompletableFuture$AsyncSupply.run(CompletableFuture.java:1789)
	at forbric/net.minecraft.util.thread.BlockableEventLoop.doRunTask(BlockableEventLoop.java:148)
```

**OFF**, verbatim — the same NPE, reached inline instead:

```
[Render thread/ERROR]: Failed to handle registry data map sync:
java.lang.NullPointerException: Cannot invoke "net.minecraft.client.multiplayer.ClientLevel.registryAccess()" because "net.minecraft.client.Minecraft.getInstance().level" is null
	at net.neoforged.neoforge.network.handling.ClientPayloadContext.enqueueWork(ClientPayloadContext.java:31)
	at net.neoforged.neoforge.network.handling.MainThreadPayloadHandler.lambda$handle$0(MainThreadPayloadHandler.java:16)
	at net.neoforged.neoforge.registries.ClientRegistryManager.handleDataMapSync(ClientRegistryManager.java:39)
```

A normalised line-multiset diff of the two consoles leaves only the transform's own line and the two stack frames
above (submit path vs inline path) plus their consequence; every other observable — verdict, exit, screens,
disconnect reason, the `handleDataMapSync` NPE, zero frames, zero `joined world` — is identical. In both, the
console also shows, before the NPE, a `ClientboundLoginPacket` failure:
`java.lang.ClassCastException: class java.util.Optional cannot be cast to class net.minecraft.world.level.dimension.DimensionType`
at `ClientboundLoginPacket.handle(ClientboundLoginPacket.java:69)`, i.e. the login packet throws before any level is
created.

**Verdict, exactly to the artifact's criterion.** Absence of `joined world via quick-play` **with the same
`Network Protocol Error`** means the hand-off was not the ordering's cause and candidate 2's mechanism needs
re-reading. That is now established, with the transform verified and visibly active (submit path in the ON frame,
inline path in the OFF frame), and the two runs indistinguishable otherwise: the payload is handled before
`Minecraft.level` exists whether its work is submitted or run inline. No further claim is made here; in particular
the `ClientboundLoginPacket` `ClassCastException` is recorded as what the console shows, not diagnosed.

**Fixture note (W7Harness, recorded so the next comparison does not trip on it).** The two runs' spawn positions
differ (`(-29.5, 66.0, 54.5)` ON vs `(-26.5, 65.0, 49.5)` OFF), so the staged client world fixture is not
byte-identical run to run; a future comparison that depends on world *state* should reset the fixture explicitly.
It changes nothing above — the failure is identical in both.
