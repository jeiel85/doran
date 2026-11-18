package com.jeiel85.doran.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeiel85.doran.R
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.logic.Entry
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.model.Kind
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** 내역 탭: 달력(기본)과 목록. 사람·종류 필터를 함께 씁니다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(nav: Nav) {
    val s by Hub.ledger.state.collectAsStateWithLifecycle()
    val id by Hub.identity.state.collectAsStateWithLifecycle()
    val unseen by Hub.local.unseen.collectAsStateWithLifecycle()
    var month by rememberSaveable { mutableStateOf(YearMonth.now(Fmt.zone).toString()) }
    val ym = YearMonth.parse(month)
    var calendar by rememberSaveable { mutableStateOf(true) }
    var memberFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var kindFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(LocalDate.now(Fmt.zone).toString()) }

    // 달력은 달력 달(1일~말일)로, 위 합계는 같은 범위로 계산합니다.
    val range = remember(ym) { com.jeiel85.doran.logic.Period(ym, ym.atDay(1), ym.plusMonths(1).atDay(1)) }
    val all = remember(s.version, range, id.myMemberId) { Stats.entries(s, range, id.myMemberId) }
    val filtered = all.filter { e ->
        (memberFilter == null || e.txn.memberId == memberFilter) &&
            (kindFilter == null || e.txn.kind.code == kindFilter)
    }
    val byDay = remember(filtered) { Stats.byDay(filtered) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { month = ym.minusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "이전 달", tint = P.c.ink) }
            Text(Fmt.month(ym), style = MaterialTheme.typography.titleLarge, color = P.c.ink)
            IconButton(onClick = { month = ym.plusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "다음 달", tint = P.c.ink) }
            Spacer(Modifier.weight(1f))
            Segmented(listOf("달력", "목록"), if (calendar) 0 else 1) { calendar = it == 0 }
            IconButton(onClick = { nav.go(Route.Search) }) { Icon(Icons.Rounded.Search, "검색", tint = P.c.ink) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
            Summary("지출", Stats.expense(filtered), P.c.ink, Modifier.weight(1f))
            Summary("수입", Stats.income(filtered), P.c.income, Modifier.weight(1f))
            Summary("합계", Stats.income(filtered) - Stats.expense(filtered), P.c.inkSoft, Modifier.weight(1f))
        }
        ChipRow(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Chip("모두", memberFilter == null && kindFilter == null, { memberFilter = null; kindFilter = null })
            Chip("지출", kindFilter == "e", { kindFilter = if (kindFilter == "e") null else "e" }, color = P.c.expense)
            Chip("수입", kindFilter == "i", { kindFilter = if (kindFilter == "i") null else "i" }, color = P.c.income)
            Chip("이체", kindFilter == "t", { kindFilter = if (kindFilter == "t") null else "t" }, color = P.c.transfer)
            s.activeMembers.forEach { m ->
                Chip(m.name, memberFilter == m.id, { memberFilter = if (memberFilter == m.id) null else m.id },
                    leading = { MemberAvatar(m, 18.dp) }, color = memberColor(m.color))
            }
        }
        val refreshScope = rememberCoroutineScope()
        val syncing by Hub.sync.status.collectAsStateWithLifecycle()
        PullToRefreshBox(isRefreshing = syncing.running, onRefresh = { refreshScope.launch { Hub.sync.sync() } }, modifier = Modifier.weight(1f)) {
        if (calendar) {
            val sel = selected?.let(LocalDate::parse)?.takeIf { YearMonth.from(it) == ym }
            val dayEntries = filtered.filter { !it.allowanceSummary && sel != null && Fmt.date(it.txn.at) == sel }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 120.dp)) {
                item {
                    SoftCard(padding = PaddingValues(10.dp)) {
                        CalendarGrid(ym, byDay, sel) { selected = it.toString() }
                    }
                }
                if (sel != null) {
                    item {
                        val (ex, inc) = byDay[sel] ?: (0L to 0L)
                        Row(Modifier.fillMaxWidth().padding(top = 18.dp, start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(Fmt.dayLabel(sel), style = MaterialTheme.typography.titleMedium, color = P.c.ink)
                                Text(listOfNotNull(if (ex > 0) "지출 ${Fmt.won(ex)}" else null, if (inc > 0) "수입 ${Fmt.won(inc)}" else null)
                                    .joinToString(" · ").ifEmpty { "내역 없음" }, style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                            }
                            SmallButton("이날 쓰기") { nav.go(Route.Entry(date = sel)) }
                        }
                    }
                    items(dayEntries, key = { it.txn.id }) { e ->
                        TxnRow(e, s, e.txn.id in unseen) { open(nav, e) }
                    }
                }
            }
        } else {
            val days = filtered.groupBy { Fmt.date(it.txn.at) }.toSortedMap(compareByDescending { it })
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 120.dp)) {
                if (days.isEmpty()) item { EmptyState(R.drawable.art_empty, "${Fmt.month(ym)} 내역이 없어요", "가운데 + 버튼으로 기록해 보세요") }
                days.forEach { (d, list) ->
                    item(key = "h$d") {
                        DayHeader(Fmt.dayLabel(d), list.filter { it.counted && it.txn.kind == Kind.EXPENSE }.sumOf { it.net },
                            list.filter { it.counted && it.txn.kind == Kind.INCOME }.sumOf { it.net })
                    }
                    items(list, key = { it.txn.id }) { e -> TxnRow(e, s, e.txn.id in unseen) { open(nav, e) } }
                }
            }
        }
        }
    }
}

