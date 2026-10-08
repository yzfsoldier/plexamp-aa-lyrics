/*
 * Plexamp AA Lyrics – synced lyrics for Plexamp on Android Auto
 * Copyright (C) 2026 yzfsoldier
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package tv.plex.labs.plexamp.media;
import java.util.List;
public final class PlayerStateAdapter {
  public static final class State { public static final State PLAYING=null, PAUSED=null, BUFFERING=null, STOPPED=null; }
  public static final class QueueItem { public final String getId(){return null;} public final String getTitle(){return null;} public final String getArtist(){return null;} public final String getAlbum(){return null;} public final long getDurationMs(){return 0;} }
  public static final class PlayerStateUpdate {
    public PlayerStateUpdate(String id, String title, String artist, String album, long d, long p, State s, String thumb, boolean hasNext, boolean longForm, boolean remoteSkip, int sf, int sb, int pqi, int pqc, float rating, String repeat, String shuffle, List<QueueItem> queue, int qi) {}
    public final String getId(){return null;} public final String getTitle(){return null;} public final String getArtist(){return null;} public final String getAlbum(){return null;}
    public final long getDurationMs(){return 0;} public final long getPositionMs(){return 0;} public final State getPlaybackState(){return null;} public final String getThumbUrl(){return null;}
    public final boolean getHasNextTrack(){return false;} public final boolean isLongFormAudio(){return false;} public final boolean getUseRemoteSkip(){return false;}
    public final int getSkipForwardMs(){return 0;} public final int getSkipBackwardMs(){return 0;} public final int getPlayQueueIndex(){return 0;} public final int getPlayQueueCount(){return 0;}
    public final float getUserRating(){return 0;} public final String getRepeatMode(){return null;} public final String getShuffleMode(){return null;} public final List<QueueItem> getQueue(){return null;} public final int getQueueIndex(){return 0;}
  }
}
