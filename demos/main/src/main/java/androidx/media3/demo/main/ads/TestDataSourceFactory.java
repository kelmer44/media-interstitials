package androidx.media3.demo.main.ads;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;

@UnstableApi
public class TestDataSourceFactory implements DataSource.Factory {

  private DataSource.Factory delegateFactory;

  public TestDataSourceFactory(DataSource.Factory factory) {
      delegateFactory = factory;
  }

  @Override
  public DataSource createDataSource() {
    return new TestDataSource(delegateFactory.createDataSource());
  }
}
