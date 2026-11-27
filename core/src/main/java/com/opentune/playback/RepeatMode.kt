package com.opentune.playback

import androidx.media3.common.Player

/**
 * The mode the repeat button moves to: off, then the whole queue, then the
 * current song, then off again. The app's button and the car's both step with
 * this, so a tap in either place lands on the same next state.
 */
fun nextRepeatMode(mode: Int): Int = when (mode) {
    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
    else -> Player.REPEAT_MODE_OFF
}
