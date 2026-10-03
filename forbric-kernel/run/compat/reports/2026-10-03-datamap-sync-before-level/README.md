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
