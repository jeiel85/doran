package com.jeiel85.doran.logic

import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Recurring
import java.time.LocalDate
import java.time.YearMonth

/** 연간 리포트(편한가계부 결산·가계부부 연말 회고)와 월말 마감(위트리 체크리스트). */
object Review {
    data class Month(val period: Period, val expense: Long, val income: Long, val future: Boolean)

    data class Year(
        val year: Int,
        val months: List<Month>,
        val expense: Long,
        val income: Long,
        /** 지난 달(또는 기록이 있는 달)만으로 낸 월평균 지출. 올해 아직 오지 않은 달은 빼고 셉니다. */
        val monthlyAvg: Long,
        val maxMonth: Month?,
        val byCategory: List<Pair<String, Long>>,
        val topMerchants: List<Pair<String, Long>>,
        val byMember: List<Pair<String?, Long>>,
    )

    /** [year]년 1~12월(이 폰의 한 달 시작일 기준) 결산. */
    fun year(s: LedgerState, year: Int, startDay: Int, myMemberId: String?, includeSenders: Boolean, today: LocalDate): Year {
        val now = Stats.periodOf(today, startDay).label
        val periods = (1..12).map { Stats.period(YearMonth.of(year, it), startDay) }
        val perMonth = periods.map { p -> p to Stats.entries(s, p, myMemberId, includeSenders) }
        val months = perMonth.map { (p, e) -> Month(p, Stats.expense(e), Stats.income(e), p.label.isAfter(now)) }
        val all = perMonth.flatMap { it.second }
        val counted = months.filter { !it.future && (it.expense > 0 || it.income > 0) }
        return Year(
            year = year, months = months, expense = months.sumOf { it.expense }, income = months.sumOf { it.income },
            monthlyAvg = if (counted.isEmpty()) 0 else counted.sumOf { it.expense } / counted.size,
            maxMonth = months.filter { it.expense > 0 }.maxByOrNull { it.expense },
            byCategory = Stats.byCategory(all, Kind.EXPENSE, s),
            topMerchants = Stats.topMerchants(all, 10),
            byMember = Stats.byMember(all, Kind.EXPENSE, s),
        )
    }

    /** 월말 마감을 홈에 띄울 때: 한 달의 마지막 [DAYS]일, 또는 다음 달 첫 [DAYS]일(지난달을 마감). 그 마감할 한 달을 돌려줍니다. */
    const val DAYS = 3

    fun closingPeriod(today: LocalDate, startDay: Int): Period? {
        val cur = Stats.periodOf(today, startDay)
        return when {
            !today.isBefore(cur.end.minusDays(DAYS.toLong())) -> cur
            today.isBefore(cur.start.plusDays(DAYS.toLong())) -> Stats.periodOf(cur.start.minusDays(1), startDay)
            else -> null
        }
    }

    data class Closing(
        val period: Period,
        val incomeCount: Int,
        val income: Long,
        /** 확인 필요 표시가 남은 내역 수(원화 미확정 해외 결제 등). */
        val needsReview: Int,
        /** 날짜가 지났는데 장부에 들어오지 않은 고정지출. */
        val missedFixed: List<Pair<Recurring, LocalDate>>,
        val expense: Long,
    ) {
        val left get() = income - expense
    }

    fun closing(s: LedgerState, p: Period, today: LocalDate, myMemberId: String?, includeSenders: Boolean): Closing {
        val e = Stats.entries(s, p, myMemberId, includeSenders)
        val incomes = e.filter { it.counted && !it.allowanceSummary && it.txn.kind == Kind.INCOME }
        val lastDay = minOf(today.minusDays(1), p.end.minusDays(1))
        return Closing(
            period = p, incomeCount = incomes.size, income = incomes.sumOf { it.net },
            needsReview = e.count { it.txn.needsReview && !it.allowanceSummary },
            missedFixed = Stats.ahead(s, p.start, lastDay, includeSenders),
            expense = Stats.expense(e),
        )
    }
}
