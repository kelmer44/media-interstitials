# HLS Interstitial Asset Resolution

This note summarizes how Media3 resolves HLS interstitial assets into ad
`MediaItem`s, why `AdGroup.mediaItems` can be null, and what that means for
marking or replaying ad breaks.

## Key Points

- `X-ASSET-URI` interstitials are resolved immediately during interstitial
  mapping.
- `X-ASSET-LIST` interstitials are resolved asynchronously later, close to the
  ad break playback position.
- `setWithAvailableAdGroup(...)` does not resolve asset lists. It only reuses
  `mediaItems` that are already present in the `AdPlaybackState`.
- For `X-ASSET-LIST`, `AdGroup.mediaItems[]` is expected to be null until the
  asset-list JSON has been loaded and parsed.
- The built-in unresolved asset-list queue is one-shot. Once loading starts, the
  pending entry is removed from the unresolved map.

## Main Resolution Flow

### 1. Playlist Parser Records the Asset-List URI

`HlsPlaylistParser` parses `#EXT-X-DATERANGE` tags with class
`com.apple.hls.interstitial`.

Relevant file:

```text
libraries/exoplayer_hls/src/main/java/androidx/media3/exoplayer/hls/playlist/HlsPlaylistParser.java
```

For interstitial tags, it reads:

- `X-ASSET-URI`
- `X-ASSET-LIST`
- `START-DATE`
- cue, duration, resume offset, playout limit, skip controls, and related fields

The parsed values become an `HlsMediaPlaylist.Interstitial`.

`HlsMediaPlaylist.Interstitial` enforces that exactly one of `assetUri` or
`assetListUri` is present.

Relevant file:

```text
libraries/exoplayer_hls/src/main/java/androidx/media3/exoplayer/hls/playlist/HlsMediaPlaylist.java
```

### 2. Content Timeline Update Maps Interstitials Into Ad Groups

`AdsMediaSource` forwards content timeline updates to the ads loader:

```text
libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/ads/AdsMediaSource.java
```

The relevant call is:

```java
adsLoader.handleContentTimelineChanged(this, newTimeline)
```

`HlsInterstitialsAdsLoader.handleContentTimelineChanged(...)` reads the HLS
manifest from the timeline, maps interstitials into `AdPlaybackState`, and
schedules asset-list resolution if new unresolved asset lists were discovered.

Relevant file:

```text
libraries/exoplayer_hls/src/main/java/androidx/media3/exoplayer/hls/HlsInterstitialsAdsLoader.java
```

### 3. X-ASSET-URI Is Resolved Immediately

Inside `insertOrUpdateInterstitialInAdGroup(...)`, if the interstitial has
`assetUri`, Media3 creates a `MediaItem` immediately:

```java
adPlaybackState =
    adPlaybackState.withAvailableAdMediaItem(
        adGroupIndex,
        adIndexInAdGroup,
        new MediaItem.Builder()
            .setUri(interstitial.assetUri)
            .build());
```

This means `AdGroup.mediaItems[adIndexInAdGroup]` is populated immediately for
`X-ASSET-URI` interstitials.

### 4. X-ASSET-LIST Is Stored As Unresolved

If the interstitial has `assetListUri`, Media3 does not create a `MediaItem`
during interstitial mapping.

Instead it stores an `AssetListData` object in:

```java
contentMediaSourceAdDataHolder.getUnresolvedAssetLists(adsId)
```

The map key is the ad group time:

```java
long assetListTimeUs =
    adGroup.timeUs != C.TIME_END_OF_SOURCE ? adGroup.timeUs : Long.MAX_VALUE;
```

At this point the ad group exists, but its `mediaItems` are still null.

## What Triggers X-ASSET-LIST Resolution

Resolution is position-driven. It is not triggered by
`setWithAvailableAdGroup(...)`.

The main trigger is in `handleContentTimelineChanged(...)` after mapping new
interstitials. If the number of unresolved asset lists changed and the content
item is the currently playing item, Media3 calls:

```java
maybeExecuteOrSetNextAssetListResolutionMessage(...)
```

That method:

1. Looks for the next unresolved asset list at or after the current period
   position.
2. Computes a resolution start time.
3. Starts loading immediately if the resolution time is close enough.
4. Otherwise schedules a `PlayerMessage` to run near the ad break.

The scheduled load normally starts about:

```text
3 * targetDurationUs
```

before the ad break start time.

Seek and seek-adjustment discontinuities can also trigger resolution through
`PlayerListener.onPositionDiscontinuity(...)`, which calls
`maybeExecuteOrSetNextAssetListResolutionMessage(...)` using the new content
position.

## Where MediaItems Are Actually Created For X-ASSET-LIST

The actual conversion from asset-list JSON entries to `MediaItem`s happens in
`LoaderCallback.onLoadCompleted(...)`.

