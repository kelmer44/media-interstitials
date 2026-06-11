package androidx.media3.demo.main.ads

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec


public class AdswizzDataSourceFactory(

): DataSource.Factory {
    override fun createDataSource(): DataSource {
        return AdswizzAssetListDataSource()
    }


}

@OptIn(UnstableApi::class)
class AdswizzAssetListDataSource : BaseDataSource(true) {

    private var uri: Uri? = null
    private var readPosition = 0
    private var bytesRemaining = 0
    private var opened = false
    private var data: ByteArray = ByteArray(0)

    override fun open(dataSpec: DataSpec): Long {
        TODO("Not yet implemented")
    }

    override fun getUri(): Uri? {
        TODO("Not yet implemented")
    }

    override fun close() {
        TODO("Not yet implemented")
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        TODO("Not yet implemented")
    }
}