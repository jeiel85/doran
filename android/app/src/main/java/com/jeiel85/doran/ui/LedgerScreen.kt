package com.jeiel85.doran.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
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

/** 내역 탭에서 보던 자리(달·고른 날·보기·거르기). 하위 화면을 열고 돌아와도 남도록 [Nav]가 들고 있습니다. */
class LedgerView {
    var month by mutableStateOf(YearMonth.now(Fmt.zone).toString())
    var calendar by mutableStateOf(true)
    var memberFilter by mutableStateOf<String?>(null)
    var kindFilter by mutableStateOf<String?>(null)
    var selected by mutableStateOf<String?>(LocalDate.now(Fmt.zone).toString())

    /** 달력에 지금 보이는 고른 날. 목록 보기이거나 고른 날이 다른 달이면 null. */
    fun shownDay(): LocalDate? = if (!calendar) null else selected?.let(LocalDate::parse)?.takeIf { YearMonth.from(it).toString() == month }

    /** 그날이 보이게 달과 고른 날을 옮깁니다(저장한 내역이 어디 들어갔는지 바로 보이게). */
    fun show(d: LocalDate) { month = YearMonth.from(d).toString(); selected = d.toString() }

    /**
     * 앱이 백그라운드에서 정리됐다 돌아와도 보던 자리를 되살리도록 저장합니다(#87). 하루가 지났으면 오늘로 시작하는 편이 맞아
     * 저장한 날짜도 함께 두고, 다른 날이면 되살리지 않습니다.
     */
    fun save(out: android.os.Bundle) {
        out.putString("ledger.day", LocalDate.now(Fmt.zone).toString())
        out.putString("ledger.month", month); out.putBoolean("ledger.calendar", calendar)
        out.putString("ledger.member", memberFilter); out.putString("ledger.kind", kindFilter); out.putString("ledger.selected", selected)
    }

    fun restore(b: android.os.Bundle?) {
        if (b == null || b.getString("ledger.day") != LocalDate.now(Fmt.zone).toString()) return
        b.getString("ledger.month")?.let { month = it }
        calendar = b.getBoolean("ledger.calendar", true)
        memberFilter = b.getString("ledger.member"); kindFilter = b.getString("ledger.kind")
        selected = b.getString("ledger.selected") ?: selected
    }

    // 예상 초과일(D-35)은 장부 한 달치를 훑으므로, 같은 장부·같은 날·같은 설정이면 탭을 다시 열어도 다시 세지 않습니다(#87).
    private var overKey: List<Any?>? = null
    private var overValue: LocalDate? = null
    fun overDay(key: List<Any?>, compute: () -> LocalDate?): LocalDate? {
        if (key != overKey) { overValue = compute(); overKey = key }
        return overValue
    }
}

