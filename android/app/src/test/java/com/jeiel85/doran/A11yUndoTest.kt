package com.jeiel85.doran

import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.ui.Undo
import com.jeiel85.doran.ui.spokenTxn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 내역 줄의 화면 읽기 문장과 지운 뒤 되돌리기(BENCHMARK.md 7-4). */
class A11yUndoTest {
    @Test fun spokenRowSaysKindAndAmount() {
        assertEquals("스타벅스, 지출 5,000원, 현대카드, 오후 2:30",
            spokenTxn("스타벅스", Kind.EXPENSE, 5_000, false, listOf("현대카드", "오후 2:30")))
        assertEquals("월급, 수입 3,000,000원", spokenTxn("월급", Kind.INCOME, 3_000_000, false, emptyList()))
        assertEquals("쿠팡, 돌아온 돈 12,000원", spokenTxn("쿠팡", Kind.EXPENSE, 12_000, true, emptyList()))
        // 화살표·가운뎃점은 말로
        assertEquals("이체, 이체 100,000원, 받는 곳 신한은행, 옮긴 돈, 합계 제외",
            spokenTxn("이체", Kind.TRANSFER, 100_000, false, listOf("→ 신한은행", "옮긴 돈 · 합계 제외")))
    }

    @Test fun undoRestoresAndSkipsCleanup() {
        var restored = 0; var cleaned = 0
        Undo.show("지웠어요", { restored++ }, { cleaned++ })
        Undo.undo()
        assertEquals(1, restored)
        assertEquals(0, cleaned)
        assertNull(Undo.pending)
    }

    @Test fun expiringRunsCleanupOnce() {
        var cleaned = 0
        Undo.show("지웠어요", {}, { cleaned++ })
        val p = Undo.pending!!
        Undo.expire(p)
        Undo.expire(p)
        assertEquals(1, cleaned)
        Undo.undo() // 이미 끝났으면 아무것도 안 함
        assertNull(Undo.pending)
    }

    @Test fun newDeleteFinishesThePreviousOne() {
        var first = 0; var second = 0
        Undo.show("하나", {}, { first++ })
        Undo.show("둘", {}, { second++ })
        assertEquals(1, first)
        assertEquals(0, second)
        assertEquals("둘", Undo.pending!!.message)
        Undo.expire(Undo.pending!!)
        assertEquals(1, second)
    }
}
