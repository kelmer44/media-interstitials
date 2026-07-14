package androidx.media3.demo.main.ads;

import android.util.Log;
import androidx.annotation.Nullable;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.AdViewProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Metadata;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader;
import java.io.IOException;
import org.json.JSONObject;

@UnstableApi
public class HlsInterstitialListener implements HlsInterstitialsAdsLoader.Listener {

  @Override
  public void onStart(MediaItem mediaItem, Object adsId, AdViewProvider adViewProvider) {
    Log.d("HLSTEST", "onStart for " + mediaItem.mediaId);
  }

  @Override
  public void onStop(MediaItem mediaItem, Object adsId, AdPlaybackState adPlaybackState) {
    Log.d(
        "HLSTEST",
        "onStop for "
            + mediaItem.mediaId
            + " adsId="
            + adsId
            + " adPlaybackState="
            + adPlaybackState);
  }

  @Override
  public void onAssetListLoadStarted(
      MediaItem mediaItem, Object adsId, int adGroupIndex, int adIndexInAdGroup) {
    Log.d("HLSTEST", "onAssetListLoadStarted group=" + adGroupIndex + " ad=" + adIndexInAdGroup);
  }

  @Override
  public void onAssetListLoadCompleted(
      MediaItem mediaItem,
      Object adsId,
      int adGroupIndex,
      int adIndexInAdGroup,
      HlsInterstitialsAdsLoader.AssetList assetList,
      JSONObject rawAssetListJson) {
    Log.d(
        "HLSTEST",
        "onAssetListLoadCompleted group="
            + adGroupIndex
            + " ad="
            + adIndexInAdGroup
            + " assets="
            + assetList.assets.size()
            + " raw="
            + rawAssetListJson);
  }

  @Override
  public void onAssetListLoadFailed(
      MediaItem mediaItem,
      Object adsId,
      int adGroupIndex,
      int adIndexInAdGroup,
      @Nullable IOException ioException,
      boolean cancelled) {
    Log.d(
        "HLSTEST",
        "onAssetListLoadFailed group="
            + adGroupIndex
            + " ad="
            + adIndexInAdGroup
            + " cancelled="
            + cancelled
            + " error="
            + ioException);
  }

  @Override
  public void onAdStarted(MediaItem mediaItem, Object adsId, int adGroupIndex,
      int adIndexInAdGroup) {
    Log.d("HLSTEST", "onAdStarted for " + mediaItem.mediaId);
  }

  @Override
  public void onAdCompleted(MediaItem mediaItem, Object adsId, int adGroupIndex,
      int adIndexInAdGroup) {

    Log.d("HLSTEST", "onAdCompleted for " + mediaItem.mediaId);
  }

  @Override
  public void onMetadata(MediaItem mediaItem, Object adsId, int adGroupIndex, int adIndexInAdGroup,
      Metadata metadata) {
    Log.d("HLSTEST", "onMetadata for " + mediaItem.mediaId);

  }

  @Override
  public void onAdSkipped(MediaItem mediaItem, Object adsId, int adGroupIndex,
      int adIndexInAdGroup) {
    Log.d("HLSTEST", "onAdSkipped for " + mediaItem.mediaId);
  }

  @Override
  public void onPrepareError(MediaItem mediaItem, Object adsId, int adGroupIndex,
      int adIndexInAdGroup, IOException exception) {
    Log.d("HLSTEST", "onPrepareError for " + mediaItem.mediaId + " error: " + exception);
  }

  @Override
  public void onPrepareCompleted(MediaItem mediaItem, Object adsId, int adGroupIndex,
      int adIndexInAdGroup) {
    Log.d("HLSTEST", "onPrepareCompleted for " + mediaItem.mediaId);

  }
}
