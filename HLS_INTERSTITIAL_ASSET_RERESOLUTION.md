# HLS Interstitial Asset-List Resolution and Re-resolution

This note describes how `HlsInterstitialsAdsLoader` discovers HLS interstitials, queues
`X-ASSET-LIST` resolution, and why the current Media3 model does not support a product policy where
scrubbing back can cause any interstitial asset list to be resolved again.

The second half is a Media3 feature request that keeps this behavior opt-in and generic.

## Current Processing Flow

### 1. The loader starts with per-adsId state

`HlsInterstitialsAdsLoader.start(...)` creates per-source state keyed by `adsId`.

`ContentMediaSourceAdDataHolder.startContentSource(...)` initializes:

- `activeEventListeners`
- `activeAdPlaybackStates`
- `insertedInterstitialIds`
- `unresolvedAssetLists`
- `assetListDefinitions`
- `pendingSnapInResolutions`

The active resolution queue is `unresolvedAssetLists`. It is a `TreeMap<Long, AssetListData>` keyed
by the ad group's period position. The ordering is important because the scheduler picks the first
eligible unresolved asset list.

Relevant code:

- `HlsInterstitialsAdsLoader.start(...)`
- `ContentMediaSourceAdDataHolder.startContentSource(...)`
- `ContentMediaSourceAdDataHolder.getUnresolvedAssetLists(...)`

### 2. Timeline updates map playlist interstitials into AdPlaybackState

`handleContentTimelineChanged(...)` reads the latest `HlsManifest` from the content timeline and
maps `HlsMediaPlaylist.interstitials` into an `AdPlaybackState`.

For live streams this happens through:

```java
mapInterstitialsForLive(
    window.mediaItem,
    mediaPlaylist,
    adPlaybackState,
    window.positionInFirstPeriodUs,
    window.defaultPositionUs);
```

`mapInterstitialsForLive(...)`:

- filters out already-inserted interstitial IDs
- ignores unsupported live postrolls
- discards interstitials outside the current live playlist window/tolerance
- converts playlist positions into period positions
- inserts or updates ad groups before the live postroll placeholder
- calls `insertOrUpdateInterstitialInAdGroup(...)`

### 3. X-ASSET-LIST interstitials are added to unresolvedAssetLists

Inside `insertOrUpdateInterstitialInAdGroup(...)`, there are two paths:

- `X-ASSET-URI`: the ad media item is available immediately via
  `AdPlaybackState.withAvailableAdMediaItem(...)`.
- `X-ASSET-LIST`: the loader creates an `AssetListData` object and puts it into
  `unresolvedAssetLists`.

For `X-ASSET-LIST`, the relevant operation is:

```java
checkNotNull(contentMediaSourceAdDataHolder.getUnresolvedAssetLists(adsId))
    .put(assetListTimeUs, assetListData);
contentMediaSourceAdDataHolder.putAssetListDefinition(adsId, assetListTimeUs, assetListData);
```

`assetListDefinitions` is a retained definition map. `unresolvedAssetLists` is the active queue.
Scheduling only looks at `unresolvedAssetLists`.

### 4. Timeline updates schedule asset-list resolution only when new unresolved entries appear

`handleContentTimelineChanged(...)` records the unresolved count before mapping interstitials:

```java
int assetListCount = contentMediaSourceAdDataHolder.getUnresolvedAssetListCount(adsId);
```

After mapping, it compares the count:

```java
if (assetListCount != contentMediaSourceAdDataHolder.getUnresolvedAssetListCount(adsId)
    && player != null
    && Objects.equals(window.mediaItem, player.getCurrentMediaItem())) {
  maybeExecuteOrSetNextAssetListResolutionMessage(...);
}
```

So the normal discovery path is:

1. Playlist/timeline update arrives.
2. New `X-ASSET-LIST` interstitial is mapped.
3. `unresolvedAssetLists` count changes.
4. The loader schedules or immediately starts resolution.

