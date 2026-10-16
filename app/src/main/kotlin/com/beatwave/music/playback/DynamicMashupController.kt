/**
 * BeatWave Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.beatwave.music.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.beatwave.music.extensions.toMediaItem
import com.beatwave.music.models.MediaMetadata
import com.beatwave.music.playback.queues.ListQueue
import com.beatwave.music.ui.component.MashupTransitionStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object DynamicMashupController {
    var isActive by mutableStateOf(false)
        private set

    var currentStyle by mutableStateOf(MashupTransitionStyle.LINE_BY_LINE)
        private set

    var tracks by mutableStateOf<List<MediaMetadata>>(emptyList())
        private set

    var currentTrackIndex by mutableIntStateOf(0)
        private set

    var secondsRemaining by mutableIntStateOf(10)
        private set

    var totalSwaps by mutableIntStateOf(0)
        private set

    private var mashupJob: Job? = null
    private val scope by lazy { CoroutineScope(Dispatchers.Main.immediate) }
    private var playerConnectionRef: PlayerConnection? = null

    fun getIntervalForStyle(style: MashupTransitionStyle): Int = when (style) {
        MashupTransitionStyle.LINE_BY_LINE -> 10 // Switches every 10s line-by-line
        MashupTransitionStyle.VERSE_BY_VERSE -> 20 // Switches every 20s verse-by-verse
        MashupTransitionStyle.ACAPELLA_SWAP -> 14 // Swaps hooks every 14s
    }

    fun startMashup(
        selectedTracks: List<MediaMetadata>,
        style: MashupTransitionStyle,
        playerConnection: PlayerConnection
    ) {
        if (selectedTracks.isEmpty()) return
        stopMashup()

        playerConnectionRef = playerConnection
        tracks = selectedTracks
        currentStyle = style
        currentTrackIndex = 0
        totalSwaps = 0
        secondsRemaining = getIntervalForStyle(style)
        isActive = true

        val mediaItems = selectedTracks.map { it.toMediaItem() }
        playerConnection.playQueue(
            ListQueue(
                title = "Dynamic Mashup (${selectedTracks.size} Tracks)",
                items = mediaItems
            )
        )

        startLoop()
    }

    private fun startLoop() {
        mashupJob?.cancel()
        mashupJob = scope.launch {
            while (isActive && this.isActive) {
                delay(1000L)
                val conn = playerConnectionRef ?: break
                if (conn.player.isPlaying) {
                    if (secondsRemaining > 1) {
                        secondsRemaining--
                    } else {
                        swapToNextTrack()
                    }
                }
            }
        }
    }

    fun swapToNextTrack() {
        val conn = playerConnectionRef ?: return
        if (tracks.isEmpty()) return

        val nextIndex = (currentTrackIndex + 1) % tracks.size
        val currentPos = conn.player.currentPosition
        val duration = conn.player.duration

        // Maintain continuous phrase flow: jump to corresponding bar
        val targetPos = if (duration > 0 && currentPos > 0) {
            (currentPos + 300L).coerceAtMost(duration - 1500L)
        } else 0L

        currentTrackIndex = nextIndex
        totalSwaps++
        secondsRemaining = getIntervalForStyle(currentStyle)

        conn.player.seekTo(nextIndex, targetPos)
    }

    fun stopMashup() {
        mashupJob?.cancel()
        mashupJob = null
        isActive = false
        tracks = emptyList()
        currentTrackIndex = 0
        totalSwaps = 0
        secondsRemaining = 10
    }
}
