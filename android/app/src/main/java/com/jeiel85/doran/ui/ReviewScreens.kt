package com.jeiel85.doran.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.logic.Period
import com.jeiel85.doran.logic.Review
import com.jeiel85.doran.logic.Stats
import java.time.LocalDate

/** 연간 리포트: 월별 지출·수입 막대, 한 해 합계·월평균·가장 많이 쓴 달, 카테고리·가게·사람별. */
@Composable
fun YearReportScreen(year0: Int, nav: Nav) {
    val s by Hub.ledger.state.collectAsStateWithLifecycle()
    val id by Hub.identity.state.collectAsStateWithLifecycle()
    val settings by Hub.settings.collectAsStateWithLifecycle()
    var year by remember { mutableStateOf(year0) }
    val today = LocalDate.now(Fmt.zone)
    val start = Stats.viewStart(s, settings.monthStartDay)
    val r = remember(s.version, year, start, id.myMemberId, settings.showSenders) {
        Review.year(s, year, start, id.myMemberId, settings.showSenders, today)
    }
    SubScreen("${year}년 결산", { nav.back() }, actions = {
        IconButton(onClick = { year-- }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "앞 해", tint = P.c.ink) }
        IconButton(onClick = { if (year < today.year) year++ }, enabled = year < today.year) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "다음 해", tint = if (year < today.year) P.c.ink else P.c.line)
        }
    }) {
        item {
            StatStrip(listOf(
                Stat("한 해 지출", Fmt.won(r.expense), P.c.expense),
                Stat("한 해 수입", "+" + Fmt.won(r.income), P.c.income),
                Stat("남은 돈", Fmt.won(r.income - r.expense)),
            ), background = P.c.surface)
            Spacer(Modifier.height(10.dp))
            Text(listOfNotNull(
                "한 달 평균 ${Fmt.won(r.monthlyAvg)} 썼어요",
                r.maxMonth?.let { "가장 많이 쓴 달은 ${it.period.label.monthValue}월(${Fmt.won(it.expense)})" },
            ).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft)
        }
        item {
            Spacer(Modifier.height(16.dp))
            SectionTitle("월별")
            SoftCard { MonthBars(r.months) }
        }
        if (r.byCategory.isNotEmpty()) item {
            Spacer(Modifier.height(16.dp))
            SectionTitle("카테고리")
            SoftCard {
                val total = r.byCategory.sumOf { it.second }.coerceAtLeast(1)
                r.byCategory.take(10).forEach { (cid, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CatTile(s.category(cid)?.icon ?: "etc", 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(s.category(cid)?.name ?: "기타", style = MaterialTheme.typography.bodyMedium, color = P.c.ink, modifier = Modifier.weight(1f))
                        Text("${v * 100 / total}%", style = MaterialTheme.typography.bodySmall, color = P.c.inkFaint)
                        Spacer(Modifier.width(8.dp))
                        Text(Fmt.won(v), style = MaterialTheme.typography.bodyMedium.merge(Money), color = P.c.ink)
                    }
                }
            }
        }
        if (r.topMerchants.isNotEmpty()) item {
            Spacer(Modifier.height(16.dp))
            SectionTitle("많이 쓴 곳")
            SoftCard {
                r.topMerchants.forEachIndexed { i, (m, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text("${i + 1}", style = MaterialTheme.typography.bodyMedium.merge(Money), color = P.c.inkFaint, modifier = Modifier.width(24.dp))
                        Text(m, style = MaterialTheme.typography.bodyMedium, color = P.c.ink, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(Fmt.won(v), style = MaterialTheme.typography.bodyMedium.merge(Money), color = P.c.ink)
                    }
                }
            }
        }
        if (r.byMember.size > 1) item {
            Spacer(Modifier.height(16.dp))
            SectionTitle("사람별")
            SoftCard {
                r.byMember.forEach { (mid, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(if (mid == Stats.FAMILY) "가족 공용" else s.member(mid)?.name ?: "공동", style = MaterialTheme.typography.bodyMedium,
                            color = P.c.ink, modifier = Modifier.weight(1f))
                        Text(Fmt.won(v), style = MaterialTheme.typography.bodyMedium.merge(Money), color = P.c.ink)
                    }
                }
            }
        }
        if (r.expense == 0L && r.income == 0L) item {
            EmptyState(com.jeiel85.doran.R.drawable.art_empty, "${year}년 기록이 없어요", "한 달 두 달 쌓이면 여기서 한 해를 돌아볼 수 있어요")
        }
    }
}

/** 12달 지출 막대(수입은 얇은 선). 아직 안 온 달은 비워 둡니다. */
@Composable
private fun MonthBars(months: List<Review.Month>) {
    val max = months.maxOf { maxOf(it.expense, it.income) }.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(160.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        months.forEach { m ->
            Column(Modifier.weight(1f).fillMaxHeight().semantics {
                contentDescription = "${m.period.label.monthValue}월 " + if (m.future) "아직 안 옴" else "지출 ${Fmt.won(m.expense)}, 수입 ${Fmt.won(m.income)}"
            }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    if (!m.future) Box(Modifier.fillMaxWidth(0.7f).fillMaxHeight((m.expense.toFloat() / max).coerceIn(0.01f, 1f))
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)).background(P.c.expense.copy(alpha = 0.8f)))
                }
                Spacer(Modifier.height(4.dp))
                Text("${m.period.label.monthValue}", style = MaterialTheme.typography.labelSmall, color = if (m.future) P.c.line else P.c.inkSoft)
            }
        }
    }
}

