package com.jeiel85.doran.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeiel85.doran.capture.Issuers
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.logic.Josa
import com.jeiel85.doran.logic.ShareScope
import com.jeiel85.doran.model.Member

/**
 * 구성원의 공유 범위(DECISIONS.md D-19): 함께 관리 / 정한 카드사·은행만 보내기.
 * 본인과 다른 가족 모두 바꿀 수 있고, 바꾸면 상대 폰에 알림이 갑니다.
 */
@Composable
fun ShareScreen(memberId: String, nav: Nav) {
    val s by Hub.ledger.state.collectAsStateWithLifecycle()
    val id by Hub.identity.state.collectAsStateWithLifecycle()
    val m = s.member(memberId)
    if (m == null || m.isSender) {
        SubScreen("공유 범위", { nav.back() }) { item { Text("구성원을 찾을 수 없어요", color = P.c.inkSoft) } }
        return
    }
    val isMe = m.id == id.myMemberId
    var picked by remember(memberId) { mutableStateOf(m.sharesPicked) }
    var issuers by remember(memberId) { mutableStateOf(m.shareIssuers) }
    var allowance by remember(memberId) { mutableStateOf(m.allowance.takeIf { it > 0 }?.toString() ?: "") }
    // 고를 수 있는 이름: 이 사람의 카드·계좌, 이 폰의 "자동으로 보내기" 규칙, 지금 고른 것, 그리고 흔한 카드사·은행.
    val known = remember(memberId, s.version) {
        val own = s.accounts.values.filter { it.ownerId == m.id && !it.institution.isNullOrBlank() }.map { it.institution!! }
        val rules = if (isMe) Hub.local.rules.value.map { it.issuer } else emptyList()
        (m.shareIssuers + own + rules).distinct()
    }
    val cards = (known + Issuers.cards.map { it.name }).distinct().filter { n -> Issuers.banks.none { it.name == n } }
    val banks = Issuers.banks.map { it.name }
    val draft = m.copy(share = if (picked) Member.SHARE_PICKED else Member.SHARE_ALL, shareIssuers = issuers)
    val by = m.shareBy?.let { s.member(it)?.name }
    // 화면을 연 뒤 다른 폰에서 범위가 바뀌면 알려 줍니다(늦게 저장한 쪽이 이기므로).
    var opened by remember(memberId) { mutableStateOf(m.share to m.shareIssuers.toSet()) }
    val changedElsewhere = (m.share to m.shareIssuers.toSet()) != opened && m.shareBy != id.myMemberId
    var moveAsk by remember { mutableStateOf<List<com.jeiel85.doran.model.Txn>>(emptyList()) }

    SubScreen(if (isMe) "내 공유 범위" else "${m.name} 공유 범위", { nav.back() }, bottom = {
        PrimaryButton("저장", enabled = !picked || issuers.isNotEmpty()) {
            val saved = draft.copy(shareBy = id.myMemberId, allowance = allowance.toLongOrNull() ?: 0)
            Hub.ledger.saveMember(saved)
            // 정한 결제만 보내면, 보내는 카드는 가족 카드(D-20). 카드 설정에서 다시 끌 수 있어요.
            if (saved.sharesPicked) s.accounts.values.filter {
                it.ownerId == m.id && !it.family && it.institution in saved.shareIssuers &&
                    (it.type == com.jeiel85.doran.model.AccountType.CARD || it.type == com.jeiel85.doran.model.AccountType.CHECK)
            }.forEach { Hub.ledger.saveAccount(it.copy(family = true, allowanceCard = false)) }
            if (com.jeiel85.doran.logic.AllowancePlan.sync(Hub.ledger, saved)) com.jeiel85.doran.logic.Recurrer.run(Hub.ledger, Hub.db, Hub.identity.installId)
            // 내 범위를 좁혔으면, 이번 달 이미 가계부에 올라간 범위 밖 내역을 내 용돈으로 옮길지 묻습니다(내 개인 키가 있는 내 폰에서만).
            val now = Hub.ledger.value
            val p = com.jeiel85.doran.logic.Stats.current(now)
            val past = if (isMe && saved.sharesPicked) now.txns.values.filter {
                it.memberId == m.id && !it.private && !it.personal && it.kind == com.jeiel85.doran.model.Kind.EXPENSE && it.cancelOf == null &&
                    it.source.captured && it.at in p && it.issuer != null && !saved.shares(it.issuer) && now.account(it.accountId)?.family != true
            } else emptyList()
            if (past.isEmpty()) nav.back() else moveAsk = past
        }
    }) {
        if (changedElsewhere) item {
            SoftCard(color = P.c.apricotSoft, padding = PaddingValues(14.dp)) {
                Text("방금 ${Josa.iGa(by ?: "가족")} 공유 범위를 ${Josa.ro(ShareScope.label(m))} 바꿨어요. 지금 저장하면 이 화면의 선택으로 덮어써요.",
                    style = MaterialTheme.typography.bodySmall, color = P.c.ink)
                Spacer(Modifier.height(8.dp))
                SmallButton("바뀐 내용 불러오기", color = P.c.apricot) {
                    picked = m.sharesPicked; issuers = m.shareIssuers
                    allowance = m.allowance.takeIf { it > 0 }?.toString() ?: ""
                    opened = m.share to m.shareIssuers.toSet()
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        item {
            Text(if (isMe) "내 폰이 읽은 카드·은행 알림 중 무엇을 가계부에 올릴지 정해요."
                else "${m.name} 폰이 읽은 카드·은행 알림 중 무엇을 가계부에 올릴지 정해요.",
                style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft)
            Spacer(Modifier.height(14.dp))
            SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                ListRow("함께 관리", "읽은 카드·은행 내역을 모두 가계부에 올려요", Icons.Rounded.Groups,
                    if (!picked) P.c.pine else P.c.inkFaint, onClick = { picked = false }) { RadioMark(!picked) }
                ListRow("정한 결제만 보내기", "고른 카드사·은행 내역만 올리고, 나머지는 본인만 보는 용돈으로 남겨요",
                    Icons.Rounded.Send, if (picked) P.c.apricot else P.c.inkFaint, onClick = { picked = true }) { RadioMark(picked) }
            }
        }
        if (picked) {
            item {
                Spacer(Modifier.height(18.dp))
                Text("가계부에 보낼 카드", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
                Spacer(Modifier.height(8.dp))
                ChipFlow { cards.forEach { n -> Chip(n, n in issuers, { issuers = if (n in issuers) issuers - n else issuers + n }) } }
                Spacer(Modifier.height(14.dp))
                Text("가계부에 보낼 은행", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
                Spacer(Modifier.height(8.dp))
                ChipFlow { banks.forEach { n -> Chip(n, n in issuers, { issuers = if (n in issuers) issuers - n else issuers + n }) } }
                Spacer(Modifier.height(14.dp))
                if (issuers.isEmpty()) Text("보낼 카드사·은행을 하나 이상 골라 주세요", color = P.c.danger, style = MaterialTheme.typography.bodySmall)
                Text(ShareScope.detail(draft), style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                Text("보내는 카드는 '가족 카드'로 표시돼요(카드 설정에서 바꿀 수 있어요). 본인 계좌에서 나가는 그 외 카드값 출금은 두 번 세지 않게 기록하지 않아요.",
                    style = MaterialTheme.typography.bodySmall, color = P.c.inkFaint, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(16.dp))
                Field("월 용돈(원)", allowance, { allowance = it.filter(Char::isDigit).take(9) }, "예: 350000", number = true)
                (allowance.toLongOrNull()?.takeIf { it > 0 })?.let {
                    Text("${Fmt.won(it)} · 매달 '${m.name} 용돈' 고정지출로 가계부에 한 번 들어가고, 그 안에서 쓴 것은 합계에 더하지 않아요",
                        style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft,
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp))
                }
            }
        }
        item {
            Spacer(Modifier.height(18.dp))
            Text((if (isMe) "가족도" else "${m.name}도") + " 이 설정을 바꿀 수 있고, 바꾸면 서로의 폰에 알림이 가요. 이미 기록된 내역은 그대로예요." +
                (by?.let { "\n마지막으로 바꾼 사람: $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = P.c.inkFaint)
        }
    }
    if (moveAsk.isNotEmpty()) MovePastDialog(moveAsk) { moveAsk = emptyList(); nav.back() }
}

/** 범위를 좁힌 뒤 이번 달 가계부에 이미 올라간 범위 밖 내역을 내 용돈으로 옮길지 묻는 창. */
@Composable
private fun MovePastDialog(list: List<com.jeiel85.doran.model.Txn>, onDone: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDone, containerColor = P.c.bg,
        title = { Text("이번 달 다른 카드 내역 ${list.size}건") },
        text = { Text("공유 범위 밖 카드·계좌로 이번 달 가계부에 이미 올라간 내역이에요(${Fmt.won(list.sumOf { it.amount })}). " +
            "내 용돈으로 옮기면 가족 가계부에서 빠지고 내 폰에만 남아요.") },
        confirmButton = { androidx.compose.material3.TextButton({
            val ids = list.map { it.id }.toSet()
            val cancels = Hub.ledger.value.txns.values.filter { it.cancelOf in ids }
            Hub.ledger.putAll((list + cancels).map { Triple(com.jeiel85.doran.model.T.TXN, it.id, it.copy(private = true).json()) })
            onDone()
        }) { Text("내 용돈으로 옮기기", color = P.c.apricot) } },
        dismissButton = { androidx.compose.material3.TextButton(onDone) { Text("그대로 두기") } },
    )
}

/**
 * 보내는 폰(부모님 폰): 이 폰이 읽은 카드·은행 알림 중 무엇을 그 가구로 보낼지(DECISIONS.md D-6, #10).
 * 기본은 전부. 연금·월급 같은 통장 입금도 보내면 받는 가계부의 수입에 더해지므로, 카드만 보내려면 여기서 고릅니다.
 */
@Composable
fun LinkScopeScreen(spaceId: String, nav: Nav) {
    val id by Hub.identity.state.collectAsStateWithLifecycle()
    val link = id.outLinks.firstOrNull { it.spaceId == spaceId }
    if (link == null) {
        SubScreen("보낼 알림", { nav.back() }) { item { Text("연결을 찾을 수 없어요", color = P.c.inkSoft) } }
        return
    }
    var picked by remember(spaceId) { mutableStateOf(link.issuers.isNotEmpty()) }
    var issuers by remember(spaceId) { mutableStateOf(link.issuers) }
    val cards = (link.issuers + Issuers.cards.map { it.name }).distinct().filter { n -> Issuers.banks.none { it.name == n } }
    val banks = Issuers.banks.map { it.name }

    SubScreen("'${link.label}'에 보낼 알림", { nav.back() }, bottom = {
        PrimaryButton("저장", enabled = !picked || issuers.isNotEmpty()) {
            Hub.identity.addOutLink(link.copy(issuers = if (picked) issuers else emptyList()))
            nav.back()
        }
    }) {
        item {
            Text("이 폰이 읽은 카드·은행 알림 중 무엇을 '${link.label}'에 보낼지 정해요. 통장 입금(연금·월급 등)도 보내면 그 가계부의 수입에 더해져요.",
                style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft)
            Spacer(Modifier.height(14.dp))
            SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                ListRow("모두 보내기", "읽은 카드·은행 알림을 모두 보내요", Icons.Rounded.Groups,
                    if (!picked) P.c.pine else P.c.inkFaint, onClick = { picked = false }) { RadioMark(!picked) }
                ListRow("고른 카드·은행만", "고른 곳의 알림만 보내고 나머지는 보내지 않아요",
                    Icons.Rounded.Send, if (picked) P.c.apricot else P.c.inkFaint, onClick = { picked = true }) { RadioMark(picked) }
            }
        }
        if (picked) item {
            Spacer(Modifier.height(18.dp))
            Text("보낼 카드", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
            Spacer(Modifier.height(8.dp))
            ChipFlow { cards.forEach { n -> Chip(n, n in issuers, { issuers = if (n in issuers) issuers - n else issuers + n }) } }
            Spacer(Modifier.height(14.dp))
            Text("보낼 은행", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
            Spacer(Modifier.height(8.dp))
            ChipFlow { banks.forEach { n -> Chip(n, n in issuers, { issuers = if (n in issuers) issuers - n else issuers + n }) } }
            Spacer(Modifier.height(14.dp))
            if (issuers.isEmpty()) Text("보낼 카드사·은행을 하나 이상 골라 주세요", color = P.c.danger, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 카드사·은행이 많아도 한눈에 보이게 줄을 바꿔 늘어놓는 칩 묶음. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun RadioMark(on: Boolean) {
    androidx.compose.material3.RadioButton(selected = on, onClick = null,
        colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = P.c.pine))
}
