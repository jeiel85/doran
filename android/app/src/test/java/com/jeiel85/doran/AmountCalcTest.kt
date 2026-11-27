package com.jeiel85.doran

import com.jeiel85.doran.logic.AmountCalc
import com.jeiel85.doran.logic.AmountCalc.Companion.MINUS
import com.jeiel85.doran.logic.AmountCalc.Companion.PLUS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 금액 자판의 더하기·빼기(비주얼 가계부 계산기). */
class AmountCalcTest {
    private fun type(c: AmountCalc, s: String) = s.fold(c) { a, ch -> a.digit(ch.toString()) }

    @Test fun addsAndSubtracts() {
        var c = type(AmountCalc(), "12000")
        c = c.operator(PLUS)
        assertEquals(12_000, c.total)
        c = type(c, "3500")
        assertEquals(15_500, c.total)
        assertEquals("12,000 + 3,500", c.expression)
        c = type(c.operator(MINUS), "500")
        assertEquals(15_000, c.total)
        c = c.equals()
        assertEquals(15_000, c.total)
        assertNull(c.expression)
        assertEquals("15000", c.cur)
    }

    @Test fun changingOperatorWithoutNumberJustSwaps() {
        val c = type(AmountCalc(), "1000").operator(PLUS).operator(MINUS)
        assertEquals(MINUS, c.op)
        assertEquals(1_000, c.total)
    }

    @Test fun backspaceUndoesOperator() {
        val c = type(AmountCalc(), "1000").operator(PLUS).back()
        assertNull(c.op)
        assertEquals("1000", c.cur)
    }

    @Test fun negativeResultIsShownButClampedOnEquals() {
        val c = type(type(AmountCalc(), "1000").operator(MINUS), "3000")
        assertEquals(-2_000, c.total)
        assertEquals(0, c.equals().total)
        assertEquals("", c.equals().cur)
    }

    @Test fun quickAddGoesToCurrentNumber() {
        val c = type(AmountCalc(), "5000").operator(PLUS).add(10_000)
        assertEquals(15_000, c.total)
    }

    @Test fun limitsDigitsAndLeadingZeros() {
        assertEquals("", AmountCalc().digit("0").cur)
        assertEquals("5", AmountCalc().digit("000").digit("5").cur)
        val big = type(AmountCalc(), "99999999999")
        assertEquals(big, big.digit("1"))
    }
}
