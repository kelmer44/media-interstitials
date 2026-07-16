package androidx.media3.demo.main.ads;

import static androidx.media3.common.AdPlaybackState.AD_STATE_AVAILABLE;
import static androidx.media3.common.AdPlaybackState.AD_STATE_ERROR;
import static androidx.media3.common.AdPlaybackState.AD_STATE_PLAYED;
import static androidx.media3.common.AdPlaybackState.AD_STATE_SKIPPED;
import static androidx.media3.common.AdPlaybackState.AD_STATE_UNAVAILABLE;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_INTERNAL;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_REMOVE;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_SILENCE_SKIP;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_SKIP;

import android.util.Log;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.demo.main.PlayerProgressListener;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader;
import androidx.media3.exoplayer.hls.HlsManifest;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import com.google.common.collect.ImmutableList;
import java.util.Set;


@UnstableApi
public class AdsManager implements Player.Listener, PlayerProgressListener.Callback {
  private static final String TAG = "AdsManager";

  private final HlsInterstitialsAdsLoader hlsInterstitialsAdsLoader;
  @Nullable
  private Player player;

  @OptIn(markerClass = UnstableApi.class)
  public AdsManager(HlsInterstitialsAdsLoader hlsInterstitialsAdsLoader) {
    this.hlsInterstitialsAdsLoader = hlsInterstitialsAdsLoader;

  }


  public void setPlayer(ExoPlayer player) {
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
    maybeSyncAdGroupsForDiscontinuity(newPosition, reason);
  }

