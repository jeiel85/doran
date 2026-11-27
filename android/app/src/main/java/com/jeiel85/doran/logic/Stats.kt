package com.jeiel85.doran.logic

import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.model.Account
import com.jeiel85.doran.model.AccountType
import com.jeiel85.doran.model.Defaults
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Recurring
import com.jeiel85.doran.model.Txn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** [start, end) 날짜 구간. */
data class Period(val label: YearMonth, val start: LocalDate, val end: LocalDate) {
    val startMs get() = Fmt.millis(start)
    val endMs get() = Fmt.millis(end)
    operator fun contains(ms: Long) = ms >= startMs && ms < endMs
    val days get() = ChronoUnit.DAYS.between(start, end).toInt()
    val key get() = "%04d%02d".format(label.year, label.monthValue)
}

/** 화면에 쓰는 한 줄: 실제 거래이거나, 다른 구성원의 용돈 월 합계(내용은 숨김, DECISIONS.md D-7). */
data class Entry(
    val txn: Txn,
    /** 부분 취소를 뺀 실제 금액. */
    val net: Long,
    val canceled: Boolean,
    val allowanceSummary: Boolean = false,
    /**
     * 가계부 합계(지출·카테고리·사람별·예산·달력)에 더하는지. 월 용돈이 정해진 사람의 용돈 내역과 그 월 합계는
     * 이미 "○○ 용돈" 고정지출로 들어가므로 보이기만 하고 더하지 않습니다(DECISIONS.md D-20).
     */
    val counted: Boolean = true,
)

data class CardStatus(
    val account: Account,
    val monthSpent: Long,
    val cycleStart: LocalDate,
    val cycleSpent: Long,
    val dueDate: LocalDate?,
    val dueAmount: Long,
    /** 카드사가 마지막 알림에 적어 준 누적 이용액(가장 정확). */
    val issuerCumulative: Long?,
    val issuerCumulativeAt: Long?,
)

object Stats {
    fun period(ym: YearMonth, startDay: Int): Period {
        val d = startDay.coerceIn(1, 28)
        return Period(ym, ym.atDay(d), ym.plusMonths(1).atDay(d))
    }

    fun periodOf(date: LocalDate, startDay: Int): Period {
        val ym = YearMonth.from(date)
        return if (date.dayOfMonth >= startDay.coerceIn(1, 28)) period(ym, startDay) else period(ym.minusMonths(1), startDay)
    }

    fun current(s: LedgerState, today: LocalDate = LocalDate.now(Fmt.zone), startDay: Int = s.household.monthStartDay) = periodOf(today, startDay)

    /**
     * 이 폰 화면(홈·통계·예산·위젯·엑셀)이 쓰는 한 달 시작일(DECISIONS.md D-33): 폰 설정 [mine](1~28)이 있으면 그것, 없으면(0)
     * 가족 공통 한 달 시작일. 아내는 1일, 남편은 월급날처럼 사람마다 보고 싶은 한 달이 달라서 폰마다 고를 수 있게 했습니다.
     * 고정지출 날짜·용돈 날·가족에게 올리는 용돈 월 합계(D-7)는 두 폰이 같아야 해서 늘 가족 공통 날짜를 씁니다.
     */
    fun viewStart(s: LedgerState, mine: Int): Int = if (mine in 1..28) mine else s.household.monthStartDay

    /**
     * 수입·지출 합계에 들어가는 줄. 취소 엔티티는 원거래를 상쇄하고 자기는 빠지며, 원화 확정 전 해외 결제는 0이라
     * 저절로 빠집니다. 다른 구성원의 용돈은 월 합계 한 줄로 들어갑니다.
     * [summaryAt]: 그 용돈 월 합계 줄을 둘 시각(기본은 [p]의 첫날). 달력은 앞뒤 달 날짜까지 한 번에 읽으므로(#67) 그 달 1일에 두어
     * 앞 달 칸에 찍히지 않게 합니다.
     */
    fun entries(s: LedgerState, p: Period, myMemberId: String?, includeSenders: Boolean = true, summaryAt: Long = p.startMs): List<Entry> {
        val canceled = s.canceled
        val out = ArrayList<Entry>()
        for (t in s.txns.values) {
            if (t.cancelOf != null || t.at !in p) continue
            if (!includeSenders && s.member(t.memberId)?.isSender == true) continue
            val c = canceled[t.id] ?: 0
            val net = (t.amount - c).coerceAtLeast(0)
            out += Entry(t, net, canceled = c > 0 && net == 0L, counted = counts(s, t))
        }
        // 다른 가족의 용돈 월 합계는 가족 공통 한 달로 셉니다. 이 폰의 한 달 시작일이 다르면(D-33) 며칠이 어긋날 수 있어 그렇다고 적습니다(#78).
        val familyMonthNote = if (p.start.dayOfMonth != s.household.monthStartDay.coerceIn(1, 28) && s.household.monthStartDay in 1..28)
            " · 가족 한 달(${s.household.monthStartDay}일 시작) 기준" else ""
        for (a in s.allowances.values) {
            if (a.month != p.key || a.memberId == myMemberId || a.spent <= 0) continue
            val name = s.member(a.memberId)?.name ?: "가족"
            val fixed = hasFixedAllowance(s, a.memberId)
            out += Entry(
                Txn(id = a.id, kind = Kind.EXPENSE, amount = a.spent, at = summaryAt, categoryId = Defaults.ALLOWANCE,
                    merchant = (if (fixed) "$name 용돈 사용 (${a.count}건)" else "$name 용돈 (${a.count}건)") + familyMonthNote, memberId = a.memberId),
                net = a.spent, canceled = false, allowanceSummary = true, counted = !fixed,
            )
        }
        out.sortByDescending { it.txn.at }
        return out
    }

