package androidx.media3.demo.main.ads;

import static androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.net.Uri;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.Clock;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceUtil;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader;
import androidx.media3.exoplayer.hls.TestHlsManifest;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AdPrefetcherTest {
  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void update_bufferReachesMarker_cachesConfiguredDurationAndPlaybackReusesBytes()
      throws Exception {
    SimpleCache cache = new SimpleCache(temporaryFolder.newFolder(), new NoOpCacheEvictor());
    AtomicInteger requests = new AtomicInteger();
    Map<String, byte[]> responses = new HashMap<>();
    responses.put(
        "http://test/ad.m3u8",
        bytes(
            "#EXTM3U\n#EXT-X-TARGETDURATION:6\n"
                + "#EXT-X-PROGRAM-DATE-TIME:2026-10-26T08:47:45.446Z\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x1\n#EXT-X-MAP:URI=\"init.bin\"\n"
                + "#EXTINF:6,\n#EXT-X-BYTERANGE:4@2\nseg.bin\n"
                + "#EXTINF:6,\nlater.bin\n#EXT-X-ENDLIST\n"));
    responses.put("http://test/seg.bin", new byte[] {0, 1, 2, 3, 4, 5, 6, 7});
    responses.put("http://test/init.bin", new byte[] {8, 9});
    responses.put("http://test/key.bin", new byte[16]);
    DataSource.Factory upstream =
        () ->
            new ByteArrayDataSource(
                uri -> {
                  requests.incrementAndGet();
                  byte[] data = responses.get(uri.toString());
                  if (data == null) {
                    throw new IOException("Unexpected prefetch: " + uri);
                  }
                  return data;
                });
    CacheDataSource.Factory cacheFactory =
        new CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream);
    HlsInterstitialsAdsLoader loader = new HlsInterstitialsAdsLoader(upstream);
    AdPrefetcher prefetcher =
        new AdPrefetcher(
            upstream,
            cacheFactory,
            loader,
            /* resolveLookaheadUs= */ 0,
            /* cacheDurationUs= */ 5_000_000);
    try {
      Player player = playerWithMarker(/* startSeconds= */ 20, /* endSeconds= */ 21);
      when(player.getContentPosition()).thenReturn(1_000L);
      when(player.getContentBufferedPosition()).thenReturn(20_000L);
      prefetcher.update(player);
      runMainLooperUntil(() -> cache.isCached("http://test/seg.bin", 2, 4), 10_000, Clock.DEFAULT);
      assertThat(cache.isCached("http://test/init.bin", 0, 2)).isTrue();
      assertThat(cache.isCached("http://test/key.bin", 0, 16)).isTrue();
      assertThat(cache.getKeys()).doesNotContain("http://test/later.bin");
      assertThat(cache.getKeys()).doesNotContain("http://test/ad.m3u8");

      int requestsAfterPrefetch = requests.get();
      // Passing END-DATE while still before the ad does not trigger another download.
      when(player.getContentPosition()).thenReturn(22_000L);
      when(player.getContentBufferedPosition()).thenReturn(25_000L);
      prefetcher.update(player);
      DataSource playback =
          new CacheDataSource.Factory()
              .setCache(cache)
              .setUpstreamDataSourceFactory(upstream)
              .setCacheWriteDataSinkFactory(null)
              .createDataSource();
      try {
        playback.open(new DataSpec(Uri.parse("http://test/seg.bin"), 2, 4));
        assertThat(DataSourceUtil.readToEnd(playback)).isEqualTo(new byte[] {2, 3, 4, 5});
      } finally {
        playback.close();
      }
      assertThat(requests.get()).isEqualTo(requestsAfterPrefetch);
      assertThat(requests.get()).isEqualTo(4);
    } finally {
      prefetcher.reset();
      loader.release();
      cache.release();
    }
  }

  @Test
  public void update_markerEndPassesBeforeAd_keepsRunningDownload() throws Exception {
    verifyPrefetchContinues(/* useLookahead= */ false);
  }

  @Test
  public void update_lookaheadReachesMarkerWithEmptyBuffer_startsDownload() throws Exception {
    verifyPrefetchContinues(/* useLookahead= */ true);
  }

  private void verifyPrefetchContinues(boolean useLookahead) throws Exception {
    SimpleCache cache = new SimpleCache(temporaryFolder.newFolder(), new NoOpCacheEvictor());
    CountDownLatch segmentStarted = new CountDownLatch(1);
    CountDownLatch finishSegment = new CountDownLatch(1);
    DataSource.Factory upstream =
        () ->
            new ByteArrayDataSource(
                uri -> {
                  if (uri.getPath().endsWith(".m3u8")) {
                    return bytes(
                        "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg.bin\n#EXT-X-ENDLIST\n");
                  }
                  segmentStarted.countDown();
                  try {
                    if (!finishSegment.await(5, TimeUnit.SECONDS)) {
                      throw new IOException("Test did not release segment");
                    }
                  } catch (InterruptedException e) {
                    throw new IOException("Prefetch cancelled before the ad starts", e);
                  }
                  return new byte[] {1, 2, 3};
                });
    HlsInterstitialsAdsLoader loader = new HlsInterstitialsAdsLoader(upstream);
    AdPrefetcher prefetcher =
        new AdPrefetcher(
            upstream,
            new CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream),
            loader,
            /* resolveLookaheadUs= */ useLookahead ? 30_000_000 : 0,
            /* cacheDurationUs= */ 5_000_000);
    try {
      Player player = playerWithMarker(/* startSeconds= */ 20, /* endSeconds= */ 21);
      when(player.getContentPosition()).thenReturn(1_000L);
      when(player.getContentBufferedPosition()).thenReturn(useLookahead ? 1_000L : 20_000L);
      prefetcher.update(player);
      assertThat(segmentStarted.await(5, TimeUnit.SECONDS)).isTrue();
      // The marker ends at 21s, but the actual ad does not start until 60s.
      when(player.getContentPosition()).thenReturn(22_000L);
      when(player.getContentBufferedPosition()).thenReturn(25_000L);
      prefetcher.update(player);
      finishSegment.countDown();
      runMainLooperUntil(() -> cache.isCached("http://test/seg.bin", 0, 3), 10_000, Clock.DEFAULT);
    } finally {
      finishSegment.countDown();
      prefetcher.reset();
      loader.release();
      cache.release();
    }
  }

  @Test
  public void update_seekPastAdBreak_cancelsRunningDownload() throws Exception {
    SimpleCache cache = new SimpleCache(temporaryFolder.newFolder(), new NoOpCacheEvictor());
    CountDownLatch segmentStarted = new CountDownLatch(1);
    CountDownLatch segmentInterrupted = new CountDownLatch(1);
    DataSource.Factory upstream =
        () ->
            new ByteArrayDataSource(
                uri -> {
                  if (uri.getPath().endsWith(".m3u8")) {
                    return bytes(
                        "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg.bin\n#EXT-X-ENDLIST\n");
                  }
                  segmentStarted.countDown();
                  try {
                    new CountDownLatch(1).await(5, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    segmentInterrupted.countDown();
                    throw new IOException(e);
                  }
                  throw new IOException("Download was not cancelled");
                });
    HlsInterstitialsAdsLoader loader = new HlsInterstitialsAdsLoader(upstream);
    AdPrefetcher prefetcher =
        new AdPrefetcher(
            upstream,
            new CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream),
            loader);
    try {
      Player player = playerWithMarker();
      prefetcher.update(player);
      assertThat(segmentStarted.await(5, TimeUnit.SECONDS)).isTrue();
      when(player.getContentPosition()).thenReturn(70_000L);
      prefetcher.update(player);
      assertThat(segmentInterrupted.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(cache.getCacheSpace()).isEqualTo(0);
    } finally {
      prefetcher.reset();
      loader.release();
      cache.release();
    }
  }

  @Test
  public void update_bufferAndLookaheadBeforeMarker_doesNotDownload() throws Exception {
    SimpleCache cache = new SimpleCache(temporaryFolder.newFolder(), new NoOpCacheEvictor());
    AtomicInteger requests = new AtomicInteger();
    DataSource.Factory upstream =
        () ->
            new ByteArrayDataSource(
                uri -> {
                  requests.incrementAndGet();
                  throw new IOException("Should not load");
                });
    HlsInterstitialsAdsLoader loader = new HlsInterstitialsAdsLoader(upstream);
    AdPrefetcher prefetcher =
        new AdPrefetcher(
            upstream,
            new CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream),
            loader);
    try {
      Player player = playerWithMarker(/* startSeconds= */ 40, /* endSeconds= */ 41);
      when(player.getContentPosition()).thenReturn(1_000L);
      when(player.getContentBufferedPosition()).thenReturn(10_000L);
      prefetcher.update(player);
      assertThat(requests.get()).isEqualTo(0);
      assertThat(cache.getKeys()).isEmpty();
    } finally {
      prefetcher.reset();
      loader.release();
      cache.release();
    }
  }

  private static Player playerWithMarker() throws Exception {
    return playerWithMarker(/* startSeconds= */ 0, /* endSeconds= */ 1);
  }

  private static Player playerWithMarker(long startSeconds, long endSeconds) throws Exception {
    Instant playlistStart = Instant.parse("2026-10-26T08:47:39.408Z");
    String manifest =
        "#EXTM3U\n#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2026-10-26T08:47:39.408Z\n"
            + "#EXT-X-DATERANGE:ID=\"prefetch_ad\",X-PREFETCH-ID=\"ad\","
            + "START-DATE=\""
            + playlistStart.plusSeconds(startSeconds)
            + "\",END-DATE=\""
            + playlistStart.plusSeconds(endSeconds)
            + "\","
            + "X-PREFETCH-DURATION=\"210\"\n#EXTINF:6,\na.ts\n#EXTINF:6,\nb.ts\n";
    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist)
            new HlsPlaylistParser()
                .parse(
                    Uri.parse("http://test/main.m3u8"), new ByteArrayInputStream(bytes(manifest)));
    AdPlaybackState state =
        new AdPlaybackState("ads", 60_000_000L)
            .withAdCount(0, 1)
            .withAdId(0, 0, "ad")
            .withAvailableAdMediaItem(0, 0, MediaItem.fromUri("http://test/ad.m3u8"))
            .withAdDurationsUs(new long[][] {{12_000_000L}});
    Player player = mock(Player.class);
    when(player.getCurrentTimeline())
        .thenReturn(
            new FakeTimeline(
                new Object[] {TestHlsManifest.create(playlist)},
                new TimelineWindowDefinition.Builder()
                    .setDurationUs(120_000_000L)
                    .setWindowPositionInFirstPeriodUs(0)
                    .setAdPlaybackStates(Collections.singletonList(state))
                    .build()));
    return player;
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
