package androidx.media3.demo.main.ads;

import android.util.Log;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.AdViewProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Metadata;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader;
import java.io.IOException;

@UnstableApi
public class HlsInterstitialListener implements HlsInterstitialsAdsLoader.Listener {

  @Override
  public void onStart(MediaItem mediaItem, Object adsId, AdViewProvider adViewProvider) {
    Log.d("Gabriel", "onStart for " + mediaItem.mediaId);
  }

  @Override
  public void onStop(MediaItem mediaItem, Object adsId, AdPlaybackState adPlaybackState) {
    Log.d("Gabriel", "onStop for " + mediaItem.mediaId);
  }

  @Override
  public void onAdStarted(MediaItem mediaItem, Object adsId, int adGroupIndex,
      int adIndexInAdGroup) {
    Log.d("Gabriel", "onAdStarted for " + mediaItem.mediaId);
  }

  @Override
  public void onAdCompleted(MediaItem mediaItem, Object adsId, int adGroupIndex,
      int adIndexInAdGroup) {

    Log.d("Gabriel", "onAdCompleted for " + mediaItem.mediaId);
  }

  @Override
  public void onMetadata(MediaItem mediaItem, Object adsId, int adGroupIndex, int adIndexInAdGroup,
      Metadata metadata) {
    Log.d("Gabriel", "onMetadata for " + mediaItem.mediaId);

  }

  @Override
  public void onAdSkipped(MediaItem mediaItem, Object adsId, int adGroupIndex,
      int adIndexInAdGroup) {
    Log.d("Gabriel", "onAdSkipped for " + mediaItem.mediaId);
  }

  @Override
  public void onPrepareError(MediaItem mediaItem, Object adsId, int adGroupIndex,
      int adIndexInAdGroup, IOException exception) {
    Log.d("Gabriel", "onPrepareError for " + mediaItem.mediaId + " error: " + exception);
  }

  @Override
  public void onPrepareCompleted(MediaItem mediaItem, Object adsId, int adGroupIndex,
      int adIndexInAdGroup) {
    Log.d("Gabriel", "onPrepareCompleted for " + mediaItem.mediaId);

  }
}
