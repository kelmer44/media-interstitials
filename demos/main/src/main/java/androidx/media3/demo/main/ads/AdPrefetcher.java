package androidx.media3.demo.main.ads;

import static androidx.media3.common.util.Assertions.checkArgument;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.UriUtil;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.demo.main.DemoUtil;
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader;
import androidx.media3.exoplayer.hls.HlsManifest;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser;
import androidx.media3.exoplayer.upstream.ParsingLoadable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Demo proposal: resolve ads once and warm media bytes, without changing playback state. */
@UnstableApi
final class AdPrefetcher {
  private static final String TAG = "AdPrefetch";
  private static final long MAX_BYTES_PER_BREAK = 16L * 1024 * 1024;
  private static final long MAX_WALL_TIME_MS = 15_000;
  private static final long DEFAULT_RESOLVE_LOOKAHEAD_US = 30_000_000;
  private static final long DEFAULT_CACHE_DURATION_US = 5_000_000;

  private final HlsInterstitialsAdsLoader loader;
  private final DataSource.Factory upstream;
  private final CacheDataSource.Factory cacheFactory;
  private final long resolveLookaheadUs;
  private final long cacheDurationUs;
  private final Timeline.Window window = new Timeline.Window();
  private final Timeline.Period period = new Timeline.Period();
  private final Map<String, Task> tasks = new HashMap<>();
  @Nullable private ExecutorService executor;
  @Nullable private Object adsId;
  @Nullable private HlsMediaPlaylist lastPlaylist;
  private final Map<String, PrefetchMarker> markers = new HashMap<>();

  public AdPrefetcher(Context context, HlsInterstitialsAdsLoader loader) {
    this(
        DemoUtil.getHttpDataSourceFactory(context),
        DemoUtil.getAdPrefetchDataSourceFactory(context),
        loader);
  }

  AdPrefetcher(
      DataSource.Factory upstream,
      CacheDataSource.Factory cacheFactory,
      HlsInterstitialsAdsLoader loader) {
    this(upstream, cacheFactory, loader, DEFAULT_RESOLVE_LOOKAHEAD_US, DEFAULT_CACHE_DURATION_US);
  }

  /** Cache duration is an app policy, independent of the marker's scheduled break duration. */
  AdPrefetcher(
      DataSource.Factory upstream,
      CacheDataSource.Factory cacheFactory,
      HlsInterstitialsAdsLoader loader,
      long resolveLookaheadUs,
      long cacheDurationUs) {
    checkArgument(resolveLookaheadUs >= 0 && cacheDurationUs > 0);
    this.loader = loader;
    this.upstream = upstream;
    this.cacheFactory = cacheFactory;
    this.resolveLookaheadUs = resolveLookaheadUs;
    this.cacheDurationUs = cacheDurationUs;
  }

  public void reset() {
    for (Task task : tasks.values()) {
      task.cancel();
    }
    tasks.clear();
    adsId = null;
    lastPlaylist = null;
    markers.clear();
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
  }