/** 내역 탭: 달력(기본)과 목록. 사람·종류 필터를 함께 씁니다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(nav: Nav) {
    val s by Hub.ledger.state.collectAsStateWithLifecycle()
    val id by Hub.identity.state.collectAsStateWithLifecycle()
    val unseen by Hub.local.unseen.collectAsStateWithLifecycle()
    // 보던 자리는 Nav에 둡니다. 내역을 열면 탭 화면이 통째로 빠졌다 돌아오므로, 여기서 기억하면 늘 오늘로 되돌아갔어요.
    val v = nav.ledger
    var month by v::month
    val ym = YearMonth.parse(month)
    var calendar by v::calendar
    var memberFilter by v::memberFilter
    var kindFilter by v::kindFilter
    var selected by v::selected

    // 달력은 달력 달(1일~말일)로, 위 합계는 같은 범위로 계산합니다.
    val range = remember(ym) { com.jeiel85.doran.logic.Period(ym, ym.atDay(1), ym.plusMonths(1).atDay(1)) }
    // 달력 칸은 앞뒤 달 날짜까지 그리므로(D-30) 그 넓은 범위를 한 번만 읽고, 그 달 합계는 그중에서 거릅니다(#67 — 전에는 장부를 두 번 훑었어요).
    // 한 주 합계도 이것으로 셉니다. 다른 가족의 용돈 월 합계 줄은 그 달 1일에 둡니다.
    val grid = remember(ym) { Stats.calendarRange(ym).let { (a, b) -> com.jeiel85.doran.logic.Period(ym, a, b) } }
    val gridAll = remember(s.version, grid, id.myMemberId) { Stats.entries(s, grid, id.myMemberId, summaryAt = range.startMs) }
    val all = remember(gridAll, range) { gridAll.filter { it.txn.at in range } }
    fun keep(e: Entry) = (memberFilter == null || e.txn.memberId == memberFilter) && (kindFilter == null || e.txn.kind.code == kindFilter)
    val filtered = all.filter(::keep)
    // '이체'를 고르면 달력에 옮긴 돈을(합계에서 뺀 가계부 통장끼리 옮긴 출금 포함) 그 색으로 보입니다.
    val movedView = kindFilter == "t"
    val byDay = remember(gridAll, memberFilter, kindFilter) {
        if (movedView) Stats.byDayMoved(gridAll.filter { memberFilter == null || it.txn.memberId == memberFilter })
        else Stats.byDay(gridAll.filter(::keep))
    }
    // 이대로면 예산을 넘을 날(D-35): 홈 첫 카드와 같은 셈. 사람·종류로 거른 화면이어도 가족 전체 예산 기준입니다.
    val settings by Hub.settings.collectAsStateWithLifecycle()
    val overDay = v.overDay(listOf(s.version, LocalDate.now(Fmt.zone), settings.monthStartDay, settings.showSenders, id.myMemberId)) {
        Stats.paceNow(s, LocalDate.now(Fmt.zone), Stats.viewStart(s, settings.monthStartDay), id.myMemberId, settings.showSenders)?.overDay
    }
    // 앞으로 나갈 고정지출(정기결제)을 그날 칸에 흐리게(머니차곡 '예정 표시'). 수입만 보거나 이체만 볼 때는 감춥니다.
    val today = LocalDate.now(Fmt.zone)
    val planned = remember(s.version, grid, today, memberFilter, kindFilter) {
        if (kindFilter != null && kindFilter != "e") emptyMap()
        else Stats.ahead(s, maxOf(today, grid.start), grid.end.minusDays(1))
            .filter { memberFilter == null || it.first.memberId == memberFilter }.groupBy({ it.second }, { it.first })
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { month = ym.minusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "이전 달", tint = P.c.ink) }
            // 달 이름을 누르면 오늘로 돌아옵니다.
            Text(Fmt.month(ym), style = MaterialTheme.typography.titleLarge, color = P.c.ink,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                    month = YearMonth.now(Fmt.zone).toString(); selected = LocalDate.now(Fmt.zone).toString()
                }.padding(horizontal = 4.dp, vertical = 2.dp))
            IconButton(onClick = { month = ym.plusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "다음 달", tint = P.c.ink) }
            Spacer(Modifier.weight(1f))
            Segmented(listOf("달력", "목록"), if (calendar) 0 else 1) { calendar = it == 0 }
            IconButton(onClick = { nav.go(Route.Search) }) { Icon(Icons.Rounded.Search, "검색", tint = P.c.ink) }
        }
        val ex = Stats.expense(filtered)
        val inc = Stats.income(filtered)
        if (movedView) StatStrip(listOf(
            Stat("옮긴 돈", Fmt.won(byDay.filterKeys { YearMonth.from(it) == ym }.values.sumOf { it.first }), P.c.transfer),
            Stat("합계에서", "빠짐"),
        ), Modifier.padding(horizontal = 14.dp, vertical = 6.dp), background = P.c.surface)
        else StatStrip(listOf(
            Stat("지출", Fmt.won(ex), P.c.expense),
            Stat("수입", "+" + Fmt.won(inc), P.c.income),
            Stat("합계", (if (inc - ex > 0) "+" else "") + Fmt.won(inc - ex)),
        ), Modifier.padding(horizontal = 14.dp, vertical = 6.dp), background = P.c.surface)
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
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = navBarBottom(120.dp))) {
                item {
                    // 옆으로 밀어 앞뒤 달로 넘깁니다(화살표와 같음).
                    val swipe = with(LocalDensity.current) { 56.dp.toPx() }
                    SoftCard(
                        Modifier.pointerInput(ym) {
                            var dx = 0f
                            detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = {
                                if (dx > swipe) month = ym.minusMonths(1).toString()
                                else if (dx < -swipe) month = ym.plusMonths(1).toString()
                            }) { change, amount -> dx += amount; change.consume() }
                        },
                        padding = PaddingValues(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 6.dp),
                    ) {
                        CalendarGrid(ym, byDay, sel, oneKind = kindFilter != null, moved = movedView, planned = planned.mapValues { (_, l) -> l.sumOf { it.amount } }, overDay = overDay) { d -> month = YearMonth.from(d).toString(); selected = d.toString() }
                    }
                }
                if (sel != null) {
                    item {
                        val (ex, inc) = byDay[sel] ?: (0L to 0L)
                        val (week, weekSum) = Stats.week(byDay, sel)
                        Row(Modifier.fillMaxWidth().padding(top = 18.dp, start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(Fmt.dayLabel(sel), style = MaterialTheme.typography.titleMedium, color = P.c.ink)
                                if (sel == overDay) Text("지금 속도면 이날 이 달 예산을 넘어요", style = MaterialTheme.typography.bodySmall, color = P.c.expense)
                                Text(if (movedView) (if (ex > 0) "옮긴 돈 ${Fmt.won(ex)}" else "옮긴 돈 없음")
                                    else listOfNotNull(if (ex > 0) "지출 ${Fmt.won(ex)}" else null, if (inc > 0) "수입 ${Fmt.won(inc)}" else null)
                                    .joinToString(" · ").ifEmpty { "지출·수입 없음" }, style = MaterialTheme.typography.bodySmall.merge(Money), color = P.c.inkSoft)
                                // 그 주(달력 한 줄) 합계: 하루하루보다 한 주 단위로 씀씀이를 보는 경우가 많아서.
                                Text("${week.start.monthValue}/${week.start.dayOfMonth}~${week.endInclusive.monthValue}/${week.endInclusive.dayOfMonth} 한 주 · " +
                                    (if (movedView) "옮긴 돈 ${Fmt.won(weekSum.first)}"
                                    else listOfNotNull("지출 ${Fmt.won(weekSum.first)}", if (weekSum.second > 0) "수입 ${Fmt.won(weekSum.second)}" else null).joinToString(" · ")),
                                    style = MaterialTheme.typography.bodySmall.merge(Money), color = P.c.inkFaint)
                            }
                            SmallButton("이날 쓰기") { nav.go(Route.Entry(date = sel)) }
                        }
                    }
                    // 고른 날에 잡힌 정기결제(아직 안 나감).
                    planned[sel].orEmpty().forEach { rule -> item(key = "p${rule.id}") {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { nav.go(Route.RecurringEdit(rule.id)) }
                            .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(rule.name, style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft)
                                Text("나갈 예정 · 정기결제", style = MaterialTheme.typography.bodySmall, color = P.c.inkFaint)
                            }
                            Text("-" + Fmt.won(rule.amount), style = MaterialTheme.typography.bodyMedium.merge(Money), color = P.c.inkFaint)
                        }
                    } }
                    if (dayEntries.isEmpty() && planned[sel].isNullOrEmpty()) item {
                        Text("이날 기록이 없어요", style = MaterialTheme.typography.bodyMedium, color = P.c.inkFaint,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp), textAlign = TextAlign.Center)
                    }
                    items(dayEntries, key = { it.txn.id }) { e ->
                        TxnRow(e, s, e.txn.id in unseen) { open(nav, e) }
                    }
                }
            }
        } else {
            val days = filtered.groupBy { Fmt.date(it.txn.at) }.toSortedMap(compareByDescending { it })
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = navBarBottom(120.dp))) {
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

/**
 * 월 달력(일요일 시작, DECISIONS.md D-30 — 비주얼 가계부의 달력을 따름). 날짜 칸마다 위에 날짜, 아래 두 줄에 지출(빨강, "-")과
 * 수입(파랑, "+")을 오른쪽 맞춤으로 둡니다. 두 줄은 자리가 정해져 있어 한 주를 가로로 훑을 때 지출끼리, 수입끼리 줄이 맞습니다.
 * 금액은 [Fmt.cell](100만 원 아래는 원 단위)로 쓰고, 칸보다 길면 글자가 줄어듭니다. [oneKind](지출이나 수입만 골라 봄)면 한 줄만 크게 씁니다.
 * 주마다 가는 줄로 나누고, 고른 날은 칠하지 않고 옅은 바탕과 테두리로만 표시해 금액 색이 그대로 보이게 합니다.
 * 앞뒤 달 날짜도 금액까지 흐리게 보여 주고([byDay]는 [Stats.calendarRange] 범위), 누르면 그 달로 갑니다.
 */
