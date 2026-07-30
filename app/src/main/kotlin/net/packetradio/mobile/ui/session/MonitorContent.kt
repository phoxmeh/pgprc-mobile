package net.packetradio.mobile.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import net.packetradio.mobile.model.HighlightPrefs
import net.packetradio.mobile.model.defaultHighlightRules

/** One rendered line — [unproto] marks a UI/unproto frame, see [SessionViewModel.unprotoOnly]. */
data class MonitorLine(val text: String, val unproto: Boolean = false)

/**
 * [showFilter] defaults the Filter field to hidden — toggled via a header
 * icon (see [SessionScreen]) rather than always reserving space for it, so
 * the line list gets the most room by default. The free-text filter is only
 * applied while the field is shown, so hiding it also visibly means "not
 * filtering" rather than silently keeping a stale filter active. [unprotoOnly]
 * is a separate, persistent preference (defaults on) that applies regardless
 * of [showFilter] — pass [onUnprotoOnlyChanged] to also show its checkbox
 * next to the filter field (omit it for a line list, like the Log tab, where
 * "unproto" has no meaning).
 */
@Composable
fun MonitorContent(
    lines: List<MonitorLine>,
    filter: String,
    onFilterChanged: (String) -> Unit,
    myCall: String,
    highlightPrefs: HighlightPrefs,
    showFilter: Boolean,
    unprotoOnly: Boolean = false,
    onUnprotoOnlyChanged: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val byUnproto = if (unprotoOnly) lines.filter { it.unproto } else lines
    val filtered = if (!showFilter || filter.isBlank()) byUnproto else byUnproto.filter { it.text.contains(filter, ignoreCase = true) }
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val errorColor = MaterialTheme.colorScheme.error

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp).padding(top = 4.dp, bottom = 12.dp)) {
        if (showFilter) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = onFilterChanged,
                    label = { Text("Filter") },
                    modifier = Modifier.weight(1f),
                )
                if (onUnprotoOnlyChanged != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                        Checkbox(checked = unprotoOnly, onCheckedChange = onUnprotoOnlyChanged)
                        Text("UI", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        val listState = rememberLazyListState()
        LaunchedEffect(filtered.size) {
            if (filtered.isNotEmpty()) listState.scrollToItem(filtered.size - 1)
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(filtered) { line ->
                Text(
                    highlightMonitorLine(line.text, myCall, highlightPrefs, defaultHighlightRules(), mutedColor, errorColor),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}
