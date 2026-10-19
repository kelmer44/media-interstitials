package androidx.media3.demo.main.ads;

import android.net.Uri;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceUtil;
import androidx.media3.datasource.DataSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

@UnstableApi
public class AdsWizzAssetListResolverDataSource extends BaseDataSource {

  private static final String ASSETS_JSON_NAME = "ASSETS";

  private final DataSource.Factory upstreamDataSourceFactory;

  protected AdsWizzAssetListResolverDataSource(DataSource.Factory upstreamDataSourceFactory) {
    super(/* isNetwork= */ true);
    this.upstreamDataSourceFactory = upstreamDataSourceFactory;
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
      JSONObject assetList = loadOriginalAssetList(dataSpec);
      String jsonString = buildAssetListJson(requestUri, assetList);
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
      throw new IOException("Error parsing or creating JSON for asset list", e);
    }

    opened = true;
    transferStarted(dataSpec);
    return bytesRemaining;
  }

  private JSONObject loadOriginalAssetList(DataSpec dataSpec) throws IOException, JSONException {
    DataSource upstreamDataSource = upstreamDataSourceFactory.createDataSource();
    try {
      upstreamDataSource.open(dataSpec);
      String originalJsonString =
          new String(DataSourceUtil.readToEnd(upstreamDataSource), StandardCharsets.UTF_8);
      JSONObject assetList = new JSONObject(originalJsonString);
      Log.d("HLSTEST", "Original asset list contains "
          + assetList.getJSONArray(ASSETS_JSON_NAME).length() + " assets");
      return assetList;
    } finally {
      DataSourceUtil.closeQuietly(upstreamDataSource);
    }
  }

  private static String buildAssetListJson(Uri requestUri, JSONObject assetList)
      throws JSONException {
    // Preserve the server's asset order, URIs, durations, and optional metadata.
    JSONArray assets = assetList.getJSONArray(ASSETS_JSON_NAME);
    double totalDuration = 0.0;
    for (int i = 0; i < assets.length(); i++) {
      totalDuration += assets.getJSONObject(i).getDouble("DURATION");
    }

    double targetDuration = Double.parseDouble(
        Objects.requireNonNull(requestUri.getQueryParameter("duration"))
    );

    Log.d("HLSTEST", "targetDuration " + targetDuration + " totalDuration From assets " + totalDuration);


    if (targetDuration > totalDuration) {
      double missingDuration = targetDuration - totalDuration;
      Log.d("HLSTEST", "Should insert shim");
      assets.put(shim(requestUri, missingDuration));
    }

    return assetList.toString();
  }

  private static JSONObject shim(Uri requestUri, double missingDuration) throws JSONException {
    Uri assetPlaylistUri =
        new Uri.Builder()
            .scheme(requestUri.getScheme())
            .encodedAuthority(requestUri.getEncodedAuthority())
            .path("/shim.m3u8")
            .build();
    Log.d("HLSTEST", "Returning shim Uri " + assetPlaylistUri);
    return new JSONObject()
        .put("URI", assetPlaylistUri.toString())
        .put("DURATION", missingDuration);
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
