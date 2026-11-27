package com.jeiel85.doran.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.logic.Entry
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Source

/** 내역 한 줄: 카테고리 그림 · 가맹점 · (사람 · 카드 · 시각) · 금액. */
@Composable
fun TxnRow(e: Entry, s: LedgerState, isNew: Boolean, showDate: Boolean = false, onClick: (() -> Unit)?) {
    val t = e.txn
    val cat = s.category(t.categoryId)
    val member = s.member(t.memberId)
    val icon = when {
        t.kind == Kind.TRANSFER -> "transfer"
        e.allowanceSummary -> "allowance"
        else -> cat?.icon ?: if (t.kind == Kind.INCOME) "income_etc" else "etc"
    }
    val name = t.merchant.ifBlank { cat?.name ?: if (t.kind == Kind.TRANSFER) "이체" else "내역" }
    val bits = txnBits(e, s, showDate)
    val status = when {
        t.isCancel -> "승인 취소"
        e.canceled -> "취소됨"
        e.net != t.amount -> "부분 취소"
        t.needsReview -> "확인 필요"
        else -> null
    }
    // 화면 읽기: 줄 하나를 "스타벅스, 지출 5,000원, 현대카드, 오후 2:30, 새 내역"처럼 한 번에 읽습니다.
    // 전에는 '-' 부호와 아이콘 이름이 따로 읽혀 지출인지 수입인지 들리지 않았어요(BENCHMARK.md 7-4).
    // 원화 금액이 아직 없는 해외 결제는 줄에 보이는 외화 금액을 그대로 읽습니다(#89 리뷰).
    val foreignOnly = t.needsReview && t.amount == 0L
    val spoken = spokenTxn(name, t.kind, if (t.isCancel || e.canceled) t.amount else e.net, t.isCancel,
        amountText = if (foreignOnly) t.foreign ?: "원화 금액 확인 필요" else null,
        rest =
        bits + listOfNotNull(status, if (t.private) "나만 보는 용돈" else null, if (t.receiptIds.isNotEmpty()) "영수증 있음" else null,
            if (isNew) "새 내역" else null))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "자세히 보기", onClick = onClick) else Modifier)
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            CatTile(icon, 46.dp)
            if (member != null) Box(Modifier.align(Alignment.BottomEnd).padding(start = 30.dp, top = 30.dp)) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(P.c.surface).padding(2.dp)) { MemberAvatar(member, 16.dp) }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall, color = P.c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (e.canceled) TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isNew) {
                    Spacer(Modifier.width(6.dp))
                    Text("NEW", style = MaterialTheme.typography.labelMedium, color = P.c.apricot,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(P.c.apricotSoft).padding(horizontal = 5.dp, vertical = 1.dp))
                }
                if (t.private) { Spacer(Modifier.width(4.dp)); Icon(Icons.Rounded.Lock, "용돈", tint = P.c.inkFaint, modifier = Modifier.size(14.dp)) }
                if (t.receiptIds.isNotEmpty()) { Spacer(Modifier.width(4.dp)); Icon(Icons.Rounded.Photo, "영수증", tint = P.c.inkFaint, modifier = Modifier.size(14.dp)) }
            }
            Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            when {
                // 승인 취소 엔티티: 원래 결제를 상쇄하는 것이라 돌아온 돈처럼 보여 줍니다(합계에는 원거래 쪽에서 반영).
                t.isCancel -> Text("+" + Fmt.won(t.amount), style = MaterialTheme.typography.titleSmall.merge(Money), color = P.c.inkSoft)
                t.needsReview && t.amount == 0L -> Text(t.foreign ?: "확인 필요", style = MaterialTheme.typography.titleSmall.merge(Money), color = P.c.apricot)
                e.canceled -> Text(Fmt.won(t.amount), style = MaterialTheme.typography.titleSmall.merge(Money), color = P.c.inkFaint,
                    textDecoration = TextDecoration.LineThrough)
                else -> Amount(e.net, t.kind, MaterialTheme.typography.titleSmall)
            }
            when {
                t.isCancel -> Text("승인 취소", style = MaterialTheme.typography.bodySmall, color = P.c.inkFaint)
                e.canceled -> Text("취소됨", style = MaterialTheme.typography.bodySmall, color = P.c.inkFaint)
                e.net != t.amount -> Text("부분 취소", style = MaterialTheme.typography.bodySmall, color = P.c.inkFaint)
                t.cumulative != null -> Text("누적 ${Fmt.compact(t.cumulative)}", style = MaterialTheme.typography.bodySmall, color = P.c.inkFaint)
                t.needsReview -> Text("확인 필요", style = MaterialTheme.typography.bodySmall, color = P.c.apricot)
            }
        }
    }
}

