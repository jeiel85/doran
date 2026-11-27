package com.jeiel85.doran.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 지운 뒤 잠깐 '되돌리기'(BENCHMARK.md 7-4). 장부에서는 바로 지우고(가족 폰에도 묘비가 감), 되돌리면 같은 id로 다시 써서
 * 더 새 시각이 묘비를 이깁니다. 되돌릴 수 없는 뒷정리(영수증 사진 지우기)는 [finalize]로 미뤄 시간이 지난 뒤에만 합니다.
 *
 * 되돌리기 창이 떠 있는 동안 앱이 강제로 닫히면 [finalize]가 돌지 않아 영수증 파일이 남을 수 있습니다(장부에는 영향 없음).
 */
object Undo {
    class Pending(val message: String, val restore: () -> Unit, val finalize: () -> Unit)

    var pending by mutableStateOf<Pending?>(null)
        private set

    const val SHOW_MS = 6_000L

    /** 새로 띄우면 앞의 것은 되돌릴 기회가 끝난 것으로 보고 뒷정리합니다. */
    fun show(message: String, restore: () -> Unit, finalize: () -> Unit = {}) {
        pending?.finalize?.invoke()
        pending = Pending(message, restore, finalize)
    }

    fun undo() { pending?.let { pending = null; it.restore() } }

    fun expire(p: Pending) { if (pending === p) { pending = null; p.finalize() } }
}

/** 화면 아래(하단 막대 위)에 뜨는 되돌리기 띠. 화면 읽기에도 바로 알립니다. */
@Composable
fun UndoBar(modifier: Modifier = Modifier) {
    val p = Undo.pending ?: return
    LaunchedEffect(p) { delay(Undo.SHOW_MS); Undo.expire(p) }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xF0222A26))
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(p.message, style = MaterialTheme.typography.bodyMedium, color = Color.White, modifier = Modifier.weight(1f).padding(vertical = 14.dp))
        TextButton(onClick = { Undo.undo() }) { Text("되돌리기", color = Color(0xFFFFD36B), style = MaterialTheme.typography.labelLarge) }
    }
}
