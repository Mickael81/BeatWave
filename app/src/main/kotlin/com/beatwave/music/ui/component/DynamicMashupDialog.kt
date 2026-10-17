/**
 * BeatWave Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.beatwave.music.ui.component

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.beatwave.music.LocalDatabase
import com.beatwave.music.LocalPlayerConnection
import com.beatwave.music.R
import com.beatwave.music.extensions.metadata
import com.beatwave.music.extensions.toMediaItem
import com.beatwave.music.models.MediaMetadata
import com.beatwave.music.models.toMediaMetadata
import com.beatwave.music.playback.queues.ListQueue
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

enum class MashupTransitionStyle(val title: String, val subtitle: String) {
    LINE_BY_LINE("Line-by-Line Shuffler", "Alternates verses line-by-line in sync with master BPM"),
    VERSE_BY_VERSE("Verse-by-Verse (4-8 Bars)", "Rotates songs every musical phrase for party vibes"),
    ACAPELLA_SWAP("Acapella Vocal Swap", "Swaps lead vocal lines over a single unified instrumental groove")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DynamicMashupDialog(
    initialSong: MediaMetadata,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current

    val allLibrarySongs by database.allSongs().collectAsState(initial = emptyList())
    val likedSongs by database.likedSongsByCreateDateAsc().collectAsState(initial = emptyList())
    val queueWindows by (playerConnection?.queueWindows ?: MutableStateFlow(emptyList())).collectAsState()
    val queueSongs = remember(queueWindows, allLibrarySongs) {
        val songMap = allLibrarySongs.associateBy { it.id }
        val fromWindows = queueWindows.mapNotNull { window ->
            window.mediaItem.metadata ?: songMap[window.mediaItem.mediaId]?.toMediaMetadata()
        }
        val fromPlayer = try {
            playerConnection?.player?.let { p ->
                (0 until p.mediaItemCount).mapNotNull { idx ->
                    val item = p.getMediaItemAt(idx)
                    item.metadata ?: songMap[item.mediaId]?.toMediaMetadata()
                }
            }.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        (fromWindows + fromPlayer).distinctBy { it.id }
    }

    val selectedTracks = remember { mutableStateListOf(initialSong) }
    var selectedStyle by remember { mutableStateOf(MashupTransitionStyle.LINE_BY_LINE) }
    var activeSourceTab by remember { mutableStateOf(if (queueSongs.isNotEmpty()) "QUEUE" else "ALL") }
    LaunchedEffect(queueSongs) {
        if (queueSongs.isNotEmpty() && activeSourceTab == "ALL") {
            activeSourceTab = "QUEUE"
        }
    }
    var searchQuery by remember { mutableStateOf("") }
    var onlineResults by remember { mutableStateOf<List<MediaMetadata>>(emptyList()) }
    var isSearchingOnline by remember { mutableStateOf(false) }

    // Auto-populate Slot 2 from queue or library so user can mix in 1 click
    LaunchedEffect(queueSongs, likedSongs, allLibrarySongs) {
        if (selectedTracks.size == 1) {
            val candidate = queueSongs.firstOrNull { it.id != initialSong.id }
                ?: likedSongs.firstOrNull { it.id != initialSong.id }?.toMediaMetadata()
                ?: allLibrarySongs.firstOrNull { it.id != initialSong.id }?.toMediaMetadata()
            if (candidate != null && selectedTracks.none { it.id == candidate.id }) {
                selectedTracks.add(candidate)
            }
        }
    }

    // Debounced online search if user types in search box
    LaunchedEffect(searchQuery) {
        val q = searchQuery.trim()
        if (q.length >= 2) {
            delay(350L)
            isSearchingOnline = true
            withContext(Dispatchers.IO) {
                val res = YouTube.search(q, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                val songItems = res?.items?.filterIsInstance<SongItem>().orEmpty()
                onlineResults = songItems.map { it.toMediaMetadata() }
            }
            isSearchingOnline = false
        } else {
            onlineResults = emptyList()
            isSearchingOnline = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .fillMaxHeight(0.88f),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.equalizer),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "🎧 DJ Song Mashup Studio",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.padding(2.dp)
                        ) {
                            Text(
                                text = "${selectedTracks.size}/4",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = "Real-time AI phrase & verse DJ mixing • Up to 4 tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Selected Tracks Slots Display (Max 4)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Selected Tracks (Anchor + up to 3 more)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (queueSongs.isNotEmpty() && selectedTracks.size < 4) {
                            TextButton(
                                onClick = {
                                    val availableFromQueue = queueSongs.filter { qSong -> selectedTracks.none { it.id == qSong.id } }
                                    val slotsNeeded = 4 - selectedTracks.size
                                    val toAdd = availableFromQueue.take(slotsNeeded)
                                    if (toAdd.isNotEmpty()) {
                                        selectedTracks.addAll(toAdd)
                                        Toast.makeText(context, "Filled ${toAdd.size} slot(s) from Queue", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "All queue songs already added", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.queue_music),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Fill from Queue",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (selectedTracks.size > 1) {
                            TextButton(
                                onClick = {
                                    val anchor = selectedTracks.first()
                                    selectedTracks.clear()
                                    selectedTracks.add(anchor)
                                    Toast.makeText(context, "Reset to Anchor track only", Toast.LENGTH_SHORT).show()
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "Clear Slots",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    for (slotIndex in 0 until 4) {
                        val track = selectedTracks.getOrNull(slotIndex)
                        if (track != null) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (slotIndex == 0) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = if (slotIndex == 0) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                                else null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (slotIndex == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "${slotIndex + 1}",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (slotIndex == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondary
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    AsyncImage(
                                        model = track.thumbnailUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = track.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (slotIndex == 0) {
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                                ) {
                                                    Text(
                                                        text = "ANCHOR BPM",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontSize = 9.sp,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = track.artists.joinToString(", ") { it.name },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (slotIndex > 0) {
                                        IconButton(
                                            onClick = {
                                                // Swap track with slot 0 so it becomes Anchor!
                                                val currentAnchor = selectedTracks[0]
                                                selectedTracks[0] = track
                                                selectedTracks[slotIndex] = currentAnchor
                                                Toast.makeText(context, "${track.title} is now the Anchor BPM track", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.equalizer),
                                                contentDescription = "Set as Anchor",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { selectedTracks.remove(track) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.close),
                                                contentDescription = "Remove",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        activeSourceTab = "QUEUE"
                                        Toast.makeText(context, "Select a song from Queue below for Slot ${slotIndex + 1}", Toast.LENGTH_SHORT).show()
                                    }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "${slotIndex + 1}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "+ Tap to select from Queue for Slot ${slotIndex + 1}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }

                // Transition Algorithm Picker
                Text(
                    text = "Mixing Algorithm",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MashupTransitionStyle.values().forEach { style ->
                        FilterChip(
                            selected = selectedStyle == style,
                            onClick = { selectedStyle = style },
                            label = {
                                Text(
                                    text = style.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (selectedStyle == style) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                Text(
                    text = selectedStyle.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )

                // Source Tabs
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    listOf(
                        "QUEUE" to "Queue (${queueSongs.size})",
                        "ALL" to "All",
                        "LIKED" to "Liked (${likedSongs.size})",
                        "LIBRARY" to "Library (${allLibrarySongs.size})"
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = activeSourceTab == key,
                            onClick = { activeSourceTab = key },
                            leadingIcon = if (key == "QUEUE") {
                                {
                                    Icon(
                                        painter = painterResource(R.drawable.queue_music),
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            } else null,
                            label = {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (activeSourceTab == key) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                // Search Candidate Songs
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = "Search songs in library or YouTube...",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.search),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    painter = painterResource(R.drawable.close),
                                    contentDescription = "Clear",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                    )
                )

                // Candidate Songs List (Queue + Liked + Library + Online YouTube Results)
                val candidateSongs = remember(allLibrarySongs, likedSongs, queueSongs, onlineResults, searchQuery, activeSourceTab) {
                    val q = searchQuery.trim()
                    val baseList: List<MediaMetadata> = when (activeSourceTab) {
                        "QUEUE" -> queueSongs
                        "LIKED" -> likedSongs.map { it.toMediaMetadata() }
                        "LIBRARY" -> allLibrarySongs.map { it.toMediaMetadata() }
                        else -> (queueSongs + likedSongs.map { it.toMediaMetadata() } + allLibrarySongs.map { it.toMediaMetadata() }).distinctBy { it.id }
                    }

                    if (q.isEmpty()) {
                        baseList.take(60)
                    } else {
                        val localMatches = baseList.filter { meta ->
                            meta.title.contains(q, ignoreCase = true) ||
                                meta.artists.any { it.name.contains(q, ignoreCase = true) }
                        }
                        (localMatches + onlineResults).distinctBy { it.id }.take(60)
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (candidateSongs.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isSearchingOnline) "Searching online..."
                                    else if (searchQuery.isNotEmpty()) "No matching songs found"
                                    else if (activeSourceTab == "QUEUE") "No songs found in current queue"
                                    else "No songs in queue or library yet",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        items(candidateSongs, key = { it.id }) { meta ->
                            val isSelected = selectedTracks.any { it.id == meta.id }
                            val isAnchor = selectedTracks.firstOrNull()?.id == meta.id
                            val slotIndex = selectedTracks.indexOfFirst { it.id == meta.id }
                            val queueIdx = queueSongs.indexOfFirst { it.id == meta.id }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                else Color.Transparent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isAnchor) {
                                            Toast.makeText(context, "Anchor track is required for tempo reference", Toast.LENGTH_SHORT).show()
                                        } else if (isSelected) {
                                            selectedTracks.removeAll { it.id == meta.id }
                                            Toast.makeText(context, "Removed ${meta.title} from mashup", Toast.LENGTH_SHORT).show()
                                        } else {
                                            if (selectedTracks.size < 4) {
                                                selectedTracks.add(meta)
                                                Toast.makeText(context, "Added to Slot ${selectedTracks.size}", Toast.LENGTH_SHORT).show()
                                            } else {
                                                selectedTracks[3] = meta
                                                Toast.makeText(context, "Replaced Slot 4 with ${meta.title}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
                                ) {
                                    AsyncImage(
                                        model = meta.thumbnailUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = meta.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (queueIdx >= 0) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
                                                ) {
                                                    Text(
                                                        text = "Queue #${queueIdx + 1}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = meta.artists.joinToString(", ") { it.name },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    if (isSelected) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = if (isAnchor) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.padding(end = 4.dp)
                                        ) {
                                            Text(
                                                text = if (isAnchor) "Slot 1 (Anchor)" else "Slot ${slotIndex + 1}",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isAnchor) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    painter = painterResource(R.drawable.check),
                                                    contentDescription = "Selected",
                                                    tint = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    } else {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    painter = painterResource(R.drawable.add),
                                                    contentDescription = "Add",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (playerConnection != null && selectedTracks.size >= 2) {
                        com.beatwave.music.playback.DynamicMashupController.startMashup(
                            selectedTracks = selectedTracks.toList(),
                            style = selectedStyle,
                            playerConnection = playerConnection
                        )
                        Toast.makeText(
                            context,
                            "Dynamic Mashup started! Blending ${selectedTracks.size} tracks with ${selectedStyle.title}",
                            Toast.LENGTH_LONG
                        ).show()
                        onDismiss()
                    } else {
                        Toast.makeText(context, "Please select at least 2 tracks to start mashup", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = selectedTracks.size >= 2,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    painter = painterResource(R.drawable.equalizer),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (selectedTracks.size >= 2) "Launch Dynamic Mashup (${selectedTracks.size} Tracks)" else "Select 2+ tracks to mix",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (com.beatwave.music.playback.DynamicMashupController.isActive) {
                    FilledTonalButton(
                        onClick = {
                            com.beatwave.music.playback.DynamicMashupController.stopMashup()
                            Toast.makeText(context, "DJ Mashup stopped", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.close),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop Mashup", fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}
