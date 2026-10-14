package com.rtbishop.look4sat.feature.logbook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.rtbishop.look4sat.core.domain.logbook.LogbookFilter
import com.rtbishop.look4sat.core.domain.logbook.SATELLITE_MODE_FILTER
import com.rtbishop.look4sat.core.domain.logbook.displayMode

@Composable
internal fun LogbookFilters(state: LogbookState, onAction: (LogbookAction) -> Unit) {
    OutlinedTextField(
        value = state.query,
        onValueChange = { onAction(LogbookAction.Search(it)) },
        placeholder = { Text(stringResource(R.string.logbook_search)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        trailingIcon = {
            TextButton(onClick = { onAction(LogbookAction.ToggleGrouping) }) {
                Text(stringResource(if (state.groupByCallsign) R.string.logbook_group_calls else R.string.logbook_group_time))
            }
        }
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        items(LogbookFilter.entries.filter { it != LogbookFilter.DRAFTS }) { filter ->
            itemFilter(state.confirmationFilter == filter, filterLabel(filter)) {
                onAction(LogbookAction.ConfirmationFilter(filter))
            }
        }
        item {
            itemFilter(
                state.modeFilter == SATELLITE_MODE_FILTER,
                stringResource(R.string.logbook_satellite_mode)
            ) {
                onAction(
                    LogbookAction.ModeFilter(
                        if (state.modeFilter == SATELLITE_MODE_FILTER) "" else SATELLITE_MODE_FILTER
                    )
                )
            }
        }
        item {
            itemFilter(state.modeFilter.isBlank(), stringResource(R.string.logbook_all_modes)) {
                onAction(LogbookAction.ModeFilter(""))
            }
        }
        items(state.records.map { it.displayMode }.filter(String::isNotBlank).distinct().sorted()) { mode ->
            itemFilter(state.modeFilter == mode, mode) {
                onAction(LogbookAction.ModeFilter(if (state.modeFilter == mode) "" else mode))
            }
        }
    }
}

@Composable
private fun itemFilter(selected: Boolean, text: String, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(text) })
}

@Composable
private fun filterLabel(filter: LogbookFilter): String = stringResource(
    when (filter) {
        LogbookFilter.ALL -> R.string.logbook_all
        LogbookFilter.CONFIRMED -> R.string.logbook_confirmed
        LogbookFilter.UNCONFIRMED -> R.string.logbook_unconfirmed
        LogbookFilter.DRAFTS -> R.string.logbook_incomplete
    }
)

@Composable
internal fun LoTWDialog(state: LogbookState, onAction: (LogbookAction) -> Unit) {
    LoTWUploadDialog(state, onAction)
}

@Composable
internal fun LogbookStationDialog(state: LogbookState, onAction: (LogbookAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(LogbookAction.DismissStation) },
        title = { Text(stringResource(R.string.logbook_station)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.logbook_station_hint))
                OutlinedTextField(
                    value = state.stationCallsign,
                    onValueChange = { onAction(LogbookAction.StationCallsign(it)) },
                    label = { Text(stringResource(R.string.logbook_my_callsign)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth()
                )
                state.error?.let { error ->
                    Text(logbookErrorText(error), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAction(LogbookAction.SaveStation) }) { Text(stringResource(R.string.logbook_save)) } },
        dismissButton = { TextButton(onClick = { onAction(LogbookAction.DismissStation) }) { Text(stringResource(R.string.logbook_cancel)) } }
    )
}