    /** 월 용돈이 정해진 사람: 그 용돈은 고정지출 한 번으로 세고, 안에서 쓴 것은 더하지 않습니다. */
    fun hasFixedAllowance(s: LedgerState, memberId: String?): Boolean = (s.member(memberId)?.allowance ?: 0) > 0

    /**
     * 이 거래를 가계부 합계에 더하는지: 월 용돈이 정해진 사람의 용돈 내역은 빼요. 가계부 통장끼리 옮긴 돈(출금·입금 한 쌍)도
     * 쓴 돈·수입이 아니라 빼요(D-28, 통장 잔액에는 그대로 들어감). 짝 없이 '내 통장끼리 옮긴 돈'으로 고른 입금도 수입이 아니에요(D-31).
     */
    fun counts(s: LedgerState, t: Txn): Boolean =
        !t.personal && !(t.private && hasFixedAllowance(s, t.memberId)) && !Moves.linked(t) && !Moves.selfIn(t)

    /**
     * 이 기간에 [memberId]가 용돈으로 쓴 결제(본인 폰에만 있음, D-7). 용돈 계좌로 들어온 입금(내 다른 통장에서 옮긴 돈 등)은
     * 쓴 돈이 아니라 빼고, 취소 엔티티는 원거래에서 상쇄합니다.
     */
    fun allowanceTxns(s: LedgerState, memberId: String?, p: Period): List<Txn> =
        s.txns.values.filter { it.private && it.memberId == memberId && it.cancelOf == null && it.kind == Kind.EXPENSE && it.at in p }

    /**
     * [memberId]의 용돈 기간: 월 용돈 규칙이 들어가는 날(주말이면 앞 금요일)부터 다음 그날 전날까지(DECISIONS.md D-27).
     * 용돈은 그날 채워지므로, 가계부 한 달(한 달 시작일)과 날짜가 달라도 남은 용돈은 이 기간으로 셉니다.
     * 규칙이 없거나 멈췄으면 가계부 기간. 가족에게 가는 용돈 월 합계(D-7)는 그대로 가계부 기간입니다.
     */
    fun allowancePeriod(s: LedgerState, memberId: String?, date: LocalDate = LocalDate.now(Fmt.zone)): Period {
        val r = memberId?.let { s.recurrings[AllowancePlan.idFor(it)] }?.takeIf { it.period == "m" && !it.paused }
            ?: return periodOf(date, s.household.monthStartDay)
        fun due(ym: YearMonth) = allowanceDue(ym, r.day, s.household.monthStartDay)
        // 지난달 몫은 늘 [date] 전이고, 두 달 뒤 몫은 늘 뒤라 그 사이에서 찾습니다(1·2일이 앞 달로 당겨져도).
        val months = (-1L..2L).map { YearMonth.from(date).plusMonths(it) }
        val i = months.indexOfLast { !due(it).isAfter(date) }
        return Period(months[i], due(months[i]), due(months[i + 1]))
    }

    fun allowanceSpent(s: LedgerState, memberId: String?, p: Period): Long {
        val canceled = s.canceled
        return allowanceTxns(s, memberId, p).sumOf { (it.amount - (canceled[it.id] ?: 0)).coerceAtLeast(0) }
    }

    /**
     * 카드·계좌 금액에 넣는 거래. 가족 카드에서 용돈으로 옮긴 결제는 금액 자리(personal)가 대신 들어가므로
     * 본인 폰의 용돈 원본은 빼서 두 번 세지 않습니다(DECISIONS.md D-20).
     */
    fun onAccount(a: Account, t: Txn) = !(t.private && a.family)

