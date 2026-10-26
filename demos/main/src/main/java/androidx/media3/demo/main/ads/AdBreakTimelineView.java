package androidx.media3.demo.main.ads;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.hls.HlsManifest;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist.Interstitial;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@UnstableApi
public final class AdBreakTimelineView extends View {

  private static final int COLOR_AVAILABLE = Color.rgb(76, 175, 80);
  private static final int COLOR_SKIPPED_OR_UNAVAILABLE = Color.rgb(244, 67, 54);
  private static final int COLOR_PLACEHOLDER = Color.rgb(33, 150, 243);
  private static final int COLOR_TIMELINE = Color.argb(180, 255, 255, 255);
  private static final int COLOR_POSITION = Color.WHITE;
  private static final int COLOR_LABEL = Color.WHITE;
  private static final int COLOR_PREFETCH = Color.rgb(255, 193, 7);

  private final Timeline.Window window;
  private final Timeline.Period period;
  private final Paint paint;
  private final RectF barRect;
  private final List<AdBreak> adBreaks;
  private final List<PrefetchRange> prefetchRanges;
  private final float markerMinWidthPx;

  private final Map<String, Long> playoutLimitsUsById;
  @Nullable private Object windowUid;

  private long durationUs;
  private long positionUs;

  public AdBreakTimelineView(Context context) {
    this(context, null);
  }

  public AdBreakTimelineView(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    window = new Timeline.Window();
    period = new Timeline.Period();
    paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    barRect = new RectF();
    adBreaks = new ArrayList<>();
    prefetchRanges = new ArrayList<>();
    playoutLimitsUsById = new HashMap<>();
    markerMinWidthPx = 4 * getResources().getDisplayMetrics().density;
  }

  public void update(@Nullable Player player) {
    adBreaks.clear();
    prefetchRanges.clear();
    durationUs = 0;
    positionUs = 0;
    if (player == null) {
      playoutLimitsUsById.clear();
      windowUid = null;
      invalidate();
      return;
    }

    Timeline timeline = player.getCurrentTimeline();
    if (timeline.isEmpty()) {
      playoutLimitsUsById.clear();
      windowUid = null;
      invalidate();
      return;
    }

    int windowIndex = player.getCurrentMediaItemIndex();
    if (windowIndex >= timeline.getWindowCount()) {
      invalidate();
      return;
    }

    timeline.getWindow(windowIndex, window);
    if (!Objects.equals(windowUid, window.uid)) {
      playoutLimitsUsById.clear();
      windowUid = window.uid;
    }
    // Keep the declared limit separately: resolved ad durations can overwrite it.
    if (window.manifest instanceof HlsManifest) {
      HlsMediaPlaylist playlist = ((HlsManifest) window.manifest).mediaPlaylist;
      readPrefetchRanges(playlist);
      for (Interstitial interstitial : playlist.interstitials) {
        playoutLimitsUsById.put(interstitial.id, interstitial.playoutLimitUs);
      }
    }
    Set<String> activeInterstitialIds = new HashSet<>();
    durationUs = resolveDurationUs(window, player);
    positionUs = Util.msToUs(player.getContentPosition());
    long derivedDurationUs = Math.max(durationUs, positionUs);

    for (int periodIndex = window.firstPeriodIndex;
        periodIndex <= window.lastPeriodIndex;
        periodIndex++) {
      timeline.getPeriod(periodIndex, period, true);
      AdPlaybackState adPlaybackState = period.adPlaybackState;
      for (int adGroupIndex = adPlaybackState.removedAdGroupCount;
          adGroupIndex < adPlaybackState.adGroupCount;
          adGroupIndex++) {
        AdPlaybackState.AdGroup adGroup = adPlaybackState.getAdGroup(adGroupIndex);
        long startUs = getAdGroupWindowPositionUs(adGroup);
        long endUs = getAdGroupEndUs(adGroup, startUs);
        if (startUs < 0 && endUs < 0) {
          continue;
        }
        int color = getColor(adGroup);
        for (@Nullable String id : adGroup.ids) {
          if (id != null) {
            activeInterstitialIds.add(id);
          }
        }
        adBreaks.add(
            new AdBreak(
                Math.max(0, startUs),
                Math.max(0, endUs),
                color,
                /* displayNumber= */ adBreaks.size() + 1,
                "C = " + (adGroup.count == C.LENGTH_UNSET ? "?" : adGroup.count)
                    + ", PL = " + getPlayoutLimitLabel(adGroup),
                "Shim = " + getShimLabel(adGroup)));
        derivedDurationUs = Math.max(derivedDurationUs, endUs);
      }
    }

    // Retain limits for visible groups after their dateranges leave the live playlist.
    playoutLimitsUsById.keySet().retainAll(activeInterstitialIds);
    durationUs = derivedDurationUs;
    invalidate();
  }

