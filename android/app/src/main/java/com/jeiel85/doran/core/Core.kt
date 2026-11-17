package com.jeiel85.doran.core

import com.jeiel85.doran.crypto.Crypto
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 22자 무작위 id (16바이트). 엔티티·기기·blob id에 씁니다. */
fun newId(): String = Crypto.b64(Crypto.randomBytes(16))

/**
 * 하이브리드 논리 시계. 문자열 "ms(13)-counter(5)-device"는 사전순 비교가 곧 시간순 비교라
 * 서버(SQL 문자열 비교)와 폰이 같은 판단을 합니다 (DECISIONS.md D-2).
 */
class Hlc(private val device: String, private val clock: () -> Long = System::currentTimeMillis) {
    private var lastMs = 0L
    private var counter = 0

    @Synchronized
    fun next(): String {
        val now = clock()
        if (now > lastMs) {
            lastMs = now
            counter = 0
        } else {
            counter++
            if (counter > 99_999) { lastMs++; counter = 0 }
        }
        return format(lastMs, counter, device)
    }

    /** 다른 폰의 시각이 내 시계보다 앞서 있으면 따라가서, 내 다음 수정이 그보다 늦게 정렬되게 합니다. */
    @Synchronized
    fun observe(remote: String) {
        val ms = remote.substringBefore('-').toLongOrNull() ?: return
        val c = remote.substringAfter('-').substringBefore('-').toIntOrNull() ?: return
        if (ms > lastMs || (ms == lastMs && c > counter)) {
            lastMs = ms
            counter = c
        }
    }

    companion object {
        const val SEED = "0000000000000-00000-seed"
        fun format(ms: Long, counter: Int, device: String) =
            "${ms.toString().padStart(13, '0')}-${counter.toString().padStart(5, '0')}-$device"
        fun millis(h: String): Long = h.substringBefore('-').toLongOrNull() ?: 0

        /** 같은 (ms, counter)에서 가장 낮게 정렬되는 값. 기기 자리 "-"는 base64url 문자 중 가장 작습니다. */
        fun lowestAt(h: String): String {
            val ms = h.substringBefore('-')
            val counter = h.substringAfter('-').substringBefore('-')
            return "$ms-$counter--"
        }
    }
}

object Fmt {
    val zone: ZoneId get() = ZoneId.systemDefault()
    private val comma = java.text.DecimalFormat("#,###")

    fun won(amount: Long): String = comma.format(amount) + "원"
    fun num(amount: Long): String = comma.format(amount)
    fun signed(amount: Long, plus: Boolean): String = (if (plus) "+" else "-") + comma.format(amount)

    /** 달력 칸처럼 좁은 곳: 12,345 → 1.2만, 1,234,567 → 123만. */
    fun compact(amount: Long): String {
        val a = kotlin.math.abs(amount)
        val s = when {
            a >= 100_000_000 -> String.format(Locale.KOREA, "%.1f억", a / 100_000_000.0).replace(".0억", "억")
            a >= 1_000_000 -> "${a / 10_000}만"
            a >= 10_000 -> String.format(Locale.KOREA, "%.1f만", a / 10_000.0).replace(".0만", "만")
            else -> comma.format(a)
        }
        return if (amount < 0) "-$s" else s
    }

    fun date(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    fun dateTime(ms: Long): LocalDateTime = Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()
    fun millis(d: LocalDate): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()
    fun millis(dt: LocalDateTime): Long = dt.atZone(zone).toInstant().toEpochMilli()

    private val dayFmt = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)
    private val timeFmt = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)
    private val fullFmt = DateTimeFormatter.ofPattern("yyyy년 M월 d일 (E) a h:mm", Locale.KOREAN)
    fun dayLabel(d: LocalDate): String {
        val today = LocalDate.now(zone)
        return when (d) {
            today -> "오늘 · " + d.format(dayFmt)
            today.minusDays(1) -> "어제 · " + d.format(dayFmt)
            else -> d.format(dayFmt)
        }
    }
    fun time(ms: Long): String = dateTime(ms).format(timeFmt)
    fun full(ms: Long): String = dateTime(ms).format(fullFmt)
    fun month(ym: YearMonth): String =
        if (ym.year == YearMonth.now(zone).year) "${ym.monthValue}월" else "${ym.year}년 ${ym.monthValue}월"

    fun relative(ms: Long, now: Long = System.currentTimeMillis()): String {
        if (ms <= 0) return "아직 없음"
        val d = (now - ms) / 1000
        return when {
            d < 60 -> "방금"
            d < 3600 -> "${d / 60}분 전"
            d < 86_400 -> "${d / 3600}시간 전"
            d < 86_400 * 30 -> "${d / 86_400}일 전"
            else -> date(ms).toString()
        }
    }
}
