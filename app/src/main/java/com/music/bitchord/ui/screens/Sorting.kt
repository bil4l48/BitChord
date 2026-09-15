package com.music.bitchord.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.LocalMusicSort

/**
 * The one ordering engine behind every sortable collection in the app.
 *
 * A list of anything with a title can be sorted by the full universal menu —
 * a track, an artist grouping, an album, a shelf card — provided the caller
 * hands over whatever extra data it carries. [LocalMusicSort.DEFAULT] always
 * means "leave the list as it came", and every other option falls back to the
 * item's title as the tie-break so an ordering can never produce a shuffled
 * pile from a lot of ties.
 */
internal fun <T> sortByOrder(
    items: List<T>,
    order: LocalMusicSort,
    title: (T) -> String,
    playCount: (T) -> Int = { 0 },
    dateAdded: (T) -> Long? = { null },
    dateModified: (T) -> Long? = { null },
): List<T> = when (order) {
    LocalMusicSort.DEFAULT -> items
    LocalMusicSort.TITLE_ASC -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { title(it) })
    LocalMusicSort.TITLE_DESC -> items.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { title(it) })
    LocalMusicSort.DATE_ADDED -> items.sortedWith(
        compareByDescending<T> { dateAdded(it) ?: Long.MIN_VALUE }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { title(it) },
    )
    LocalMusicSort.DATE_ADDED_OLDEST -> items.sortedWith(
        compareBy<T> { dateAdded(it) ?: Long.MAX_VALUE }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { title(it) },
    )
    LocalMusicSort.DATE_MODIFIED -> items.sortedWith(
        compareByDescending<T> { dateModified(it) ?: Long.MIN_VALUE }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { title(it) },
    )
    LocalMusicSort.PLAYS_MOST -> items.sortedWith(
        compareByDescending<T> { playCount(it) }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { title(it) },
    )
    LocalMusicSort.PLAYS_LEAST -> items.sortedWith(
        compareBy<T> { playCount(it) }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { title(it) },
    )
}

/**
 * The track-level sort shared by the device folders and every album/playlist
 * page. Kept under this name because that is what the on-device libraries have
 * always called it, and the local/play-count/date data it reads is exactly the
 * data a `Song` carries.
 */
internal fun List<Song>.sortedForLibrary(
    order: LocalMusicSort,
    playCounts: Map<String, Int> = emptyMap(),
): List<Song> = sortByOrder(
    items = this,
    order = order,
    title = { it.title },
    playCount = { playCounts[it.videoId] ?: 0 },
    dateAdded = { it.localDateAddedSeconds },
    dateModified = { it.localDateModifiedSeconds },
)

// ── The universal menu sets ──────────────────────────────────────────────────

/**
 * The device folders' sort — every criterion except [LocalMusicSort.DEFAULT],
 * in the order the folders have always shown them.
 */
internal val FOLDER_SORT_OPTIONS: List<LocalMusicSort> =
    LocalMusicSort.entries.filter { it != LocalMusicSort.DEFAULT }

/**
 * An album or playlist page's sort — the release's own running order plus all
 * six universal criteria. Date modified is deliberately absent: a catalogue
 * row has no "changed on this device" moment to speak of.
 */
internal val DETAIL_SORT_OPTIONS: List<LocalMusicSort> =
    LocalMusicSort.entries.filter { it != LocalMusicSort.DATE_MODIFIED }

/**
 * A Library "Show all" grid's sort. A card there only ever carries a title —
 * no MediaStore date, no play count — so the only reorderings it can honestly
 * offer are alphabetical, with the shelf's own order as the default.
 */
internal val LIBRARY_GRID_SORT_OPTIONS: List<LocalMusicSort> =
    listOf(LocalMusicSort.DEFAULT, LocalMusicSort.TITLE_ASC, LocalMusicSort.TITLE_DESC)

@Composable
internal fun LocalMusicSort.localizedLabel(): String = when (this) {
    LocalMusicSort.DEFAULT -> stringResource(R.string.sort_default)
    LocalMusicSort.TITLE_ASC -> stringResource(R.string.sort_title_ascending)
    LocalMusicSort.TITLE_DESC -> stringResource(R.string.sort_title_descending)
    LocalMusicSort.DATE_ADDED -> stringResource(R.string.sort_date_added)
    LocalMusicSort.DATE_ADDED_OLDEST -> stringResource(R.string.sort_date_added_oldest)
    LocalMusicSort.DATE_MODIFIED -> stringResource(R.string.sort_date_modified)
    LocalMusicSort.PLAYS_MOST -> stringResource(R.string.sort_most_listened)
    LocalMusicSort.PLAYS_LEAST -> stringResource(R.string.sort_least_listened)
}

/**
 * The sort menu itself, shared by every screen that offers one. [options]
 * picks the criteria the collection can actually answer — see the three option
 * sets above — and the current selection is checked.
 */
@Composable
internal fun LocalMusicSortMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    current: LocalMusicSort,
    options: List<LocalMusicSort> = DETAIL_SORT_OPTIONS,
    onSelect: (LocalMusicSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
    ) {
        options.forEach { option ->
            DropdownMenuItem(
                text = { Text(option.localizedLabel()) },
                trailingIcon = if (option == current) {
                    { Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.selected)) }
                } else {
                    null
                },
                onClick = {
                    onSelect(option)
                    onDismiss()
                },
            )
        }
    }
}