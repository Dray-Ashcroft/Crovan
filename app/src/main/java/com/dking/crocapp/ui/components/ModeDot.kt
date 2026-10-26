package com.dking.crocapp.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dking.crocapp.R
import com.dking.crocapp.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 🟢 Meko (phone: Send + Receive) */
val MekoGreen = Color(0xFF2EA65A)

/** 🟣 Rimas (panel: Receive only, waits with a QR) */
val RimasViolet = Color(0xFF8B5CF6)

/**
 * The small mode dot shown beside each main screen's title.
 *
 * Hold it (~1 s, the system long-press) to switch Meko ⇄ Rimas; a short
 * callout says who was summoned. A single tap does nothing on purpose, so a
 * student brushing the smart panel can't switch it by accident.
 *
 * It reads and writes the saved mode directly; the app reacts to the change
 * (Rimas hides the bottom bar and goes to the QR screen).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModeDot(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val repo = remember { UserPreferencesRepository(context) }
    val panelModeFlow = remember { repo.preferencesFlow.map { it.panelMode } }
    val panelMode by panelModeFlow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    val isRimas = panelMode ?: return // not loaded yet: draw nothing
    val color = if (isRimas) RimasViolet else MekoGreen
    val name = stringResource(if (isRimas) R.string.mode_rimas else R.string.mode_meko)
    val a11y = stringResource(R.string.mode_dot_a11y, name)

    // Remember the last summoned mode so the label keeps its colour while fading out.
    var lastRimas by remember { mutableStateOf(false) }
    ModeCallout.summoned?.let { lastRimas = it }
    val labelColor = if (lastRimas) RimasViolet else MekoGreen

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(28.dp) // comfortable touch target around a small dot
                .clip(CircleShape)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* intentionally nothing */ },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val next = !isRimas
                        scope.launch { repo.updatePanelMode(next) }
                        ModeCallout.show(next)
                    }
                )
                .semantics { contentDescription = a11y },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }

        // Quiet inline callout: slides out beside the dot, then fades away.
        AnimatedVisibility(
            visible = ModeCallout.summoned != null,
            enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
            exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start)
        ) {
            Text(
                text = stringResource(
                    if (lastRimas) R.string.mode_summoned_rimas else R.string.mode_summoned_meko
                ),
                color = labelColor,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

/**
 * App-wide "… is summoned" callout. Lives outside the screens because
 * switching mode changes the screen (and the dot that was pressed).
 */
object ModeCallout {
    /** true = Rimas summoned, false = Meko summoned, null = hidden */
    var summoned by mutableStateOf<Boolean?>(null)
        private set
    var key by mutableIntStateOf(0)
        private set

    fun show(rimas: Boolean) {
        summoned = rimas
        key++
    }

    fun hide() {
        summoned = null
    }
}

/**
 * Runs the callout timer. The label itself is drawn by [ModeDot] (beside the
 * title), so the new screen's dot shows it even after the mode switch
 * changed screens. Place once in the app.
 */
@Composable
fun ModeCalloutHost() {
    val key = ModeCallout.key
    LaunchedEffect(key) {
        if (key > 0) {
            delay(1_500)
            ModeCallout.hide()
        }
    }
}
