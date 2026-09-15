package com.music.bitchord.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Upgrade
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import coil3.SingletonImageLoader
import com.music.bitchord.R
import com.music.bitchord.data.AppUpdateChecker
import com.music.bitchord.data.canvas.CanvasCache
import com.music.bitchord.data.storage.StorageSizes
import com.music.bitchord.download.DownloadArtwork
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.AudioCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Storage & cache page — what BitChord keeps on this device, and what may be
 * cleared to get it back.
 *
 * Every figure is read from a store the app already owns ([StorageSizes]), so
 * what the page reports is always what the rest of the app sees, and every
 * clear goes through that store's existing delete — [Downloads] for downloaded
 * tracks, [AudioCache] / [CanvasCache] for the two Media3 caches, Coil and
 * [DownloadArtwork] for artwork, [AppUpdateChecker] for a sideloaded APK.
 * Nothing that is not a cache is ever reached from here: playlists, offline
 * library metadata, listening history, likes, pins and the account live
 * outside these stores and the page both refuses to list them and never
 * clears them.
 *
 * Deliberately offline: every number and every clear is local to the device.
 */
@Composable
fun StorageCacheScreen(
    contentPadding: PaddingValues,
    onDownloadsChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loader = remember(context) { SingletonImageLoader.get(context) }
    var totals by remember { mutableStateOf(StorageSizes.Totals()) }
    var computing by remember { mutableStateOf(true) }
    var pending by remember { mutableStateOf<ClearTarget?>(null) }

    fun recompute() {
        computing = true
        scope.launch {
            totals = withContext(Dispatchers.IO) {
                StorageSizes.Totals(
                    downloadsBytes = StorageSizes.downloadBytes(context),
                    cachedAudioBytes = StorageSizes.audioCacheBytes(),
                    artworkBytes = StorageSizes.artworkBytes(loader, context),
                    canvasBytes = StorageSizes.canvasBytes(),
                    updatesBytes = StorageSizes.updatesBytes(context),
                )
            }
            computing = false
        }
    }

    LaunchedEffect(Unit) { recompute() }

    fun toast(text: String) =
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    fun doClear(target: ClearTarget) {
        pending = null
        when (target) {
            ClearTarget.DOWNLOADS -> scope.launch {
                // The existing per-track and release deletion — nothing new to
                // keep in step with the Downloads page and Cached Music.
                withContext(Dispatchers.IO) {
                    Downloads.saved.value.keys.toList().forEach { Downloads.delete(context, it) }
                    Downloads.collections.value.keys.toList().forEach(Downloads::forgetCollection)
                }
                recompute()
                toast(context.getString(R.string.downloads_cleared))
                onDownloadsChanged()
            }
            ClearTarget.AUDIO -> AudioCache.clear {
                recompute()
                toast(context.getString(R.string.song_cache_cleared))
                onDownloadsChanged()
            }
            ClearTarget.ARTWORK -> scope.launch {
                // Empty the memory mirror and the disk cache, and the app's own
                // per-download covers too: only the cached copies respawn from
                // each other, a stored cover is a deliberate offline copy and
                // belongs to the rows it was saved for.
                withContext(Dispatchers.Main) {
                    loader.memoryCache?.clear()
                    loader.diskCache?.clear()
                }
                withContext(Dispatchers.IO) {
                    DownloadArtwork.deleteAll(context)
                }
                recompute()
                toast(context.getString(R.string.image_cache_cleared))
            }
            ClearTarget.CANVAS -> CanvasCache.clear {
                recompute()
                toast(context.getString(R.string.canvas_cleared))
            }
            ClearTarget.UPDATES -> scope.launch {
                AppUpdateChecker.clearCache(context)
                recompute()
                toast(context.getString(R.string.updates_cleared))
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        SettingsGroup(header = stringResource(R.string.storage_and_cache)) {
            SettingsRow(
                icon = Icons.Rounded.Download,
                title = stringResource(R.string.downloaded_tracks),
                subtitle = stringResource(R.string.downloaded_tracks_subtitle),
                value = StorageSizes.formatBytes(totals.downloadsBytes),
                onClick = { pending = ClearTarget.DOWNLOADS },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Storage,
                title = stringResource(R.string.cached_songs),
                subtitle = stringResource(R.string.cached_songs_subtitle),
                value = StorageSizes.formatBytes(totals.cachedAudioBytes),
                onClick = { pending = ClearTarget.AUDIO },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Image,
                title = stringResource(R.string.artwork_and_thumbnails),
                subtitle = stringResource(R.string.artwork_and_thumbnails_subtitle),
                value = StorageSizes.formatBytes(totals.artworkBytes),
                onClick = { pending = ClearTarget.ARTWORK },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Movie,
                title = stringResource(R.string.canvas_cache),
                subtitle = stringResource(R.string.canvas_cache_subtitle),
                value = StorageSizes.formatBytes(totals.canvasBytes),
                onClick = { pending = ClearTarget.CANVAS },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Upgrade,
                title = stringResource(R.string.app_updates),
                subtitle = stringResource(R.string.app_updates_subtitle),
                value = StorageSizes.formatBytes(totals.updatesBytes),
                onClick = { pending = ClearTarget.UPDATES },
            )
            RowDivider()
            // There is no lyrics cache to clear — lyrics are fetched on demand
            // and remembered in memory for the session only.
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.Notes,
                title = stringResource(R.string.lyrics_cache),
                subtitle = stringResource(R.string.lyrics_cache_subtitle),
                value = "—",
                onClick = null,
            )
        }

        SettingsGroup(header = stringResource(R.string.storage_total)) {
            SettingsRow(
                icon = Icons.Rounded.Storage,
                title = stringResource(R.string.total_storage),
                subtitle = if (computing) {
                    stringResource(R.string.computing_size)
                } else {
                    stringResource(R.string.storage_total_subtitle)
                },
                value = StorageSizes.formatBytes(totals.totalBytes),
                onClick = null,
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Refresh,
                title = stringResource(R.string.recalculate),
                onClick = ::recompute,
            )
        }
    }

    pending?.let { target ->
        val label = when (target) {
            ClearTarget.DOWNLOADS -> stringResource(R.string.downloaded_tracks)
            ClearTarget.AUDIO -> stringResource(R.string.cached_songs)
            ClearTarget.ARTWORK -> stringResource(R.string.artwork_and_thumbnails)
            ClearTarget.CANVAS -> stringResource(R.string.canvas_cache)
            ClearTarget.UPDATES -> stringResource(R.string.app_updates)
        }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = {
                if (target == ClearTarget.DOWNLOADS) {
                    Text(stringResource(R.string.clear_confirm_downloads_title))
                } else {
                    Text(stringResource(R.string.clear_confirm_cache_title, label))
                }
            },
            text = {
                Text(
                    if (target == ClearTarget.DOWNLOADS) {
                        stringResource(R.string.clear_confirm_downloads)
                    } else {
                        stringResource(R.string.clear_confirm_cache_body)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { doClear(target) }) {
                    Text(stringResource(R.string.clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** The one thing each row on this page is allowed to remove. */
private enum class ClearTarget { DOWNLOADS, AUDIO, ARTWORK, CANVAS, UPDATES }