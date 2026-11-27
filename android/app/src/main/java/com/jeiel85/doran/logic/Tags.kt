package com.jeiel85.doran.logic

import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.model.Txn

/** 태그(DECISIONS.md D-36): 여행·경조사·명절처럼 카테고리를 가로지르는 묶음. */
object Tags {
    const val MAX_LEN = 20
    const val MAX_PER_TXN = 10

    /** 사용자가 친 글을 태그로: 띄어쓰기·쉼표로 나누고 앞의 '#'을 떼고, 같은 것은 한 번만. */
    fun parse(input: String): List<String> =
        input.split(' ', ',', '\n', '\t').map { it.trim().trimStart('#').trim().take(MAX_LEN) }.filter { it.isNotEmpty() }.distinct()

    /** [current]에 [input]을 더한 목록(이미 있는 것은 그대로, 최대 [MAX_PER_TXN]개). */
    fun add(current: List<String>, input: String): List<String> = (current + parse(input)).distinct().take(MAX_PER_TXN)

    /** 장부에 쓰인 태그를 많이 쓴 순으로(같으면 가나다). 쓰기 화면의 추천과 검색 칩에 씁니다. */
    fun used(s: LedgerState): List<String> =
        s.txns.values.flatMap { it.tags }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }

    /** 검색어가 태그를 찾는지: "#여행"은 그 태그만, 그냥 "여행"은 태그에도 맞으면. */
    fun matches(t: Txn, query: String): Boolean {
        val q = query.trim()
        return if (q.startsWith("#")) q.removePrefix("#").trim().let { tag -> tag.isNotEmpty() && t.tags.any { it == tag } }
        else q.isNotEmpty() && t.tags.any { it.contains(q, ignoreCase = true) }
    }
}
