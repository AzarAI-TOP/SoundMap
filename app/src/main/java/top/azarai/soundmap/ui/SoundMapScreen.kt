package top.azarai.soundmap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.azarai.soundmap.R
import top.azarai.soundmap.SoundMapViewModel
import top.azarai.soundmap.audio.DemoSourceId
import top.azarai.soundmap.audio.EngineState
import top.azarai.soundmap.ui.theme.OnSurface
import top.azarai.soundmap.ui.theme.OnSurfaceMuted
import kotlin.math.roundToInt

@Composable
fun SoundMapScreen(viewModel: SoundMapViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Text("SoundMap", style = MaterialTheme.typography.headlineMedium, color = OnSurface)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "佩戴耳机 · 点中心播放演示音 · 拖动圆盘移动声音的方位与距离",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(36.dp))

            ClockDial(
                enabled = state.playing,
                position = state.position,
                onToggle = viewModel::toggle,
                onPositionChange = viewModel::onPositionChange,
            )

            Spacer(Modifier.height(32.dp))

            StatusReadout(state)

            Spacer(Modifier.height(24.dp))

            SourceDropdown(state.sourceId, viewModel::onSourceChange)
        }
    }
}

@Composable
private fun StatusReadout(state: EngineState) {
    val angle = state.position.angleDeg.roundToInt() % 360
    val distance = (state.position.radius * 100f).roundToInt()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "方向 $angle°   ·   距离 $distance%",
            style = MaterialTheme.typography.labelLarge,
            color = if (state.playing) OnSurface else OnSurfaceMuted,
        )
        Spacer(Modifier.height(8.dp))
        val text = when {
            state.error != null -> "⚠ ${state.error}"
            state.playing -> "演示音播放中"
            else -> "已停止 · 点中心开始播放"
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted,
            textAlign = TextAlign.Center,
        )
    }
}

private fun DemoSourceId.labelRes(): Int = when (this) {
    DemoSourceId.BEEP -> R.string.source_beep
    DemoSourceId.NOISE -> R.string.source_noise
    DemoSourceId.CLICK -> R.string.source_click
}

@Composable
private fun SourceDropdown(selected: DemoSourceId, onSelect: (DemoSourceId) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.source_label) + "：" + stringResource(selected.labelRes()))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DemoSourceId.entries.forEach { id ->
                DropdownMenuItem(
                    text = { Text(stringResource(id.labelRes())) },
                    onClick = { onSelect(id); expanded = false },
                )
            }
        }
    }
}
