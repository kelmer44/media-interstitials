package androidx.media3.exoplayer.hls;

import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;

/** Creates a manifest fixture without making the library constructor public. */
public final class TestHlsManifest {
  public static HlsManifest create(HlsMediaPlaylist playlist) {
    return new HlsManifest(null, playlist);
  }

  private TestHlsManifest() {}
}