  @Override
  public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
    if (player != null && !player.isPlayingAd()) {
      maybeSyncAdGroupsForCurrentContentPosition();
    }
  }

  private boolean firstUpdate = true;


  @Override
  public void onTimelineChanged(Timeline timeline, int reason) {
    Log.w(TAG, "PREFETCH - on timeline changed");
    if (firstUpdate && !timeline.isEmpty()) {
      maybeSyncAdGroupsForInitialJoin(timeline);
    }
    Timeline.Window window = new Timeline.Window();

    Object manifest = timeline.getWindow(0, window).manifest;
    if(manifest instanceof HlsManifest) {
      HlsManifest hlsManifest = (HlsManifest) timeline.getWindow(0,window).manifest;
      newInterstitials(hlsManifest);
    }

  }

  String PREFETCH_ID_KEY = "X-PREFETCH-ID";
  private void newInterstitials(HlsManifest manifest) {
    Log.d("PREFETCH", "New interstitials! " + manifest.mediaPlaylist.tags.size());

    for (String tag: manifest.mediaPlaylist.tags) {
      if(tag.startsWith("#EXT-X-DATERANGE:ID=\"prefetch")) {
//      ImmutableList<HlsMediaPlaylist.ClientDefinedAttribute> attrs = interstitial.clientDefinedAttributes;
        Log.d("PREFETCH", "daterange tag: " + tag);

      }
//      for (HlsMediaPlaylist.ClientDefinedAttribute attr : attrs) {
//        Log.d("PREFETCH", "Found prefetch marker for "  + attr.name);
//        if(attr.name.equals(PREFETCH_ID_KEY)) {
//          Log.d("PREFETCH", "Found prefetch marker for "  + attr.getTextValue());
//        }
//      }
    }
  }

  /**
   * upon joining we need to make sure we disable all past ads that have not been skipped, so we
   * trigger an update.
   *
   * @param timeline
   */
  private void maybeSyncAdGroupsForInitialJoin(Timeline timeline) {
    Timeline.Window window = timeline.getWindow(0, new Timeline.Window());
    Timeline.Period period = timeline.getPeriod(0, new Timeline.Period(), true);
    AdPlaybackState state = period.adPlaybackState;
    if (state.adGroupCount > 0) {
      firstUpdate = false;
      synAdGroupsForPosition(
          state, window.positionInFirstPeriodUs + window.defaultPositionUs);
    }
  }

  private void maybeSyncAdGroupsForDiscontinuity(Player.PositionInfo newPosition, int reason) {
    if (reason == DISCONTINUITY_REASON_REMOVE
        || reason == DISCONTINUITY_REASON_INTERNAL
        || reason == DISCONTINUITY_REASON_SILENCE_SKIP) {
      return;
    }
    if (reason == DISCONTINUITY_REASON_AUTO_TRANSITION
        && newPosition.adGroupIndex != C.INDEX_UNSET) {
      // Normal transition from content into an ad. Leave the ad available so it can play.
      return;
    }
    if (player == null) {
      return;
    }

    Timeline timeline = player.getCurrentTimeline();
    if (timeline.isEmpty()
        || newPosition.mediaItemIndex >= timeline.getWindowCount()
        || newPosition.periodIndex >= timeline.getPeriodCount()) {
      return;
    }

    Timeline.Window window = timeline.getWindow(newPosition.mediaItemIndex, new Timeline.Window());
    Timeline.Period period =
        timeline.getPeriod(newPosition.periodIndex, new Timeline.Period(), true);
    synAdGroupsForPosition(
        period.adPlaybackState,
        window.positionInFirstPeriodUs + Util.msToUs(newPosition.contentPositionMs));
  }

  private void maybeSyncAdGroupsForCurrentContentPosition() {
    if (player == null) {
      return;
    }
    Timeline timeline = player.getCurrentTimeline();
    if (timeline.isEmpty()) {
      return;
    }
    int mediaItemIndex = player.getCurrentMediaItemIndex();
    int periodIndex = player.getCurrentPeriodIndex();
    if (mediaItemIndex >= timeline.getWindowCount() || periodIndex >= timeline.getPeriodCount()) {
      return;
    }

    Timeline.Window window = timeline.getWindow(mediaItemIndex, new Timeline.Window());
    Timeline.Period period = timeline.getPeriod(periodIndex, new Timeline.Period(), true);
    synAdGroupsForPosition(
        period.adPlaybackState,
        window.positionInFirstPeriodUs + Util.msToUs(player.getContentPosition()));
  }

  /**
   * given the [playbackPositionInPeriodUs] marks all previous ads as skipped, and all future ads
   * as available.
   *
   * @param state
   * @param playbackPositionInPeriodUs
   */
  private void synAdGroupsForPosition(
      AdPlaybackState state,
      long playbackPositionInPeriodUs
  ) {
    int skippedAdGroups = 0;
    int rearmedAdGroups = 0;
    for (int i = 0; i < state.adGroupCount; i++) {
      AdPlaybackState.AdGroup group = state.getAdGroup(i);
      if (group.isLivePostrollPlaceholder() || group.timeUs == C.TIME_END_OF_SOURCE) {
        // do nothing with placeholders
        continue;
      }
      // ads before the current position are skipped, ads after are available
      if (group.timeUs <= playbackPositionInPeriodUs) {
        if (isSkippable(group)) {
          hlsInterstitialsAdsLoader.setWithSkippedAdGroup(i);
          skippedAdGroups++;
        }
      } else if (isReplayable(group)) {
//        hlsInterstitialsAdsLoader.setWithResetAdGroup(i);
//        hlsInterstitialsAdsLoader.setWithAvailableAdGroup(i);
        hlsInterstitialsAdsLoader.setWithAssetListReset(state.adsId, i);
        rearmedAdGroups++;
      }
    }
    if (skippedAdGroups > 0 || rearmedAdGroups > 0) {
      Log.w(
          "ADSMANAGER",
          "Synced ad groups for position "
              + playbackPositionInPeriodUs
              + "us. skipped="
              + skippedAdGroups
              + " rearmed="
              + rearmedAdGroups);
    }
  }

  private static boolean isSkippable(AdPlaybackState.AdGroup group) {
    for (int state : group.states) {
      if (state == AD_STATE_AVAILABLE
          || state == AD_STATE_UNAVAILABLE) {
        return true;
      }
    }
    return false;
  }

  private static boolean isReplayable(AdPlaybackState.AdGroup group) {
    for (int i = 0; i < group.states.length; i++) {
      if ((group.states[i] == AD_STATE_PLAYED
          || group.states[i] == AD_STATE_SKIPPED
          || group.states[i] == AD_STATE_ERROR)) {
        return true;
      }
    }
    return false;
  }

  private static String adStateForGroup(AdPlaybackState.AdGroup group) {
    StringBuilder statesStr = new StringBuilder("");

    for (int i = 0; i < group.states.length; i++) {
      String thisState = "";
      switch (group.states[i]) {
        case AD_STATE_UNAVAILABLE:
          thisState = "AD_STATE_UNAVAILABLE";
          break;
        case AD_STATE_AVAILABLE:
          thisState = "AD_STATE_AVAILABLE";
          break;
        case AD_STATE_SKIPPED:
          thisState = "AD_STATE_SKIPPED";
          break;
        case AD_STATE_PLAYED:
          thisState = "AD_STATE_PLAYED";
          break;
        case AD_STATE_ERROR:
          thisState = "AD_STATE_ERROR";
          break;
      }
      statesStr.append(thisState);
      statesStr.append(",");
    }
    return statesStr.toString();
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

  @OptIn(markerClass = UnstableApi.class)
  @Override
  public void onProgress(PlayerProgressListener.Progress progress) {

  }
}
