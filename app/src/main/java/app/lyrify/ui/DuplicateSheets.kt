package app.lyrify.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lyrify.R
import app.lyrify.dupes.AudioFile
import app.lyrify.dupes.DuplicateGroup
import app.lyrify.dupes.TrashedSong
import app.lyrify.lyrics.TrackNames

/**
 * A group's files, the suggested keep first with its star: only a hint, never acted on. Every file has a checkbox,
 * unchecked at first, the suggested keep's too: the user picks what goes. "Move to trash" asks Lyrify's dialog for the
 * checked ones, never every copy ("Keep at least one copy."); "Not duplicates" hides the group for good. Each file row is
 * one TalkBack stop: where it is, its facts, "Suggested keep" when it is, and its checkbox state.
 */
@Composable
fun GroupSheet(group: DuplicateGroup, stale: Boolean, onMove: (Set<Long>) -> Unit, onNotDuplicates: () -> Unit, onDismiss: () -> Unit) {
    var checked by rememberSaveable(group.key) { mutableStateOf(LongArray(0)) }
    val artist = group.keep.artist?.takeIf { TrackNames.cleanArtist(it) != null } ?: stringResource(R.string.check_no_artist)
    val count = checked.size
    LyrifySheet(
        onDismiss = onDismiss,
        footer = {
            LyrifyButton(
                if (count > 0) stringResource(R.string.dupes_move, count) else stringResource(R.string.dupes_move_none),
                onClick = { onMove(checked.toSet()) },
                modifier = Modifier.fillMaxWidth(),
                kind = ButtonKind.DESTRUCTIVE,
                icon = R.drawable.ic_delete,
                enabled = !stale && canMove(count, group.copies),
            )
            LyrifyButton(stringResource(R.string.dupes_not_duplicates), onClick = onNotDuplicates, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(group.keep.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            Text(artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(groupSentence(group.confidence)), style = MaterialTheme.typography.bodyLarge)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            group.files.forEachIndexed { index, file ->
                FileRow(file, keep = index == 0, checked = file.id in checked) { on ->
                    checked = if (on) checked + file.id else checked.filter { it != file.id }.toLongArray()
                }
            }
        }
        when {
            stale -> Fact(R.drawable.ic_warning, stringResource(R.string.dupes_changed))
            mustKeepOne(count, group.copies) ->
                Fact(R.drawable.ic_info, stringResource(R.string.dupes_keep_one), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

/**
 * One file as a selectable card: its cover, folder and name, format and bitrate, size, length and date added, its pills
 * and its checkbox; checked, the card takes the brand's container and a coral ring.
 */
@Composable
private fun FileRow(file: AudioFile, keep: Boolean, checked: Boolean, onCheck: (Boolean) -> Unit) {
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val brand = LocalBrandColors.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheck)
            .background(if (checked) brand.highlightContainer.copy(alpha = CHECKED_ALPHA) else MaterialTheme.colorScheme.surfaceContainerLow)
            .then(if (checked) Modifier.border(1.5.dp, brand.highlight, shape) else Modifier)
            .heightIn(min = 56.dp)
            .padding(12.dp),
    ) {
        SongCover(SongArt(file.path), size = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(fileLocation(file), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
            val format = fileFormat(file)
            Text(fileKbps(file)?.let { stringResource(R.string.dupes_file_format, format, formatCount(it)) } ?: format, style = MaterialTheme.typography.bodySmall, color = muted)
            val size = Formatter.formatShortFileSize(context, file.size)
            val length = songLength(file.durationMs)
            Text(
                file.addedAt?.let { added ->
                    val date = DateUtils.formatDateTime(context, added, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR)
                    stringResource(R.string.dupes_file_facts, size, length, date)
                } ?: stringResource(R.string.dupes_file_facts_no_date, size, length),
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = muted,
            )
            if (keep || file.hasLyrics) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    if (keep) SmallPill(R.drawable.ic_star_fill, stringResource(R.string.dupes_keep), brand.highlightContainer, brand.onHighlightContainer)
                    val synced = LocalStatusColors.current[Tone.SYNCED]
                    if (file.hasLyrics) SmallPill(R.drawable.ic_lyrics_fill, stringResource(R.string.dupes_lyrics), synced.container, synced.onContainer)
                }
            }
        }
        LyrifyCheckbox(checked)
    }
}

/** A small pill: an icon and a label, on [container]. */
@Composable
fun SmallPill(@DrawableRes icon: Int, label: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = CircleShape) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Lyrify's own dialog before the phone's: "Move 2 songs to trash?" */
@Composable
fun TrashDialog(count: Int, onCancel: () -> Unit, onConfirm: () -> Unit) {
    ConfirmDialog(
        title = pluralStringResource(R.plurals.dupes_trash_title, count, count),
        text = stringResource(R.string.dupes_trash_text),
        confirm = stringResource(R.string.dupes_trash_confirm),
        dismiss = stringResource(R.string.remove_cancel),
        onConfirm = onConfirm,
        onDismiss = onCancel,
    )
}

/** "Trashed songs" from the top bar's menu: the songs Lyrify moved to the phone's trash, each with Restore. */
@Composable
fun TrashedSheet(songs: List<TrashedSong>, onRestore: (TrashedSong) -> Unit, onDismiss: () -> Unit) {
    LyrifySheet(onDismiss = onDismiss, spacing = 0.dp, padding = 0.dp) {
        Text(
            stringResource(R.string.dupes_section_trashed),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics { heading() },
        )
        if (songs.isEmpty()) {
            Text(stringResource(R.string.dupes_trashed_empty), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 20.dp))
        }
        songs.forEach { song -> TrashedRow(song) { onRestore(song) } }
    }
}

private const val CHECKED_ALPHA = 0.6f
