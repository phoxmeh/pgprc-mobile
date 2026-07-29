package net.packetradio.mobile.ui.session

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
import androidx.compose.ui.unit.dp

@Composable
fun MonitorContent(lines: List<String>, filter: String, onFilterChanged: (String) -> Unit) {
    val filtered = if (filter.isBlank()) lines else lines.filter { it.contains(filter, ignoreCase = true) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = filter,
            onValueChange = onFilterChanged,
            label = { Text("Filter") },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        val listState = rememberLazyListState()
        LaunchedEffect(filtered.size) {
            if (filtered.isNotEmpty()) listState.scrollToItem(filtered.size - 1)
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(filtered) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
