package com.jeiel85.doran

import com.jeiel85.doran.logic.ShareScope
import com.jeiel85.doran.model.Member
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 구성원 공유 범위 (DECISIONS.md D-19): 함께 관리 / 정한 카드사·은행만 보내기. */
class ShareScopeTest {
    private val husband = Member("h", "남편")

    @Test fun defaultSharesEverything() {
        assertFalse(husband.sharesPicked)
        assertTrue(husband.shares("현대카드"))
        assertTrue(husband.shares("신한카드"))
        assertEquals("함께 관리", ShareScope.label(husband))
    }

    @Test fun pickedSharesOnlyChosenIssuers() {
        val m = husband.copy(share = Member.SHARE_PICKED, shareIssuers = listOf("현대카드"))
        assertTrue(m.shares("현대카드"))
        assertTrue(m.shares(" 현대카드 "))
        assertFalse(m.shares("신한카드"))
        assertFalse(m.shares("KB국민은행"))
        assertEquals("현대카드만 보내기", ShareScope.label(m))
        // 고른 것이 없으면 자동 기록은 모두 본인 용돈으로
        assertFalse(husband.copy(share = Member.SHARE_PICKED).shares("현대카드"))
    }

    @Test fun issuerNamesMatchAcrossSpellings() {
        // "자동으로 보내기" 규칙에서 사용자가 쓴 다른 표기도 같은 카드사로 (하드닝 #4)
        val m = husband.copy(share = Member.SHARE_PICKED, shareIssuers = listOf("현대카드M"))
        assertTrue(m.shares("현대카드"))
        assertTrue(husband.copy(share = Member.SHARE_PICKED, shareIssuers = listOf("현대 카드")).shares("현대카드"))
        assertTrue(husband.copy(share = Member.SHARE_PICKED, shareIssuers = listOf("KB Pay")).shares("KB국민카드"))
        assertFalse(m.shares("신한카드"))
        // 모르는 이름은 띄어쓰기·대소문자만 맞춤
        assertTrue(husband.copy(share = Member.SHARE_PICKED, shareIssuers = listOf("동네 페이")).shares("동네페이"))
        assertFalse(husband.copy(share = Member.SHARE_PICKED, shareIssuers = listOf("동네페이")).shares("옆동네페이"))
    }

    @Test fun jsonRoundTripKeepsScopeAndOldDataDefaultsToAll() {
        val m = husband.copy(share = Member.SHARE_PICKED, shareIssuers = listOf("현대카드", "토스뱅크"), shareBy = "w", allowance = 350_000)
        val back = Member.from("h", JSONObject(m.json().toString()))
        assertEquals(m, back)
        // 공유 범위가 없던 옛 구성원 데이터, 모르는 값은 함께 관리로
        val old = Member.from("h", JSONObject().put("name", "남편").put("role", "member"))
        assertEquals(Member.SHARE_ALL, old.share)
        assertNull(old.shareBy)
        assertEquals(Member.SHARE_ALL, Member.from("h", JSONObject().put("name", "남편").put("share", "weird")).share)
    }

    @Test fun changeNoticeOnlyForScopeAndWordedByWhoChangedIt() {
        val before = husband
        val after = husband.copy(share = Member.SHARE_PICKED, shareIssuers = listOf("현대카드"), shareBy = "w")
        assertTrue(ShareScope.changed(before, after))
        assertFalse(ShareScope.changed(after, after.copy(name = "신랑", allowance = 1)))
        assertFalse(ShareScope.changed(after, after.copy(shareIssuers = listOf("현대카드"))))
        // 남편 폰: 아내가 내 공유 범위를 바꿈
        assertEquals("아내가 내 공유 범위를 바꿨어요", ShareScope.changedTitle(after, me = "h", by = "아내"))
        // 아내 폰: 남편이 자기 공유 범위를 바꿈
        assertEquals("남편이 공유 범위를 바꿨어요", ShareScope.changedTitle(after.copy(shareBy = "h"), me = "w", by = "남편"))
    }
}
