package androidx.media3.demo.main.ads;

import android.content.Context;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;

@UnstableApi
public class AdsWizzAssetListResolverDataSourceFactory implements DataSource.Factory {

  public AdsWizzAssetListResolverDataSourceFactory(Context context) {
  }

  @Override
  public DataSource createDataSource() {
    Log.i("HLSTEST", "Using custom data source!");
    return new AdsWizzAssetListResolverDataSource(true);
  }
}
