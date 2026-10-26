package androidx.media3.demo.main.ads;

import androidx.media3.common.ParserException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** App-specific signaling. X-PREFETCH-DURATION declares the scheduled ad-break duration. */
@UnstableApi
final class PrefetchMarker {
  private static final Pattern ATTRIBUTE =
      Pattern.compile("(?:^|[:,])([A-Z0-9-]+)=(?:\"([^\"]*)\"|([^,]*))");

  public final String interstitialId;
  public final long startDateUnixUs;
  public final long endDateUnixUs;
  public final long scheduledDurationUs;

  private PrefetchMarker(String id, long startUs, long endUs, long durationUs) {
    interstitialId = id;
    startDateUnixUs = startUs;
    endDateUnixUs = endUs;
    scheduledDurationUs = durationUs;
  }

  public static List<PrefetchMarker> parse(HlsMediaPlaylist playlist) {
    List<PrefetchMarker> markers = new ArrayList<>();
    if (!playlist.hasProgramDateTime) {
      return markers;
    }
    for (String tag : playlist.tags) {
      if (!tag.startsWith("#EXT-X-DATERANGE:")) {
        continue;
      }
      Map<String, String> attributes = new HashMap<>();
      Matcher matcher = ATTRIBUTE.matcher(tag);
      while (matcher.find()) {
        attributes.put(
            matcher.group(1), matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
      }
      String id = attributes.get("X-PREFETCH-ID");
      String start = attributes.get("START-DATE");
      String end = attributes.get("END-DATE");
      String markerId = attributes.get("ID");
      String duration = attributes.get("X-PREFETCH-DURATION");
      if (id == null
          || id.isEmpty()
          || markerId == null
          || !markerId.startsWith("prefetch_")
          || start == null
          || end == null
          || duration == null) {
        continue;
      }
      try {
        long startUs = Util.msToUs(Util.parseXsDateTime(start));
        long endUs = Util.msToUs(Util.parseXsDateTime(end));
        long durationUs = new BigDecimal(duration).movePointRight(6).longValueExact();
        if (endUs > startUs && durationUs > 0) {
          markers.add(new PrefetchMarker(id, startUs, endUs, durationUs));
        }
      } catch (ParserException | IllegalArgumentException | ArithmeticException e) {
        // Optional malformed signaling must not affect playback.
      }
    }
    return markers;
  }
}