    /** 사람별 지출에서 가족 카드 결제를 묶는 칸의 키. */
    const val FAMILY = "family"

    fun expense(e: List<Entry>) = e.filter { it.counted && it.txn.kind == Kind.EXPENSE }.sumOf { it.net }
    fun income(e: List<Entry>) = e.filter { it.counted && it.txn.kind == Kind.INCOME }.sumOf { it.net }

    /** 카테고리별 합계. [s]를 주면 하위 카테고리를 상위로 묶습니다(D-37 — 통계 도넛·예산). 엑셀은 세부 그대로. */
    fun byCategory(e: List<Entry>, kind: Kind, s: LedgerState? = null): List<Pair<String, Long>> =
        e.filter { it.counted && it.txn.kind == kind && it.net > 0 }
            .groupBy { (it.txn.categoryId ?: if (kind == Kind.INCOME) Defaults.INCOME_FALLBACK else Defaults.EXPENSE_FALLBACK).let { c -> s?.let { topCategory(it, c) } ?: c } }
            .map { (k, v) -> k to v.sumOf { it.net } }.sortedByDescending { it.second }

    /** 하위 카테고리면 상위 id, 아니면 그대로. 상위가 지워졌으면 그대로 둡니다. */
    fun topCategory(s: LedgerState, id: String): String = s.categories[id]?.parentId?.takeIf { s.categories.containsKey(it) } ?: id

    /** 사람별 합계. 가족 카드 결제는 쓴 사람이 아니라 [FAMILY] 칸으로 묶습니다(DECISIONS.md D-20). */
    fun byMember(e: List<Entry>, kind: Kind = Kind.EXPENSE, s: LedgerState? = null): List<Pair<String?, Long>> =
        e.filter { it.counted && it.txn.kind == kind && it.net > 0 }
            .groupBy { if (s?.account(it.txn.accountId)?.family == true) FAMILY else it.txn.memberId }
            .map { (k, v) -> k to v.sumOf { it.net } }.sortedByDescending { it.second }

    fun byDay(e: List<Entry>): Map<LocalDate, Pair<Long, Long>> =
        e.filter { it.counted && !it.allowanceSummary }.groupBy { Fmt.date(it.txn.at) }.mapValues { (_, v) ->
            v.filter { it.txn.kind == Kind.EXPENSE }.sumOf { it.net } to v.filter { it.txn.kind == Kind.INCOME }.sumOf { it.net }
        }

    /**
     * '이체'만 볼 때 달력 칸의 금액: 그날 옮긴 돈(이체와, 가계부 통장끼리 옮겨 합계에서 뺀 출금 쪽). 지출·수입 달력에는 이 돈이 안 보여
     * 어디로 얼마를 옮겼는지 날짜로 보려면 이 보기가 필요합니다(초록·비주얼 '제외 내역'). 둘째 값은 늘 0입니다.
     */
    fun byDayMoved(e: List<Entry>): Map<LocalDate, Pair<Long, Long>> =
        e.filter(::isMoved)
            .groupBy { Fmt.date(it.txn.at) }.mapValues { (_, v) -> v.sumOf { it.net } to 0L }

    /** 옮긴 돈 보기에 들어가는 내역: 이체, 또는 가계부 통장끼리 옮겨 합계에서 뺀 출금 쪽(입금 쪽은 두 번 세지 않게 뺌). */
    fun isMoved(e: Entry): Boolean = !e.allowanceSummary && e.txn.cancelOf == null &&
        (e.txn.kind == Kind.TRANSFER || e.txn.kind == Kind.EXPENSE && Moves.linked(e.txn))

    /**
     * 달력에 그리는 날들(D-30): [month] 1일이 든 주의 일요일부터 말일이 든 주의 토요일까지. 끝(second)은 그다음 날(배타).
     * 앞뒤 달 날짜도 흐리게 금액을 보여 주므로 내역도 이 범위로 읽습니다.
     */
    fun calendarRange(month: YearMonth): Pair<LocalDate, LocalDate> {
        val first = month.atDay(1)
        val last = month.atEndOfMonth()
        return first.minusDays((first.dayOfWeek.value % 7).toLong()) to last.plusDays((6 - last.dayOfWeek.value % 7).toLong() + 1)
    }

    /** [d]가 든 달력 한 줄(일요일~토요일)과 그 [byDay] 합계(지출 to 수입). 달 경계에 걸친 주도 일곱 날을 다 셉니다. */
    fun week(byDay: Map<LocalDate, Pair<Long, Long>>, d: LocalDate): Pair<ClosedRange<LocalDate>, Pair<Long, Long>> {
        val sunday = d.minusDays((d.dayOfWeek.value % 7).toLong())
        val days = (0L until 7L).map { sunday.plusDays(it) }
        return sunday..sunday.plusDays(6) to (days.sumOf { byDay[it]?.first ?: 0L } to days.sumOf { byDay[it]?.second ?: 0L })
    }

