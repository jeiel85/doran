package com.jeiel85.doran.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeiel85.doran.R
import com.jeiel85.doran.capture.CaptureService
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.model.AccountType
import com.jeiel85.doran.model.Kind
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: Nav) {
    val s by Hub.ledger.state.collectAsStateWithLifecycle()
    val id by Hub.identity.state.collectAsStateWithLifecycle()
    val unseen by Hub.local.unseen.collectAsStateWithLifecycle()
    val sync by Hub.sync.status.collectAsStateWithLifecycle()
    val settings by Hub.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val tick = rememberResumeTick()
    val captureOn = remember(tick) { CaptureService.isEnabled(context) }

    val today = LocalDate.now(Fmt.zone)
    val start = Stats.viewStart(s, settings.monthStartDay)
    val p = remember(start, today) { Stats.current(s, today, start) }
    val entries = remember(s.version, p, id.myMemberId, settings.showSenders) { Stats.entries(s, p, id.myMemberId, settings.showSenders) }
    val spent = Stats.expense(entries)
    val income = Stats.income(entries)
    val (totalBudget, budgetLines) = remember(s.version, entries) { Stats.budgets(s, entries, Stats.carryIn(s, p, id.myMemberId, settings.showSenders)) }
    val fresh = remember(s.version, unseen) {
        s.txns.values.filter { it.id in unseen }.sortedByDescending { it.at }.take(5)
    }
    val members = Stats.byMember(entries, s = s)
    val cards = remember(s.version) {
        s.accounts.values.filter { !it.hidden && (it.type == AccountType.CARD || it.type == AccountType.CHECK) }
            .sortedBy { it.order }.map { Stats.card(s, it, today) }
    }
    val upcoming = remember(s.version) { Stats.upcoming(s, 7, today) }
    // 이 달이 끝나기 전에 나갈 고정지출: 예산 게이지에서 따로 보여 '실제로 쓸 수 있는 돈'을 가려냅니다.
    // 지난달 같은 때까지 쓴 돈. 지난달 기록이 하나도 없으면(첫 달) 비교하지 않습니다.
    val lastSame = remember(s.version, p, today, id.myMemberId, settings.showSenders) {
        val lp = Stats.sameTimeLastPeriod(p, today, start)
        Stats.entries(s, lp, id.myMemberId, settings.showSenders).takeIf { e -> e.any { it.counted } }?.let { Stats.expense(it) }
    }
    val aheadList = remember(s.version, p, today, settings.showSenders) { Stats.ahead(s, today, p.end.minusDays(1), settings.showSenders) }
    val reserved = aheadList.sumOf { it.first.amount }
    // 이번 달 페이스(D-35): 월말 예상·예상 초과일·평소 하루 평균. 첫 3일은 null.
    val pace = remember(entries, aheadList, p, today, totalBudget) { Stats.pace(entries, aheadList, p, today, totalBudget?.budget ?: 0) }
    val goals = s.goals.values.filter { !it.done }.sortedBy { it.order }.take(3)
    val me = s.member(id.myMemberId)
    val allowanceFirst = me != null && me.sharesPicked && me.allowance > 0
    var showFamily by rememberSaveable { mutableStateOf(false) }

    if (!id.inHousehold) {
        SenderHome(nav)
        return
    }

    val refreshScope = rememberCoroutineScope()
    val update by com.jeiel85.doran.update.SelfUpdate.state.collectAsStateWithLifecycle()
    PullToRefreshBox(isRefreshing = sync.running, onRefresh = { refreshScope.launch { Hub.sync.sync() } }, modifier = Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = navBarBottom(120.dp))) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.household.name, style = MaterialTheme.typography.headlineSmall, color = P.c.ink)
                    Text("${Fmt.month(p.label)} · ${p.start.monthValue}.${p.start.dayOfMonth} ~ ${p.end.minusDays(1).monthValue}.${p.end.minusDays(1).dayOfMonth}",
                        style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                }
                // 가족 얼굴 묶음 → 가족 화면
                Row(Modifier.clip(RoundedCornerShape(20.dp)).clickable { nav.go(Route.Family) }.padding(6.dp)) {
                    s.activeMembers.take(4).forEachIndexed { i, m ->
                        Box(Modifier.offset(x = (-8 * i).dp).size(32.dp).clip(CircleShape).background(P.c.bg).padding(2.dp)) { MemberAvatar(m, 28.dp) }
                    }
                }
                IconButton(onClick = { nav.go(Route.Search) }) { Icon(Icons.Rounded.Search, "검색", tint = P.c.ink) }
                IconButton(onClick = { nav.go(Route.Settings) }) { Icon(Icons.Rounded.Settings, "설정", tint = P.c.ink) }
            }
        }

        updateBanner(update)?.let { (title, sub) ->
            item {
                SoftCard(onClick = {
                    when (val u = update) {
                        is com.jeiel85.doran.update.UpdateState.NeedsConfirm ->
                            runCatching { context.startActivity(u.confirm.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        is com.jeiel85.doran.update.UpdateState.Available, is com.jeiel85.doran.update.UpdateState.StoreUpdate ->
                            refreshScope.launch { com.jeiel85.doran.update.SelfUpdate.run(context, userInitiated = true) }
                        else -> Unit
                    }
                }, color = P.c.incomeSoft, padding = PaddingValues(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.SystemUpdate, null, tint = P.c.income)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleSmall, color = P.c.ink)
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
        }

        // '정한 결제만 보내기'이고 월 용돈이 있는 사람(남편 폰)은 맨 위가 내 용돈입니다. 가계부 전체 합계는 아내 통장 출금이 대부분이라
        // 내 돈처럼 읽히면 크게 틀려 보여서, 그 아래 한 줄로 두고 누르면 펼칩니다(D-28 A). '함께 관리'하는 사람은 그대로.
        if (allowanceFirst && me != null) {
            item {
                val ap = remember(s.version, today, me.id) { Stats.allowancePeriod(s, me.id, today) }
                val mine = remember(s.version, ap) { Stats.allowanceSpent(s, me.id, ap) }
                AllowanceHero(me.allowance, mine, ap, today, onClick = { nav.go(Route.Allowance) })
            }
            item {
                Spacer(Modifier.height(12.dp))
                SoftCard(onClick = { showFamily = !showFamily }, padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("가족 가계부 · ${Fmt.month(p.label)}", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
                            Text("쓴 돈 ${Fmt.won(spent)} · 수입 ${Fmt.won(income)}", style = MaterialTheme.typography.bodySmall.merge(Money), color = P.c.inkSoft)
                        }
                        Text(if (showFamily) "접기" else "펼치기", style = MaterialTheme.typography.labelMedium, color = P.c.pine)
                    }
                }
            }
            if (showFamily) item { Spacer(Modifier.height(12.dp)); Hero(spent, income, totalBudget?.budget ?: 0, reserved, lastSame, pace, p, today) { nav.go(Route.Budget) } }
        } else item { Hero(spent, income, totalBudget?.budget ?: 0, reserved, lastSame, pace, p, today) { nav.go(Route.Budget) } }
        // 정기결제 제안: 두 달 넘게 같은 가게·비슷한 금액·비슷한 날이면 등록할지 묻습니다.
        item { SuggestCard(s, today, nav) }
        // 두 폰이 각자 기록해 두 번 들어간 같은 결제(#64)
        item { DuplicatesCard(s) }
        // 월말 마감(한 달 끝 3일·다음 달 첫 3일): 수입 → 빠진 지출 → 고정지출 → 저축 순서로 점검.
        com.jeiel85.doran.logic.Review.closingPeriod(today, start)?.let { cp ->
            if (!Hub.local.checked("closed.${cp.key}")) item { Spacer(Modifier.height(14.dp)); ClosingCard(s, cp, today, id.myMemberId, settings.showSenders, nav) }
        }

        if (s.activeMembers.count { !it.isSender } <= 1) item {
            Spacer(Modifier.height(14.dp))
            SoftCard(onClick = { nav.go(Route.Invite("member")) }, color = P.c.pineSoft, padding = PaddingValues(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.art_family), null, Modifier.size(64.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("배우자를 초대해 함께 써요", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
                        Text("QR을 띄우면 배우자 폰에서 찍기만 하면 돼요", style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                    }
                    SmallButton("QR 띄우기") { nav.go(Route.Invite("member")) }
                }
            }
        }

        item { FailedRecordsCard(nav) }
        item { StaleMovesCard(nav) }
        item { GapsCard(nav) }
        item { CandidatesCard(nav) }

        if (!captureOn) item {
            Spacer(Modifier.height(14.dp))
            SoftCard(onClick = { nav.go(Route.Capture) }, color = P.c.apricotSoft, padding = PaddingValues(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.art_capture), null, Modifier.size(64.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("카드 알림을 자동으로 기록할까요?", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
                        Text("알림 접근을 허용하면 결제하자마자 장부에 들어가요", style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                    }
                    Icon(Icons.Rounded.NotificationsActive, null, tint = P.c.apricot)
                }
            }
        }

        if (fresh.isNotEmpty()) {
            item { Spacer(Modifier.height(20.dp)); SectionTitle("새로 들어온 내역", action = "모두 확인") { Hub.local.markAllSeen() } }
            item {
                SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                    val c = s.canceled
                    fresh.forEach { t ->
                        val net = (t.amount - (c[t.id] ?: 0)).coerceAtLeast(0)
                        TxnRow(com.jeiel85.doran.logic.Entry(t, net, net == 0L && c[t.id] != null, counted = Stats.counts(s, t)), s, isNew = true, showDate = false) {
                            Hub.local.markSeen(listOf(t.id)); nav.go(Route.Entry(txnId = t.id))
                        }
                    }
                }
            }
        }

        if (members.size > 1 || s.activeMembers.size > 1) {
            item { Spacer(Modifier.height(20.dp)); SectionTitle("가족별 지출", action = "가족", onAction = { nav.go(Route.Family) }) }
            item {
                SoftCard {
                    val max = members.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
                    if (members.isEmpty()) Text("이번 달 지출이 아직 없어요", style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft)
                    members.forEachIndexed { i, (mid, v) ->
                        val m = s.member(mid)
                        if (i > 0) Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MemberAvatar(m, 34.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row {
                                    Text(if (mid == Stats.FAMILY) "가족 공용 (가족 카드)" else m?.name ?: "공동",
                                        style = MaterialTheme.typography.titleSmall, color = P.c.ink, modifier = Modifier.weight(1f))
                                    Text(Fmt.won(v), style = MaterialTheme.typography.titleSmall.merge(Money), color = P.c.ink)
                                }
                                Spacer(Modifier.height(6.dp))
                                Progress(v.toFloat() / max, memberColor(m?.color ?: 5), height = 8.dp)
                            }
                        }
                    }
                }
            }
        }

        if (cards.isNotEmpty()) {
            item { Spacer(Modifier.height(20.dp)); SectionTitle("카드", action = "자산", onAction = { nav.tab = Tab.ASSETS }) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(cards, key = { it.account.id }) { c -> CardTile(c, s.member(c.account.ownerId)) { nav.go(Route.AccountDetail(c.account.id)) } }
                }
            }
        }

        if (budgetLines.isNotEmpty()) {
            item { Spacer(Modifier.height(20.dp)); SectionTitle("카테고리 예산", action = "예산", onAction = { nav.go(Route.Budget) }) }
            item {
                SoftCard {
                    budgetLines.take(4).forEachIndexed { i, b ->
                        if (i > 0) Spacer(Modifier.height(14.dp))
                        val cat = s.category(b.categoryId)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CatTile(cat?.icon, 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row {
                                    Text(cat?.name ?: "", style = MaterialTheme.typography.titleSmall, color = P.c.ink, modifier = Modifier.weight(1f))
                                    Text("${Fmt.num(b.spent)} / ${Fmt.compact(b.budget)}", style = MaterialTheme.typography.bodySmall.merge(Money),
                                        color = if (b.ratio > 1) P.c.expense else P.c.inkSoft)
                                }
                                Spacer(Modifier.height(6.dp))
                                Progress(b.ratio, if (b.ratio > 0.8f) P.c.apricot else P.c.pine, height = 8.dp)
                            }
                        }
                    }
                }
            }
        }

        if (me != null && me.allowance > 0 && !allowanceFirst) item {
            Spacer(Modifier.height(20.dp))
            // 남은 용돈은 용돈이 들어온 날부터(주말이면 앞 금요일, D-27). 가계부 한 달과 날짜가 다를 수 있어요.
            val ap = remember(s.version, today, me.id) { Stats.allowancePeriod(s, me.id, today) }
            val mine = remember(s.version, ap) { Stats.allowanceSpent(s, me.id, ap) }
            SoftCard(onClick = { nav.go(Route.Allowance) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CatTile("allowance", 44.dp, P.c.apricotSoft)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("내 용돈", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
                        Text("${ap.start.monthValue}.${ap.start.dayOfMonth} ~ ${ap.end.minusDays(1).monthValue}.${ap.end.minusDays(1).dayOfMonth} · 가족에게는 월 합계만 보여요",
                            style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Fmt.won(me.allowance - mine), style = MaterialTheme.typography.titleMedium.merge(Money), color = P.c.ink)
                        Text("남음", style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Progress(mine.toFloat() / me.allowance, P.c.apricot, height = 8.dp)
            }
        }

        if (upcoming.isNotEmpty()) {
            val shown = upcoming.take(4)
            // 고정 규칙은 수입(월급 등)일 수도 있어, 제목을 보이는 내역의 종류에 맞춥니다.
            val title = "다가오는 ${com.jeiel85.doran.logic.Fixed.label(shown.map { it.first.kind })}"
            item { Spacer(Modifier.height(20.dp)); SectionTitle(title, action = "관리", onAction = { nav.go(Route.Recurring) }) }
            item {
                SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                    shown.forEach { (r, d) ->
                        val days = ChronoUnit.DAYS.between(today, d)
                        ListRow(r.name, "${d.monthValue}월 ${d.dayOfMonth}일 · ${if (days == 1L) "내일" else "${days}일 뒤"}",
                            leading = { CatTile(s.category(r.categoryId)?.icon, 40.dp) }) {
                            Amount(r.amount, r.kind, MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }

        if (goals.isNotEmpty()) {
            item { Spacer(Modifier.height(20.dp)); SectionTitle("저축 목표", action = "전체", onAction = { nav.go(Route.Goals) }) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(goals, key = { it.id }) { g ->
                        val saved = Stats.goalSaved(s, g)
                        SoftCard(Modifier.width(200.dp), onClick = { nav.go(Route.GoalEdit(g.id)) }) {
                            CatTile(g.icon, 40.dp, P.c.pineSoft)
                            Spacer(Modifier.height(10.dp))
                            Text(g.name, style = MaterialTheme.typography.titleSmall, color = P.c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${Fmt.won(saved)} / ${Fmt.compact(g.target)}", style = MaterialTheme.typography.bodySmall.merge(Money), color = P.c.inkSoft)
                            Spacer(Modifier.height(8.dp))
                            Progress(if (g.target > 0) saved.toFloat() / g.target else 0f, P.c.pine, height = 8.dp)
                        }
                    }
                }
            }
        }

        if (entries.isEmpty()) item {
            Spacer(Modifier.height(16.dp))
            EmptyState(R.drawable.art_empty, "이번 달 내역이 아직 없어요", "가운데 + 버튼으로 첫 내역을 남기거나\n카드 알림 자동 기록을 켜 보세요")
        } else {
            val recent = entries.filter { !it.allowanceSummary }.take(6)
            item { Spacer(Modifier.height(20.dp)); SectionTitle("최근 내역", action = "전체", onAction = { nav.tab = Tab.LEDGER }) }
            item {
                SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                    recent.forEach { e -> TxnRow(e, s, e.txn.id in unseen, showDate = true) { nav.go(Route.Entry(txnId = e.txn.id)) } }
                }
            }
        }

        item { Spacer(Modifier.height(18.dp)); SyncLine(sync, id.inHousehold) { Hub.requestSync() } }
    }
    }
}

/** 새 버전이 있거나 설치 확인이 필요할 때 홈 맨 위에 띄울 문구. 앱이 떠 있으면 자동 설치를 미루므로(D-8) 여기서 누르게 합니다. */
private fun updateBanner(u: com.jeiel85.doran.update.UpdateState): Pair<String, String>? = when (u) {
    is com.jeiel85.doran.update.UpdateState.Available -> "새 버전 ${u.release.version}이 있어요" to "눌러서 바로 설치해요. 장부는 그대로예요"
    is com.jeiel85.doran.update.UpdateState.StoreUpdate -> "새 버전 ${u.version}이 나왔어요" to "눌러서 Play 스토어에서 업데이트해요. 장부는 그대로예요"
    is com.jeiel85.doran.update.UpdateState.NeedsConfirm -> "업데이트 설치를 허락해 주세요" to "${u.release.version} · 처음 한 번만 확인해요"
    is com.jeiel85.doran.update.UpdateState.Downloading -> "새 버전 받는 중 ${u.percent}%" to u.release.version
    is com.jeiel85.doran.update.UpdateState.Installing -> "업데이트 설치 중…" to "잠시 뒤 다시 열려요"
    else -> null
}

@Composable
private fun Hero(spent: Long, income: Long, budget: Long, reserved: Long, lastSame: Long?, pace: Stats.Pace?, p: com.jeiel85.doran.logic.Period,
                 today: LocalDate, onBudget: () -> Unit) {
    val end = p.end
    val daysLeft = ChronoUnit.DAYS.between(today, end).toInt().coerceAtLeast(1)
    HeroCard(PineHero, R.drawable.art_cat_savings, "이번 달 쓴 돈", Fmt.won(spent), onClick = onBudget) {
        Spacer(Modifier.height(16.dp))
        if (budget > 0) {
            val b = Stats.BudgetSplit(budget, spent, reserved)
            val left = b.left
            BudgetGauge(b, Color(0xFFFFD36B))
            Spacer(Modifier.height(10.dp))
            Text(
                if (left >= 0) "예산 ${Fmt.compact(budget)} 중 ${Fmt.won(left)} 남음"
                else "예산보다 ${Fmt.won(-left)} 더 썼어요",
                style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.9f), fontWeight = FontWeight.SemiBold,
            )
            // 남은 돈 가운데 이 달 안에 나갈 고정지출을 빼면 실제로 쓸 수 있는 돈이 됩니다.
            if (left >= 0 && reserved > 0) Text(
                // 페이스가 보이면 하루 금액은 아래 '하루 권장' 상자에 있으므로 여기서는 뺍니다.
                if (b.free >= 0) "고정지출 ${Fmt.won(reserved)} 빼면 ${Fmt.won(b.free)}" + if (pace == null) " · 하루 ${Fmt.won(b.perDay(daysLeft))}" else ""
                else "남은 고정지출 ${Fmt.won(reserved)}이 남은 예산보다 ${Fmt.won(-b.free)} 많아요",
                style = MaterialTheme.typography.bodySmall.merge(Money), color = Color.White.copy(alpha = 0.85f),
            ) else if (left >= 0 && pace == null) Text("하루 ${Fmt.won(b.perDay(daysLeft))}", style = MaterialTheme.typography.bodySmall.merge(Money),
                color = Color.White.copy(alpha = 0.85f))
        } else {
            Text("예산을 정하면 하루에 쓸 수 있는 돈을 알려 드려요 →", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
        }
        PaceBlock(pace, budget, spent, reserved, p, today, daysLeft)
        // 지난달 같은 때까지와 견줌: 한 달 전체와 견주면 월말이 아니면 늘 덜 쓴 것처럼 보여서(뱅크샐러드·똑똑 방식).
        lastSame?.let { prev ->
            val diff = spent - prev
            Spacer(Modifier.height(6.dp))
            Text(when {
                diff > 0 -> "지난달 이때보다 ${Fmt.compact(diff)}원 더 썼어요"
                diff < 0 -> "지난달 이때보다 ${Fmt.compact(-diff)}원 덜 썼어요"
                else -> "지난달 이때와 같아요"
            }, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
        }
        Spacer(Modifier.height(16.dp))
        // "남은 날"만 쓰면 월급날부터 세는지 말일까지 세는지 헷갈려서(2026-11-20 리뷰) 가계부 한 달의 끝 날을 함께 씁니다.
        val last = end.minusDays(1)
        HeroStats(listOf(
            Stat("수입", "+" + Fmt.won(income)),
            Stat("남은 돈", Fmt.won(income - spent)),
            Stat("${last.monthValue}/${last.dayOfMonth}까지", "${daysLeft}일"),
        ))
    }
}

/**
 * 이번 달 페이스(D-35): "이대로면 월말 ○ · ○일에 넘어요", 하루 권장 대 지금 하루 평균 두 상자, 앞으로 며칠 동안 쓸 수 있는 돈 한 줄.
 * 하루 권장은 위 게이지 아래 '하루 ○원'과 같은 셈(고정지출 몫을 뺀 남은 예산 ÷ 남은 날)입니다.
 */
@Composable
private fun PaceBlock(pace: Stats.Pace?, budget: Long, spent: Long, reserved: Long, p: com.jeiel85.doran.logic.Period, today: LocalDate, daysLeft: Int) {
    val white = Color.White
    if (pace == null) {
        // 첫 3일: 평균이 크게 흔들려 아직 셈하지 않습니다. 기록이 전혀 없을 때는 말하지 않습니다.
        if (spent > 0 && today.isBefore(p.start.plusDays(Stats.PACE_MIN_DAYS.toLong()))) {
            Spacer(Modifier.height(6.dp))
            Text("월말 예상은 기록이 ${Stats.PACE_MIN_DAYS}일 쌓이면 보여 드려요", style = MaterialTheme.typography.bodySmall, color = white.copy(alpha = 0.75f))
        }
        return
    }
    Spacer(Modifier.height(6.dp))
    val projected = "이대로면 월말 ${Fmt.compact(pace.projected)}"
    Text(when {
        budget <= 0 -> projected
        pace.overDay != null -> "$projected · ${pace.overDay.monthValue}/${pace.overDay.dayOfMonth}에 넘어요"
        spent > budget -> projected
        pace.projected > budget -> "$projected · 예산을 넘어요"
        else -> "$projected · 예산 안이에요"
    }, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
        color = if (budget > 0 && pace.projected > budget) Color(0xFFFFD0C4) else white.copy(alpha = 0.92f))
    if (budget <= 0) return
    val b = Stats.BudgetSplit(budget, spent, reserved)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("남은 날 하루 권장" to b.perDay(daysLeft), "지금 하루 평균" to pace.dailyVariable).forEach { (k, v) ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(white.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(k, style = MaterialTheme.typography.labelSmall, color = white.copy(alpha = 0.8f))
                Text(Fmt.won(v), style = MaterialTheme.typography.titleSmall.merge(Money), color = white)
            }
        }
    }
    // 앞으로 며칠(최대 7일)을 한 덩어리로: 월 예산을 주 단위 행동으로 바꿔 줍니다(비주얼 "남은 한 주간 ○만원 이하로").
    val days = minOf(7, daysLeft)
    val week = b.perDay(daysLeft) * days / 1_000 * 1_000
    if (b.free > 0 && days >= 2 && week > 0) {
        Spacer(Modifier.height(8.dp))
        Text("앞으로 ${days}일 동안 ${Fmt.compact(week)}원 이하로 쓰면 예산 안이에요",
            style = MaterialTheme.typography.bodySmall, color = white.copy(alpha = 0.85f))
    }
}

/**
 * 예산 게이지: 쓴 돈(굵게) 뒤에 앞으로 나갈 고정지출을 얇은 줄무늬 몫으로 잇습니다. 그 뒤 빈 자리가 실제로 쓸 수 있는 돈입니다.
 * 고정지출 몫이 예산을 넘으면 넘는 부분은 빨갛게 그립니다.
 */
@Composable
private fun BudgetGauge(b: Stats.BudgetSplit, color: Color) {
    val over = P.c.expense
    val desc = "예산 ${Fmt.won(b.budget)} 중 쓴 돈 ${Fmt.won(b.spent)}, 앞으로 나갈 고정지출 ${Fmt.won(b.reserved)}, 쓸 수 있는 돈 ${Fmt.won(b.free.coerceAtLeast(0))}"
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(12.dp)
        .semantics { contentDescription = desc }) {
        val h = size.height
        val r = androidx.compose.ui.geometry.CornerRadius(h / 2, h / 2)
        drawRoundRect(Color.White.copy(alpha = 0.22f), cornerRadius = r)
        val total = b.budget.coerceAtLeast(1).toFloat()
        val spentW = (size.width * (b.spent / total)).coerceIn(0f, size.width)
        val resW = (size.width * (b.reserved / total)).coerceIn(0f, size.width - spentW)
        // 고정지출 몫: 굵기의 절반, 흐린 색 + 빗금. 남은 예산으로 고정지출도 못 내면(게이지 끝까지 차면) 빨강.
        if (resW > 0) {
            val c = if (b.free < 0) over else color
            val top = h / 4
            val rh = h / 2
            drawRect(c.copy(alpha = 0.45f), androidx.compose.ui.geometry.Offset(spentW, top), androidx.compose.ui.geometry.Size(resW, rh))
            var x = spentW
            while (x < spentW + resW) {
                drawRect(c.copy(alpha = 0.95f), androidx.compose.ui.geometry.Offset(x, top), androidx.compose.ui.geometry.Size(minOf(3f, spentW + resW - x), rh))
                x += 7f
            }
        }
        if (spentW > 0) drawRoundRect(if (b.spent > b.budget) over else color,
            size = androidx.compose.ui.geometry.Size(spentW.coerceAtLeast(h), h), cornerRadius = r)
    }
}

private val PineHero = listOf(Color(0xFF2E7A62), Color(0xFF1B4D3E))

@Composable
fun CardTile(c: com.jeiel85.doran.logic.CardStatus, owner: com.jeiel85.doran.model.Member?, onClick: () -> Unit) {
    val base = memberColor(owner?.color ?: 5)
    Box(
        Modifier.width(236.dp).height(150.dp).clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(base.copy(alpha = 0.95f), base.copy(alpha = 0.7f))))
            .clickable(onClick = onClick).padding(18.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.account.name, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (c.account.family) Text("가족 카드", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
                else if (owner != null) Text(owner.name, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
            }
            Spacer(Modifier.weight(1f))
            Text("이번 달", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
            Text(Fmt.won(c.monthSpent), style = MaterialTheme.typography.titleLarge.merge(Money), color = Color.White)
            // 카드 예산·실적 목표(D-39)
            listOfNotNull(
                c.account.monthBudget.takeIf { it > 0 }?.let { "예산 ${c.monthSpent * 100 / it}%" },
                c.account.perfGoal.takeIf { it > 0 }?.let { g -> if (c.cycleSpent >= g) "실적 채움" else "실적까지 ${Fmt.compact(g - c.cycleSpent)}원" },
            ).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f)) }
            Text(
                listOfNotNull(
                    c.issuerCumulative?.let { "카드사 누적 ${Fmt.compact(it)}" },
                    c.dueDate?.let { "${it.monthValue}/${it.dayOfMonth} 결제 ${Fmt.compact(c.dueAmount)}" },
                ).joinToString(" · ").ifEmpty {
                    // 체크카드는 청구가 없어 결제일을 묻지 않습니다(D-29).
                    if (c.account.type == AccountType.CHECK) "체크카드 · 통장에서 바로 나가요" else "결제일을 정하면 결제 예정액을 보여 드려요"
                },
                style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun SyncLine(sync: com.jeiel85.doran.sync.SyncStatus, inHousehold: Boolean, onRetry: () -> Unit) {
    if (!inHousehold) return
    val (icon, text, color) = when {
        sync.removed -> Triple(Icons.Rounded.CloudOff, "이 폰이 가족 가계부에서 빠졌어요. 가족 화면에서 다시 연결하세요", P.c.danger)
        sync.running -> Triple(Icons.Rounded.Sync, "동기화하는 중…", P.c.inkSoft)
        sync.error != null -> Triple(Icons.Rounded.CloudOff, "${sync.error} · 눌러서 다시 시도", P.c.apricot)
        sync.pending > 0 -> Triple(Icons.Rounded.Sync, "올릴 변경 ${sync.pending}건 대기 중", P.c.inkSoft)
        else -> Triple(Icons.Rounded.CloudDone, "동기화됨 · ${Fmt.relative(sync.lastOkAt)}" + if (Hub.sync.realtime) "" else " · 실시간 알림 꺼짐", P.c.inkFaint)
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onRetry).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** 보내기 전용 폰(부모님 폰): 장부 대신 보낸 기록과 연결 상태. */
@Composable
private fun SenderHome(nav: Nav) {
    val id by Hub.identity.state.collectAsStateWithLifecycle()
    val logs = remember(id) { Hub.db.captures(30) }
    val context = LocalContext.current
    val tick = rememberResumeTick()
    val captureOn = remember(tick) { CaptureService.isEnabled(context) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = navBarBottom(120.dp))) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("둥지 · 보내기", style = MaterialTheme.typography.headlineSmall, color = P.c.ink, modifier = Modifier.weight(1f))
                IconButton(onClick = { nav.go(Route.Settings) }) { Icon(Icons.Rounded.Settings, "설정", tint = P.c.ink) }
            }
        }
        item {
            SoftCard {
                Image(painterResource(R.drawable.art_family), null, Modifier.fillMaxWidth().height(150.dp))
                Spacer(Modifier.height(10.dp))
                Text(if (id.outLinks.isEmpty()) "아직 연결된 가족이 없어요"
                else id.outLinks.joinToString { l -> (l.memberName?.let { "'$it' 이름으로 " } ?: "") + l.label } + "에 보내고 있어요",
                    style = MaterialTheme.typography.headlineSmall, color = P.c.ink)
                val today = remember(logs) { logs.count { Fmt.date(it.at) == LocalDate.now(Fmt.zone) && it.result.contains("보냄") } }
                if (id.outLinks.isNotEmpty()) Text("오늘 보낸 카드·은행 알림 ${today}건", style = MaterialTheme.typography.titleMedium, color = P.c.pine)
                Text(if (captureOn) "카드·은행 알림이 오면 자동으로 보내요" else "알림 접근을 허용해야 보낼 수 있어요",
                    style = MaterialTheme.typography.bodyMedium, color = if (captureOn) P.c.inkSoft else P.c.apricot)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("QR 찍어서 연결") { nav.go(Route.Join()) }
                    SmallButton("알림 골라 보내기") { nav.go(Route.CaptureLog) }
                    SmallButton("알림 설정") { nav.go(Route.Capture) }
                }
            }
        }
        if (id.outLinks.isNotEmpty()) item {
            Spacer(Modifier.height(12.dp))
            SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                id.outLinks.forEach { l ->
                    ListRow("'${l.label}'에 보내는 알림",
                        (if (l.issuers.isEmpty()) "카드·은행 알림 전부" else l.issuers.joinToString()) + " · 눌러서 바꾸기",
                        Icons.Rounded.Send, P.c.apricot, onClick = { nav.go(Route.LinkScope(l.spaceId)) })
                }
            }
        }
        item { CandidatesCard(nav) }
        item { Spacer(Modifier.height(20.dp)); SectionTitle("보낸 기록") }
        if (logs.isEmpty()) item { Text("아직 보낸 알림이 없어요", style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft) }
        items(logs, key = { it.id }) { l ->
            ListRow(l.title ?: l.text.take(24), "${Fmt.full(l.at)} · ${l.result}", icon = Icons.Rounded.NotificationsActive, tint = P.c.apricot)
        }
    }
}