@Composable
fun CalendarGrid(ym: YearMonth, byDay: Map<LocalDate, Pair<Long, Long>>, selected: LocalDate?, oneKind: Boolean = false, moved: Boolean = false,
                 planned: Map<LocalDate, Long> = emptyMap(), overDay: LocalDate? = null, onSelect: (LocalDate) -> Unit) {
    val (start, end) = Stats.calendarRange(ym)
    val rows = (java.time.temporal.ChronoUnit.DAYS.between(start, end) / 7).toInt()
    val today = LocalDate.now(Fmt.zone)
    // 시스템 글꼴을 키우면 줄·칸 높이도 같이 늘려 금액 위아래가 잘리지 않게 합니다(#66 리뷰).
    val density = LocalDensity.current
    val dims = remember(density, oneKind) { CalendarSize.of(oneKind) { sp -> with(density) { sp.sp.toDp().value } } }
    Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, d ->
                Text(d, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium,
                    color = when (i) { 0 -> P.c.expense; 6 -> P.c.income; else -> P.c.inkSoft })
            }
        }
        for (r in 0 until rows) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(P.c.line))
            Row(Modifier.fillMaxWidth().height(dims.row.dp)) {
                for (c in 0 until 7) {
                    val d = start.plusDays((r * 7 + c).toLong())
                    DayCell(d, YearMonth.from(d) == ym, byDay[d], planned[d] ?: 0, d == overDay, d == selected, d == today, oneKind, moved, dims, Modifier.weight(1f).fillMaxHeight()) { onSelect(d) }
                }
            }
        }
    }
}

