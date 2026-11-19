package com.jeiel85.doran.capture

import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.model.Defaults
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.MerchantRule

/** 가맹점 이름으로 카테고리를 고릅니다: 직접 고친 기록(학습 규칙) → 키워드 사전 → 기타. */
object Categorizer {
    /** "스타벅스 성수점", "(주)스타벅스코리아" 같은 변형을 한 규칙으로 묶기 위한 정규화. */
    fun normalize(merchant: String): String =
        merchant.uppercase()
            .replace(Regex("""\(주\)|주식회사|㈜|\(유\)|유한회사"""), "")
            .trim()
            // 지점명은 띄어 쓴 마지막 단어일 때만 뗍니다("이마트 성수점" → 이마트). 붙여 쓴 "본죽전문점"은 그대로.
            .replace(Regex("""\s+\S{1,10}점$"""), "")
            .replace(Regex("""[^0-9A-Z가-힣]"""), "")
            .take(24)

    fun ruleId(merchant: String) = "r." + normalize(merchant)

    /**
     * 이 가맹점에 배운 규칙. 지금 이름의 규칙이 없으면 0.1.13 전 해석기가 남긴 이름의 규칙도 봅니다(#19):
     * 그때는 괄호로 나눈 조각 중 글자가 가장 많은 것을 가맹점으로 썼습니다("씨유(CU)가산하이엔드6차점" → "가산하이엔드6차점").
     */
    fun rule(s: LedgerState, merchant: String): MerchantRule? {
        s.rules[ruleId(merchant)]?.let { return it }
        return legacyName(merchant)?.let { s.rules[ruleId(it)] }
    }

    /** 0.1.13 전 해석기가 괄호 든 가맹점에서 골랐을 이름. 괄호가 없으면 null. */
    fun legacyName(merchant: String): String? {
        if ('(' !in merchant && ')' !in merchant) return null
        return merchant.split('(', ')').map { it.trim() }
            .filter { p -> p.length >= 2 && !p.all { it.isDigit() || it == ' ' } }
            .maxByOrNull { p -> p.count { it.isLetter() } * 2 - p.count { it.isDigit() } }
            ?.takeIf { it != merchant.trim() }
    }

    fun categorize(s: LedgerState, merchant: String?, kind: Kind): String? {
        if (kind == Kind.TRANSFER) return null
        val m = merchant?.trim().orEmpty()
        if (m.isNotEmpty()) {
            rule(s, m)?.categoryId?.takeIf { s.categories.containsKey(it) }?.let { return it }
            val up = m.uppercase()
            if (kind == Kind.INCOME) {
                if (listOf("급여", "월급", "상여", "SALARY").any { up.contains(it) }) return "c.salary"
                if (listOf("이자", "배당", "결산").any { up.contains(it) }) return "c.interest"
                return Defaults.INCOME_FALLBACK
            }
            for ((cat, words) in Defaults.keywords) {
                if (words.any { up.contains(it.uppercase()) } && s.categories[cat]?.hidden != true) return cat
            }
        }
        return if (kind == Kind.INCOME) Defaults.INCOME_FALLBACK else Defaults.EXPENSE_FALLBACK
    }
}