### 5. The scheduler picks the first unresolved asset list at or after the lookup position

`maybeExecuteOrSetNextAssetListResolutionMessage(...)` computes the current period position:

```java
long currentPeriodPositionUs = positionInFirstPeriodUs + windowPositionUs;
```

It then calls:

```java
RunnableAtPosition nextAssetResolution = getNextAssetResolution(adsId, currentPeriodPositionUs);
```

`getNextAssetResolution(...)` iterates the `TreeMap` in ascending order and selects the first entry
where:

```java
periodPositionUs <= assetListTimeUs
```

This is the point where asset lists "behind" the lookup position are skipped. The scheduler is
designed to preload upcoming interstitials, not revisit older ones.

If an eligible entry is found, the scheduler computes when to resolve:

```java
resolutionStartTimeUs =
    max(currentPeriodPositionUs, adStartTimeUs - 3 * targetDurationUs);
```

If the resolution point is effectively now, the runnable starts immediately. Otherwise the loader
creates a `PlayerMessage` at the future playback position.

Only one `pendingAssetListResolutionMessage` is maintained at a time.

### 6. Starting a load removes the unresolved entry

The runnable returned by `getNextAssetResolution(...)` removes the entry from
`unresolvedAssetLists` before starting the load:

```java
if (assetListDataMap.remove(assetListTimeUs) != null) {
  startLoadingAssetList(assetListData);
}
```

This means that once an asset-list load is selected, it is no longer in the active queue.

`startLoadingAssetList(...)` starts a `Loader` request for the JSON document and emits
`onAssetListLoadStarted(...)`.

### 7. Load completion turns unresolved placeholders into available ads

`LoaderCallback.onLoadCompleted(...)` applies the returned assets to `AdPlaybackState`.

It first validates that the ad was not manually changed while the load was in flight:

```java
int assetListAdState =
    adPlaybackState != null
        ? adPlaybackState.getAdGroup(assetListData.adGroupIndex)
            .states[assetListData.adIndexInAdGroup]
        : AD_STATE_ERROR;

if (assetListAdState != AD_STATE_UNAVAILABLE) {
  maybeContinueAssetResolution();
  notify load failed/cancelled;
  return;
}
```

If the state is still `AD_STATE_UNAVAILABLE`, the loader:

- expands the ad group if the asset list returned multiple assets
- sets durations
- creates concrete ad `MediaItem`s
- marks those ads available via `withAvailableAdMediaItem(...)`
- adjusts `contentResumeOffsetUs`
- publishes the updated `AdPlaybackState`
- calls `maybeContinueAssetResolution()` to schedule the next unresolved asset list

### 8. Other scheduler entry points

`maybeExecuteOrSetNextAssetListResolutionMessage(...)` is not called on every playback tick. It is
called from specific state transitions:

- `handleContentTimelineChanged(...)`, when new unresolved asset lists were discovered for the
  current media item.
- Position discontinuity handling for seek/seek adjustment. This can snap the lookup position back
  to an unresolved ad cue if one exists.
- `maybeContinueAssetResolution()` after an asset-list load completes, fails, or is canceled.
- Local/non-upstream helper flows such as `setWithResetAdGroup(...)` in this branch.

There is no separate background queue. `unresolvedAssetLists` plus one pending `PlayerMessage` is
the queueing mechanism.

## When unresolvedAssetLists Is Modified

### Initialized

`startContentSource(...)` creates an empty `TreeMap` for the `adsId`.

### Added

`insertOrUpdateInterstitialInAdGroup(...)` adds an entry when it maps an `X-ASSET-LIST`
interstitial.

This branch also has a local path that can re-add an entry from `assetListDefinitions` when an ad
group is reset.

### Removed

Entries are removed when `getNextAssetResolution(...)` selects them and the runnable begins loading.

