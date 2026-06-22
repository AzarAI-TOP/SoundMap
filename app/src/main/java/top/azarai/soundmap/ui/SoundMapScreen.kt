package top.azarai.soundmap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.azarai.soundmap.SoundMapViewModel
import top.azarai.soundmap.audio.EngineState
import top.azarai.soundmap.ui.theme.OnSurface
import top.azarai.soundmap.ui.theme.OnSurfaceMuted
import androidx.compose.runtime.collectAsState
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
            Text(
                text = "SoundMap",
                style = MaterialTheme.typography.headlineMedium,
                color = OnSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "佩戴耳机时使用 · 点击圆盘选择声音的方位",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(36.dp))

            ClockDial(
                enabled = state.enabled,
                position = state.position,
                onToggle = viewModel::toggle,
                onPositionChange = viewModel::onPositionChange,
            )

            Spacer(Modifier.height(32.dp))

            StatusReadout(state)
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
            color = if (state.enabled) OnSurface else OnSurfaceMuted,
        )
        Spacer(Modifier.height(8.dp))
        val status = state.status
        when {
            !state.enabled -> Text(
                text = "效果已关闭",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted,
            )
            status != null && !status.anyEffectAttached -> Text(
                text = "⚠ 当前没有任何音频会话被挂上效果。请先开始播放音乐，" +
                    "再确认设备是否允许应用音效（部分定制系统会限制）。",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted,
                textAlign = TextAlign.Center,
            )
            else -> {
                Text(
                    text = "效果已开启 · 正作用于 ${status?.attachedSessions ?: 0} 个播放会话",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceMuted,
                    textAlign = TextAlign.Center,
                )
                if (status != null && !status.dumpGranted) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "提示：未授予 DUMP 权限，可能只覆盖部分应用。一次性开启全覆盖：\n" +
                            "adb shell pm grant top.azarai.soundmap android.permission.DUMP",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
