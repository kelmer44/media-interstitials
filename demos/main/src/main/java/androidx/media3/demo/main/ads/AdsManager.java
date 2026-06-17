package androidx.media3.demo.main.ads;

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
    Log.w("ADMANAGER" ,"Setting player");
    this.player = player;
    this.player.addListener(this);
  }


  @Override
  public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
      Player.PositionInfo newPosition, int reason) {

  }

  private boolean firstUpdate = true;

  @Override
  public void onTimelineChanged(Timeline timeline, int reason) {
    Log.w(TAG, "ADMANAGER - on timeline changed");
    if (firstUpdate && !timeline.isEmpty()) {
      maybeSkipHistoricalInterstitialsOnInitialJoin(timeline);
    }
  }

  /**
   * Will skip all ads that are in the past.
   *
   * @param timeline
   */
  private void maybeSkipHistoricalInterstitialsOnInitialJoin(Timeline timeline) {

    Timeline.Window window = timeline.getWindow(0, new Timeline.Window());
    Timeline.Period period = timeline.getPeriod(0, new Timeline.Period(), true);

    long joinPositionUs = window.defaultPositionUs;
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
        long groupEndUs = groupStartUs + group.contentResumeOffsetUs;
//
        boolean shouldBeSkipped = groupStartUs <= joinPositionUs;
        if (shouldBeSkipped) {
          Log.w("ADSMANAGER", "Skipping ad group " + i + " with groupStartUs = " + groupStartUs + " when joinPositionUs is =" + joinPositionUs);
//          if(!state.isLivePostrollPlaceholder(i))
          skippedAdGroups++;
          hlsInterstitialsAdsLoader.setWithSkippedAdGroup(i);
//        }
        }
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
