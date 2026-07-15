package androidx.media3.demo.main.ads;

import android.net.Uri;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

@UnstableApi
public class AdsWizzAssetListResolverDataSource extends BaseDataSource {

  /**
   * Creates base data source.
   *
   * @param isNetwork Whether the data source loads data through a network.
   */
  protected AdsWizzAssetListResolverDataSource(boolean isNetwork) {
    super(isNetwork);
  }

  private Uri uri;
  private boolean opened = false;
  private byte[] data = new byte[0];
  private int readPosition;
  private int bytesRemaining;

  @Override
  public long open(DataSpec dataSpec) throws IOException {
    Log.i("HLSTEST", "Opening " + dataSpec.uri);
    transferInitializing(dataSpec);

    Uri requestUri = dataSpec.uri;
    this.uri = requestUri;
    try {
      String jsonString = buildAssetListJson(requestUri);
      Log.d("HLSTEST", "Returning asset list " + jsonString);
      // Saving whatever gets read in data to be read in [read]
      data = jsonString.getBytes(StandardCharsets.UTF_8);

      if (dataSpec.position > data.length) {
        throw new IOException("Read position out of range: " + dataSpec.position);
      }
      readPosition = (int) dataSpec.position;
      bytesRemaining = data.length - readPosition;
      if (dataSpec.length != C.LENGTH_UNSET) {
        bytesRemaining = (int) Math.min(bytesRemaining, dataSpec.length);
      }
    } catch (JSONException e) {
      throw new IOException("Error creating JSON for asset list", e);
    }

    opened = true;
    transferStarted(dataSpec);
    return bytesRemaining;
  }

  private static String buildAssetListJson(Uri requestUri) throws JSONException {
    String interstitialId = requestUri.getQueryParameter("interstitialId");
    if (interstitialId == null) {
      interstitialId = "ad-break";
    }

    double assetDurationSeconds = 6.037333;
    JSONArray assets = new JSONArray();
    assets.put(buildAssetJson(requestUri, interstitialId, "interstitial-2.ts", assetDurationSeconds, 1));
    assets.put(buildAssetJson(requestUri, interstitialId, "interstitial-1.ts", assetDurationSeconds, 2));
    return new JSONObject().put("ASSETS", assets).toString();
  }

  private static JSONObject buildAssetJson(
      Uri requestUri, String interstitialId, String asset, double durationSeconds, int position)
      throws JSONException {
    Uri assetPlaylistUri =
        new Uri.Builder()
            .scheme(requestUri.getScheme())
            .encodedAuthority(requestUri.getEncodedAuthority())
            .path("/interstitial.m3u8")
            .appendQueryParameter("interstitialId", interstitialId)
            .appendQueryParameter("asset", asset)
            .appendQueryParameter("duration", String.format(java.util.Locale.US, "%.6f", durationSeconds))
            .appendQueryParameter("position", String.valueOf(position))
            .build();
    Log.d("HLSTEST", "Returning asset Uri " + assetPlaylistUri);
    return new JSONObject()
        .put("URI", assetPlaylistUri.toString())
        .put("DURATION", durationSeconds);
  }

  @Nullable
  @Override
  public Uri getUri() {
    return uri;
  }

  @Override
  public void close() throws IOException {
    data = new byte[0];
    readPosition = 0;
    bytesRemaining = 0;
    uri = null;
    if (opened) {
      opened = false;
      transferEnded();
    }
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    if (!opened) {
      throw new IllegalStateException("DataSource not opened. Call open() first.");
    }
    if (length == 0) {
      return 0;
    }
    if (bytesRemaining == 0) {
      return C.RESULT_END_OF_INPUT;
    }
    int bytesToRead = Math.min(length, bytesRemaining);
    System.arraycopy(data, readPosition, buffer, offset, bytesToRead);
    readPosition += bytesToRead;
    bytesRemaining -= bytesToRead;
    bytesTransferred(bytesToRead);
    return bytesToRead;
  }
}
