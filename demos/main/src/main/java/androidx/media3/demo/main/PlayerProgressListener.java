/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.demo.main;

import android.os.Handler;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import java.util.ArrayList;

/**
 * Emits player progress updates at a fixed interval.
 */
@UnstableApi
public final class PlayerProgressListener implements Player.Listener {

  public interface Callback {

    void onProgress(Progress progress);
  }

  public static final class Progress {

    public final long positionMs;
    public final long contentPositionMs;
    public final long bufferedPositionMs;
    public final long contentBufferedPositionMs;
    public final long durationMs;
    public final int mediaItemIndex;
    public final boolean isPlayingAd;
    public final int adGroupIndex;
    public final int adIndexInAdGroup;

    private Progress(Player player) {
      positionMs = player.getCurrentPosition();
      contentPositionMs = player.getContentPosition();
      bufferedPositionMs = player.getBufferedPosition();
      contentBufferedPositionMs = player.getContentBufferedPosition();
      durationMs = player.getDuration();
      mediaItemIndex = player.getCurrentMediaItemIndex();
      isPlayingAd = player.isPlayingAd();
      adGroupIndex = isPlayingAd ? player.getCurrentAdGroupIndex() : C.INDEX_UNSET;
      adIndexInAdGroup = isPlayingAd ? player.getCurrentAdIndexInAdGroup() : C.INDEX_UNSET;
    }
  }

  private static final long DEFAULT_UPDATE_INTERVAL_MS = 1_000;

  private final Player player;
  private final ArrayList<Callback> callbacks = new ArrayList<>();
  private final Handler handler;
  private final long updateIntervalMs;
  private final Runnable updateRunnable;
  private boolean started;

  public PlayerProgressListener(Player player) {
    this(player, DEFAULT_UPDATE_INTERVAL_MS);
  }

  public PlayerProgressListener(Player player, long updateIntervalMs) {
    this.player = player;
    this.updateIntervalMs = updateIntervalMs;
    handler = new Handler(player.getApplicationLooper());
    updateRunnable = this::emitAndScheduleNextUpdate;
  }

  public void addCallback(Callback callback) {
    this.callbacks.add(callback);
  }

  public void removeCallback(Callback callback) {
    this.callbacks.remove(callback);
  }

  public void start() {
    if (started) {
      return;
    }
    started = true;
    player.addListener(this);
    emitAndScheduleNextUpdate();
  }

  public void stop() {
    if (!started) {
      return;
    }
    started = false;
    handler.removeCallbacks(updateRunnable);
    player.removeListener(this);
  }

  @Override
  public void onPlaybackStateChanged(@Player.State int playbackState) {
    if (started && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED) {
      scheduleNextUpdate();
    }
  }

  private void emitAndScheduleNextUpdate() {
    if (!started) {
      return;
    }
    for (Callback callback : callbacks) {
      callback.onProgress(new Progress(player));
    }
    scheduleNextUpdate();
  }

  private void scheduleNextUpdate() {
    handler.removeCallbacks(updateRunnable);
    @Player.State int playbackState = player.getPlaybackState();
    if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
      return;
    }
    handler.postDelayed(updateRunnable, updateIntervalMs);
  }
}