    fun topMerchants(e: List<Entry>, n: Int = 5): List<Pair<String, Long>> =
        e.filter { it.counted && it.txn.kind == Kind.EXPENSE && it.net > 0 && !it.allowanceSummary && it.txn.merchant.isNotBlank() }
            .groupBy { it.txn.merchant }.map { (k, v) -> k to v.sumOf { it.net } }.sortedByDescending { it.second }.take(n)

    /** 지난 n개 기간의 지출·수입 (오래된 것부터). */
    fun trend(s: LedgerState, myMemberId: String?, n: Int, today: LocalDate = LocalDate.now(Fmt.zone)): List<Triple<Period, Long, Long>> {
        val cur = current(s, today)
        return (n - 1 downTo 0).map { back ->
            val p = period(cur.label.minusMonths(back.toLong()), s.household.monthStartDay)
            val e = entries(s, p, myMemberId)
            Triple(p, expense(e), income(e))
        }
    }

    // --- 예산 -------------------------------------------------------------------------------------

    data class BudgetLine(val categoryId: String?, val budget: Long, val spent: Long) {
        val ratio: Float get() = if (budget <= 0) 0f else spent.toFloat() / budget
    }

    fun budgets(s: LedgerState, e: List<Entry>): Pair<BudgetLine?, List<BudgetLine>> {
        val spentBy = byCategory(e, Kind.EXPENSE, s).toMap()
        val total = s.household.budget.takeIf { it > 0 }?.let { BudgetLine(null, it, expense(e)) }
        val lines = s.budgets.values.filter { it.amount > 0 && s.categories.containsKey(it.categoryId) }
            .map { BudgetLine(it.categoryId, it.amount, spentBy[it.categoryId] ?: 0) }
            .sortedByDescending { it.ratio }
        return total to lines
    }

    // --- 계좌·카드 ------------------------------------------------------------------------------

    private fun live(s: LedgerState): Sequence<Pair<Txn, Long>> {
        val canceled = s.canceled
        return s.txns.values.asSequence().filter { it.cancelOf == null }
            .map { it to (it.amount - (canceled[it.id] ?: 0)).coerceAtLeast(0) }
    }

    /**
     * 신용카드 갚을 돈 = 결제일이 아직 오지 않은 이용액(할부는 남은 회차만).
     * 카드값 납부 이체로 계산하지 않는 까닭: 앱을 깔기 전 달의 카드값을 내면 카드가 "돈을 넣은" 것처럼 +가 되기 때문.
     */
    fun cardOutstanding(s: LedgerState, a: Account, today: LocalDate = LocalDate.now(Fmt.zone)): Long {
        val startDay = a.cycleStartDay ?: 1
        val bd = a.billingDay ?: DEFAULT_BILLING_DAY
        var sum = 0L
        for ((t, net) in live(s)) {
            if (t.accountId != a.id || t.kind != Kind.EXPENSE || net <= 0 || !onAccount(a, t)) continue
            val months = t.installment.coerceAtLeast(1)
            val tc = cycleStartOf(Fmt.date(t.at), startDay)
            for (i in 0 until months) {
                if (!dueDateFor(tc.plusMonths(i.toLong()), bd).isBefore(today)) sum += if (months == 1) net else installmentPart(net, months, i)
            }
        }
        return sum
    }

    /** 결제일을 모르는 카드는 흔한 14일로 어림합니다(카드 설정에서 바꾸면 정확해짐). */
    const val DEFAULT_BILLING_DAY = 14

    /**
     * 이 계좌가 잔액을 따로 세는지. 체크카드는 연결된 통장에서 바로 나가 돈이 머무르지 않으므로, 잔액을 정한 적이 없으면
     * 잔액(쓴 돈이 쌓여 계속 마이너스) 대신 이번 달 쓴 돈만 보여 줍니다. 예전에 체크카드에 잔액을 적어 통장처럼 쓰던 것은 그대로(D-29).
     */
    fun keepsBalance(a: Account): Boolean = when (a.type) {
        AccountType.CARD -> false
        AccountType.CHECK -> a.anchor != null || a.opening != 0L
        else -> true
    }

