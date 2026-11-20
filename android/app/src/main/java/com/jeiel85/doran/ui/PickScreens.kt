package com.jeiel85.doran.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeiel85.doran.R
import com.jeiel85.doran.capture.Parsed
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.data.Db
import com.jeiel85.doran.model.Txn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 최근 받은 알림: 둥지가 기록한 것과, 금액은 있는데 형식을 몰라 기록하지 못한 것(후보).
 * 후보를 골라 보내고, "앞으로도 자동으로"를 켜면 같은 발신처는 다음부터 저절로 보냅니다.
 */
@Composable
fun CaptureLogScreen(nav: Nav) {
    var tick by remember { mutableIntStateOf(0) }
    val rows = remember(tick) { Hub.db.captures(300) }
    val candidates = rows.filter { it.isCandidate }
    var tab by remember { mutableIntStateOf(if (candidates.isNotEmpty()) 0 else 1) }
    val shown = if (tab == 0) candidates else rows.filter { it.status != "dismissed" }
    val counts = remember(tick) { Hub.db.captureCounts() }
    val failures by produceState(emptyList<Pair<Db.CaptureRow, Txn>>(), tick) {
        value = withContext(Dispatchers.IO) { Hub.capture.recordedFailures() }
    }
    val resumed = rememberResumeTick()
    androidx.compose.runtime.LaunchedEffect(resumed) { if (resumed > 0) tick++ }

    SubScreen("최근 받은 알림", { nav.back() }, actions = {
        IconButton(onClick = { Hub.db.clearCaptures(); tick++ }) { Icon(Icons.Rounded.DeleteSweep, "모두 지우기", tint = P.c.ink) }
    }) {
        item {
            Segmented(listOf("기록 안 된 것 ${candidates.size}", "모두"), tab) { tab = it }
            Spacer(Modifier.height(10.dp))
            Text(if (tab == 0) "금액이 적혀 있는데 둥지가 형식을 몰라 기록하지 못한 알림이에요. 골라서 보낼 수 있어요."
            else "원문은 이 폰에만 30일 남아요.", style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
            // 묶기·실패 기준이 실제 알림에서 어떻게 도는지 보이게(#13·#19)
            if (tab == 1) Text("최근 30일 · 기록 ${counts.recorded} · 같은 결제로 묶음 ${counts.joined} · 실패·예정이라 뺀 것 ${counts.failed} · 기록 안 된 것 ${counts.candidates}",
                style = MaterialTheme.typography.bodySmall, color = P.c.inkFaint, modifier = Modifier.padding(top = 4.dp))
        }
        if (failures.isNotEmpty()) item { RecordedFailuresCard(failures) { tick++ } }
        if (shown.isEmpty()) item {
            EmptyState(R.drawable.art_capture, if (tab == 0) "기록 안 된 금액 알림이 없어요" else "아직 읽은 알림이 없어요",
                if (tab == 0) "카드·은행 알림이 모두 잘 기록되고 있어요" else "카드 결제 알림이 오면 여기에 원문과 결과가 남아요")
        }
        items(shown, key = { it.id }) { r ->
            Spacer(Modifier.height(10.dp))
            SoftCard(onClick = r.txnId?.let { t -> { nav.go(Route.Entry(txnId = t)) } }, padding = PaddingValues(16.dp),
                color = if (r.isCandidate) P.c.apricotSoft else P.c.surface) {
                Text("${Fmt.full(r.at)} · ${r.label ?: if (r.source == "sms") "문자" else r.pkg ?: "앱"}",
                    style = MaterialTheme.typography.bodySmall, color = P.c.inkFaint)
                Spacer(Modifier.height(4.dp))
                Text(listOfNotNull(r.title, r.text).joinToString("\n"), style = MaterialTheme.typography.bodyMedium, color = P.c.ink)
                Spacer(Modifier.height(8.dp))
                if (r.isCandidate) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton("골라서 보내기") { nav.go(Route.SendPicked(r.id)) }
                        SmallButton("숨기기", color = P.c.inkSoft) { Hub.db.updateCapture(r.id, "dismissed", "숨김", null); tick++ }
                    }
                } else {
                    Text(r.result, style = MaterialTheme.typography.labelMedium, color = if (r.isFailed) P.c.inkSoft else P.c.pine)
                    // 실패·예정 기준에 걸렸지만 실제로 돈이 나간 알림(오탐)이면 직접 입력하지 않고 바로 보낼 수 있게(#13)
                    if (r.isFailed) {
                        Spacer(Modifier.height(8.dp))
                        SmallButton("그래도 보내기", color = P.c.inkSoft) { nav.go(Route.SendPicked(r.id)) }
                    }
                }
            }
        }
    }
}

/**
 * 예전 버전이 내역으로 기록한 결제·출금 실패 알림(0.1.11 전, #13)과 출금 예정 안내(0.2.3 전)를 한 번 묻습니다:
 * 모두 지우기(가족 폰에서도) 또는 그대로 두기.
 */
