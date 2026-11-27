package com.jeiel85.doran

import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.logic.Entry
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.model.Category
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Txn
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 하위 카테고리(D-37): 통계·예산은 상위로 묶고, 엑셀은 세부 그대로. */
class SubCategoryTest {
    private val food = Category("c.food", "식비", Kind.EXPENSE, "food")
    private val cafe = Category("c.cafe", "카페", Kind.EXPENSE, "cafe", parentId = "c.food")
    private val orphan = Category("c.orphan", "남은 것", Kind.EXPENSE, "etc", parentId = "c.gone")
    private val s = LedgerState(categories = listOf(food, cafe, orphan).associateBy { it.id })
    private fun e(id: String, cat: String, amount: Long) = Entry(Txn(id, Kind.EXPENSE, amount, 0, categoryId = cat), amount, false)

    @Test fun subcategoriesRollUpWhenAsked() {
        val list = listOf(e("a", "c.food", 10_000), e("b", "c.cafe", 5_000), e("c", "c.orphan", 1_000))
        assertEquals(listOf("c.food" to 15_000L, "c.orphan" to 1_000L), Stats.byCategory(list, Kind.EXPENSE, s))
        // 세부 그대로(엑셀)
        assertEquals(3, Stats.byCategory(list, Kind.EXPENSE).size)
        // 상위가 지워진 하위는 제자리
        assertEquals("c.orphan", Stats.topCategory(s, "c.orphan"))
    }

    @Test fun parentSurvivesJson() {
        assertEquals("c.food", Category.from("c.cafe", cafe.json()).parentId)
        assertNull(Category.from("c.food", food.json()).parentId)
        assertNull(Category.from("x", JSONObject().put("name", "옛것").put("kind", "e")).parentId)
    }
}
