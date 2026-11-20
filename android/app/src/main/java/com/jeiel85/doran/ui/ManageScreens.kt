package com.jeiel85.doran.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeiel85.doran.R
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.core.newId
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.model.Account
import com.jeiel85.doran.model.AccountType
import com.jeiel85.doran.model.Budget
import com.jeiel85.doran.model.Category
import com.jeiel85.doran.model.Goal
import com.jeiel85.doran.model.GoalEntry
import com.jeiel85.doran.model.Defaults
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Recurring
import com.jeiel85.doran.model.T
import java.time.LocalDate

private fun digits(s: String) = s.filter(Char::isDigit).take(12)

@Composable
private fun AmountField(label: String, value: String, onChange: (String) -> Unit) {
    Field(label, value, { onChange(digits(it)) }, "0", number = true)
    value.toLongOrNull()?.takeIf { it > 0 }?.let {
        Text(Fmt.won(it), style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
    }
}

// --- 예산 -----------------------------------------------------------------------------------------

@Composable
fun BudgetScreen(nav: Nav) {
    val s by Hub.ledger.state.collectAsStateWithLifecycle()
    val id by Hub.identity.state.collectAsStateWithLifecycle()
    val p = Stats.current(s)
    val entries = Stats.entries(s, p, id.myMemberId)
    val spent = Stats.byCategory(entries, Kind.EXPENSE).toMap()
    var total by remember { mutableStateOf(s.household.budget.takeIf { it > 0 }?.toString() ?: "") }
    var edit by remember { mutableStateOf<Category?>(null) }
    SubScreen("예산", { nav.back() }) {
        item {
            SoftCard {
                Text("한 달 전체 예산", style = MaterialTheme.typography.titleMedium, color = P.c.ink)
                Text("이번 달 지금까지 ${Fmt.won(Stats.expense(entries))} 썼어요", style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                Spacer(Modifier.height(12.dp))
                AmountField("월 예산", total) { total = it }
                Spacer(Modifier.height(12.dp))
                PrimaryButton("저장") { Hub.ledger.saveHousehold(s.household.copy(budget = total.toLongOrNull() ?: 0)) }
            }
        }
        item { Spacer(Modifier.height(20.dp)); SectionTitle("카테고리별 예산") }
        item {
            SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                s.categories.values.filter { it.kind == Kind.EXPENSE && !it.hidden }.sortedBy { it.order }.forEach { c ->
                    val b = s.budgets[Budget.idFor(c.id)]
                    val sp = spent[c.id] ?: 0
                    ListRow(c.name, if (b != null) "${Fmt.won(sp)} / ${Fmt.won(b.amount)}" else "이번 달 ${Fmt.won(sp)}",
                        leading = { CatTile(c.icon, 40.dp) }, onClick = { edit = c }) {
                        if (b != null) Text("${(sp * 100 / b.amount.coerceAtLeast(1))}%", style = MaterialTheme.typography.labelMedium,
                            color = if (sp > b.amount) P.c.expense else P.c.pine)
                    }
                }
            }
        }
    }
    edit?.let { c ->
        var v by remember(c.id) { mutableStateOf(s.budgets[Budget.idFor(c.id)]?.amount?.toString() ?: "") }
        AlertDialog(onDismissRequest = { edit = null }, containerColor = P.c.bg, title = { Text("${c.name} 예산") },
            text = { Column { AmountField("월 예산 (0이면 없음)", v) { v = it } } },
            confirmButton = { TextButton({ Hub.ledger.saveBudget(Budget(Budget.idFor(c.id), c.id, v.toLongOrNull() ?: 0)); edit = null }) { Text("저장") } },
            dismissButton = { TextButton({ edit = null }) { Text("취소") } })
    }
}

// --- 고정지출 ---------------------------------------------------------------------------------------

private fun describe(r: Recurring): String = when (r.period) {
    "w" -> "매주 ${listOf("", "월", "화", "수", "목", "금", "토", "일")[r.day.coerceIn(1, 7)]}요일"
    "y" -> "매년 ${r.month}월 ${r.day}일"
    else -> (if (r.day >= 31) "매달 말일" else "매달 ${r.day}일") +
        // 월 용돈은 그날이 주말이면 앞 금요일에 들어갑니다(Stats.dueDates, D-27).
        (if (com.jeiel85.doran.logic.AllowancePlan.isRule(r.id)) " · 주말이면 앞 금요일" else "")
}

@Composable
fun RecurringScreen(nav: Nav) {
    val s by Hub.ledger.state.collectAsStateWithLifecycle()
    val list = s.recurrings.values.sortedBy { it.day }
    fun monthlyOf(kind: Kind) = list.filter { !it.paused && it.kind == kind }.sumOf {
        when (it.period) { "w" -> it.amount * 52 / 12; "y" -> it.amount / 12; else -> it.amount }
    }
    val monthly = monthlyOf(Kind.EXPENSE)
    val monthlyIncome = monthlyOf(Kind.INCOME)
    SubScreen(com.jeiel85.doran.logic.Fixed.title(list), { nav.back() }, actions = {
        IconButton(onClick = { nav.go(Route.RecurringEdit(null)) }) { Icon(Icons.Rounded.Add, "추가", tint = P.c.ink) }
    }) {
        item {
            SoftCard {
                Text("한 달 고정지출", style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft)
                Text(Fmt.won(monthly), style = MaterialTheme.typography.headlineSmall.merge(Money), color = P.c.ink)
                if (monthlyIncome > 0) {
                    Text("한 달 고정수입 +${Fmt.won(monthlyIncome)}", style = MaterialTheme.typography.titleSmall.merge(Money), color = P.c.income)
                }
                Text("정해진 날 아침에 장부에 들어가요. 같은 금액이 카드·은행 알림으로도 오면 새로 만들지 않고 그 내역에 이어 붙여요.",
                    style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
            }
        }
        if (list.isEmpty()) item { EmptyState(R.drawable.art_cat_subscription, "고정지출이 없어요", "월세·통신비·보험·넷플릭스처럼 매달 나가는 돈을 더해 보세요") }
        else item {
            Spacer(Modifier.height(16.dp))
            SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                list.forEach { r ->
                    ListRow(r.name + if (r.paused) " (멈춤)" else "", listOfNotNull(describe(r), s.account(r.accountId)?.name, s.member(r.memberId)?.name).joinToString(" · "),
                        leading = { CatTile(s.category(r.categoryId)?.icon ?: "subscription", 40.dp) }, onClick = { nav.go(Route.RecurringEdit(r.id)) }) {
                        Amount(r.amount, r.kind, MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecurringEditScreen(rid: String?, nav: Nav) {
    val s by Hub.ledger.state.collectAsStateWithLifecycle()
    val id by Hub.identity.state.collectAsStateWithLifecycle()
    val o = remember(rid) { rid?.let { s.recurrings[it] } }
    var name by remember { mutableStateOf(o?.name ?: "") }
    // 월 용돈 규칙(DECISIONS.md D-20)은 가계부에서 나가는 돈이라 늘 지출입니다. 수입으로 두면 합계가 용돈의 두 배만큼 틀어져요.
    // 예전 버전에서 수입으로 바뀐 규칙도 열면 지출로 잡혀, 저장하면 바로잡힙니다.
    val allowanceRule = o != null && com.jeiel85.doran.logic.AllowancePlan.isRule(o.id)
    var kind by remember { mutableStateOf(if (allowanceRule) Kind.EXPENSE else o?.kind ?: Kind.EXPENSE) }
    var amount by remember { mutableStateOf(o?.amount?.toString() ?: "") }
    var period by remember { mutableStateOf(o?.period ?: "m") }
    var day by remember { mutableStateOf((o?.day ?: LocalDate.now(Fmt.zone).dayOfMonth).toString()) }
    var month by remember { mutableStateOf((o?.month ?: LocalDate.now(Fmt.zone).monthValue).toString()) }
    var categoryId by remember { mutableStateOf(o?.categoryId ?: "c.subscription") }
    var accountId by remember { mutableStateOf(o?.accountId) }
    var memberId by remember { mutableStateOf(o?.memberId ?: id.myMemberId) }
    var paused by remember { mutableStateOf(o?.paused ?: false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val valid = name.isNotBlank() && (amount.toLongOrNull() ?: 0) > 0 && (day.toIntOrNull() ?: 0) in 1..31

    val label = com.jeiel85.doran.logic.Fixed.label(kind)
    SubScreen(if (o == null) "$label 추가" else label, { nav.back() }, actions = {
        if (o != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.DeleteOutline, "지우기", tint = P.c.danger) }
    }, bottom = {
        PrimaryButton("저장", enabled = valid) {
            Hub.ledger.saveRecurring(Recurring(
                id = o?.id ?: newId(), name = name.trim(), kind = kind, amount = amount.toLong(),
                categoryId = com.jeiel85.doran.logic.Fixed.categoryFor(s, name.trim(), kind, categoryId),
                accountId = accountId, memberId = memberId, period = period, day = day.toInt().coerceIn(1, if (period == "w") 7 else 31),
                month = (month.toIntOrNull() ?: 1).coerceIn(1, 12), startAt = o?.startAt ?: Fmt.millis(LocalDate.now(Fmt.zone)), paused = paused,
            ))
            com.jeiel85.doran.logic.Hooks.daily(Hub.app)
            nav.back()
        }
    }) {
        item {
            if (allowanceRule) {
                Text("월 용돈은 가계부에서 나가는 돈이라 늘 지출로 한 번 들어가요. 정한 날이 토·일이면 월급이 먼저 들어오는 앞 금요일에 들어가요.",
                    style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
            } else Segmented(listOf("지출", "수입"), if (kind == Kind.INCOME) 1 else 0) {
                kind = if (it == 1) Kind.INCOME else Kind.EXPENSE
                // 지출 카테고리(구독 등)가 수입 규칙에 남지 않게, 종류가 바뀌면 그 종류의 카테고리로.
                categoryId = com.jeiel85.doran.logic.Fixed.categoryFor(s, name.trim(), kind, categoryId)
            }
            Spacer(Modifier.height(14.dp))
            Field("이름", name, { name = it }, "예: 넷플릭스, 관리비, 월급")
            Spacer(Modifier.height(10.dp))
            AmountField("금액", amount) { amount = it }
            Spacer(Modifier.height(14.dp))
            ChipRow { listOf("m" to "매달", "w" to "매주", "y" to "매년").forEach { (k, l) -> Chip(l, period == k, { period = k }) } }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (period == "y") Column(Modifier.weight(1f)) { Field("월", month, { month = digits(it).take(2) }, "1", number = true) }
                Column(Modifier.weight(1f)) {
                    Field(if (period == "w") "요일 (1=월 … 7=일)" else "날짜 (31=말일)", day, { day = digits(it).take(2) }, "1", number = true)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("카테고리", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                s.categories.values.filter { it.kind == kind && !it.hidden }.sortedBy { it.order }.forEach { c ->
                    Chip(c.name, categoryId == c.id, { categoryId = c.id }, leading = { ArtImage(c.icon, 18.dp) })
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(if (kind == Kind.INCOME) "들어오는 곳" else "결제 수단", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("없음", accountId == null, { accountId = null })
                s.accounts.values.filter { !it.hidden }.sortedBy { it.order }.forEach { a -> Chip(a.name, accountId == a.id, { accountId = a.id }) }
            }
            Spacer(Modifier.height(16.dp))
            Text("누구", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
            Spacer(Modifier.height(8.dp))
            ChipRow {
                Chip("공동", memberId == null, { memberId = null })
                s.activeMembers.filter { !it.isSender }.forEach { m -> Chip(m.name, memberId == m.id, { memberId = m.id }, color = memberColor(m.color)) }
            }
            if (o != null) {
                Spacer(Modifier.height(10.dp))
                ToggleRow("잠시 멈추기", "멈춘 동안은 장부에 넣지 않아요", Icons.Rounded.VisibilityOff, P.c.inkSoft, paused) { paused = it }
            }
        }
    }
    if (confirmDelete && o != null) AlertDialog(onDismissRequest = { confirmDelete = false }, containerColor = P.c.bg,
        title = { Text("이 ${com.jeiel85.doran.logic.Josa.eulReul(com.jeiel85.doran.logic.Fixed.label(o.kind))} 지울까요?") },
        text = { Text("이미 장부에 들어간 내역은 그대로 남아요.") },
        confirmButton = { TextButton({ Hub.ledger.delete(T.RECURRING, o.id); confirmDelete = false; nav.back() }) { Text("지우기", color = P.c.danger) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("두기") } })
}

// --- 저축 목표 ---------------------------------------------------------------------------------------

@Composable
fun GoalsScreen(nav: Nav) {
    val s by Hub.ledger.state.collectAsStateWithLifecycle()
    val goals = s.goals.values.sortedWith(compareBy({ it.done }, { it.order }))
    SubScreen("저축 목표", { nav.back() }, actions = {
        IconButton(onClick = { nav.go(Route.GoalEdit(null)) }) { Icon(Icons.Rounded.Add, "추가", tint = P.c.ink) }
    }) {
        if (goals.isEmpty()) item { EmptyState(R.drawable.art_cat_savings, "목표가 없어요", "여행·비상금·적금처럼 모을 돈을 정해 보세요") }
        items(goals, key = { it.id }) { g ->
            val saved = Stats.goalSaved(s, g)
            Spacer(Modifier.height(12.dp))
            SoftCard(onClick = { nav.go(Route.GoalEdit(g.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CatTile(g.icon, 48.dp, P.c.pineSoft)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(g.name + if (g.done) " · 달성" else "", style = MaterialTheme.typography.titleMedium, color = P.c.ink)
                        Text(listOfNotNull("${Fmt.won(saved)} / ${Fmt.won(g.target)}", g.deadline?.let { "~${Fmt.date(it)}" }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall.merge(Money), color = P.c.inkSoft)
                    }
                    Text("${(saved * 100 / g.target.coerceAtLeast(1))}%", style = MaterialTheme.typography.titleMedium, color = P.c.pine)
                }
                Spacer(Modifier.height(12.dp))
                Progress(saved.toFloat() / g.target.coerceAtLeast(1), P.c.pine)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoalEditScreen(gid: String?, nav: Nav) {
    val s by Hub.ledger.state.collectAsStateWithLifecycle()
    val o = s.goals[gid ?: ""]
    var name by remember { mutableStateOf(o?.name ?: "") }
    var target by remember { mutableStateOf(o?.target?.toString() ?: "") }
    var icon by remember { mutableStateOf(o?.icon ?: "savings") }
    var accountId by remember { mutableStateOf(o?.accountId) }
    var add by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    SubScreen(if (o == null) "목표 만들기" else o.name, { nav.back() }, actions = {
        if (o != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.DeleteOutline, "지우기", tint = P.c.danger) }
    }, bottom = {
        PrimaryButton("저장", enabled = name.isNotBlank() && (target.toLongOrNull() ?: 0) > 0) {
            val g = (o ?: Goal(id = newId(), name = "", target = 0, order = s.goals.size)).copy(name = name.trim(), target = target.toLong(), icon = icon, accountId = accountId)
            Hub.ledger.saveGoal(g.copy(done = Stats.goalSaved(s, g) >= g.target))
            nav.back()
        }
    }) {
        item {
            Field("이름", name, { name = it }, "예: 제주도 여행, 비상금")
            Spacer(Modifier.height(10.dp))
            AmountField("목표 금액", target) { target = it }
            Spacer(Modifier.height(16.dp))
            Text("그림", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("savings", "travel", "car", "housing", "education", "childcare", "gift", "invest", "pets", "leisure").forEach { n ->
                    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(if (icon == n) P.c.pineSoft else P.c.surface)
                        .border(if (icon == n) 2.dp else 1.dp, if (icon == n) P.c.pine else P.c.line, RoundedCornerShape(14.dp))
                        .clickable { icon = n }.padding(8.dp)) { ArtImage(n, 36.dp) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("모으는 계좌 (고르면 그 잔액이 모은 돈이에요)", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("직접 기록", accountId == null, { accountId = null })
                s.accounts.values.filter { !it.hidden && it.type.asset }.forEach { a -> Chip(a.name, accountId == a.id, { accountId = a.id }) }
            }
            if (o != null && accountId == null) {
                Spacer(Modifier.height(16.dp))
                SoftCard {
                    Text("모은 돈 기록", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
                    Spacer(Modifier.height(8.dp))
                    AmountField("더할 금액", add) { add = it }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton("더하기") {
                            add.toLongOrNull()?.takeIf { it > 0 }?.let { v ->
                                val g = o.copy(entries = o.entries + GoalEntry(System.currentTimeMillis(), v))
                                Hub.ledger.saveGoal(g.copy(done = g.entries.sumOf { it.amount } >= g.target)); add = ""
                            }
                        }
                        SmallButton("빼기", color = P.c.expense) {
                            add.toLongOrNull()?.takeIf { it > 0 }?.let { v ->
                                Hub.ledger.saveGoal(o.copy(entries = o.entries + GoalEntry(System.currentTimeMillis(), -v), done = false)); add = ""
                            }
                        }
                    }
                    o.entries.takeLast(8).reversed().forEach { e ->
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text(Fmt.date(e.at).toString(), style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft, modifier = Modifier.weight(1f))
                            Text((if (e.amount >= 0) "+" else "") + Fmt.won(e.amount), style = MaterialTheme.typography.bodySmall.merge(Money),
                                color = if (e.amount >= 0) P.c.pine else P.c.expense)
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete && o != null) AlertDialog(onDismissRequest = { confirmDelete = false }, containerColor = P.c.bg,
        title = { Text("이 목표를 지울까요?") },
        confirmButton = { TextButton({ Hub.ledger.delete(T.GOAL, o.id); confirmDelete = false; nav.back() }) { Text("지우기", color = P.c.danger) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("두기") } })
}

// --- 계좌·카드 ---------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountEditScreen(aid: String?, nav: Nav) {
    val s by Hub.ledger.state.collectAsStateWithLifecycle()
    val id by Hub.identity.state.collectAsStateWithLifecycle()
    val o = s.accounts[aid ?: ""]
    var name by remember { mutableStateOf(o?.name ?: "") }
    var type by remember { mutableStateOf(o?.type ?: AccountType.BANK) }
    var ownerId by remember { mutableStateOf(o?.ownerId ?: id.myMemberId) }
    var balance by remember { mutableStateOf(o?.let { Stats.balance(s, it) }?.let { kotlin.math.abs(it).toString() } ?: "") }
    var billingDay by remember { mutableStateOf(o?.billingDay?.toString() ?: "") }
    var cycleStart by remember { mutableStateOf((o?.cycleStartDay ?: 1).toString()) }
    var institution by remember { mutableStateOf(o?.institution ?: "") }
    var allowanceCard by remember { mutableStateOf(o?.allowanceCard ?: false) }
    var family by remember { mutableStateOf(o?.family ?: false) }
    var hidden by remember { mutableStateOf(o?.hidden ?: false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val used = o != null && s.txns.values.any { it.accountId == o.id || it.toAccountId == o.id }

    SubScreen(if (o == null) "계좌·카드 추가" else o.name, { nav.back() }, actions = {
        if (o != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.DeleteOutline, "지우기", tint = P.c.danger) }
    }, bottom = {
        PrimaryButton("저장", enabled = name.isNotBlank()) {
            val now = System.currentTimeMillis()
            val bal = if (type == AccountType.CARD) null else balance.toLongOrNull()?.let { if (type.asset) it else -it }
            val base = o ?: Account(id = newId(), name = "", type = type, openingAt = now, order = s.accounts.size)
            // 잔액을 고쳤으면 지금 시각 기준점으로 맞춥니다(이후 내역만 더함).
            val changed = bal != null && (o == null || bal != Stats.balance(s, o))
            Hub.ledger.saveAccount(base.copy(
                name = name.trim(), type = type, ownerId = ownerId, institution = institution.trim().ifEmpty { null },
                billingDay = billingDay.toIntOrNull()?.coerceIn(1, 31), cycleStartDay = cycleStart.toIntOrNull()?.coerceIn(1, 28),
                allowanceCard = allowanceCard && !family, family = family, hidden = hidden,
                anchor = if (changed) bal else base.anchor, anchorAt = if (changed) now else base.anchorAt,
            ))
            nav.back()
        }
    }) {
        item {
            Field("이름", name, { name = it }, "예: 현대카드 M, 생활비 통장")
            Spacer(Modifier.height(14.dp))
            Text("종류", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AccountType.entries.forEach { t -> Chip(t.label, type == t, { type = t }) }
            }
            Spacer(Modifier.height(14.dp))
            if (type == AccountType.CARD) {
                Text("카드의 갚을 돈은 결제일이 오지 않은 이용액으로 저절로 계산해요(할부는 남은 회차만).",
                    style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft, modifier = Modifier.padding(start = 8.dp))
            } else AmountField(if (type.asset) "지금 잔액" else "지금 갚을 금액", balance) { balance = it }
            Spacer(Modifier.height(10.dp))
            Field("카드사·은행 (알림 연결용)", institution, { institution = it }, "예: 현대카드, KB국민은행")
            if (type == AccountType.CARD) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) { Field("결제일", billingDay, { billingDay = digits(it).take(2) }, "14", number = true) }
                    Column(Modifier.weight(1f)) { Field("이용기간 시작일", cycleStart, { cycleStart = digits(it).take(2) }, "1", number = true) }
                }
                Text("예: 결제일 14일 · 이용기간 전월 1일~말일이면 시작일 1", style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text("주인", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
            Spacer(Modifier.height(8.dp))
            ChipRow {
                Chip("공동", ownerId == null, { ownerId = null })
                s.activeMembers.forEach { m -> Chip(m.name, ownerId == m.id, { ownerId = m.id }, color = memberColor(m.color)) }
            }
            Spacer(Modifier.height(10.dp))
            if (type == AccountType.CARD || type == AccountType.CHECK) {
                ToggleRow("가족 카드", "명의와 관계없이 가족이 함께 쓰는 카드예요. 사람별 지출에서 '가족 공용'으로 묶어요",
                    Icons.Rounded.Groups, P.c.pine, family) { family = it; if (it) allowanceCard = false }
            }
            if (ownerId == id.myMemberId && !family && (type == AccountType.CARD || type == AccountType.CHECK)) {
                ToggleRow("내 용돈 카드", "이 카드 알림으로 들어온 내역은 용돈으로 처리해 가족에게 합계만 보여요", Icons.Rounded.Visibility, P.c.apricot, allowanceCard) { allowanceCard = it }
            }
            ToggleRow("숨기기", "자산 화면과 합계에서 뺍니다", Icons.Rounded.VisibilityOff, P.c.inkSoft, hidden) { hidden = it }
        }
    }
    if (confirmDelete && o != null) AlertDialog(onDismissRequest = { confirmDelete = false }, containerColor = P.c.bg,
        title = { Text("이 계좌를 지울까요?") },
        text = { Text(if (used) "이 계좌를 쓴 내역이 있어요. 지우면 내역의 결제 수단이 비어요. 숨기기를 권해요." else "지운 뒤에는 되돌릴 수 없어요.") },
        confirmButton = { TextButton({ Hub.ledger.delete(T.ACCOUNT, o.id); confirmDelete = false; nav.back() }) { Text("지우기", color = P.c.danger) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("두기") } })
}

// --- 용돈 -----------------------------------------------------------------------------------------

@Composable
fun AllowanceScreen(nav: Nav) {
    val s by Hub.ledger.state.collectAsStateWithLifecycle()
    val id by Hub.identity.state.collectAsStateWithLifecycle()
    val me = s.member(id.myMemberId)
    var amount by remember { mutableStateOf(me?.allowance?.takeIf { it > 0 }?.toString() ?: "") }
    val p = Stats.current(s)
    // 내 내역은 용돈이 들어온 날부터 센 용돈 기간(D-27), 가족 용돈 합계는 가계부 기간(D-7).
    val ap = Stats.allowancePeriod(s, me?.id)
    val mine = s.txns.values.filter { it.private && it.memberId == me?.id && it.cancelOf == null && it.at in ap }.sortedByDescending { it.at }
    val others = s.allowances.values.filter { it.month == p.key && it.memberId != me?.id }
    SubScreen("용돈", { nav.back() }) {
        item {
            SoftCard {
                Text("내 한 달 용돈", style = MaterialTheme.typography.titleMedium, color = P.c.ink)
                Text("용돈으로 표시한 내역은 내 폰에서만 열리고, 가족에게는 이번 달 합계만 보여요. 월 용돈을 정하면 매달 '내 용돈' 고정지출이 가계부에 한 번 들어가고, 그 안에서 쓴 것은 합계에 더하지 않아요.",
                    style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                Spacer(Modifier.height(12.dp))
                AmountField("월 용돈 (0이면 쓰지 않음)", amount) { amount = it }
                Spacer(Modifier.height(10.dp))
                PrimaryButton("저장", enabled = me != null) {
                    me?.let {
                        val m = it.copy(allowance = amount.toLongOrNull() ?: 0)
                        Hub.ledger.saveMember(m)
                        if (com.jeiel85.doran.logic.AllowancePlan.sync(Hub.ledger, m)) com.jeiel85.doran.logic.Recurrer.run(Hub.ledger, Hub.db, Hub.identity.installId)
                    }
                }
            }
        }
        if (others.isNotEmpty()) {
            item { Spacer(Modifier.height(20.dp)); SectionTitle("가족 용돈 (합계만)") }
            item {
                SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                    others.forEach { a ->
                        val m = s.member(a.memberId)
                        ListRow(m?.name ?: "가족", "${a.count}건 · 세부 내역은 본인만 볼 수 있어요", leading = { MemberAvatar(m, 40.dp) }) {
                            Text(Fmt.won(a.spent) + (m?.allowance?.takeIf { it > 0 }?.let { " / ${Fmt.compact(it)}" } ?: ""),
                                style = MaterialTheme.typography.titleSmall.merge(Money), color = P.c.ink)
                        }
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(20.dp))
            SectionTitle("이번 용돈 기간 내역 · ${ap.start.monthValue}.${ap.start.dayOfMonth} ~ ${ap.end.minusDays(1).monthValue}.${ap.end.minusDays(1).dayOfMonth}")
        }
        if (mine.isEmpty()) item { Text("아직 없어요. 내역을 쓸 때 '내 용돈으로 쓰기'를 켜거나, 계좌 설정에서 용돈 카드를 지정하세요.",
            style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft) }
        items(mine, key = { it.id }) { t ->
            val c = s.canceled[t.id] ?: 0
            TxnRow(com.jeiel85.doran.logic.Entry(t, (t.amount - c).coerceAtLeast(0), c >= t.amount, counted = Stats.counts(s, t)), s, false, showDate = true) {
                nav.go(Route.Entry(txnId = t.id))
            }
        }
    }
}

// --- 카테고리 --------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoriesScreen(nav: Nav) {
    val s by Hub.ledger.state.collectAsStateWithLifecycle()
    var kind by remember { mutableStateOf(Kind.EXPENSE) }
    var edit by remember { mutableStateOf<Category?>(null) }
    SubScreen("카테고리", { nav.back() }, actions = {
        IconButton(onClick = { edit = Category(newId(), "", kind, "etc", order = s.categories.size) }) { Icon(Icons.Rounded.Add, "추가", tint = P.c.ink) }
    }) {
        item { Segmented(listOf("지출", "수입"), if (kind == Kind.INCOME) 1 else 0) { kind = if (it == 1) Kind.INCOME else Kind.EXPENSE }; Spacer(Modifier.height(12.dp)) }
        item {
            SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                s.categories.values.filter { it.kind == kind }.sortedBy { it.order }.forEach { c ->
                    ListRow(c.name + if (c.hidden) " (숨김)" else "", null, leading = { CatTile(c.icon, 40.dp) }, onClick = { edit = c })
                }
            }
        }
    }
    edit?.let { c ->
        var name by remember(c.id) { mutableStateOf(c.name) }
        var icon by remember(c.id) { mutableStateOf(c.icon) }
        var hidden by remember(c.id) { mutableStateOf(c.hidden) }
        AlertDialog(onDismissRequest = { edit = null }, containerColor = P.c.bg, title = { Text(if (c.name.isEmpty()) "카테고리 추가" else "카테고리") },
            text = {
                Column {
                    Field("이름", name, { name = it.take(10) })
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Art.names.forEach { n ->
                            Column(Modifier.clip(RoundedCornerShape(10.dp)).background(if (icon == n) P.c.pineSoft else P.c.surface).clickable { icon = n }.padding(4.dp)) {
                                ArtImage(n, 30.dp)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    ToggleRow("숨기기", "입력 화면에서 빼요(지난 내역은 그대로)", Icons.Rounded.VisibilityOff, P.c.inkSoft, hidden) { hidden = it }
                }
            },
            confirmButton = { TextButton({ Hub.ledger.saveCategory(c.copy(name = name.trim(), icon = icon, hidden = hidden)); edit = null }, enabled = name.isNotBlank()) { Text("저장") } },
            dismissButton = { TextButton({ edit = null }) { Text("취소") } })
    }
}

// --- 검색 -----------------------------------------------------------------------------------------

@Composable
fun SearchScreen(nav: Nav) {
    val s by Hub.ledger.state.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf("") }
    val results = remember(q, s.version) {
        if (q.isBlank()) emptyList() else {
            val c = s.canceled
            val needle = q.trim()
            s.txns.values.filter { it.cancelOf == null }.filter { t ->
                t.merchant.contains(needle, true) || t.memo.contains(needle, true) ||
                    s.category(t.categoryId)?.name?.contains(needle) == true || t.amount.toString() == needle.replace(",", "")
            }.sortedByDescending { it.at }.take(200).map { t ->
                val net = (t.amount - (c[t.id] ?: 0)).coerceAtLeast(0)
                com.jeiel85.doran.logic.Entry(t, net, c[t.id] != null && net == 0L, counted = Stats.counts(s, t))
            }
        }
    }
    SubScreen("검색", { nav.back() }) {
        item { Field("가맹점·메모·카테고리·금액", q, { q = it }, "예: 스타벅스, 12300"); Spacer(Modifier.height(10.dp)) }
        if (q.isNotBlank()) item {
            Text("${results.size}건 · 지출 ${Fmt.won(results.filter { it.txn.kind == Kind.EXPENSE }.sumOf { it.net })}",
                style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft, modifier = Modifier.padding(8.dp))
        }
        items(results, key = { it.txn.id }) { e -> TxnRow(e, s, false, showDate = true) { nav.go(Route.Entry(txnId = e.txn.id)) } }
    }
}

// --- 카테고리별 내역(통계에서 누름) ---------------------------------------------------------------

@Composable
fun CategoryTxnsScreen(categoryId: String, month: String, nav: Nav) {
    val s by Hub.ledger.state.collectAsStateWithLifecycle()
    val id by Hub.identity.state.collectAsStateWithLifecycle()
    val p = remember(month, s.household.monthStartDay) { Stats.period(java.time.YearMonth.parse(month), s.household.monthStartDay) }
    val cat = s.category(categoryId)
    val list = remember(s.version, p, categoryId) {
        Stats.entries(s, p, id.myMemberId).filter { it.txn.kind != Kind.TRANSFER && (it.txn.categoryId ?: Defaults.EXPENSE_FALLBACK) == categoryId }
    }
    SubScreen("${cat?.name ?: "카테고리"} · ${Fmt.month(p.label)}", { nav.back() }) {
        item {
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CatTile(cat?.icon, 52.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${list.size}건", style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                        Text(Fmt.won(list.filter { it.counted }.sumOf { it.net }), style = MaterialTheme.typography.headlineSmall.merge(Money), color = P.c.ink)
                    }
                    s.budgets[Budget.idFor(categoryId)]?.let { b ->
                        Text("예산 ${Fmt.compact(b.amount)}", style = MaterialTheme.typography.labelMedium, color = P.c.pine)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        items(list, key = { it.txn.id }) { e ->
            TxnRow(e, s, false, showDate = true) {
                if (e.allowanceSummary) nav.go(Route.Allowance) else nav.go(Route.Entry(txnId = e.txn.id))
            }
        }
    }
}
