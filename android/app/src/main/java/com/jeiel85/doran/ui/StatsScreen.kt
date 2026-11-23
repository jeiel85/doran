package com.jeiel85.doran.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeiel85.doran.R
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.model.Kind
import java.time.YearMonth
import kotlin.math.roundToInt

@Composable
fun StatsScreen(nav: Nav) {
    val s by Hub.ledger.state.collectAsStateWithLifecycle()
    val id by Hub.identity.state.collectAsStateWithLifecycle()
    val settings by Hub.settings.collectAsStateWithLifecycle()
    val start = Stats.viewStart(s, settings.monthStartDay)
    val cur = remember(start) { Stats.current(s, startDay = start) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var mode by rememberSaveable { mutableStateOf(0) } // 0 지출, 1 수입
    val p = remember(cur, offset, start) { Stats.period(cur.label.minusMonths(offset.toLong()), start) }
    val entries = remember(s.version, p, id.myMemberId) { Stats.entries(s, p, id.myMemberId) }
    val kind = if (mode == 0) Kind.EXPENSE else Kind.INCOME
    val cats = remember(entries, kind) { Stats.byCategory(entries, kind) }
    val total = cats.sumOf { it.second }
    val prev = remember(s.version, p, id.myMemberId) {
        Stats.entries(s, Stats.period(p.label.minusMonths(1), start), id.myMemberId)
    }
    val prevTotal = if (kind == Kind.EXPENSE) Stats.expense(prev) else Stats.income(prev)
    val trend = remember(s.version, p) {
        val now = Stats.current(s, startDay = start)
        (5 downTo 0).map { back ->
            val pp = Stats.period(p.label.minusMonths(back.toLong()), start)
            if (pp.label.isAfter(now.label)) pp to 0L
            else pp to Stats.entries(s, pp, id.myMemberId).let { if (kind == Kind.EXPENSE) Stats.expense(it) else Stats.income(it) }
        }
    }
    val byMember = Stats.byMember(entries, kind, s)
    val top = Stats.topMerchants(entries)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp)) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { offset++ }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "이전", tint = P.c.ink) }
                Text(Fmt.month(p.label), style = MaterialTheme.typography.titleLarge, color = P.c.ink)
                IconButton(onClick = { if (offset > 0) offset-- }, enabled = offset > 0) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "다음", tint = if (offset > 0) P.c.ink else P.c.line)
                }
                Spacer(Modifier.weight(1f))
                Segmented(listOf("지출", "수입"), mode) { mode = it }
            }
        }
        item {
            SoftCard {
                Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
                    Donut(cats.take(chartColors.size).mapIndexed { i, (_, v) -> chartColors[i] to v }, Modifier.size(220.dp), thickness = 26.dp) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (kind == Kind.EXPENSE) "쓴 돈" else "번 돈", style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                            Text(Fmt.won(total), style = MaterialTheme.typography.titleLarge.merge(Money), color = P.c.ink)
                            if (prevTotal > 0) {
                                val diff = total - prevTotal
                                Text((if (diff >= 0) "지난달보다 +" else "지난달보다 -") + Fmt.compact(kotlin.math.abs(diff)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if ((diff > 0) == (kind == Kind.EXPENSE)) P.c.expense else P.c.pine)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (cats.isEmpty()) Text("이 달 내역이 없어요", style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft)
                cats.forEachIndexed { i, (cid, v) ->
                    val cat = s.category(cid)
                    Row(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .clickable { nav.go(Route.CategoryTxns(cid, p.label.toString())) }.padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(chartColors.getOrElse(i) { P.c.inkFaint }))
                        Spacer(Modifier.width(10.dp))
                        CatTile(cat?.icon, 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(cat?.name ?: "기타", style = MaterialTheme.typography.titleSmall, color = P.c.ink, modifier = Modifier.weight(1f))
                        Text("${(v * 100f / total.coerceAtLeast(1)).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = P.c.inkFaint)
                        Spacer(Modifier.width(10.dp))
                        Text(Fmt.won(v), style = MaterialTheme.typography.titleSmall.merge(Money), color = P.c.ink)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)); SectionTitle("6개월 흐름") }
        item {
            SoftCard {
                Bars(trend.map { it.second }, trend.map { "${it.first.label.monthValue}월" }, color = if (kind == Kind.EXPENSE) P.c.pine else P.c.income)
                Spacer(Modifier.height(8.dp))
                val avg = trend.dropLast(1).map { it.second }.filter { it > 0 }.let { if (it.isEmpty()) 0 else it.sum() / it.size }
                if (avg > 0) Text("지난 달들 평균 ${Fmt.won(avg)}", style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
            }
        }
        if (byMember.isNotEmpty()) {
            item { Spacer(Modifier.height(20.dp)); SectionTitle("사람별") }
            item {
                SoftCard {
                    val sum = byMember.sumOf { it.second }.coerceAtLeast(1)
                    // 한 줄 띠 그래프
                    Row(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape)) {
                        byMember.forEach { (mid, v) ->
                            Box(Modifier.weight(v.toFloat() / sum).fillMaxSize().background(memberColor(s.member(mid)?.color ?: 5)))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    byMember.forEach { (mid, v) ->
                        val m = s.member(mid)
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            MemberAvatar(m, 28.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(if (mid == Stats.FAMILY) "가족 공용 (가족 카드)" else m?.name?.plus(if (m.isSender) " (보낸 가족)" else "") ?: "공동",
                                style = MaterialTheme.typography.titleSmall,
                                color = P.c.ink, modifier = Modifier.weight(1f))
                            Text("${(v * 100 / sum)}%", style = MaterialTheme.typography.bodySmall, color = P.c.inkFaint)
                            Spacer(Modifier.width(10.dp))
                            Text(Fmt.won(v), style = MaterialTheme.typography.titleSmall.merge(Money), color = P.c.ink)
                        }
                    }
                }
            }
        }
        if (kind == Kind.EXPENSE && top.isNotEmpty()) {
            item { Spacer(Modifier.height(20.dp)); SectionTitle("많이 쓴 곳") }
            item {
                SoftCard {
                    top.forEachIndexed { i, (m, v) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", style = MaterialTheme.typography.titleSmall, color = P.c.apricot, modifier = Modifier.width(22.dp))
                            Text(m, style = MaterialTheme.typography.titleSmall, color = P.c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(Fmt.won(v), style = MaterialTheme.typography.titleSmall.merge(Money), color = P.c.ink)
                        }
                    }
                }
            }
        }
        if (entries.isEmpty()) item { EmptyState(R.drawable.art_empty, "통계를 보여 줄 내역이 없어요", "내역이 쌓이면 어디에 많이 쓰는지 알려 드려요") }
    }
}
