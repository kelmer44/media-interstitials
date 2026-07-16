package androidx.media3.demo.main.ads;

import android.content.Context;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.demo.main.DemoUtil;
import androidx.media3.datasource.DataSource;

@UnstableApi
public class AdsWizzAssetListResolverDataSourceFactory implements DataSource.Factory {

  private final Context context;

  public AdsWizzAssetListResolverDataSourceFactory(Context context) {
    this.context = context.getApplicationContext();
  }

  @Override
  public DataSource createDataSource() {
    Log.i("HLSTEST", "Using custom data source!");
    return new AdsWizzAssetListResolverDataSource(DemoUtil.getHttpDataSourceFactory(context));
  }
}