Entries can also be removed when the app manually changes an ad/group state through methods that
call `removeUnresolvedAssetListOfAdGroup(...)`, such as skip/available operations.

### Cleared

`stopContentSource(...)` removes the whole unresolved map for the `adsId`.

## Why Scrub-Back Re-resolution Is Not Achievable Today

The desired product behavior is:

1. The user scrubs backward.
2. Ads before the new playback position are treated as skipped.
3. The next relevant ad is made playable.
4. If that ad uses `X-ASSET-LIST`, its JSON should be resolved again, even if it was resolved or
   played before.
5. If the asset list was never resolved because it was "behind" the old playback position, scrubbing
   before it should allow first-time resolution.

The current Media3 model does not provide a generic way to do this.

### Resolved asset lists are no longer unresolved

After an `X-ASSET-LIST` is selected for loading, its entry is removed from `unresolvedAssetLists`.
After the load completes, the returned assets become concrete `MediaItem`s in `AdPlaybackState`.

At that point Media3 sees the group as resolved ad media, not as an asset-list request that can be
run again.

### Changing ad state does not recreate the unresolved queue entry

Marking an ad group `AVAILABLE` or `SKIPPED` only changes `AdPlaybackState`. It does not put an
`AssetListData` back into `unresolvedAssetLists`.

The scheduler only resolves entries from `unresolvedAssetLists`, so changing playback state alone
cannot trigger asset-list loading.

### Resetting a group replays old media; it does not invalidate dynamic asset decisions

`AdPlaybackState.withResetAdGroup(...)` resets final states:

- if an ad still has a media item, it becomes `AD_STATE_AVAILABLE`
- if it has no media item, it becomes `AD_STATE_UNAVAILABLE`

For a previously resolved asset list, the ad has concrete media items. Resetting therefore makes the
old assets replayable. It does not clear media items, does not restore the original unresolved
placeholder, and does not make the asset-list JSON eligible for loading again.

This is the core mismatch for dynamic asset lists.

### LoaderCallback requires the target ad to still be unavailable

Even if an app re-adds an asset-list entry manually, the existing load completion path rejects the
result unless the target ad is still `AD_STATE_UNAVAILABLE`.

If reset left the ad as `AVAILABLE`, the loaded result is ignored as a manual-change conflict.

### The scheduler skips unresolved entries behind the lookup position

`getNextAssetResolution(...)` only selects entries where:

```java
periodPositionUs <= assetListTimeUs
```

If the scheduler is called with the current playback position after the ad cue, that unresolved
entry is skipped. The seek path can compensate only if an unresolved entry already exists and the
lookup position is snapped back to that cue.

For a previously resolved ad, no unresolved entry exists, so there is nothing to snap to.

### Already-inserted interstitial IDs prevent remapping from timeline updates

Timeline updates do not naturally recreate an ad group for a previously inserted interstitial. The
loader tracks inserted interstitial IDs to avoid duplicating groups as live playlists refresh.

That is correct for the default Media3 model, but it prevents using playlist refresh alone as a
generic re-resolution mechanism.

## Feature Request for Media3

### Title

Add opt-in support for invalidating and re-resolving HLS `X-ASSET-LIST` interstitials.

### Business Case

Some products use HLS interstitial `X-ASSET-LIST` endpoints as dynamic ad decision points. The
asset-list response may depend on current session state, user state, time, entitlement, targeting,
or business rules. In these products, scrubbing back to an interstitial should be able to trigger a
fresh asset-list resolution instead of replaying the assets that were resolved earlier.

The product policy may also require:

- marking ad groups before the new playback position as skipped
- making the next relevant ad group playable
- resolving an older `X-ASSET-LIST` for the first time if it was previously skipped because playback
  had already passed it
- re-resolving an already resolved `X-ASSET-LIST` because the response can change

This behavior is not mandated by HLS and should not be Media3's default. Media3 should remain
policy-neutral and provide generic primitives that apps can opt into.

### Current Limitation