/** 내역 줄 둘째 줄의 조각들(사람·결제 수단·표시·시각). 화면 읽기 문장에도 같은 것을 씁니다. */
private fun txnBits(e: Entry, s: LedgerState, showDate: Boolean): List<String> {
    val t = e.txn
    val member = s.member(t.memberId)
    return buildList {
        if (showDate) add(Fmt.date(t.at).let { "${it.monthValue}/${it.dayOfMonth}" })
        if (e.allowanceSummary) add(if (e.counted) "세부 내역은 본인만 볼 수 있어요" else "참고 · 용돈 안에서 써서 합계에 안 더함")
        else {
            member?.name?.let(::add)
            val acc = s.account(t.accountId)
            (acc?.name ?: t.cardLabel)?.let { add(if (acc?.family == true) "$it(가족 카드)" else it) }
            val moves = com.jeiel85.doran.logic.Moves
            when {
                t.private && moves.fromOf(t) != null -> add("가족 돈에서 나감 · 용돈에서 안 뺌")
                t.private && moves.isMoved(t) -> add("옮긴 돈 · 용돈에서 안 뺌")
                t.private && moves.fundOf(t) != null -> add("가족 돈")
                moves.linked(t) -> add("옮긴 돈 · 합계 제외")
                moves.selfIn(t) -> add("내 통장끼리 옮긴 돈 · 수입 아님")
                !e.counted -> add("용돈 · 합계 제외")
            }
            moves.destination(s, t)?.let { add("→ $it") }
            if (t.installment > 1) add("${t.installment}개월")
            if (t.source == Source.RECURRING) add("고정")
            if (!showDate) add(Fmt.time(t.at))
        }
    }
}

/** 내역 한 줄의 화면 읽기 문장. 지출·수입·이체를 말로 밝히고 금액은 원 단위로. */
internal fun spokenTxn(name: String, kind: Kind, amount: Long, isCancel: Boolean, rest: List<String>, amountText: String? = null): String {
    val what = when {
        isCancel -> "돌아온 돈"
        kind == Kind.INCOME -> "수입"
        kind == Kind.TRANSFER -> "이체"
        else -> "지출"
    }
    // 화살표·가운뎃점은 읽으면 어색해서 말로 바꿉니다.
    return (listOf(name, "$what ${amountText ?: Fmt.won(amount)}") + rest.map { it.replace("→ ", "받는 곳 ").replace(" · ", ", ") }).joinToString(", ")
}

/** 날짜 머리 + 그날 합계. */
@Composable
fun DayHeader(label: String, expense: Long, income: Long) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp, start = 6.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = P.c.inkSoft, modifier = Modifier.weight(1f))
        if (income > 0) Text("+" + Fmt.num(income), style = MaterialTheme.typography.labelMedium.merge(Money), color = P.c.income)
        if (income > 0 && expense > 0) Spacer(Modifier.width(8.dp))
        if (expense > 0) Text("-" + Fmt.num(expense), style = MaterialTheme.typography.labelMedium.merge(Money), color = P.c.inkSoft)
    }
}
