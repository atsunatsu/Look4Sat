/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
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
package com.rtbishop.look4sat.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.rtbishop.look4sat.core.domain.model.UpdateSource
import com.rtbishop.look4sat.core.presentation.CardButton
import com.rtbishop.look4sat.core.presentation.IconCard
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.ScreenColumn
import com.rtbishop.look4sat.core.presentation.TopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateCheckerScreen(
    currentVersion: String,
    state: UpdateCheckerState,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onSourceChange: (UpdateSource) -> Unit,
    onConsumeApk: () -> Unit
) {
    val context = LocalContext.current
    var sourceMenuExpanded by remember { mutableStateOf(false) }

    // When the APK finishes downloading, hand it to the system installer.
    LaunchedEffect(state.apkFile) {
        val file = state.apkFile ?: return@LaunchedEffect
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        onConsumeApk()
    }

    ScreenColumn(
        topBar = { isVerticalLayout ->
            TopBar {
                IconCard(
                    action = onBack,
                    resId = R.drawable.ic_arrow,
                    modifier = Modifier.rotate(180f)
                )
                Text(
                    text = stringResource(R.string.update_check_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
        ) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.update_check_current, currentVersion),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val canChangeSource = !state.isChecking && !state.isDownloading
                    ExposedDropdownMenuBox(
                        expanded = sourceMenuExpanded && canChangeSource,
                        onExpandedChange = { if (canChangeSource) sourceMenuExpanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = updateSourceLabel(state.selectedSource),
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            enabled = canChangeSource,
                            label = { Text(stringResource(R.string.update_source_selection)) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(
                                    expanded = sourceMenuExpanded && canChangeSource
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = canChangeSource)
                        )
                        ExposedDropdownMenu(
                            expanded = sourceMenuExpanded && canChangeSource,
                            onDismissRequest = { sourceMenuExpanded = false }
                        ) {
                            UpdateSource.entries.forEach { source ->
                                DropdownMenuItem(
                                    text = { Text(updateSourceLabel(source)) },
                                    onClick = {
                                        sourceMenuExpanded = false
                                        onSourceChange(source)
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    CardButton(
                        onClick = onCheck,
                        text = stringResource(R.string.update_check_button),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            state.activeSource?.let { source ->
                Text(
                    text = stringResource(
                        if (state.isChecking || state.isDownloading) R.string.update_source_trying
                        else R.string.update_source_served,
                        updateSourceLabel(source)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when {
                state.isChecking -> StatusRow(showSpinner = true)
                state.release == null -> {
                    state.errorResId?.let { error ->
                        StatusRow(showSpinner = false, message = stringResource(error))
                    }
                }
                !state.hasUpdate -> StatusRow(showSpinner = false, message = stringResource(R.string.update_check_up_to_date))
                else -> ReleaseCard(
                    state = state,
                    onDownload = onDownload,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StatusRow(showSpinner: Boolean, message: String? = null) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (showSpinner) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        if (message != null) {
            Text(
                text = message,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ReleaseCard(state: UpdateCheckerState, onDownload: () -> Unit, modifier: Modifier = Modifier) {
    val release = state.release ?: return
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = stringResource(R.string.update_check_available),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.update_check_latest, release.versionTag.removePrefix("v")),
                fontSize = 14.sp
            )
            if (release.title.isNotBlank() && release.title != release.versionTag) {
                Text(text = release.title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (release.body.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.update_check_release_notes),
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp)
                ) {
                    Text(
                        text = release.body,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(10.dp))
            state.errorResId?.let { error ->
                Text(
                    text = stringResource(error),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
            }
            when {
                release.apkUrl == null -> Text(
                    text = stringResource(R.string.update_check_no_apk),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
                state.isDownloading -> Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.update_check_downloading))
                }
                else -> CardButton(
                    onClick = onDownload,
                    text = stringResource(R.string.update_check_download),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun updateSourceLabel(source: UpdateSource): String = stringResource(
    when (source) {
        UpdateSource.AUTO -> R.string.update_source_auto
        UpdateSource.GITHUB -> R.string.update_source_github
        UpdateSource.GHFAST -> R.string.update_source_ghfast
        UpdateSource.LLKK -> R.string.update_source_llkk
        UpdateSource.MOEYY -> R.string.update_source_moeyy
    }
)