Media3 currently treats `X-ASSET-LIST` resolution as a one-time preparation step for upcoming
interstitials:

- once selected, an asset-list entry is removed from the unresolved queue
- once loaded, concrete ad media items are stored in `AdPlaybackState`
- seeking backward does not invalidate resolved assets
- public reset/skip/available APIs change playback state but do not restore the unresolved
  asset-list placeholder
- there is no public API to invalidate a resolved `X-ASSET-LIST` interstitial and schedule it for
  resolution again

### Desired Capability

Media3 should allow apps to opt into this flow:

1. Identify an HLS interstitial ad backed by `X-ASSET-LIST`.
2. Invalidate any previously resolved assets for that interstitial.
3. Restore the ad to an unresolved state.
4. Add the asset-list request back to the loader's pending resolution set.
5. Schedule or immediately start resolution using a caller-provided or current playback position.
6. Let the existing asset-list load callback mark the newly returned assets available.

The app, not Media3, should decide when this happens.

### Proposal A: Narrow HLS Loader API

Add an HLS-specific invalidation method to `HlsInterstitialsAdsLoader`.

Example:

```java
public void invalidateAssetListAd(int adGroupIndex, int adIndexInAdGroup);
```

Possible semantics:

- valid only for ads originally created from `X-ASSET-LIST`
- clears resolved ad media for that asset-list interstitial
- restores the target ad to `AD_STATE_UNAVAILABLE`
- restores the asset-list request to the pending unresolved set
- cancels any stale pending resolution message if needed
- schedules resolution if the current player/timeline can resolve it
- no-op or throws if the ad was not asset-list backed

This API is narrow, explicit, and policy-neutral. Apps can combine it with existing skip/reset
methods to implement their own seek behavior.

Example app policy:

```java
for (int groupIndex : adGroupsBeforeSeekTarget) {
  hlsInterstitialsAdsLoader.setWithSkippedAdGroup(groupIndex);
}
hlsInterstitialsAdsLoader.invalidateAssetListAd(targetGroupIndex, targetAdIndex);
```

### Proposal B: Policy Callback for Seek Handling

Add an optional callback that lets an app customize ad-state transitions on seek without Media3
hard-coding a policy.

Example:

```java
public interface InterstitialSeekPolicy {
  SeekAction onSeekToInterstitial(
      AdPlaybackState adPlaybackState,
      int targetAdGroupIndex,
      int targetAdIndexInAdGroup);
}
```

Possible `SeekAction` values:

- keep existing state
- skip previous ad groups
- replay existing resolved assets
- invalidate and re-resolve asset list
- skip target ad

This keeps policy outside Media3 while allowing the loader to perform operations that require its
internal metadata.

### Proposal C: Lower-level State Primitive Plus Loader API

Add a state primitive that can clear resolved ad media and return an ad to `AD_STATE_UNAVAILABLE`,
then expose an HLS loader method that uses it for asset-list ads.

Example state primitive:

```java
AdPlaybackState withAdMediaCleared(int adGroupIndex, int adIndexInAdGroup);
```

Example loader method:

```java
public void requeueAssetListResolution(int adGroupIndex, int adIndexInAdGroup);
```

This is more flexible but has a wider API surface. It may be harder to keep correct for ad groups
expanded from multi-asset asset-list responses.

### Recommended Direction

Proposal A is the smallest and most compatible path.

The required logic is naturally owned by `HlsInterstitialsAdsLoader` because the loader already has
the HLS interstitial metadata and asset-list definitions. A generic `AdPlaybackState` reset method
does not know whether an ad came from `X-ASSET-LIST`, what URI should be reloaded, or how to restore
the placeholder semantics safely.

Media3's default behavior can remain unchanged:

- resolved asset lists stay resolved
- played/skipped ads remain final
- scrub-back does not automatically re-request ads

Apps with dynamic asset-list requirements can opt into invalidation and define their own seek
policy.

