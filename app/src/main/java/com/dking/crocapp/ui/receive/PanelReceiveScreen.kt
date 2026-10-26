package com.dking.crocapp.ui.receive

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dking.crocapp.R
import com.dking.crocapp.croc.CrocTransferState
import com.dking.crocapp.ui.components.GlassCard
import com.dking.crocapp.ui.components.ModeDot
import com.dking.crocapp.ui.components.QrCodeImage
import com.dking.crocapp.ui.components.TransferProgressCard
import com.dking.crocapp.util.QrCodeParser
import kotlinx.coroutines.delay

/**
 * Panel mode Receive screen: a large QR code (plus the code in text) that a
 * phone scans to send a file here. The view model swaps the code by itself
 * (every 10 min, after each transfer, when the connection drops).
 *
 * Kept deliberately light for slow smart panels: no animation runs while
 * waiting — the crocodile only appears during an actual transfer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelReceiveScreen(
    viewModel: ReceiveViewModel,
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Run the waiting session only while this screen is showing.
    DisposableEffect(Unit) {
        viewModel.startPanelSession()
        onDispose { viewModel.stopPanelSession() }
    }

    // Clock for the "new code in N min" hint (no need for per-second redraws).
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }

    val state = uiState.transferState
    val code = uiState.codePhrase

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.nav_receive), fontWeight = FontWeight.Bold)
                        ModeDot(modifier = Modifier.padding(start = 4.dp))
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(Icons.Outlined.History, contentDescription = stringResource(R.string.nav_history))
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.nav_settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            val wide = maxWidth > 720.dp
            val qrSize = if (wide) min(maxHeight * 0.62f, 380.dp) else min(maxWidth * 0.7f, 320.dp)

            val qrBlock: @Composable () -> Unit = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.panel_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.panel_subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                    )
                    AnimatedContent(
                        targetState = code,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "panelQr"
                    ) { shownCode ->
                        if (shownCode.isNotBlank()) {
                            QrCodeImage(
                                data = QrCodeParser.sendDeepLink(shownCode),
                                size = qrSize,
                                padding = 16.dp,
                                expandable = false,
                                showExpandHint = false
                            )
                        }
                    }
                    Text(
                        text = "${stringResource(R.string.panel_code_label)}: $code",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }

            val statusBlock: @Composable () -> Unit = {
                Column(
                    modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    // Stacked under the QR on phones: centre it like the QR above.
                    horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally
                ) {
                    when (state) {
                        is CrocTransferState.Transferring -> {
                            TransferProgressCard(
                                state = state,
                                isSending = false,
                                onCancel = { viewModel.cancelTransfer() },
                                activeEngine = uiState.activeEngine
                            )
                        }
                        is CrocTransferState.Completed -> {
                            StatusLine(
                                text = stringResource(R.string.panel_received),
                                icon = { Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) }
                            )
                        }
                        is CrocTransferState.Preparing,
                        is CrocTransferState.WaitingForPeer -> {
                            val minsLeft = (((uiState.panelCodeIssuedAt + PANEL_CODE_LIFETIME_MS) - now) / 60_000L + 1)
                                .coerceIn(1, PANEL_CODE_LIFETIME_MS / 60_000L)
                            StatusLine(text = stringResource(R.string.panel_waiting))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    stringResource(R.string.panel_new_code_in, minsLeft.toInt()),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(onClick = { viewModel.refreshPanelCode() }) {
                                    Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
                                    Text(stringResource(R.string.panel_new_code_now), modifier = Modifier.padding(start = 6.dp))
                                }
                            }
                        }
                        is CrocTransferState.LegacyFallbackAvailable -> {
                            StatusLine(text = stringResource(R.string.panel_connecting))
                        }
                        else -> {
                            StatusLine(text = stringResource(R.string.panel_reconnecting))
                        }
                    }

                    if (uiState.receivedFiles.isNotEmpty()) {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    stringResource(R.string.panel_last_received),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                uiState.receivedFiles.take(5).forEach { file ->
                                    ReceivedFileRow(
                                        file = file,
                                        onOpen = { openReceivedFile(context, file) },
                                        onShare = { shareReceivedFile(context, file) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (wide) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    qrBlock()
                    Column(modifier = Modifier.weight(1f, fill = false)) { statusBlock() }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    qrBlock()
                    statusBlock()
                }
            }
        }
    }
}

@Composable
private fun StatusLine(text: String, icon: (@Composable () -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        icon?.invoke()
        Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}
