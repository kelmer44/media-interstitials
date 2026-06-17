package androidx.media3.demo.main.ads;

import android.net.Uri;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import java.io.IOException;

@UnstableApi
public class TestDataSource implements DataSource {

  private DataSource delegate;

  public TestDataSource(DataSource delegate) {
    this.delegate = delegate;
  }

  @Override
  public void addTransferListener(TransferListener transferListener) {
    delegate.addTransferListener(transferListener);
  }

  @Override
  public long open(DataSpec dataSpec) throws IOException {
    Log.i("HLSTEST", "open called for " + dataSpec.uri );
    return delegate.open(dataSpec);
  }

  @Nullable
  @Override
  public Uri getUri() {
    return delegate.getUri();
  }

  @Override
  public void close() throws IOException {
    Log.i("HLSTEST", "close called for " + getUri());
    delegate.close();
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
//    Log.i("HLSTEST", "read from offset = " + offset + " and length = " + length);
    return delegate.read(buffer, offset, length);
  }
}

