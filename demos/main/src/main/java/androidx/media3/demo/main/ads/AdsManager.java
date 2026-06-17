package androidx.media3.demo.main.ads;

import static androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_INTERNAL;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_REMOVE;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_SILENCE_SKIP;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_SKIP;

import androidx.annotation.Nullable;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader;

@UnstableApi
public class AdsManager implements Player.Listener {

  private static final String TAG = "AdsManager";

  private final HlsInterstitialsAdsLoader hlsInterstitialsAdsLoader;
  @Nullable
  private Player player;

  public AdsManager(HlsInterstitialsAdsLoader hlsInterstitialsAdsLoader) {
    this.hlsInterstitialsAdsLoader = hlsInterstitialsAdsLoader;
  }

  public void setPlayer(@Nullable ExoPlayer player) {
    Log.w("ADMANAGER", "Setting player");
    this.player = player;
    if (this.player != null) {
      this.player.addListener(this);
    }
  }

  @Override
  public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
      Player.PositionInfo newPosition, int reason) {

    String strReason;
    switch (reason) {
      case DISCONTINUITY_REASON_AUTO_TRANSITION:
        strReason = "DISCONTINUITY_REASON_AUTO_TRANSITION";
        break;
      case DISCONTINUITY_REASON_SEEK:
        strReason = "DISCONTINUITY_REASON_SEEK";
        break;
      case DISCONTINUITY_REASON_SEEK_ADJUSTMENT:
        strReason = "DISCONTINUITY_REASON_SEEK_ADJUSTMENT";
        break;
      case DISCONTINUITY_REASON_SKIP:
        strReason = "DISCONTINUITY_REASON_SKIP";
        break;
      case DISCONTINUITY_REASON_REMOVE:
        strReason = "DISCONTINUITY_REASON_REMOVE";
        break;
      case DISCONTINUITY_REASON_INTERNAL:
        strReason = "DISCONTINUITY_REASON_INTERNAL";
        break;
      case DISCONTINUITY_REASON_SILENCE_SKIP:
        strReason = "DISCONTINUITY_REASON_SILENCE_SKIP";
        break;
      default:
        strReason = "unknown";
        break;
    }

    Log.w(TAG, "ADMANAGER - onPositionDiscontinuity reason = " + strReason);
  }

  private boolean firstUpdate = true;

  @Override
  public void onTimelineChanged(Timeline timeline, int reason) {
    Log.w(TAG, "ADMANAGER - on timeline changed");
    if (firstUpdate && !timeline.isEmpty()) {
      maybeSkipPastInterstitialsOnInitialJoin(timeline);
    }
  }

  /**
   * Will skip all ads that are in the past.
   *
   * @param timeline
   */
  private void maybeSkipPastInterstitialsOnInitialJoin(Timeline timeline) {

    Timeline.Window window = timeline.getWindow(0, new Timeline.Window());
    Timeline.Period period = timeline.getPeriod(0, new Timeline.Period(), true);

    long joinPositionInPeriodUs = window.positionInFirstPeriodUs + window.defaultPositionUs;
    AdPlaybackState state = period.adPlaybackState;

    int adGroupCount = state.adGroupCount;
    if (adGroupCount > 0) {
      Log.i("ADMANAGER", "Will try to skip past ads now");
      firstUpdate = false;
      int skippedAdGroups = 0;
      for (int i = 0; i < adGroupCount; i++) {
        AdPlaybackState.AdGroup group = state.getAdGroup(i);
        if (group.isLivePostrollPlaceholder()) {
          continue;
        }
        long groupStartUs = group.timeUs;
        // Keep only interstitials whose start time is strictly in the future. If playback joins
        // in the middle of a break, that break is considered past and skipped.
        boolean shouldBeSkipped = groupStartUs <= joinPositionInPeriodUs;
        if (shouldBeSkipped) {
          Log.w("ADSMANAGER", "Skipping ad group " + i + " with groupStartUs = " + groupStartUs
              + " when joinPositionUs is =" + joinPositionInPeriodUs);
          skippedAdGroups++;
          hlsInterstitialsAdsLoader.setWithSkippedAdGroup(i);
        }
//        else {
//          hlsInterstitialsAdsLoader.setWithAvailableAdGroup(i);
//        }
      }
      Log.w("ADSMANAGER", "Skipped adgroups = " + skippedAdGroups + "/" + adGroupCount);

    }
  }

  public void skipAd() {
    if (player == null) {
      Log.d(TAG, "ADSMANAGER - skipAd ignored: player is null");
      return;
    }
    if (!player.isPlayingAd()) {
      Log.d(TAG, "ADSMANAGER - skipAd ignored: no ad is currently playing");
      return;
    }
    Log.d(
        TAG,
        "ADSMANAGER - Skipping current ad group="
            + player.getCurrentAdGroupIndex()
            + " ad="
            + player.getCurrentAdIndexInAdGroup());
    hlsInterstitialsAdsLoader.skipCurrentAdGroup();
  }
}
