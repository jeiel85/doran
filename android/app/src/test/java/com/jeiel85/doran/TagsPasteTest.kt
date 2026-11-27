package com.jeiel85.doran

import com.jeiel85.doran.capture.Pasted
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.logic.Tags
import com.jeiel85.doran.model.Account
import com.jeiel85.doran.model.AccountType
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** 태그(D-36)와 문자 붙여 넣어 쓰기. */
class TagsPasteTest {
    @Test fun tagsAreParsedWithoutHashAndDeduped() {
        assertEquals(listOf("여행", "제주"), Tags.parse("#여행, 제주 #여행"))
        assertEquals(listOf("여행", "경조사"), Tags.add(listOf("여행"), "경조사 여행"))
        assertEquals(Tags.MAX_PER_TXN, Tags.add(emptyList(), (1..20).joinToString(" ") { "t$it" }).size)
    }

    @Test fun tagsSurviveJsonAndOldWritersKeepThem() {
        val t = Txn("a", Kind.EXPENSE, 1_000, 0, tags = listOf("여행"))
        assertEquals(listOf("여행"), Txn.from("a", t.json()).tags)
        // 태그를 모르는 옛 내역(칸이 없음)은 빈 목록
        assertEquals(emptyList<String>(), Txn.from("a", JSONObject().put("kind", "e").put("amount", 1)).tags)
    }

    @Test fun usedTagsAndSearch() {
        val s = LedgerState(txns = listOf(
            Txn("a", Kind.EXPENSE, 1, 0, tags = listOf("여행", "제주")),
            Txn("b", Kind.EXPENSE, 1, 0, tags = listOf("여행")),
            Txn("c", Kind.EXPENSE, 1, 0, tags = listOf("경조사")),
        ).associateBy { it.id })
        assertEquals(listOf("여행", "경조사", "제주"), Tags.used(s))
        val a = s.txns.getValue("a")
        assertTrue(Tags.matches(a, "#제주"))
        assertFalse(Tags.matches(a, "#제")) // '#'은 태그 그대로만
        assertTrue(Tags.matches(a, "제")) // 그냥 글은 태그 일부에도
    }

    @Test fun pastedCardTextFillsADraft() {
        val card = Account("acc.c", "현대카드", AccountType.CARD, institution = "현대카드", last4 = "1234")
        val other = Account("acc.d", "현대카드 2", AccountType.CARD, institution = "현대카드", last4 = "9999")
        val s = LedgerState(accounts = listOf(card, other).associateBy { it.id })
        val now = LocalDateTime.of(2026, 11, 27, 12, 0)
        val p = Pasted.parse("현대카드(1234) 승인 홍*동 12,300원 일시불 11/27 12:10 스타벅스", now)!!
        val d = Pasted.draft(s, p, "h", now)
        assertEquals(Kind.EXPENSE, d.kind)
        assertEquals(12_300L, d.amount)
        assertEquals("acc.c", d.accountId) // 끝자리로 고름
        assertEquals(Source.MANUAL, d.source)
        assertEquals("h", d.memberId)
        assertNull(Pasted.parse("   "))
        assertNull(Pasted.parse("안녕하세요 오늘 날씨가 좋네요"))
    }
}
