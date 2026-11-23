package com.jeiel85.doran.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeiel85.doran.R
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.logic.Banks
import com.jeiel85.doran.logic.Entry
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.model.Account
import com.jeiel85.doran.model.AccountType
import com.jeiel85.doran.model.Txn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val BANK_HERO = listOf(Color(0xFF2B3A67), Color(0xFF1E2747))
private const val SHOWN = 300

/** 들어온 돈·나간 돈만 보기(0 전체, 1 들어온 돈, 2 나간 돈). */
private fun keep(filter: Int, sign: Int) = when (filter) { 1 -> sign > 0; 2 -> sign < 0; else -> true }

@Composable
private fun FlowFilter(filter: Int, onPick: (Int) -> Unit) {
    ChipRow { listOf("전체", "들어온 돈", "나간 돈").forEachIndexed { i, l -> Chip(l, filter == i, { onPick(i) }) } }
}

private fun entryOf(s: LedgerState, t: Txn): Entry {
    val c = s.canceled[t.id] ?: 0
    return Entry(t, (t.amount - c).coerceAtLeast(0), c > 0 && c >= t.amount, counted = Stats.counts(s, t))
}

/**
 * 계좌·카드 한 곳의 입출금(DECISIONS.md D-33): 은행을 연결하지 않고 알림만으로 기록된 입금·출금을 통장마다 보여 주고(비주얼 가계부의
 * 계좌 관리), 통장이면 이은 체크카드로 쓴 돈도 함께 봅니다. 위 연필로 이름·끝자리·돈이 나가는 통장을 고칩니다.
 */
@Composable
fun AccountDetailScreen(aid: String, nav: Nav) {
    val s by Hub.ledger.state.collectAsStateWithLifecycle()
    val settings by Hub.settings.collectAsStateWithLifecycle()
    val a = s.accounts[aid]
    if (a == null) { LaunchedEffect(aid) { nav.back() }; return }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val flow = remember(s.version, aid) { Banks.flow(s, a) }
    val p = remember(s.version, settings.monthStartDay) { Stats.current(s, startDay = Stats.viewStart(s, settings.monthStartDay)) }
    val month = flow.filter { it.at in p }
    fun sum(sign: Int) = month.filter { Banks.sign(a, it) == sign }.sumOf { entryOf(s, it).net }
    val cards = remember(s.version, aid) { if (a.type == AccountType.BANK) Banks.linkedCards(s, a) else emptyList() }
    val tracked = Stats.keepsBalance(a)

    SubScreen(a.name, { nav.back() }, actions = {
        IconButton(onClick = { nav.go(Route.AccountEdit(a.id)) }) { Icon(Icons.Rounded.Edit, "고치기", tint = P.c.ink) }
    }) {
        item {
            when {
                a.type == AccountType.CARD -> {
                    val c = Stats.card(s, a)
                    HeroCard(BANK_HERO, R.drawable.art_cat_bank, "갚을 돈", Fmt.won(-Stats.balance(s, a))) {
                        Spacer(Modifier.height(16.dp))
                        HeroStats(listOfNotNull(
                            Stat("이번 달 쓴 돈", Fmt.won(c.monthSpent)),
                            c.dueDate?.let { Stat("${it.monthValue}/${it.dayOfMonth} 결제", Fmt.won(c.dueAmount)) },
                        ))
                    }
                }
                !tracked -> HeroCard(BANK_HERO, R.drawable.art_cat_bank, "이번 달 쓴 돈", Fmt.won(Stats.card(s, a).monthSpent)) {
                    Spacer(Modifier.height(16.dp))
                    HeroStats(listOf(Stat("돈이 나가는 통장", s.accounts[a.payFromId ?: ""]?.name ?: "모름")))
                }
                else -> HeroCard(BANK_HERO, R.drawable.art_cat_bank, if (a.type.asset) "지금 잔액" else "갚을 돈",
                    Fmt.won(Stats.balance(s, a).let { if (a.type.asset) it else -it })) {
                    Spacer(Modifier.height(16.dp))
                    HeroStats(listOf(
                        Stat("${Fmt.month(p.label)} 들어온 돈", Fmt.won(sum(1))),
                        Stat("나간 돈", Fmt.won(sum(-1)), Color(0xFFFFB4A8)),
                    ))
                }
            }
        }
        item {
            Spacer(Modifier.height(14.dp))
            SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                if (a.type == AccountType.BANK) {
                    ListRow("계좌번호 끝자리", a.last4?.let { "…$it · 이 끝자리로 온 알림만 이 통장으로" }
                        ?: "적지 않음 · 같은 은행 통장이 둘 이상이면 적어서 나눠요", Icons.Rounded.Pin, onClick = { nav.go(Route.AccountEdit(a.id)) })
                    ListRow("이은 체크카드", cards.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name }?.let { "$it · 쓴 돈이 이 통장에서 빠져요" }
                        ?: "없음 · 체크카드 화면에서 '돈이 나가는 통장'으로 이어요", Icons.Rounded.CreditCard)
                }
                if (a.type == AccountType.CHECK) {
                    ListRow("돈이 나가는 통장", s.accounts[a.payFromId ?: ""]?.let { "${it.name} · 쓴 돈이 그 통장 잔액에서 빠져요" }
                        ?: "모름 · 고르면 쓴 돈이 그 통장 잔액에서 바로 빠져요", Icons.Rounded.AccountBalance, onClick = { nav.go(Route.AccountEdit(a.id)) })
                }
                if (tracked) ListRow("잔액 맞춤", a.anchorAt?.let { "은행 알림의 잔액으로 ${Fmt.relative(it)} 맞췄어요" }
                    ?: "아직 은행 알림의 잔액을 받지 못했어요 · 연필로 지금 잔액을 적을 수 있어요", Icons.Rounded.Sync)
            }
        }
        item {
            Spacer(Modifier.height(18.dp))
            SectionTitle("입출금 ${flow.size}건")
            FlowFilter(filter) { filter = it }
            Spacer(Modifier.height(6.dp))
        }
        val shown = flow.filter { keep(filter, Banks.sign(a, it)) }
        if (shown.isEmpty()) item {
            Text("아직 없어요. 이 은행·카드 알림이 오면 저절로 쌓여요.", style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft,
                modifier = Modifier.padding(8.dp))
        }
        items(shown.take(SHOWN), key = { it.id }) { t -> TxnRow(entryOf(s, t), s, false, showDate = true) { nav.go(Route.Entry(txnId = t.id)) } }
        if (shown.size > SHOWN) item {
            Text("최근 ${SHOWN}건만 보여요. 더 앞의 내역은 내역 탭에서 찾아보세요.", style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft,
                modifier = Modifier.padding(8.dp))
        }
    }
}