/**
 * 달력 칸 크기(dp): 날짜 동그라미, 금액 한 줄, 한 주 줄. [spToDp]로 글자 크기(sp)를 dp로 바꿔(시스템 글꼴 배율 반영) 글자가 커지면
 * 같이 늘어납니다 — 고정 dp 높이에 sp 글자를 넣으면 큰 글꼴에서 금액 위아래가 잘립니다(#66 리뷰). 기본 배율에서는 20·14(한 종류면 16)·62.
 */
object CalendarSize {
    data class Dims(val dayBox: Float, val line: Float, val row: Float)

    /** 금액 글씨의 줄 높이(sp): [CellMoney]·[CellMoneyBig]와 같아야 합니다. */
    const val LINE_SP = 13f
    const val LINE_BIG_SP = 15f

    fun of(oneKind: Boolean, spToDp: (Float) -> Float): Dims {
        val line = spToDp(if (oneKind) LINE_BIG_SP else LINE_SP) + 1f
        val dayBox = maxOf(20f, spToDp(16f) + 4f) // 날짜 12sp의 줄 높이 16sp + 여백
        return Dims(dayBox, line, maxOf(62f, dayBox + line * (if (oneKind) 1 else 2) + 10f))
    }
}

/** 달력 칸의 금액 글씨: 고정폭 숫자, 좁은 칸에서도 읽히도록 굵기는 한 단계 낮춤. 한 종류만 볼 때는 한 줄이라 더 크게. */
private val CellMoney = Money.copy(fontSize = 10.5.sp, lineHeight = CalendarSize.LINE_SP.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
private val CellMoneyBig = Money.copy(fontSize = 12.5.sp, lineHeight = CalendarSize.LINE_BIG_SP.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)

@Composable
private fun DayCell(
    d: LocalDate, inMonth: Boolean, sums: Pair<Long, Long>?, planned: Long, isOver: Boolean, isSel: Boolean, isToday: Boolean, oneKind: Boolean, moved: Boolean,
    dims: CalendarSize.Dims, modifier: Modifier, onClick: () -> Unit,
) {
    val (ex, inc) = sums ?: (0L to 0L)
    // 아직 안 나간 정기결제: 쓴 돈이 없는 칸에만 지출 자리에 흐린 회색으로.
    val plannedText = if (ex == 0L && planned > 0) "-" + Fmt.cell(planned) else null
    // 화면 읽기: 칸 하나를 "11월 27일 금요일, 지출 12,000원, 선택됨"으로 묶어 읽습니다(전에는 숫자만 따로 읽혔어요).
    val spoken = buildString {
        append("${d.monthValue}월 ${d.dayOfMonth}일 ${d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.KOREAN)}")
        if (isToday) append(", 오늘")
        if (ex > 0) append(if (moved) ", 옮긴 돈 ${Fmt.won(ex)}" else ", 지출 ${Fmt.won(ex)}")
        if (inc > 0) append(", 수입 ${Fmt.won(inc)}")
        if (plannedText != null) append(", 나갈 예정 ${Fmt.won(planned)}")
        if (isOver) append(", 지금 속도면 이날 예산을 넘어요")
        if (ex == 0L && inc == 0L && plannedText == null) append(", 기록 없음")
    }
    // 앞뒤 달 날짜는 금액까지 흐리게(비주얼 가계부처럼) — 이 달 것과 섞여 보이지 않게.
    val fade = if (inMonth) 1f else 0.4f
    // 옮긴 돈 보기에서는 부호 없이 이체 색으로(쓴 돈이 아니라서).
    val exText = if (ex > 0) (if (moved) "" else "-") + Fmt.cell(ex) else null
    val incText = if (inc > 0) "+" + Fmt.cell(inc) else null
    val shape = RoundedCornerShape(10.dp)
    val overColor = P.c.expense
    Column(
        modifier.padding(1.dp).clip(shape)
            .then(if (isSel) Modifier.background(P.c.pineSoft).border(1.5.dp, P.c.pine, shape) else Modifier)
            // 예상 초과일: 칸 아래에 빨간 줄(구성안 결정 3).
            .then(if (isOver) Modifier.drawBehind { drawRect(overColor, Offset(0f, size.height - 3.dp.toPx()), Size(size.width, 3.dp.toPx())) } else Modifier)
            .clickable(onClick = onClick)
            .clearAndSetSemantics { contentDescription = spoken; selected = isSel; role = Role.Button }
            .padding(horizontal = 3.dp, vertical = 3.dp),
    ) {
        Box(
            Modifier.size(dims.dayBox.dp).then(if (isToday) Modifier.clip(CircleShape).background(P.c.pine) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${d.dayOfMonth}", fontSize = 12.sp, fontWeight = if (isToday) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = when {
                    isToday -> Color.White
                    !inMonth -> P.c.inkFaint.copy(alpha = 0.6f)
                    d.dayOfWeek == DayOfWeek.SUNDAY -> P.c.expense
                    d.dayOfWeek == DayOfWeek.SATURDAY -> P.c.income
                    else -> P.c.ink
                },
            )
        }
        Spacer(Modifier.weight(1f))
        val expense = (if (moved) P.c.transfer else P.c.expense).copy(alpha = fade)
        val income = P.c.income.copy(alpha = fade)
        if (oneKind) {
            Box(Modifier.fillMaxWidth().height(dims.line.dp)) {
                when {
                    exText != null -> FitText(exText, CellMoneyBig, expense, minSize = 8.sp, textAlign = TextAlign.End)
                    plannedText != null -> FitText(plannedText, CellMoneyBig, P.c.inkFaint.copy(alpha = 0.8f * fade), minSize = 8.sp, textAlign = TextAlign.End)
                    incText != null -> FitText(incText, CellMoneyBig, income, minSize = 8.sp, textAlign = TextAlign.End)
                }
            }
        } else {
            Box(Modifier.fillMaxWidth().height(dims.line.dp)) {
                if (exText != null) FitText(exText, CellMoney, expense, minSize = 7.5.sp, textAlign = TextAlign.End)
                else if (plannedText != null) FitText(plannedText, CellMoney, P.c.inkFaint.copy(alpha = 0.8f * fade), minSize = 7.5.sp, textAlign = TextAlign.End)
            }
            Box(Modifier.fillMaxWidth().height(dims.line.dp)) { if (incText != null) FitText(incText, CellMoney, income, minSize = 7.5.sp, textAlign = TextAlign.End) }
        }
    }
}
