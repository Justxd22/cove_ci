package org.bitcoinppl.cove.flows.SettingsFlow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.bitcoinppl.cove.R
import org.bitcoinppl.cove.views.MaterialDivider
import org.bitcoinppl.cove.views.MaterialSection
import org.bitcoinppl.cove.views.MaterialSettingsItem
import org.bitcoinppl.cove.views.SectionHeader
import org.bitcoinppl.cove_core.AppAction
import org.bitcoinppl.cove_core.Database
import org.bitcoinppl.cove_core.GlobalConfigKey
import org.bitcoinppl.cove_core.GlobalFlagKey
import org.bitcoinppl.cove_core.types.Network
import org.bitcoinppl.cove_core.types.allNetworks

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkSettingsScreen(
    app: org.bitcoinppl.cove.AppManager,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val networks = remember { allNetworks() }
    val selectedNetwork = app.selectedNetwork
    var pendingNetworkChange by remember { mutableStateOf<Network?>(null) }

    val database = remember { Database() }
    val globalConfig = remember { database.globalConfig() }
    val globalFlag = remember { database.globalFlag() }
    val torSettingsDiscovered = remember {
        globalFlag.getBoolConfig(GlobalFlagKey.TOR_SETTINGS_DISCOVERED)
    }

    val persistedUseTor = remember { globalConfig.useTor() }
    val persistedTorMode = remember { parseTorMode(globalConfig.get(GlobalConfigKey.TorMode)) }
    val persistedExternalHost = remember {
        globalConfig.get(GlobalConfigKey.TorExternalHost)
            ?.takeIf { it.isNotBlank() }
            ?: "127.0.0.1"
    }
    val persistedExternalPort = remember { globalConfig.torExternalPort().toString() }

    var uiState by
        remember {
            mutableStateOf(
                TorUiState(
                    enabled = persistedUseTor,
                    mode = persistedTorMode,
                    externalHost = persistedExternalHost,
                    externalPort = persistedExternalPort,
                    externalValidationError = validateExternalConfig(persistedExternalHost, persistedExternalPort),
                ),
            )
        }
    var showFullLogDialog by remember { mutableStateOf(false) }
    var showModeDialog by remember { mutableStateOf(false) }

    val statusDisabledText = stringResource(R.string.tor_status_disabled)
    val logDisabledText = stringResource(R.string.tor_log_disabled)
    val stepExternalReadyText = stringResource(R.string.tor_step_external_ready)
    val logExternalText = stringResource(R.string.tor_log_external)
    val stepBootstrapText = stringResource(R.string.tor_step_bootstrap)
    val stepDirectoryText = stringResource(R.string.tor_step_directory)
    val stepCircuitText = stringResource(R.string.tor_step_circuit)
    val stepProxyText = stringResource(R.string.tor_step_proxy)
    val stepReadyText = stringResource(R.string.tor_step_ready)

    LaunchedEffect(Unit) {
        val (status, version) = OrbotPackageHelper.detect(context)
        uiState = uiState.copy(orbotStatus = status, orbotVersion = version)
    }

    LaunchedEffect(uiState.enabled, uiState.mode) {
        if (!uiState.enabled) {
            uiState =
                uiState.copy(
                    status = TorStatus.Disabled,
                    progressPercent = 0,
                    currentStep = statusDisabledText,
                    latestLogLine = logDisabledText,
                    logLines = listOf(logDisabledText),
                )
            return@LaunchedEffect
        }

        if (uiState.mode == TorMode.External || (uiState.mode == TorMode.Orbot && uiState.orbotStatus == OrbotStatus.Detected)) {
            uiState =
                uiState.copy(
                    status = TorStatus.Ready,
                    progressPercent = 100,
                    currentStep = stepExternalReadyText,
                    latestLogLine = logExternalText,
                    logLines =
                        listOf(
                            "External Tor mode active",
                            "Configuration: ${if (uiState.mode == TorMode.Orbot) "Orbot" else "${uiState.externalHost}:${uiState.externalPort}"}",
                        ),
                )
            return@LaunchedEffect
        }

        val timeline =
            listOf(
                10 to stepBootstrapText,
                35 to stepDirectoryText,
                65 to stepCircuitText,
                90 to stepProxyText,
                100 to stepReadyText,
            )

        val logs = mutableListOf("Tor enabled")
        uiState = uiState.copy(status = TorStatus.Bootstrapping, logLines = logs.toList())

        for ((pct, step) in timeline) {
            delay(550)
            logs.add("$step ($pct%)")
            uiState =
                uiState.copy(
                    status = if (pct >= 100) TorStatus.Ready else TorStatus.Bootstrapping,
                    progressPercent = pct,
                    currentStep = step,
                    latestLogLine = logs.last(),
                    logLines = logs.toList(),
                )
        }
    }

    Scaffold(
        modifier =
            modifier
                .fillMaxSize()
                .padding(WindowInsets.safeDrawing.asPaddingValues()),
        topBar = @Composable {
            TopAppBar(
                title = {
                    Text(
                        style = MaterialTheme.typography.bodyLarge,
                        text = stringResource(R.string.title_settings_network),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { app.popRoute() }) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { },
            )
        },
        content = { paddingValues ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(paddingValues),
            ) {
                SectionHeader(stringResource(R.string.title_settings_network), showDivider = false)
                MaterialSection {
                    Column {
                        networks.forEachIndexed { index, network ->
                            NetworkRow(
                                network = network,
                                isSelected = network == selectedNetwork,
                                onClick = {
                                    pendingNetworkChange = network
                                },
                            )

                            if (index < networks.size - 1) {
                                MaterialDivider()
                            }
                        }
                    }
                }

                if (torSettingsDiscovered) {
                    SectionHeader(stringResource(R.string.tor_section_privacy), showDivider = false)
                    MaterialSection {
                        Column {
                            MaterialSettingsItem(
                                title = stringResource(R.string.tor_use_tor_title),
                                subtitle = stringResource(R.string.tor_use_tor_subtitle),
                                icon = Icons.Default.Lock,
                                isSwitch = true,
                                switchCheckedState = uiState.enabled,
                                onCheckChanged = { enabled ->
                                    globalConfig.setUseTor(enabled)
                                    uiState = uiState.copy(enabled = enabled)
                                },
                            )
                            MaterialDivider()
                            val modeEnabled = uiState.enabled
                            val modeSubtitle = when (uiState.mode) {
                                TorMode.BuiltIn -> stringResource(R.string.tor_mode_builtin)
                                TorMode.Orbot -> stringResource(R.string.tor_mode_orbot)
                                TorMode.External -> stringResource(R.string.tor_mode_external)
                            }
                            MaterialSettingsItem(
                                title = stringResource(R.string.tor_mode_title),
                                subtitle = modeSubtitle,
                                icon = Icons.Default.Public,
                                modifier = Modifier.alpha(if (modeEnabled) 1f else 0.5f),
                                onClick = if (modeEnabled) { { showModeDialog = true } } else null,
                            )
                            MaterialDivider()
                            val statusLabel = when (uiState.status) {
                                TorStatus.Disabled -> stringResource(R.string.tor_status_disabled)
                                TorStatus.Bootstrapping -> stringResource(R.string.tor_status_bootstrapping)
                                TorStatus.Ready -> stringResource(R.string.tor_status_ready)
                                TorStatus.Error -> stringResource(R.string.tor_status_error)
                            }
                            MaterialSettingsItem(
                                title = stringResource(R.string.tor_status_title),
                                subtitle = statusLabel,
                                leadingContent = {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp),
                                    )
                                },
                                modifier = Modifier.alpha(if (modeEnabled) 1f else 0.5f),
                                trailingContent = {
                                    if (uiState.enabled && uiState.status == TorStatus.Ready) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }

                if (torSettingsDiscovered && uiState.enabled) {
                    if (uiState.mode == TorMode.BuiltIn) {
                        SectionHeader(stringResource(R.string.tor_section_bootstrap), showDivider = false)
                        MaterialSection {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(R.string.tor_progress_title),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        text = "${uiState.progressPercent}%",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { uiState.progressPercent / 100f },
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                )
                                Text(
                                    text = "${stringResource(R.string.tor_current_step_prefix)} ${uiState.currentStep}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                                Text(
                                    text = "${stringResource(R.string.tor_latest_log_prefix)} ${uiState.latestLogLine}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }

                        MaterialSection {
                            Column {
                                MaterialSettingsItem(
                                    title = stringResource(R.string.tor_view_full_log_title),
                                    subtitle = stringResource(R.string.tor_view_full_log_subtitle),
                                    icon = Icons.Default.Terminal,
                                    onClick = { showFullLogDialog = true },
                                )
                            }
                        }
                    }

                    if (uiState.mode == TorMode.External) {
                        SectionHeader(stringResource(R.string.tor_section_external), showDivider = false)
                        MaterialSection {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(
                                    text = stringResource(R.string.tor_external_config_notice),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 12.dp),
                                )
                                OutlinedTextField(
                                    value = uiState.externalHost,
                                    onValueChange = { value ->
                                        globalConfig.set(GlobalConfigKey.TorExternalHost, value)
                                        uiState =
                                            uiState.copy(
                                                externalHost = value,
                                                externalValidationError = validateExternalConfig(value, uiState.externalPort),
                                            )
                                    },
                                    label = { Text(stringResource(R.string.tor_external_host_label)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = uiState.externalPort,
                                    onValueChange = { value ->
                                        val validationError = validateExternalConfig(uiState.externalHost, value)
                                        if (validationError == null) {
                                            value.toUShortOrNull()?.let { globalConfig.setTorExternalPort(it) }
                                        }

                                        uiState =
                                            uiState.copy(
                                                externalPort = value,
                                                externalValidationError = validationError,
                                            )
                                    },
                                    label = { Text(stringResource(R.string.tor_external_port_label)) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    isError = uiState.externalValidationError != null,
                                    supportingText = {
                                        if (uiState.externalValidationError != null) {
                                            Text(uiState.externalValidationError!!)
                                        }
                                    },
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                )
                            }
                        }
                    }

                    if (uiState.mode == TorMode.Orbot) {
                        SectionHeader(stringResource(R.string.tor_orbot_status_title), showDivider = false)
                        MaterialSection {
                            Column {
                                val orbotTitle = when (uiState.orbotStatus) {
                                    OrbotStatus.Checking -> stringResource(R.string.tor_orbot_checking)
                                    OrbotStatus.Detected -> {
                                        val version = uiState.orbotVersion
                                        if (version.isNullOrBlank()) {
                                            stringResource(R.string.tor_orbot_detected)
                                        } else {
                                            stringResource(R.string.tor_orbot_detected_version, version)
                                        }
                                    }
                                    OrbotStatus.NotDetected -> stringResource(R.string.tor_orbot_not_detected)
                                }
                                val orbotSubtitle = if (uiState.orbotStatus == OrbotStatus.Detected) {
                                    stringResource(R.string.tor_open_orbot_subtitle)
                                } else {
                                    stringResource(R.string.tor_install_orbot_subtitle)
                                }
                                MaterialSettingsItem(
                                    title = orbotTitle,
                                    subtitle = orbotSubtitle,
                                    icon = Icons.Default.Settings,
                                    onClick = {
                                        if (uiState.orbotStatus == OrbotStatus.Detected) {
                                            OrbotPackageHelper.openOrbot(context)
                                        } else {
                                            OrbotPackageHelper.openInstallPage(context)
                                        }
                                    },
                                )
                                MaterialDivider()
                                MaterialSettingsItem(
                                    title = stringResource(R.string.tor_detect_orbot_title),
                                    subtitle = stringResource(R.string.tor_detect_orbot_subtitle),
                                    icon = Icons.Default.Refresh,
                                    onClick = {
                                        val (status, version) = OrbotPackageHelper.detect(context)
                                        uiState = uiState.copy(orbotStatus = status, orbotVersion = version)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
    )

    if (showModeDialog) {
        AlertDialog(
            onDismissRequest = { showModeDialog = false },
            title = { Text(stringResource(R.string.tor_mode_title)) },
            text = {
                Column {
                    TorModeOption(
                        title = stringResource(R.string.tor_mode_builtin),
                        selected = uiState.mode == TorMode.BuiltIn,
                        onClick = {
                            globalConfig.set(GlobalConfigKey.TorMode, org.bitcoinppl.cove_core.TorMode.BUILT_IN.name)
                            uiState = uiState.copy(mode = TorMode.BuiltIn)
                            showModeDialog = false
                        },
                    )
                    TorModeOption(
                        title = stringResource(R.string.tor_mode_orbot),
                        selected = uiState.mode == TorMode.Orbot,
                        onClick = {
                            globalConfig.set(GlobalConfigKey.TorMode, org.bitcoinppl.cove_core.TorMode.ORBOT.name)
                            uiState = uiState.copy(mode = TorMode.Orbot)
                            showModeDialog = false
                        },
                    )
                    TorModeOption(
                        title = stringResource(R.string.tor_mode_external),
                        selected = uiState.mode == TorMode.External,
                        onClick = {
                            globalConfig.set(GlobalConfigKey.TorMode, org.bitcoinppl.cove_core.TorMode.EXTERNAL.name)
                            uiState = uiState.copy(mode = TorMode.External)
                            showModeDialog = false
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showModeDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }

    pendingNetworkChange?.let { network ->
        AlertDialog(
            onDismissRequest = { pendingNetworkChange = null },
            title = { Text("Warning: Network Changed") },
            text = { Text("You've changed your network to ${network.toString()}") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingNetworkChange = null
                        app.dispatch(AppAction.ChangeNetwork(network))
                        app.rust.selectLatestOrNewWallet()
                        app.popRoute()
                    },
                ) {
                    Text("Yes, Change Network")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingNetworkChange = null },
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showFullLogDialog) {
        AlertDialog(
            onDismissRequest = { showFullLogDialog = false },
            title = { Text(stringResource(R.string.tor_full_log_title)) },
            text = {
                Column(
                    modifier =
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                            .padding(8.dp),
                ) {
                    uiState.logLines.forEach { line ->
                        Text(
                            text = "> $line",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFullLogDialog = false }) {
                    Text(stringResource(R.string.btn_done))
                }
            },
        )
    }
}

@Composable
private fun TorModeOption(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

private fun parseTorMode(mode: String?): TorMode {
    return when (mode) {
        org.bitcoinppl.cove_core.TorMode.ORBOT.name -> TorMode.Orbot
        org.bitcoinppl.cove_core.TorMode.EXTERNAL.name -> TorMode.External
        else -> TorMode.BuiltIn
    }
}

private fun validateExternalConfig(host: String, port: String): String? {
    if (host.isBlank()) return "Host is required"
    val parsed = port.toIntOrNull() ?: return "Port must be a number"
    if (parsed !in 1..65535) return "Port must be between 1 and 65535"
    return null
}

@Composable
private fun NetworkRow(
    network: Network,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = network.toString(),
            style = MaterialTheme.typography.bodyLarge,
        )

        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