    /** 현재 잔액. 자산은 +, 카드·대출 빚은 −. 잔액을 세지 않는 체크카드는 0. */
    fun balance(s: LedgerState, a: Account, today: LocalDate = LocalDate.now(Fmt.zone)): Long {
        if (a.type == AccountType.CARD) return -cardOutstanding(s, a, today)
        if (!keepsBalance(a)) return 0
        val baseAt = a.anchorAt ?: a.openingAt
        var bal = a.anchor ?: a.opening
        // 이 통장에 이은 체크카드의 결제도 이 통장에서 나간 돈입니다(D-33, `Account.payFromId`).
        val cards = if (a.type == AccountType.BANK) Banks.linkedCards(s, a).mapTo(HashSet()) { it.id } else emptySet()
        fun covered(at: Long) = at <= baseAt && a.anchorAt != null || at < baseAt
        // 이은 카드는 결제와 취소를 각자의 시각으로 셉니다: 잔액 알림 전에 결제하고 뒤에 취소되면 돌아온 돈만 더해야 해서(#77 리뷰).
        // 아래 live(s)는 취소를 원래 결제에 접어 결제 시각 하나로만 보므로 쓰지 않습니다.
        if (cards.isNotEmpty()) for (t in s.txns.values) {
            if (t.accountId == null || t.accountId !in cards || !Banks.countsOnBank(t) || covered(t.at)) continue
            bal += Gaps.delta(t) ?: 0L
        }
        // 이 통장에 바로 기록된 결제(토스뱅크 체크카드처럼 통장으로 들어온 결제)도 취소를 각자의 시각으로 셉니다(#78):
        // 잔액 알림 전에 결제하고 뒤에 취소되면 돌아온 돈만 더해야 합니다. 결제는 원래 금액으로 빼고 취소는 아래에서 따로 더합니다.
        for (c in s.txns.values) {
            val orig = c.cancelOf?.let { s.txns[it] } ?: continue
            if (orig.accountId != a.id || orig.kind != Kind.EXPENSE || !onAccount(a, orig) || covered(c.at)) continue
            bal += c.amount
        }
        for ((t, net) in live(s)) {
            if (covered(t.at)) continue
            if (t.accountId != null && t.accountId in cards) continue
            if (!onAccount(a, t)) continue
            when (t.kind) {
                Kind.EXPENSE -> if (t.accountId == a.id) bal -= t.amount
                Kind.INCOME -> if (t.accountId == a.id) bal += net
                Kind.TRANSFER -> {
                    if (t.accountId == a.id) bal -= net
                    if (t.toAccountId == a.id) bal += net
                }
            }
        }
        return bal
    }

    data class NetWorth(val assets: Long, val debts: Long) { val net get() = assets + debts }

    fun netWorth(s: LedgerState, today: LocalDate = LocalDate.now(Fmt.zone)): NetWorth {
        var assets = 0L
        var debts = 0L
        for (a in s.accounts.values) {
            if (a.hidden) continue
            val b = balance(s, a, today)
            if (a.type.asset) assets += b else debts += b
        }
        return NetWorth(assets, debts)
    }

    /** 카드 이용 기간: cycleStartDay(기본 1일)부터 한 달. 결제일은 기간이 끝난 뒤 처음 오는 billingDay. */
    fun cycleStartOf(date: LocalDate, startDay: Int): LocalDate {
        val d = startDay.coerceIn(1, 28)
        val ym = YearMonth.from(date)
        return if (date.dayOfMonth >= d) ym.atDay(d) else ym.minusMonths(1).atDay(d)
    }

    fun dueDateFor(cycleStart: LocalDate, billingDay: Int): LocalDate {
        val end = cycleStart.plusMonths(1)
        var ym = YearMonth.from(end)
        while (true) {
            val due = ym.atDay(billingDay.coerceIn(1, ym.lengthOfMonth()))
            if (!due.isBefore(end)) return due
            ym = ym.plusMonths(1)
        }
    }

    fun card(s: LedgerState, a: Account, today: LocalDate = LocalDate.now(Fmt.zone)): CardStatus {
        val startDay = a.cycleStartDay ?: 1
        val month = current(s, today)
        val cur = cycleStartOf(today, startDay)
        val cardTxns = live(s).filter { (t, _) -> t.accountId == a.id && t.kind == Kind.EXPENSE && onAccount(a, t) }.toList()
        val monthSpent = cardTxns.filter { (t, _) -> t.at in month }.sumOf { it.second }
        val cycleSpent = cardTxns.filter { (t, _) -> !Fmt.date(t.at).isBefore(cur) }.sumOf { it.second }

        // 다음에 낼 청구서: 직전 기간의 결제일이 아직 안 지났으면 그것, 지났으면 이번 기간.
        var due: LocalDate? = null
        var dueAmount = 0L
        a.billingDay?.let { bd ->
            val prev = cur.minusMonths(1)
            val billCycle = if (!dueDateFor(prev, bd).isBefore(today)) prev else cur
            due = dueDateFor(billCycle, bd)
            for ((t, net) in cardTxns) {
                val tc = cycleStartOf(Fmt.date(t.at), startDay)
                val months = t.installment.coerceAtLeast(1)
                val idx = ChronoUnit.MONTHS.between(YearMonth.from(tc), YearMonth.from(billCycle))
                if (idx in 0 until months) dueAmount += if (months == 1) net else installmentPart(net, months, idx.toInt())
            }
        }
        val last = s.txns.values.filter { it.accountId == a.id && it.cumulative != null }.maxByOrNull { it.at }
        return CardStatus(a, monthSpent, cur, cycleSpent, due, dueAmount, last?.cumulative, last?.at)
    }

