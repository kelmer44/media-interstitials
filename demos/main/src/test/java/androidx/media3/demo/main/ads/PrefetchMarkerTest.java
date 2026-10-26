package androidx.media3.demo.main.ads;

import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PrefetchMarkerTest {
  @Test
  public void parse_reorderedAttributes_preservesScheduledBreakDuration() throws Exception {
    List<PrefetchMarker> markers =
        PrefetchMarker.parse(
            playlist(
                "#EXT-X-DATERANGE:X-PREFETCH-ID=\"ad-break-8\",X-SNAP=\"OUT,IN\","
                    + "END-DATE=\"2026-10-26T08:47:45.446Z\",X-PREFETCH-DURATION=\"12.074666\","
                    + "START-DATE=\"2026-10-26T08:47:39.408Z\",ID=\"prefetch_ad-break-8\""));

    assertThat(markers).hasSize(1);
    assertThat(markers.get(0).interstitialId).isEqualTo("ad-break-8");
    assertThat(markers.get(0).endDateUnixUs - markers.get(0).startDateUnixUs).isEqualTo(6_038_000);
    assertThat(markers.get(0).scheduledDurationUs).isEqualTo(12_074_666);
  }

  @Test
  public void parse_longScheduledBreakDuration_isNotCappedAsACacheBudget() throws Exception {
    List<PrefetchMarker> markers =
        PrefetchMarker.parse(
            playlist(
                "#EXT-X-DATERANGE:ID=\"prefetch_ad\",X-PREFETCH-ID=\"ad\","
                    + "START-DATE=\"2026-10-26T08:47:39.408Z\",END-DATE=\"2026-10-26T08:47:45.446Z\","
                    + "X-PREFETCH-DURATION=\"210\""));
    assertThat(markers).hasSize(1);
    assertThat(markers.get(0).scheduledDurationUs).isEqualTo(210_000_000);
  }

  @Test
  public void parse_missingDurationOrUnrelatedId_ignoresMarker() throws Exception {
    String tag =
        "#EXT-X-DATERANGE:ID=\"prefetch_ad\",X-PREFETCH-ID=\"ad\","
            + "START-DATE=\"2026-10-26T08:47:39.408Z\",END-DATE=\"2026-10-26T08:47:45.446Z\"";
    assertThat(PrefetchMarker.parse(playlist(tag))).isEmpty();
    assertThat(
            PrefetchMarker.parse(
                playlist(tag.replace("prefetch_ad", "unrelated") + ",X-PREFETCH-DURATION=\"210\"")))
        .isEmpty();
  }

  @Test
  public void parse_badDatesOrDuration_ignoresOptionalMarker() throws Exception {
    String tag =
        "#EXT-X-DATERANGE:ID=\"prefetch_ad\",X-PREFETCH-ID=\"ad\","
            + "START-DATE=\"bad\",END-DATE=\"2026-10-26T08:47:45.446Z\"";
    assertThat(PrefetchMarker.parse(playlist(tag + ",X-PREFETCH-DURATION=\"210\""))).isEmpty();
    tag = tag.replace("bad", "2026-10-26T08:47:39.408Z") + ",X-PREFETCH-DURATION=\"NaN\"";
    assertThat(PrefetchMarker.parse(playlist(tag))).isEmpty();
  }

  @Test
  public void parse_missingProgramDateTime_cannotMapMarkers() throws Exception {
    String marker =
        "#EXT-X-DATERANGE:ID=\"prefetch_ad\",X-PREFETCH-ID=\"ad\","
            + "START-DATE=\"2026-10-26T08:47:39.408Z\",END-DATE=\"2026-10-26T08:47:45.446Z\","
            + "X-PREFETCH-DURATION=\"210\"";
    String text = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n" + marker + "\n#EXTINF:6,\na.ts\n";
    assertThat(PrefetchMarker.parse(parse(text))).isEmpty();
  }

  private static HlsMediaPlaylist playlist(String tag) throws Exception {
    return parse(
        "#EXTM3U\n#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2026-10-26T08:47:39.408Z\n"
            + tag
            + "\n#EXTINF:6,\na.ts\n");
  }

  private static HlsMediaPlaylist parse(String text) throws Exception {
    return (HlsMediaPlaylist)
        new HlsPlaylistParser()
            .parse(
                Uri.parse("http://test/main.m3u8"),
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
  }
}