/** 월말 마감 카드(위트리): 수입 확인 → 빠진 지출 → 고정지출 → 저축. 체크는 이 폰에만 남습니다. */
@Composable
fun ClosingCard(s: LedgerState, p: Period, today: LocalDate, me: String?, senders: Boolean, nav: Nav) {
    val c = remember(s.version, p, today) { Review.closing(s, p, today, me, senders) }
    var tick by remember { mutableStateOf(0) }
    var closed by remember(p.key) { mutableStateOf(false) }
    if (closed) return
    fun key(i: Int) = "close.${p.key}.$i"
    val steps = listOf(
        Triple("수입 확인", "수입 ${c.incomeCount}건 ${Fmt.won(c.income)} — 빠진 월급·용돈은 없나요?") {
            nav.tab = Tab.LEDGER; nav.ledger.kindFilter = "i"; nav.ledger.calendar = false; nav.ledger.month = p.label.toString()
        },
        Triple("빠진 지출", (if (c.needsReview > 0) "확인 필요 ${c.needsReview}건 · " else "") + "기록 안 된 알림과 현금 쓴 것을 확인해요") {
            nav.go(Route.CaptureLog)
        },
        Triple("고정지출", if (c.missedFixed.isEmpty()) "날짜가 지난 고정지출은 모두 들어왔어요"
            else "아직 안 들어온 ${c.missedFixed.size}건: " + c.missedFixed.take(3).joinToString(", ") { it.first.name }) { nav.go(Route.Recurring) },
        Triple("저축", if (c.left > 0) "이번 달 남은 돈 ${Fmt.won(c.left)} — 저축 목표로 옮길까요?" else "이번 달은 남은 돈이 없어요") { nav.go(Route.Goals) },
    )
    SoftCard(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        Text("${p.label.monthValue}월 마감", style = MaterialTheme.typography.titleMedium, color = P.c.ink)
        Text("한 달을 닫기 전에 네 가지만 봐요", style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
        Spacer(Modifier.height(8.dp))
        steps.forEachIndexed { i, (title, sub, go) ->
            val done = remember(tick) { Hub.local.checked(key(i)) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = go).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { Hub.local.setChecked(key(i), !done); tick++ }) {
                    Icon(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        if (done) "$title 확인함" else "$title 확인하기", tint = if (done) P.c.pine else P.c.inkFaint)
                }
                Column(Modifier.weight(1f)) {
                    Text("${i + 1}. $title", style = MaterialTheme.typography.titleSmall, color = if (done) P.c.inkFaint else P.c.ink)
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                }
            }
        }
        val all = remember(tick) { steps.indices.all { Hub.local.checked(key(it)) } }
        if (all) {
            Spacer(Modifier.height(6.dp))
            SmallButton("마감 끝 · 카드 닫기") { Hub.local.setChecked("closed.${p.key}", true); closed = true }
        }
    }
}