@Composable
private fun RecordedFailuresCard(items: List<Pair<Db.CaptureRow, Txn>>, changed: () -> Unit) {
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    Spacer(Modifier.height(10.dp))
    SoftCard(color = P.c.apricotSoft, padding = PaddingValues(16.dp)) {
        Text("실패·예정 알림이 내역으로 기록돼 있어요 ${items.size}건", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
        Spacer(Modifier.height(4.dp))
        Text("예전 버전은 잔액 부족으로 실패한 결제나 \"출금할 예정이에요\" 같은 예정 안내도 내역으로 기록했어요. 돈이 나가지 않았다면 지워 주세요.",
            style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
        Spacer(Modifier.height(8.dp))
        items.forEach { (_, t) ->
            Text("· ${Fmt.full(t.at)} · ${t.merchant.ifBlank { t.issuer ?: "결제" }} · ${Fmt.won(t.amount)}",
                style = MaterialTheme.typography.bodyMedium, color = P.c.ink)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("모두 지우기", enabled = !busy, color = P.c.danger) { confirm = true }
            SmallButton("그대로 두기", enabled = !busy, color = P.c.inkSoft) {
                busy = true
                scope.launch { withContext(Dispatchers.IO) { Hub.capture.keepRecordedFailures(items) }; busy = false; changed() }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, containerColor = P.c.bg,
        title = { Text("실패·예정 알림 내역 ${items.size}건 지우기") },
        text = { Text("가계부와 가족 폰에서도 지워지고 되돌릴 수 없어요.") },
        confirmButton = {
            TextButton({
                confirm = false; busy = true
                scope.launch { withContext(Dispatchers.IO) { Hub.capture.deleteRecordedFailures(items) }; busy = false; changed() }
            }) { Text("지우기", color = P.c.danger) }
        },
        dismissButton = { TextButton({ confirm = false }) { Text("두기") } })
}

/** 고른 알림을 확인·고쳐서 보내기. */
@Composable
fun SendPickedScreen(logId: Long, nav: Nav) {
    val row = remember(logId) { Hub.db.capture(logId) }
    val id by Hub.identity.state.collectAsStateWithLifecycle()
    val household by Hub.ledger.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    if (row == null) {
        SubScreen("알림 보내기", { nav.back() }) { item { Text("지워진 알림이에요", color = P.c.inkSoft) } }
        return
    }
    val preview = remember(logId) { Hub.capture.previewPicked(row) }
    var issuer by remember { mutableStateOf(preview?.issuer ?: row.label.orEmpty()) }
    var type by remember { mutableStateOf(preview?.type ?: Parsed.Type.APPROVAL) }
    var amount by remember { mutableStateOf(preview?.amount?.takeIf { it > 0 }?.toString() ?: "") }
    var merchant by remember { mutableStateOf(preview?.merchant.orEmpty()) }
    var isCard by remember { mutableStateOf(preview?.isCard ?: true) }
    // 실패·예정으로 뺀 알림을 보내는 것은 이번 한 건만: 그런 알림은 규칙이 있어도 기록하지 않으므로 "앞으로도"가 뜻이 없습니다.
    var remember by remember { mutableStateOf(!row.isFailed) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val value = amount.toLongOrNull() ?: 0L
    val target = buildList {
        if (id.inHousehold) add("'${household.household.name}' 가계부에 기록하고 가족에게 알려요")
        id.outLinks.forEach { add("'${it.label}'에 ${it.memberName?.let { n -> "'$n' 이름으로 " } ?: ""}보내요") }
    }

    SubScreen("알림 골라 보내기", { nav.back() }, bottom = {
        PrimaryButton(if (busy) "보내는 중…" else "보내기", enabled = !busy && value > 0 && issuer.isNotBlank() && target.isNotEmpty()) {
            busy = true; error = null
            val parsed = Parsed(type = type, issuer = issuer.trim(), isCard = isCard, amount = value,
                merchant = merchant.trim().ifEmpty { null }, at = preview?.at, installment = preview?.installment ?: 0,
                cumulative = preview?.cumulative, balance = preview?.balance, foreign = preview?.foreign, last4 = preview?.last4)
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { Hub.capture.sendPicked(row, parsed, remember) } }
                    .onSuccess { nav.back() }
                    .onFailure { error = it.message ?: "보내지 못했어요"; busy = false }
            }
        }
    }) {
        item {
            SoftCard(color = P.c.surface2, padding = PaddingValues(16.dp)) {
                Text("${Fmt.full(row.at)} · ${row.label ?: row.pkg ?: "알림"}", style = MaterialTheme.typography.bodySmall, color = P.c.inkFaint)
                Spacer(Modifier.height(4.dp))
                Text(listOfNotNull(row.title, row.text).joinToString("\n"), style = MaterialTheme.typography.bodyMedium, color = P.c.ink)
            }
            if (row.isFailed) {
                Spacer(Modifier.height(10.dp))
                Text("결제·출금 실패나 예정 안내로 보고 기록하지 않았던 알림이에요. 실제로 돈이 나갔을 때만 보내 주세요.",
                    style = MaterialTheme.typography.bodySmall, color = P.c.apricot)
            }
            Spacer(Modifier.height(18.dp))
            Text("어떤 거래인가요?", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
            Spacer(Modifier.height(8.dp))
            ChipRow {
                listOf(Parsed.Type.APPROVAL to "카드 결제", Parsed.Type.CANCEL to "결제 취소", Parsed.Type.WITHDRAWAL to "출금", Parsed.Type.DEPOSIT to "입금")
                    .forEach { (t, l) -> Chip(l, type == t, { type = t; isCard = t == Parsed.Type.APPROVAL || t == Parsed.Type.CANCEL }) }
            }
            Spacer(Modifier.height(14.dp))
            Field(if (isCard) "카드사 이름" else "은행 이름", issuer, { issuer = it.take(20) }, "예: 현대카드, 토스")
            Spacer(Modifier.height(10.dp))
            Field("금액(원)", amount, { amount = it.filter(Char::isDigit).take(11) }, "0", number = true)
            if (value > 0) Text(Fmt.won(value), style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
            Spacer(Modifier.height(10.dp))
            Field("가맹점·내용", merchant, { merchant = it.take(40) }, "예: 스타벅스")
            Spacer(Modifier.height(16.dp))
            if (!row.isFailed) {
                ToggleRow("앞으로도 자동으로 보내기", "${row.label ?: "이 발신처"}에서 오는 금액 알림을 '${issuer.ifBlank { "카드사" }}'(으)로 읽어 바로 보내요",
                    Icons.Rounded.AutoMode, P.c.pine, remember) { remember = it }
                Spacer(Modifier.height(10.dp))
            }
            if (target.isEmpty()) Text("연결된 가계부가 없어요. 가족 화면에서 먼저 연결해 주세요.", color = P.c.danger, style = MaterialTheme.typography.bodyMedium)
            household.member(id.myMemberId)?.takeIf { id.inHousehold && issuer.isNotBlank() && !it.shares(issuer.trim()) }?.let {
                Text("· '${issuer.trim()}'는 내 공유 범위 밖이지만, 직접 고른 이 건은 가계부에 보내요" +
                    if (remember) ". 앞으로 오는 것도 보내도록 공유 범위에 넣어요" else "",
                    style = MaterialTheme.typography.bodySmall, color = P.c.apricot)
            }
            target.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft) }
            error?.let { Text(it, color = P.c.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

/** 알림 자동 기록 화면 안: "앞으로도 자동으로 보내는 발신처" 목록. */
@Composable
fun ForwardRulesSection() {
    val rules by Hub.local.rules.collectAsStateWithLifecycle()
    if (rules.isEmpty()) return
    Spacer(Modifier.height(20.dp))
    SectionTitle("자동으로 보내는 발신처")
    SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
        rules.forEach { r ->
            ListRow(r.label, "'${r.issuer}' ${if (r.card) "카드" else "계좌"}로 읽어 자동으로 보내요", Icons.Rounded.AutoMode, P.c.pine) {
                SmallButton("그만", color = P.c.danger) { Hub.local.removeRule(r.key) }
            }
        }
    }
}

/** 홈에 띄우는 "실패·예정 알림이 내역으로 기록돼 있어요 N건" 카드(#13). 답하면(지우기·그대로 두기) 사라집니다. */
@Composable
fun FailedRecordsCard(nav: Nav) {
    val tick = rememberResumeTick()
    val count by produceState(0, tick) { value = withContext(Dispatchers.IO) { Hub.capture.recordedFailures().size } }
    if (count == 0) return
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.c.apricotSoft).padding(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text("실패·예정 알림이 내역으로 기록돼 있어요 ${count}건", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
            Text("실패했거나 아직 예정이라 돈이 나가지 않은 결제·출금이에요. 확인하고 지워 주세요", style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
        }
        SmallButton("보기", color = P.c.apricot) { nav.go(Route.CaptureLog) }
    }
}

/** 홈·보내기 폰 홈에 띄우는 "기록 안 된 금액 알림 N건" 카드. */
@Composable
fun CandidatesCard(nav: Nav) {
    val tick = rememberResumeTick()
    val count = remember(tick) { Hub.db.candidateCount(System.currentTimeMillis() - 3L * 86_400_000) }
    if (count == 0) return
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.c.apricotSoft).padding(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text("기록되지 않은 금액 알림 ${count}건", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
            Text("형식을 몰라 못 읽었어요. 골라서 보내면 다음부터 자동으로 보낼 수 있어요", style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
        }
        SmallButton("보기", color = P.c.apricot) { nav.go(Route.CaptureLog) }
    }
}