    /** 할부 n개월 중 i번째 달 금액. 나머지 원 단위는 첫 달에 붙입니다(카드사 관례). */
    fun installmentPart(total: Long, months: Int, i: Int): Long {
        val base = total / months
        return if (i == 0) base + total % months else base
    }

    // --- 고정지출 -------------------------------------------------------------------------------

    /** [d]가 토·일이면 앞 금요일. 월급날이 주말이면 앞 평일에 들어오기 때문입니다(DECISIONS.md D-27). 공휴일은 보지 않습니다. */
    fun beforeWeekend(d: LocalDate): LocalDate = when (d.dayOfWeek) {
        DayOfWeek.SATURDAY -> d.minusDays(1)
        DayOfWeek.SUNDAY -> d.minusDays(2)
        else -> d
    }

    /**
     * 월 용돈의 [ym] 달 몫이 들어가는 날. 정한 날([day])이 토·일이나 공휴일이면 월급이 먼저 들어오는 앞 영업일입니다(D-27, D-38 — 설·추석 연휴 포함).
     * 다만 앞당긴 날이 가계부 한 달([startDay]부터) 경계를 넘으면 앞당기지 않습니다: 정한 날이 한 달 시작일과 같거나 바로 뒤면
     * 그달 몫이 앞 달 합계로 넘어가 앞 달엔 두 번, 그달엔 0번 들어가기 때문입니다(#53 리뷰).
     */
    fun allowanceDue(ym: YearMonth, day: Int, startDay: Int): LocalDate {
        val nominal = ym.atDay(day.coerceIn(1, ym.lengthOfMonth()))
        val early = Holidays.beforeOff(nominal)
        return if (periodOf(early, startDay).key == periodOf(nominal, startDay).key) early else nominal
    }

    /**
     * [from]~[to] 사이에 [r]이 장부에 들어갈 날. 월 용돈 규칙(D-20)은 월급날 받는 돈이라 그날이 토·일이면 앞 금요일에
     * 들어갑니다([allowanceDue], D-27). [startDay]는 가구 한 달 시작일이고, 모르면(null) 앞당기지 않습니다.
     * 다른 고정 규칙(자동이체·구독)은 은행이 대개 다음 영업일에 빼 가므로 그대로 둡니다.
     */
    fun dueDates(r: Recurring, from: LocalDate, to: LocalDate, startDay: Int? = null): List<LocalDate> =
        occurrences(r, from, to, startDay).map { it.second }

    /** [dueDates]와 같되 (정한 날, 들어가는 날) 쌍. 앞당긴 용돈만 둘이 다릅니다(옛 버전이 정한 날로 만든 기록을 찾을 때). */
    fun occurrences(r: Recurring, from: LocalDate, to: LocalDate, startDay: Int? = null): List<Pair<LocalDate, LocalDate>> {
        if (r.paused) return emptyList()
        val start = maxOf(from, Fmt.date(r.startAt))
        val end = r.endAt?.let { minOf(to, Fmt.date(it)) } ?: to
        if (start.isAfter(end)) return emptyList()
        val out = ArrayList<Pair<LocalDate, LocalDate>>()
        when (r.period) {
            "w" -> {
                var d = start
                val dow = DayOfWeek.of(r.day.coerceIn(1, 7))
                while (d.dayOfWeek != dow) d = d.plusDays(1)
                while (!d.isAfter(end)) { out += d to d; d = d.plusWeeks(1) }
            }
            "y" -> for (y in start.year..end.year) {
                val ym = YearMonth.of(y, r.month.coerceIn(1, 12))
                val d = ym.atDay(r.day.coerceIn(1, ym.lengthOfMonth()))
                if (!d.isBefore(start) && !d.isAfter(end)) out += d to d
            }
            else -> {
                val payday = startDay?.takeIf { AllowancePlan.isRule(r.id) }
                // 앞당긴 날은 앞 달 말일에 올 수 있어(1·2일이 토·일) 한 달 더 봅니다. 그 밖의 날은 범위 밖이라 걸러져요.
                val last = YearMonth.from(end).plusMonths(1)
                var ym = YearMonth.from(start)
                while (!ym.isAfter(last)) {
                    val nominal = ym.atDay(r.day.coerceIn(1, ym.lengthOfMonth()))
                    val d = payday?.let { allowanceDue(ym, r.day, it) } ?: nominal
                    if (!d.isBefore(start) && !d.isAfter(end)) out += nominal to d
                    ym = ym.plusMonths(1)
                }
            }
        }
        return out
    }

