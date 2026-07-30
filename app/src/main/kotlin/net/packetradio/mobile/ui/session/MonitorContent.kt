package net.packetradio.mobile.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import net.packetradio.mobile.model.HighlightPrefs
import net.packetradio.mobile.model.defaultHighlightRules

/**
 * [showFilter] defaults the Filter field to hidden — toggled via a header
 * icon (see [SessionScreen]) rather than always reserving space for it, so
 * the line list gets the most room by default. Filtering itself is only
 * applied while the field is shown, so hiding it also visibly means "not
 * filtering" rather than silently keeping a stale filter active.
 */
@Composable
fun MonitorContent(
    lines: List<String>,
    filter: String,
    onFilterChanged: (String) -> Unit,
    myCall: String,
    highlightPrefs: HighlightPrefs,
    showFilter: Boolean,
    modifier: Modifier = Modifier,
) {
    val filtered = if (!showFilter || filter.isBlank()) lines else lines.filter { it.contains(filter, ignoreCase = true) }
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val errorColor = MaterialTheme.colorScheme.error

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp).padding(top = 4.dp, bottom = 12.dp)) {
        if (showFilter) {
            OutlinedTextField(
                value = filter,
                onValueChange = onFilterChanged,
                label = { Text("Filter") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        val listState = rememberLazyListState()
        LaunchedEffect(filtered.size) {
            if (filtered.isNotEmpty()) listState.scrollToItem(filtered.size - 1)
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(filtered) { line ->
                Text(
                    highlightMonitorLine(line, myCall, highlightPrefs, defaultHighlightRules(), mutedColor, errorColor),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}