  /** Called on the application thread on timeline/position changes and periodic progress. */
  public void update(@Nullable Player player) {
    if (player == null || player.getCurrentTimeline().isEmpty()) {
      reset();
      return;
    }
    Timeline timeline = player.getCurrentTimeline();
    timeline.getWindow(player.getCurrentMediaItemIndex(), window);
    timeline.getPeriod(player.getCurrentPeriodIndex(), period);
    Object currentAdsId = period.adPlaybackState.adsId;
    if (!Objects.equals(adsId, currentAdsId)) {
      reset();
      adsId = currentAdsId;
    }
    if (!(window.manifest instanceof HlsManifest) || currentAdsId == null) {
      reset();
      return;
    }
    HlsMediaPlaylist playlist = ((HlsManifest) window.manifest).mediaPlaylist;
    if (playlist != lastPlaylist) {
      // Keep matched markers while their future ad groups remain visible, even if the marker
      // daterange itself leaves a sliding live playlist.
      for (PrefetchMarker marker : PrefetchMarker.parse(playlist)) {
        markers.put(marker.interstitialId, marker);
      }
      lastPlaylist = playlist;
    }
    long positionUs = Util.msToUs(player.getContentPosition());
    long bufferedPositionUs = Util.msToUs(player.getContentBufferedPosition());
    long resolveThroughUs = Math.max(bufferedPositionUs, positionUs + resolveLookaheadUs);
    Set<String> activeKeys = new HashSet<>();
    for (PrefetchMarker marker : new ArrayList<>(markers.values())) {
      String key = marker.interstitialId + "@" + marker.startDateUnixUs;
      List<MediaItem> items = new ArrayList<>();
      List<Long> durations = new ArrayList<>();
      boolean unresolved = false;
      long adStartUs = Long.MAX_VALUE;
      for (int p = window.firstPeriodIndex; p <= window.lastPeriodIndex; p++) {
        timeline.getPeriod(p, period);
        AdPlaybackState state = period.adPlaybackState;
        for (int g = state.removedAdGroupCount; g < state.adGroupCount; g++) {
          AdPlaybackState.AdGroup group = state.getAdGroup(g);
          for (int a = 0; a < group.ids.length; a++) {
            if (!marker.interstitialId.equals(group.ids[a])) {
              continue;
            }
            if (group.timeUs != C.TIME_END_OF_SOURCE) {
              adStartUs = Math.min(adStartUs, period.getPositionInWindowUs() + group.timeUs);
            }
            MediaItem item = group.mediaItems[a];
            if (group.states[a] == AdPlaybackState.AD_STATE_UNAVAILABLE || item == null) {
              unresolved = true;
            } else if (group.states[a] == AdPlaybackState.AD_STATE_AVAILABLE) {
              items.add(item);
              durations.add(group.durationsUs[a]);
            }
          }
        }
      }
      long resolvePositionUs = marker.startDateUnixUs - playlist.startTimeUs;
      // Like SXM, use buffer reach or a playhead lookahead, including a missed prefetch marker
      // on join. END-DATE describes the marker for display; it is not a resolution deadline.
      if (adStartUs == Long.MAX_VALUE
          || positionUs >= adStartUs
          || resolvePositionUs > resolveThroughUs
          || player.isPlayingAd()) {
        if (adStartUs == Long.MAX_VALUE && marker.endDateUnixUs < playlist.startTimeUs) {
          markers.remove(marker.interstitialId);
        }
        continue;
      }
      activeKeys.add(key);
      // Busy requests are retried on progress. A resolved list is never requested again here.
      if (loader.prefetchInterstitial(currentAdsId, marker.interstitialId)) {
        Log.i(TAG, "Resolving asset list for " + marker.interstitialId);
      }
      Task existing = tasks.get(key);
      // An explicit asset-list reset may produce different URLs under the same interstitial ID.
      if (existing != null && !existing.items.equals(items)) {
        existing.cancel();
        tasks.remove(key);
      }
      if (!unresolved && !items.isEmpty() && !tasks.containsKey(key)) {
        if (executor == null) {
          executor = Executors.newSingleThreadExecutor();
        }
        Task task = new Task(marker, items, durations);
        tasks.put(key, task);
        Log.i(
            TAG,
            "PREFETCH_TRIGGERED id="
                + marker.interstitialId
                + " trigger="
                + (bufferedPositionUs >= resolvePositionUs ? "BUFFER" : "LOOKAHEAD")
                + " playheadMs="
                + Util.usToMs(positionUs)
                + " bufferedMs="
                + Util.usToMs(bufferedPositionUs)
                + " markerMs="
                + Util.usToMs(resolvePositionUs)
                + " adStartMs="
                + Util.usToMs(adStartUs)
                + " cacheBudgetMs="
                + Util.usToMs(cacheDurationUs));
        task.future = executor.submit(task);
      }
    }
    Iterator<Map.Entry<String, Task>> iterator = tasks.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<String, Task> entry = iterator.next();
      if (!activeKeys.contains(entry.getKey())) {
        entry.getValue().cancel();
        iterator.remove();
      }
    }
  }

  private final class Task implements Runnable {
    private final PrefetchMarker marker;
    private final List<MediaItem> items;
    private final List<Long> durations;
    private final Set<String> resources = new HashSet<>();
    private volatile boolean cancelled;
    @Nullable private volatile CacheWriter writer;
    @Nullable private Future<?> future;
    private long downloadedBytes;
    private long deadlineMs;
    private int cachedMediaSegments;

    Task(PrefetchMarker marker, List<MediaItem> items, List<Long> durations) {
      this.marker = marker;
      this.items = items;
      this.durations = durations;
    }

    public void cancel() {
      cancelled = true;
      CacheWriter currentWriter = writer;
      if (currentWriter != null) {
        currentWriter.cancel();
      }
      if (future != null) {
        future.cancel(true);
      }
    }

    @Override
    public void run() {
      deadlineMs = SystemClock.elapsedRealtime() + MAX_WALL_TIME_MS;
      Log.i(
          TAG,
          "Warming "
              + marker.interstitialId
              + " for "
              + cacheDurationUs
              + "us; scheduled break duration="
              + marker.scheduledDurationUs
              + "us");
      try {
        long remainingUs = cacheDurationUs;
        for (int i = 0; i < items.size() && remainingUs > 0; i++) {
          MediaItem.LocalConfiguration config = items.get(i).localConfiguration;
          if (config == null) {
            continue;
          }
          long durationUs = durations.get(i);
          long requestedUs =
              durationUs == C.TIME_UNSET ? remainingUs : Math.min(remainingUs, durationUs);
          warmAsset(config.uri, requestedUs);
          remainingUs -= requestedUs;
        }
        Log.i(
            TAG,
            "PREFETCH_COMPLETE id="
                + marker.interstitialId
                + " mediaSegments="
                + cachedMediaSegments
                + " newCachedBytes="
                + downloadedBytes);
      } catch (IOException | RuntimeException e) {
        Log.i(
            TAG,
            "Stopped warming "
                + marker.interstitialId
                + "; new cached bytes="
                + downloadedBytes
                + "; "
                + e.getMessage());
      } finally {
        writer = null;
      }
    }

    private void checkActive() throws IOException {
      if (cancelled
          || Thread.currentThread().isInterrupted()
          || SystemClock.elapsedRealtime() >= deadlineMs
          || downloadedBytes >= MAX_BYTES_PER_BREAK) {
        throw new IOException("cancelled or prefetch budget exhausted");
      }
    }

    private HlsPlaylist loadPlaylist(Uri uri, HlsPlaylistParser parser) throws IOException {
      checkActive();
      // Never persist playlists: asset URLs can point at dynamic manifests (including shim URLs).
      return ParsingLoadable.load(upstream.createDataSource(), parser, uri, C.DATA_TYPE_MANIFEST);
    }

    private void warmAsset(Uri uri, long durationUs) throws IOException {
      if (durationUs <= 0) {
        return;
      }
      HlsPlaylist manifest = loadPlaylist(uri, new HlsPlaylistParser());
      if (manifest instanceof HlsMediaPlaylist) {
        warmPlaylist((HlsMediaPlaylist) manifest, durationUs);
      } else {
        HlsMultivariantPlaylist multivariant = (HlsMultivariantPlaylist) manifest;
        // Warm each rendition so adaptive playback can reuse the bytes without forcing a track.
        // The per-break byte/time budgets bound large ladders.
        for (Uri playlistUri : multivariant.mediaPlaylistUrls) {
          HlsPlaylist child = loadPlaylist(playlistUri, new HlsPlaylistParser(multivariant, null));
          if (child instanceof HlsMediaPlaylist) {
            warmPlaylist((HlsMediaPlaylist) child, durationUs);
          }
        }
      }
    }

    private void warmPlaylist(HlsMediaPlaylist playlist, long durationUs) throws IOException {
      if (!playlist.hasEndTag) {
        throw new IOException("media prefetch currently requires a complete VOD ad playlist");
      }
      for (HlsMediaPlaylist.Segment segment : playlist.segments) {
        // Relative time is intentional: ad playlists may also carry epoch PROGRAM-DATE-TIME.
        if (segment.relativeStartTimeUs >= durationUs) {
          break;
        }
        if (segment.hasGapTag) {
          continue;
        }
        if (segment.initializationSegment != null) {
          cacheSegment(playlist.baseUri, segment.initializationSegment);
        }
        cacheSegment(playlist.baseUri, segment);
        cachedMediaSegments++;
        if (cachedMediaSegments == 1) {
          Log.i(
              TAG,
              "PREFETCH_MEDIA_READY id="
                  + marker.interstitialId
                  + " firstSegment="
                  + UriUtil.resolveToUri(playlist.baseUri, segment.url)
                  + " newCachedBytes="
                  + downloadedBytes
                  + " elapsedMs="
                  + (SystemClock.elapsedRealtime() - (deadlineMs - MAX_WALL_TIME_MS)));
        }
      }
    }

    private void cacheSegment(String baseUri, HlsMediaPlaylist.Segment segment) throws IOException {
      if (segment.fullSegmentEncryptionKeyUri != null) {
        cache(new DataSpec(UriUtil.resolveToUri(baseUri, segment.fullSegmentEncryptionKeyUri)));
      }
      cache(
          new DataSpec(
              UriUtil.resolveToUri(baseUri, segment.url),
              segment.byteRangeOffset,
              segment.byteRangeLength));
    }

    private void cache(DataSpec spec) throws IOException {
      checkActive();
      if (!resources.add(spec.uri + "@" + spec.position + ":" + spec.length)) {
        return;
      }
      CacheWriter currentWriter =
          new CacheWriter(
              cacheFactory.createDataSource(),
              spec,
              null,
              (length, cached, newBytes) -> {
                downloadedBytes += newBytes;
                if (cancelled
                    || downloadedBytes >= MAX_BYTES_PER_BREAK
                    || SystemClock.elapsedRealtime() >= deadlineMs) {
                  CacheWriter activeWriter = writer;
                  if (activeWriter != null) {
                    activeWriter.cancel();
                  }
                }
              });
      writer = currentWriter;
      checkActive();
      currentWriter.cache();
      writer = null;
    }
  }
}
