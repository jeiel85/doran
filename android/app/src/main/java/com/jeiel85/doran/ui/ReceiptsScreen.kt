package com.jeiel85.doran.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeiel85.doran.R
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.feature.Receipts
import com.jeiel85.doran.model.Txn
import kotlinx.coroutines.launch

/**
 * 영수증 사진 정리(하드닝 #1): 사진이 붙은 내역을 오래된 것부터 보여 주고, 사진만 지웁니다(내역은 그대로).
 * 지운 사진은 이 폰과 중계 서버에서 함께 지워져 가구 사진 용량(450MB)이 줄어듭니다.
 */
@Composable
fun ReceiptsScreen(nav: Nav) {
    val context = LocalContext.current
    val s by Hub.ledger.state.collectAsStateWithLifecycle()
    val withPhotos = remember(s.version) { s.txns.values.filter { it.receiptIds.isNotEmpty() }.sortedBy { it.at } }
    val photos = withPhotos.sumOf { it.receiptIds.size }
    val cached = remember(s.version) { withPhotos.flatMap { it.receiptIds }.sumOf { Receipts.file(context, it).length() } }
    val yearAgo = System.currentTimeMillis() - 365L * 86_400_000
    val old = withPhotos.filter { it.at < yearAgo }
    var confirm by remember { mutableStateOf<List<Txn>?>(null) }

    fun clear(list: List<Txn>) {
        val ids = list.flatMap { it.receiptIds }
        Hub.ledger.putAll(list.map { Triple(com.jeiel85.doran.model.T.TXN, it.id, it.copy(receiptIds = emptyList()).json()) })
        Hub.scope.launch { ids.forEach { Receipts.delete(context, it) } } // 화면을 나가도 끝까지(하드닝 #8)
    }

    SubScreen("영수증 사진 정리", { nav.back() }) {
        item {
            SoftCard(padding = PaddingValues(16.dp)) {
                Text("사진 ${photos}장 · 이 폰에 ${"%.1f".format(cached / 1_048_576.0)}MB", style = MaterialTheme.typography.titleMedium, color = P.c.ink)
                Text("사진만 지우고 내역은 그대로 둬요. 지운 사진은 가족 폰과 서버에서도 사라져요.",
                    style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                if (old.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    SmallButton("1년 지난 사진 ${old.sumOf { it.receiptIds.size }}장 지우기", color = P.c.danger) { confirm = old }
                }
            }
        }
        if (withPhotos.isEmpty()) item {
            EmptyState(R.drawable.art_capture, "영수증 사진이 없어요", "내역을 쓸 때 사진을 붙이면 여기에서 정리할 수 있어요")
        }
        items(withPhotos, key = { it.id }) { t ->
            Spacer(Modifier.height(8.dp))
            ListRow(t.merchant.ifBlank { s.category(t.categoryId)?.name ?: "내역" },
                "${Fmt.full(t.at)} · ${Fmt.won(t.amount)} · 사진 ${t.receiptIds.size}장",
                Icons.Rounded.Photo, P.c.apricot, onClick = { nav.go(Route.Entry(txnId = t.id)) }) {
                SmallButton("사진 지우기", color = P.c.danger) { confirm = listOf(t) }
            }
        }
    }
    confirm?.let { list ->
        AlertDialog(onDismissRequest = { confirm = null }, containerColor = P.c.bg,
            title = { Text("사진 ${list.sumOf { it.receiptIds.size }}장을 지울까요?") },
            text = { Text("되돌릴 수 없어요. 내역은 그대로 남아요.") },
            confirmButton = { TextButton({ clear(list); confirm = null }) { Text("지우기", color = P.c.danger) } },
            dismissButton = { TextButton({ confirm = null }) { Text("취소") } })
    }
}
