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
cause and candidate 2's mechanism needs re-reading. The `build-kernel.sh` sha and the arm outcome are recorded in
§10 once they land.