    /**
     * [from]~[to](둘 다 포함) 사이에 날짜가 잡혔지만 아직 장부에 들어오지 않은 고정지출(지출 규칙만, 용돈 규칙 포함).
     * 홈 예산 게이지의 '앞으로 나갈 고정지출' 몫과 예산 화면의 미리 보기에 씁니다.
     * 이미 들어온 것을 빼는 기준은 규칙이 내역을 만들 때(Hooks.daily)와 같습니다: 매달 규칙은 같은 가계부 한 달에 이어진 내역이 있으면,
     * 그 밖은 정한 날 앞뒤 3일 안에 이어진 내역이 있으면 들어온 것으로 봅니다.
     */
    fun ahead(s: LedgerState, from: LocalDate, to: LocalDate, includeSenders: Boolean = true): List<Pair<Recurring, LocalDate>> {
        if (to.isBefore(from)) return emptyList()
        val linked = s.txns.values.filter { it.recurringId != null }.groupBy { it.recurringId }
        val near = 3L * 86_400_000
        // 보내 준 가족 내역을 합계에서 뺐으면([entries]의 includeSenders) 그 가족의 규칙도 몫에서 뺍니다(#86 리뷰).
        return s.recurrings.values.filter { it.kind == Kind.EXPENSE && (includeSenders || s.member(it.memberId)?.isSender != true) }.flatMap { r ->
            dueDates(r, from, to, s.household.monthStartDay).filter { d ->
                val at = Fmt.millis(d.atTime(9, 0))
                val period = if (r.period == "m") periodOf(d, s.household.monthStartDay) else null
                linked[r.id].orEmpty().none { kotlin.math.abs(it.at - at) <= near || (period != null && it.at in period) }
            }.map { r to it }
        }.sortedWith(compareBy({ it.second }, { it.first.name }))
    }

    /**
     * 예산 게이지의 세 몫: 쓴 돈 · 앞으로 나갈 고정지출([reserved]) · 마음대로 쓸 수 있는 돈([free]).
     * 하루 쓸 수 있는 돈은 고정지출 몫을 뺀 뒤 남은 날로 나눕니다(전에는 빼지 않아 실제보다 넉넉하게 보였어요).
     */
    data class BudgetSplit(val budget: Long, val spent: Long, val reserved: Long) {
        val left get() = budget - spent
        val free get() = left - reserved
        fun perDay(daysLeft: Int): Long = if (free > 0) free / daysLeft.coerceAtLeast(1) else 0
    }

    /**
     * 지난 가계부 한 달의 '같은 때'까지: 이번 달 시작에서 오늘까지 지난 날수만큼 지난달 시작에서 센 기간(뱅크샐러드·똑똑 "지난달 이때보다").
     * 지난달이 더 짧으면 그 달 끝에서 멈춥니다. 오늘이 이번 달 첫날이면 지난달 첫날 하루입니다.
     */
    fun sameTimeLastPeriod(p: Period, today: LocalDate, startDay: Int): Period {
        val prev = periodOf(p.start.minusDays(1), startDay)
        val elapsed = ChronoUnit.DAYS.between(p.start, today).coerceAtLeast(0)
        val end = minOf(prev.start.plusDays(elapsed + 1), prev.end)
        return Period(prev.label, prev.start, end)
    }

    /**
     * 이번 달 페이스(DECISIONS.md D-35, 비주얼 가계부 예산 보고서): 지금 속도면 이 달에 얼마를 쓰게 되고 언제 예산을 넘는지.
     *
     * 월말 예상 = 쓴 돈 + 아직 안 나간 고정지출([ahead]) + 평소 하루 평균 × 내일부터 남은 날.
     * '평소'는 고정 규칙에 이어지지 않은 지출이라, 월세·보험·용돈처럼 초순에 몰려 나가는 돈이 월초 예상을 부풀리지 않습니다.
     * 한 달의 첫 3일은 평균이 크게 흔들려 셈하지 않습니다(null). [budget]이 0이면 [overDay]는 없습니다.
     * 미래 날짜로 미리 적은 지출은 이미 쓴 돈([spent])에는 들어가지만 하루 평균에는 넣지 않고, 그날 나가는 돈으로 셉니다(#86 리뷰).
     */
    data class Pace(val projected: Long, val dailyVariable: Long, val overDay: LocalDate?, val elapsedDays: Int)

    const val PACE_MIN_DAYS = 3