  private void readPrefetchRanges(HlsMediaPlaylist playlist) {
    for (PrefetchMarker marker : PrefetchMarker.parse(playlist)) {
      long startUs = marker.startDateUnixUs - playlist.startTimeUs;
      long endUs = marker.endDateUnixUs - playlist.startTimeUs;
      if (endUs >= 0) {
        prefetchRanges.add(new PrefetchRange(startUs, endUs));
      }
    }
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    paint.setTextSize(getLabelTextSize());
    Paint.FontMetrics metrics = paint.getFontMetrics();
    int desiredHeight = (int) Math.ceil(
        52 * getResources().getDisplayMetrics().density
            + 2 * (metrics.descent - metrics.ascent)
            + getPaddingTop() + getPaddingBottom());
    setMeasuredDimension(
        resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec),
        resolveSize(Math.max(getSuggestedMinimumHeight(), desiredHeight), heightMeasureSpec));
  }

  private float getLabelTextSize() {
    return Math.max(9f, getResources().getDisplayMetrics().scaledDensity * 10);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    int width = getWidth() - getPaddingLeft() - getPaddingRight();
    int height = getHeight() - getPaddingTop() - getPaddingBottom();
    if (width <= 0 || height <= 0 || durationUs <= 0) {
      return;
    }

    float left = getPaddingLeft();
    float right = getWidth() - getPaddingRight();
    float density = getResources().getDisplayMetrics().density;
    float textSize = getLabelTextSize();
    paint.setTextSize(textSize);
    Paint.FontMetrics metrics = paint.getFontMetrics();
    float lineHeight = metrics.descent - metrics.ascent;
    float adTop = getPaddingTop() + 9 * density;
    float adBottom = getHeight() - getPaddingBottom() - 7 * density - 2 * lineHeight;
    float centerY = (adTop + adBottom) / 2f;
    float barHeight = Math.max(3f, (adBottom - adTop) * 0.18f);
    barRect.set(left, centerY - barHeight / 2f, right, centerY + barHeight / 2f);
    paint.setColor(COLOR_TIMELINE);
    canvas.drawRoundRect(barRect, barHeight / 2f, barHeight / 2f, paint);

    paint.setColor(COLOR_PREFETCH);
    paint.setStrokeWidth(3 * density);
    float prefetchY = getPaddingTop() + 3 * density;
    for (PrefetchRange range : prefetchRanges) {
      if (range.startUs > durationUs) {
        continue;
      }
      float prefetchLeft = left + width * constrainToDuration(range.startUs) / durationUs;
      float prefetchRight = left + width * constrainToDuration(range.endUs) / durationUs;
      if (prefetchRight - prefetchLeft < markerMinWidthPx) {
        prefetchRight = Math.min(right, prefetchLeft + markerMinWidthPx);
        prefetchLeft = Math.max(left, prefetchRight - markerMinWidthPx);
      }
      canvas.drawLine(prefetchLeft, prefetchY, prefetchRight, prefetchY, paint);
    }

    for (int i = 0; i < adBreaks.size(); i++) {
      AdBreak adBreak = adBreaks.get(i);
      float adLeft = left + width * constrainToDuration(adBreak.startUs) / durationUs;
      float adRight = left + width * constrainToDuration(adBreak.endUs) / durationUs;
      if (adRight - adLeft < markerMinWidthPx) {
        adRight = Math.min(right, adLeft + markerMinWidthPx);
        adLeft = Math.max(left, adRight - markerMinWidthPx);
      }
      paint.setColor(adBreak.color);
      canvas.drawRoundRect(adLeft, adTop, adRight, adBottom, 2f, 2f, paint);

      paint.setColor(COLOR_LABEL);
      paint.setTextAlign(Paint.Align.CENTER);
      paint.setTextSize(textSize);
      Paint.FontMetrics fontMetrics = paint.getFontMetrics();
      float adCenterY = (adTop + adBottom) / 2f;
      float labelBaselineInAdBreak =
          adCenterY - (fontMetrics.ascent + fontMetrics.descent) / 2f;
      canvas.drawText(
          String.valueOf(adBreak.displayNumber),
          (adLeft + adRight) / 2f,
          labelBaselineInAdBreak,
          paint);

      if (adBreak.color == COLOR_PLACEHOLDER) {
        continue;
      }

      // Use the space between neighboring marker centers for labels, including
      // narrow break boxes, and compress text horizontally only when necessary.
      float labelLeft = i == 0 ? left : (adLeft + adRight) / 4f
          + getAdBreakCenterX(adBreaks.get(i - 1), left, width) / 2f;
      float labelRight = i == adBreaks.size() - 1 ? right : (adLeft + adRight) / 4f
          + getAdBreakCenterX(adBreaks.get(i + 1), left, width) / 2f;
      float labelWidth = labelRight - labelLeft - 4 * density;
      if (labelWidth <= 0) {
        continue;
      }
      float textWidth = Math.max(paint.measureText(adBreak.details), paint.measureText(adBreak.shim));
      paint.setTextScaleX(Math.min(1f, labelWidth / Math.max(1f, textWidth)));
      float scaledWidth = Math.min(textWidth, labelWidth);
      float labelX = Util.constrainValue(
          (adLeft + adRight) / 2f,
          labelLeft + 2 * density + scaledWidth / 2f,
          labelRight - 2 * density - scaledWidth / 2f);
      float baseline = adBottom + 4 * density - metrics.ascent;
      canvas.drawText(adBreak.details, labelX, baseline, paint);
      canvas.drawText(adBreak.shim, labelX, baseline + lineHeight, paint);
      paint.setTextScaleX(1f);
    }

    float positionX = left + width * constrainToDuration(positionUs) / durationUs;
    paint.setColor(COLOR_POSITION);
    paint.setStrokeWidth(Math.max(2f, getResources().getDisplayMetrics().density));
    canvas.drawLine(positionX, getPaddingTop(), positionX, adBottom, paint);
  }

  private float getAdBreakCenterX(AdBreak adBreak, float left, int width) {
    return left + width * ((constrainToDuration(adBreak.startUs)
        + constrainToDuration(adBreak.endUs)) / 2f) / durationUs;
  }

  private String getPlayoutLimitLabel(AdPlaybackState.AdGroup adGroup) {
    Set<String> ids = new HashSet<>();
    long totalLimitUs = 0;
    for (@Nullable String id : adGroup.ids) {
      if (id == null || !ids.add(id)) {
        continue;
      }
      @Nullable Long limitUs = playoutLimitsUsById.get(id);
      if (limitUs == null) {
        return "?";
      }
      if (limitUs == C.TIME_UNSET) {
        return "none";
      }
      totalLimitUs += limitUs;
    }
    return ids.isEmpty() ? "?"
        : BigDecimal.valueOf(totalLimitUs, 6).stripTrailingZeros().toPlainString();
  }

  private static String getShimLabel(AdPlaybackState.AdGroup adGroup) {
    boolean unresolved = adGroup.count == C.LENGTH_UNSET;
    for (@Nullable MediaItem item : adGroup.mediaItems) {
      if (item == null || item.localConfiguration == null) {
        unresolved = true;
      } else if ("/shim.m3u8".equals(item.localConfiguration.uri.getPath())) {
        return "yes";
      }
    }
    return unresolved ? "?" : "no";
  }

  private long getAdGroupWindowPositionUs(AdPlaybackState.AdGroup adGroup) {
    if (adGroup.timeUs == C.TIME_END_OF_SOURCE) {
      return durationUs;
    }
    return period.getPositionInWindowUs() + adGroup.timeUs;
  }

  private long getAdGroupEndUs(AdPlaybackState.AdGroup adGroup, long startUs) {
    if (adGroup.timeUs == C.TIME_END_OF_SOURCE) {
      return startUs;
    }
    long adBreakDurationUs = Math.max(0, adGroup.contentResumeOffsetUs);
    return startUs + adBreakDurationUs;
  }

  private static long resolveDurationUs(Timeline.Window window, Player player) {
    if (window.durationUs != C.TIME_UNSET) {
      return window.durationUs;
    }
    if (window.defaultPositionUs != C.TIME_UNSET) {
      return Math.max(window.defaultPositionUs, Util.msToUs(player.getContentPosition()));
    }
    return Util.msToUs(player.getContentPosition());
  }

  private long constrainToDuration(long timeUs) {
    return Util.constrainValue(timeUs, 0, durationUs);
  }

  private static int getColor(AdPlaybackState.AdGroup adGroup) {
    if (adGroup.isLivePostrollPlaceholder()) {
      return COLOR_PLACEHOLDER;
    }
    for (int state : adGroup.states) {
      if (state == AdPlaybackState.AD_STATE_AVAILABLE) {
        return COLOR_AVAILABLE;
      }
    }
    return COLOR_SKIPPED_OR_UNAVAILABLE;
  }

  private static final class AdBreak {
    public final long startUs;
    public final long endUs;
    public final int color;
    public final int displayNumber;
    public final String details;
    public final String shim;

    public AdBreak(long startUs, long endUs, int color, int displayNumber, String details, String shim) {
      this.startUs = startUs;
      this.endUs = endUs;
      this.color = color;
      this.displayNumber = displayNumber;
      this.details = details;
      this.shim = shim;
    }
  }

  private static final class PrefetchRange {
    public final long startUs;
    public final long endUs;

    public PrefetchRange(long startUs, long endUs) {
      this.startUs = startUs;
      this.endUs = endUs;
    }
  }
}