The asset-list JSON is parsed by:

```text
libraries/exoplayer_hls/src/main/java/androidx/media3/exoplayer/hls/AssetListParser.java
```

The parser expects JSON like:

```json
{
  "ASSETS": [
    {
      "URI": "https://example.com/ad.m3u8",
      "DURATION": 30.0
    }
  ]
}
```

For each parsed asset, `LoaderCallback.onLoadCompleted(...)` creates:

```java
MediaItem mediaItem =
    new MediaItem.Builder()
        .setUri(asset.uri)
        .setMimeType(MimeTypes.APPLICATION_M3U8)
        .build();
```

Then it writes the item into the ad playback state:

```java
adPlaybackState =
    adPlaybackState.withAvailableAdMediaItem(
        assetListData.adGroupIndex, adIndex, mediaItem);
```

`AdPlaybackState.withAvailableAdMediaItem(...)` delegates to
`AdGroup.withAdMediaItem(...)`, which sets:

```java
mediaItems[index] = mediaItem;
states[index] = AD_STATE_AVAILABLE;
```

This is the point where `AdGroup.mediaItems[]` becomes non-null for an
`X-ASSET-LIST` ad.

## Why setWithAvailableAdGroup Sees Null MediaItems

`HlsInterstitialsAdsLoader.setWithAvailableAdGroup(...)` only loops over the
existing ad group and re-marks ads as available if they already have a
`mediaItem`.

It does not:

- fetch asset-list JSON,
- parse `X-ASSET-LIST`,
- create `MediaItem`s,
- expand an asset list into multiple ads,
- or reinsert unresolved asset-list entries.

For an unresolved `X-ASSET-LIST` ad group, null `mediaItems` are expected.

There is also an important side effect: `setWithAvailableAdGroup(...)` calls
`removeUnresolvedAssetListOfAdGroup(...)`. If the group is still unresolved,
calling this method can remove the pending asset-list entry before Media3 has a
chance to load it.

## Live Join And Old Ad Groups

For live playback, old ad groups may be marked skipped so that joining the live
stream does not immediately play old ads.

That makes sense for the join path, but it creates a separate replay problem:
when the user scrubs back to an old ad break, the ad group must become playable
again.

Media3 already has `AdPlaybackState.withResetAdGroup(...)`, which resets final
states:

- `PLAYED`
- `SKIPPED`
- `ERROR`

back to:

- `AVAILABLE` if `mediaItems[i] != null`
- `UNAVAILABLE` if `mediaItems[i] == null`

For unresolved `X-ASSET-LIST` ads, resetting generally returns the ad to
`UNAVAILABLE`, because `mediaItems[i]` is null. That can make the ad group
eligible again, but it still does not fetch the asset list by itself.

## Replaying Old X-ASSET-LIST Ad Groups

For the business rule "scrubbing back to previously played ads should retrigger
asset resolution, not reuse the previous asset list", the built-in data model is
not enough as-is.

The current unresolved asset-list map is a pending queue, not a durable source
of truth:

- `getNextAssetResolution(...)` removes the unresolved entry when loading starts.
- Manual manipulation methods remove unresolved entries for the affected group.
- Once an asset list has resolved, the original pending entry is gone.

To support fresh resolution on replay, Media3 would need a durable record of the
original interstitial asset-list metadata, separate from the one-shot unresolved
queue.

A Media3-side design would look like:

1. Keep old live ad groups skipped on join.
2. Store durable asset-list definitions by interstitial ID or ad group identity.
3. On scrub-back into a skipped/played asset-list ad group:
   - reset that ad group to an unresolved state,
   - clear any previously resolved media items if fresh resolution is required,
   - reinsert a fresh `AssetListData` into the unresolved queue,
   - call `maybeExecuteOrSetNextAssetListResolutionMessage(...)`.
4. Let the normal asset-list load path fetch current JSON and repopulate
   `mediaItems`.

## Can This Be Done Without Modifying Media3?

Only partially.

Without Media3 changes, an app can fetch an asset list itself and call:

```java
hlsInterstitialsAdsLoader.setWithAvailableAdMediaItem(
    adGroupIndex,
    adIndexInAdGroup,
    mediaItemFromFreshAssetList);
```

This can work if the asset list always resolves to exactly one media item.

It does not fully cover general `X-ASSET-LIST` behavior because the public API
does not expose a way to:

- reinsert old entries into the loader's private unresolved asset-list queue,
- rerun the built-in asset-list parser and loader for a consumed interstitial,
- clear old resolved media items while preserving the interstitial placeholder,
- expand one placeholder into multiple ads from a fresh asset list,
- or trigger the built-in asset-list lifecycle/listener path on demand.

For full support of fresh asset-list resolution when scrubbing back to old live
ad groups, the change belongs inside `HlsInterstitialsAdsLoader`.