    fun pace(e: List<Entry>, ahead: List<Pair<Recurring, LocalDate>>, p: Period, today: LocalDate, budget: Long): Pace? {
        val elapsed = (ChronoUnit.DAYS.between(p.start, today) + 1).toInt()
        if (elapsed < PACE_MIN_DAYS || !today.isBefore(p.end)) return null
        val spent = expense(e)
        val tomorrow = Fmt.millis(today.plusDays(1))
        val outflow = e.filter { it.counted && it.txn.kind == Kind.EXPENSE }
        val (later, sofar) = outflow.partition { it.txn.at >= tomorrow }
        val variable = sofar.filter { it.txn.recurringId == null }.sumOf { it.net }
        val daily = variable / elapsed
        val laterBy = later.groupBy({ Fmt.date(it.txn.at) }, { it.net }).mapValues { it.value.sum() }
        val restDays = ChronoUnit.DAYS.between(today, p.end).toInt() - 1 // 내일부터 이 달 끝까지
        val fixedBy = ahead.groupBy({ it.second }, { it.first.amount }).mapValues { it.value.sum() }
        val projected = spent + ahead.sumOf { it.first.amount } + daily * restDays.coerceAtLeast(0)
        // 이미 넘었으면 넘는 날을 따로 칠하지 않습니다(홈이 "예산보다 ○원 더 썼어요"라고 말함).
        // 오늘 아직 안 나간 고정지출이 있으면 오늘부터 더해 봅니다.
        var overDay: LocalDate? = null
        val spentSoFar = sofar.sumOf { it.net }
        if (budget > 0 && spentSoFar <= budget) {
            var cum = spentSoFar + (fixedBy[today] ?: 0)
            if (cum > budget) overDay = today
            var d = today.plusDays(1)
            while (overDay == null && d.isBefore(p.end)) {
                cum += daily + (fixedBy[d] ?: 0) + (laterBy[d] ?: 0)
                if (cum > budget) overDay = d
                d = d.plusDays(1)
            }
        }
        return Pace(projected, daily, overDay, elapsed)
    }

    /** 홈과 같은 기준(이 폰의 한 달, 보내는 가족 포함 여부)으로 지금 페이스. 내역 달력의 예상 초과일에 씁니다. */
    fun paceNow(s: LedgerState, today: LocalDate, startDay: Int, myMemberId: String?, includeSenders: Boolean): Pace? {
        val p = current(s, today, startDay)
        val e = entries(s, p, myMemberId, includeSenders)
        return pace(e, ahead(s, today, p.end.minusDays(1), includeSenders), p, today, budgets(s, e).first?.budget ?: 0)
    }

    /**
     * 알림창 한 줄의 제목·내용: "오늘 15,000원 썼어요" / "남은 예산 380,000원 · 이번 달 1,620,000원". 홈과 같은 셈(이 폰의 한 달, 보내는 가족 설정).
     * 예산이 없으면 이번 달 쓴 돈만, 예산을 넘었으면 넘은 금액.
     */
    fun statusLine(s: LedgerState, today: LocalDate, startDay: Int, myMemberId: String?, includeSenders: Boolean): Pair<String, String> {
        val p = current(s, today, startDay)
        val e = entries(s, p, myMemberId, includeSenders)
        val todayRange = Period(p.label, today, today.plusDays(1))
        val spentToday = expense(e.filter { !it.allowanceSummary && it.txn.at in todayRange })
        val month = expense(e)
        val budget = budgets(s, e).first?.budget ?: 0
        val title = if (spentToday > 0) "오늘 ${Fmt.won(spentToday)} 썼어요" else "오늘은 아직 쓴 돈이 없어요"
        val text = when {
            budget <= 0 -> "이번 달 ${Fmt.won(month)}"
            month <= budget -> "남은 예산 ${Fmt.won(budget - month)} · 이번 달 ${Fmt.won(month)}"
            else -> "예산보다 ${Fmt.won(month - budget)} 더 썼어요 · 이번 달 ${Fmt.won(month)}"
        }
        return title to text
    }

    fun upcoming(s: LedgerState, days: Int, today: LocalDate = LocalDate.now(Fmt.zone)): List<Pair<Recurring, LocalDate>> =
        s.recurrings.values.flatMap { r -> dueDates(r, today.plusDays(1), today.plusDays(days.toLong()), s.household.monthStartDay).map { r to it } }
            .sortedBy { it.second }

    // --- 저축 목표 -------------------------------------------------------------------------------

    fun goalSaved(s: LedgerState, g: com.jeiel85.doran.model.Goal): Long =
        g.accountId?.let { id -> s.account(id)?.let { balance(s, it) } } ?: g.entries.sumOf { it.amount }
}
