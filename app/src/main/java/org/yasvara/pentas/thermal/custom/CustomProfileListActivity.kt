/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.thermal.custom

import org.yasvara.pentas.ui.kit.Sym

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yasvara.pentas.thermal.ThermalProfiles
import org.yasvara.pentas.ui.theme.PentasTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Custom thermal profiles: edit, activate, duplicate, delete. */
@AndroidEntryPoint(ComponentActivity::class)
class CustomProfileListActivity : Hilt_CustomProfileListActivity() {

    @Inject lateinit var repository: CustomProfileRepository

    /**
     * Bumped on every resume. The editor is a separate activity, so without
     * this the list still shows the pre-edit snapshot when the user comes back.
     */
    private val refreshTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PentasTheme {
                ListRoot(repository, refreshTick.intValue, onBack = { finish() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick.intValue++
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, CustomProfileListActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/** Array index of the custom profile ThermalController is running, or -1. */
private fun activeArrayIndex(context: Context): Int =
    Settings.System.getIntForUser(
        context.contentResolver, ThermalProfiles.KEY_THERMAL_PROFILE, 0, UserHandle.USER_CURRENT
    ) - CustomProfileContract.CUSTOM_PROFILE_BASE

private fun setActiveArrayIndex(context: Context, arrayIndex: Int) {
    Settings.System.putIntForUser(
        context.contentResolver,
        ThermalProfiles.KEY_THERMAL_PROFILE,
        CustomProfileContract.CUSTOM_PROFILE_BASE + arrayIndex,
        UserHandle.USER_CURRENT,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListRoot(
    repository: CustomProfileRepository,
    refreshTick: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var localRefresh by remember { mutableIntStateOf(0) }
    val revision = refreshTick + localRefresh

    val profiles = remember(revision) { repository.loadAll() }
    val sources = remember(revision) { repository.listImportSources() }
    val activeIndex = remember(revision) { activeArrayIndex(context) }

    var pickerOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmResetAll by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<CustomThermalProfile?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Custom Thermal Profiles",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${profiles.size} saved",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Sym.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (profiles.isNotEmpty()) {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Sym.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Delete all profiles") },
                                leadingIcon = {
                                    Icon(Sym.DeleteSweep, contentDescription = null)
                                },
                                onClick = { menuOpen = false; confirmResetAll = true },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { pickerOpen = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) { Icon(Sym.Add, contentDescription = "Create profile") }
        },
    ) { pad ->
        if (profiles.isEmpty()) {
            EmptyState(Modifier.fillMaxSize().padding(pad))
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Sym.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            Text(
                                "Caution: Custom thermal curves directly alter kernel throttling thresholds. Higher trip points sustain performance but increase device surface temperatures.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 17.sp,
                            )
                        }
                    }
                }
                itemsIndexed(profiles) { index, profile ->
                    ProfileRow(
                        profile = profile,
                        isActive = index == activeIndex,
                        onEdit = {
                            CustomProfileEditorActivity.start(context, profileId = profile.id)
                        },
                        onActivate = {
                            setActiveArrayIndex(context, index)
                            repository.applyToDaemon(profile)
                            localRefresh++
                            scope.launch {
                                snackbarHostState.showSnackbar("\"${profile.name}\" is now active")
                            }
                        },
                        onDuplicate = {
                            CustomProfileEditorActivity.start(context, sourceId = profile.id)
                        },
                        onDelete = { confirmDelete = profile },
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }

        if (pickerOpen) {
            SourcePickerSheet(
                sources = sources,
                blankLabel = "Start blank",
                onBlank = {
                    pickerOpen = false
                    CustomProfileEditorActivity.start(context)
                },
                onPick = { s ->
                    pickerOpen = false
                    CustomProfileEditorActivity.start(context, sourceId = s.id)
                },
                onDismiss = { pickerOpen = false },
            )
        }

        confirmDelete?.let { target ->
            ConfirmDialog(
                title = "Delete \"${target.name.ifBlank { "(unnamed)" }}\"?",
                body = "This profile will be removed permanently.",
                confirmLabel = "Delete",
                onConfirm = {
                    repository.delete(target.id)
                    confirmDelete = null
                    localRefresh++
                },
                onDismiss = { confirmDelete = null },
            )
        }

        if (confirmResetAll) {
            ConfirmDialog(
                title = "Delete all profiles?",
                body = "All ${profiles.size} custom thermal profiles will be removed and " +
                    "thermal control returns to the built-in profiles.",
                confirmLabel = "Delete all",
                onConfirm = {
                    repository.resetAll()
                    Settings.System.putIntForUser(
                        context.contentResolver, ThermalProfiles.KEY_THERMAL_PROFILE, 0,
                        UserHandle.USER_CURRENT,
                    )
                    confirmResetAll = false
                    localRefresh++
                    scope.launch { snackbarHostState.showSnackbar("All profiles deleted") }
                },
                onDismiss = { confirmResetAll = false },
            )
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Sym.Thermostat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "No custom profiles yet",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap + and pick a one-click preset to get started",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProfileRow(
    profile: CustomThermalProfile,
    isActive: Boolean,
    onEdit: () -> Unit,
    onActivate: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        onClick = onEdit,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profile.name.ifBlank { "(unnamed)" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (isActive) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Sym.CheckCircle,
                            contentDescription = "Active",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = profile.summary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onActivate, modifier = Modifier.size(36.dp)) {
                Icon(
                    Sym.Bolt,
                    contentDescription = "Set active",
                    tint = if (isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onDuplicate, modifier = Modifier.size(36.dp)) {
                Icon(
                    Sym.ContentCopy,
                    contentDescription = "Duplicate",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Sym.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** One-line description: what this profile actually does, not just its size. */
private fun CustomThermalProfile.summary(): String {
    if (!hasCurves) return "No throttle levels — unrestricted"
    val onset = (cpu.values.flatten() + gpu).minOfOrNull { it.trigMc }
    val onsetText = onset?.let { " · from ${"%.0f".format(it.mcToC())}°C" }.orEmpty()
    return "$totalCpuLevels CPU · ${gpu.size} GPU levels$onsetText"
}
