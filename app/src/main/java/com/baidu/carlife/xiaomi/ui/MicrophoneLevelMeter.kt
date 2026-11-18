package com.baidu.carlife.xiaomi.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baidu.carlife.xiaomi.carplay.CarPlayMicrophone
import com.baidu.carlife.xiaomi.carplay.MicrophoneSample
import kotlinx.coroutines.delay
import kotlin.math.log10
import kotlin.math.roundToInt

@Composable
internal fun liveMicrophoneSample(stream: CarPlayMicrophone?): MicrophoneSample? {
    if (stream == null) return null
    val sample by stream.samples.collectAsStateWithLifecycle()
    val reading = sample ?: return null
    val remaining = 500L - (SystemClock.elapsedRealtime() - reading.timeMs)
    var recent by remember(reading) { mutableStateOf(remaining > 0L) }
    LaunchedEffect(reading) {
        if (remaining > 0L) delay(remaining)
        recent = false
    }
    return reading.takeIf { recent }
}

@Composable
internal fun MicrophoneLevelMeter(level: Float, modifier: Modifier = Modifier) {
    val decibels = level.takeIf { it > 0f }?.let { 20f * log10(it) }
    val progress = (((decibels ?: -60f) + 60f) / 60f).coerceIn(0f, 1f)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f))
        Text(
            text = decibels?.let { "${it.roundToInt()} dBFS" } ?: "−∞ dBFS",
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
