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
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import java.util.ArrayList;
import java.util.List;

@UnstableApi
public final class AdBreakTimelineView extends View {

  private static final int COLOR_AVAILABLE = Color.rgb(76, 175, 80);
  private static final int COLOR_SKIPPED_OR_UNAVAILABLE = Color.rgb(244, 67, 54);
  private static final int COLOR_PLACEHOLDER = Color.rgb(33, 150, 243);
  private static final int COLOR_TIMELINE = Color.argb(180, 255, 255, 255);
  private static final int COLOR_POSITION = Color.WHITE;
  private static final int COLOR_LABEL = Color.WHITE;

  private final Timeline.Window window;
  private final Timeline.Period period;
  private final Paint paint;
  private final RectF barRect;
  private final List<AdBreak> adBreaks;
  private final float markerMinWidthPx;

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
    markerMinWidthPx = 4 * getResources().getDisplayMetrics().density;
  }

  public void update(@Nullable Player player) {
    adBreaks.clear();
    durationUs = 0;
    positionUs = 0;
    if (player == null) {
      invalidate();
      return;
    }

    Timeline timeline = player.getCurrentTimeline();
    if (timeline.isEmpty()) {
      invalidate();
      return;
    }

    int windowIndex = player.getCurrentMediaItemIndex();
    if (windowIndex >= timeline.getWindowCount()) {
      invalidate();
      return;
    }

    timeline.getWindow(windowIndex, window);
    durationUs = resolveDurationUs(window, player);
    positionUs = Util.msToUs(player.getContentPosition());
    long derivedDurationUs = Math.max(durationUs, positionUs);

    for (int periodIndex = window.firstPeriodIndex;
        periodIndex <= window.lastPeriodIndex;
        periodIndex++) {
      timeline.getPeriod(periodIndex, period, true);
      AdPlaybackState adPlaybackState = period.adPlaybackState;
      for (int adGroupIndex = 0;
          adGroupIndex < adPlaybackState.adGroupCount;
          adGroupIndex++) {
        AdPlaybackState.AdGroup adGroup = adPlaybackState.getAdGroup(adGroupIndex);
        long startUs = getAdGroupWindowPositionUs(adGroup);
        long endUs = getAdGroupEndUs(adGroup, startUs);
        if (startUs < 0 && endUs < 0) {
          continue;
        }
        int color = getColor(adGroup);
        adBreaks.add(
            new AdBreak(
                Math.max(0, startUs),
                Math.max(0, endUs),
                color,
                /* displayNumber= */ adBreaks.size() + 1));
        derivedDurationUs = Math.max(derivedDurationUs, endUs);
      }
    }

    durationUs = derivedDurationUs;
    invalidate();
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
    float textSize = Math.max(9f, getResources().getDisplayMetrics().scaledDensity * 10);
    float adTop = getPaddingTop() + 3 * density;
    float adBottom = getHeight() - getPaddingBottom() - 3 * density;
    float centerY = (adTop + adBottom) / 2f;
    float barHeight = Math.max(3f, (adBottom - adTop) * 0.18f);
    barRect.set(left, centerY - barHeight / 2f, right, centerY + barHeight / 2f);
    paint.setColor(COLOR_TIMELINE);
    canvas.drawRoundRect(barRect, barHeight / 2f, barHeight / 2f, paint);

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
    }

    float positionX = left + width * constrainToDuration(positionUs) / durationUs;
    paint.setColor(COLOR_POSITION);
    paint.setStrokeWidth(Math.max(2f, getResources().getDisplayMetrics().density));
    canvas.drawLine(positionX, getPaddingTop(), positionX, getHeight() - getPaddingBottom(), paint);
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

    public AdBreak(long startUs, long endUs, int color, int displayNumber) {
      this.startUs = startUs;
      this.endUs = endUs;
      this.color = color;
      this.displayNumber = displayNumber;
    }
  }
}