private fun open(nav: Nav, e: Entry) {
    if (e.allowanceSummary) { nav.go(Route.Allowance); return }
    Hub.local.markSeen(listOf(e.txn.id))
    nav.go(Route.Entry(txnId = e.txn.id))
}

@Composable
private fun Summary(label: String, value: Long, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
        Text(Fmt.won(value), style = MaterialTheme.typography.titleSmall.merge(Money), color = color, maxLines = 1)
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(14.dp)).background(P.c.surface2).border(1.dp, P.c.line, RoundedCornerShape(14.dp)).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            Box(
                Modifier.clip(RoundedCornerShape(11.dp)).background(if (i == selected) P.c.surface else Color.Transparent)
                    .clickable { onSelect(i) }.padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(o, style = MaterialTheme.typography.labelMedium, color = if (i == selected) P.c.ink else P.c.inkFaint)
            }
        }
    }
}

/** 월 달력: 날짜마다 지출(잉크)·수입(블루) 축약 금액, 지출 크기에 따라 바탕이 짙어집니다. */
@Composable
fun CalendarGrid(ym: YearMonth, byDay: Map<LocalDate, Pair<Long, Long>>, selected: LocalDate?, onSelect: (LocalDate) -> Unit) {
    val first = ym.atDay(1)
    val lead = first.dayOfWeek.value % 7 // 일요일 시작
    val cells = lead + ym.lengthOfMonth()
    val rows = (cells + 6) / 7
    val maxEx = byDay.values.maxOfOrNull { it.first }?.coerceAtLeast(1) ?: 1
    val today = LocalDate.now(Fmt.zone)
    Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, d ->
                Text(d, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium,
                    color = when (i) { 0 -> P.c.expense; 6 -> P.c.income; else -> P.c.inkFaint })
            }
        }
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val idx = r * 7 + c - lead
                    Box(Modifier.weight(1f).aspectRatio(0.78f).padding(2.dp)) {
                        if (idx in 0 until ym.lengthOfMonth()) {
                            val d = ym.atDay(idx + 1)
                            val (ex, inc) = byDay[d] ?: (0L to 0L)
                            val heat = if (ex > 0) 0.08f + 0.32f * (ex.toFloat() / maxEx) else 0f
                            val isSel = d == selected
                            Column(
                                Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                                    .background(if (isSel) P.c.pine else P.c.apricot.copy(alpha = heat))
                                    .clickable { onSelect(d) }.padding(top = 5.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                val dayColor = when {
                                    isSel -> Color.White
                                    d.dayOfWeek == DayOfWeek.SUNDAY -> P.c.expense
                                    d.dayOfWeek == DayOfWeek.SATURDAY -> P.c.income
                                    else -> P.c.ink
                                }
                                Box(contentAlignment = Alignment.Center) {
                                    if (d == today && !isSel) Box(Modifier.size(22.dp).clip(CircleShape).border(1.5.dp, P.c.pine, CircleShape))
                                    Text("${d.dayOfMonth}", fontSize = 13.sp, fontWeight = if (d == today) FontWeight.ExtraBold else FontWeight.SemiBold, color = dayColor)
                                }
                                Spacer(Modifier.height(2.dp))
                                if (ex > 0) Text(Fmt.compact(ex), fontSize = 9.5.sp, style = Money, color = if (isSel) Color.White else P.c.ink, maxLines = 1)
                                if (inc > 0) Text("+" + Fmt.compact(inc), fontSize = 9.5.sp, style = Money, color = if (isSel) Color.White else P.c.income, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