/**
 * 나만 보는 통장 하나(공유 범위 밖 은행, D-19·D-33): 가족과 나누지 않는 통장이라 계좌를 만들지 않고, 이 폰에 남은 알림과 기록으로만
 * 잔액과 드나듦을 보여 줍니다. 순자산에는 넣지 않습니다(가족 화면과 같은 숫자를 보여야 해서).
 */
@Composable
fun PrivateBankScreen(bank: String, nav: Nav) {
    val s by Hub.ledger.state.collectAsStateWithLifecycle()
    val tick = rememberResumeTick()
    val v by produceState<Banks.Private?>(null, tick, s.version) {
        value = withContext(Dispatchers.IO) { Hub.capture.privateBanks().firstOrNull { it.bank == bank } }
    }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    SubScreen(bank, { nav.back() }) {
        item {
            HeroCard(BANK_HERO, R.drawable.art_cat_bank, "지금 잔액 · 나만 봐요", v?.balance?.let(Fmt::won) ?: "잔액 모름") {
                Spacer(Modifier.height(10.dp))
                Text(v?.balanceAt?.let { "은행 알림의 잔액으로 ${Fmt.relative(it)} 맞추고, 그 뒤 드나든 돈을 더했어요" }
                    ?: "이 은행 알림에 잔액이 적혀 오면 그때부터 보여요",
                    style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.78f))
            }
        }
        item {
            Spacer(Modifier.height(14.dp))
            SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                ListRow("가족에게 보이지 않아요", "공유 범위 밖 은행이라 이 폰에서만 보여요. 순자산에도 넣지 않아요", Icons.Rounded.Lock, P.c.apricot)
            }
        }
        val list = v?.txns.orEmpty()
        val sign: (Txn) -> Int = { t -> (com.jeiel85.doran.logic.Gaps.delta(t) ?: 0L).let { if (it >= 0) 1 else -1 } }
        item {
            Spacer(Modifier.height(18.dp))
            SectionTitle("입출금 ${list.size}건")
            FlowFilter(filter) { filter = it }
            Spacer(Modifier.height(6.dp))
        }
        val shown = list.filter { keep(filter, sign(it)) }
        if (v != null && shown.isEmpty()) item {
            Text("아직 없어요.", style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft, modifier = Modifier.padding(8.dp))
        }
        items(shown.take(SHOWN), key = { it.id }) { t ->
            // 기록하지 않은 카드값 출금(Banks.Bill)은 열 내역이 없습니다.
            val real = s.txns.containsKey(t.id)
            TxnRow(entryOf(s, t), s, false, showDate = true, onClick = if (real) ({ nav.go(Route.Entry(txnId = t.id)) }) else null)
        }
    }
}

/** 자산 화면의 나만 보는 통장 한 줄의 설명. */
internal fun privateBankSub(p: Banks.Private): String = listOfNotNull(
    "이 폰에만",
    p.balanceAt?.let { "잔액 알림 ${Fmt.relative(it)}" } ?: "잔액 알림 없음",
    "${p.txns.size}건",
).joinToString(" · ")

/** 계좌 하나의 설명에 덧붙일 통장 관리 정보(끝자리, 이은 통장·카드). */
internal fun accountLinks(s: LedgerState, a: Account): List<String> = buildList {
    if (a.type == AccountType.BANK) {
        // 끝자리로 새로 만든 통장은 이름("신한은행 999876")에 이미 있어 다시 적지 않습니다.
        a.last4?.takeIf { !a.name.endsWith(it) }?.let { add("…$it") }
        Banks.linkedCards(s, a).size.takeIf { it > 0 }?.let { add("체크카드 ${it}장") }
    }
    if (a.type == AccountType.CHECK) s.accounts[a.payFromId ?: ""]?.let { add("${it.name}에서 나가요") }
}
