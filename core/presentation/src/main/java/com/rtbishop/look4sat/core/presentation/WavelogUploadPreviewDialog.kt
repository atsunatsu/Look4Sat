/*
 * Look4Sat-BA7OPF. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2026 BA7OPF.
 * Based on Look4Sat by Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.logbook.wavelogSkipSummary
import com.rtbishop.look4sat.core.domain.repository.WavelogUploadPreview

/**
 * Confirmation sheet for a Wavelog-mode upload (radar log page and settings logbook):
 * shows the station profile, the batch size and the records held back before the POST,
 * so the operator commits the batch after seeing what goes out (the Wavelog analog of
 * the LoTW preview flow).
 */
@Composable
fun WavelogUploadPreviewDialog(
    preview: WavelogUploadPreview,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = sheetDialogShape(),
        containerColor = sheetDialogContainerColor(),
        title = { SheetDialogTitle(stringResource(R.string.prefs_logbook_upload_wavelog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (preview.stationLabel.isNotBlank()) {
                    Text(
                        stringResource(R.string.wavelog_upload_preview_station, preview.stationLabel),
                        fontSize = 13.sp
                    )
                }
                if (preview.qsoGrids.isNotEmpty()) {
                    val visibleGrids = preview.qsoGrids.take(4).joinToString(", ")
                    val more = (preview.qsoGrids.size - 4).coerceAtLeast(0)
                    Text(
                        stringResource(R.string.wavelog_upload_preview_qso_grids, visibleGrids) +
                            if (more > 0) stringResource(R.string.wavelog_upload_preview_more_grids, more) else "",
                        fontSize = 12.sp
                    )
                }
                Text("${preview.count} QSO(s) · ${preview.firstUtc} – ${preview.lastUtc}", fontSize = 13.sp)
                val skip = wavelogSkipSummary(preview)
                if (skip.isNotBlank()) {
                    Text(skip, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                Text(preview.contacts.joinToString("\n") { it }, fontSize = 12.sp, maxLines = 8)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(stringResource(R.string.prefs_logbook_upload_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}