/** 남편 폰 맨 위 카드(D-28 A): 이번 용돈 기간의 남은 용돈. 용돈 날(주말이면 앞 금요일)부터 다음 용돈 전날까지(D-27). */
@Composable
private fun AllowanceHero(allowance: Long, spent: Long, ap: com.jeiel85.doran.logic.Period, today: LocalDate, onClick: () -> Unit) {
    val left = allowance - spent
    val last = ap.end.minusDays(1)
    val dow = listOf("월", "화", "수", "목", "금", "토", "일")[ap.end.dayOfWeek.value - 1]
    HeroCard(
        PineHero, R.drawable.art_cat_savings,
        "내 용돈 · ${ap.start.monthValue}.${ap.start.dayOfMonth} ~ ${last.monthValue}.${last.dayOfMonth}",
        if (left >= 0) "${Fmt.won(left)} 남음" else "${Fmt.won(-left)} 넘었어요",
        onClick = onClick,
    ) {
        Spacer(Modifier.height(16.dp))
        Progress(spent.toFloat() / allowance.coerceAtLeast(1), Color(0xFFFFD36B), height = 10.dp)
        Spacer(Modifier.height(10.dp))
        // 용돈(월급) 들어온 날부터 거꾸로 줄어드는 날 수(2026-11-20 리뷰). 오늘도 쓸 수 있는 날이라 오늘을 넣어 셉니다.
        val daysLeft = ChronoUnit.DAYS.between(today, ap.end).toInt().coerceAtLeast(1)
        Text(
            "다음 용돈까지 ${daysLeft}일" + if (left > 0) " · 하루 ${Fmt.won(left / daysLeft)}씩 쓸 수 있어요" else "",
            style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.9f), fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(16.dp))
        HeroStats(listOf(
            Stat("월 용돈", Fmt.won(allowance)),
            Stat("쓴 돈", Fmt.won(spent)),
            Stat("다음 용돈", "${ap.end.monthValue}/${ap.end.dayOfMonth}($dow)"),
        ))
    }
}
