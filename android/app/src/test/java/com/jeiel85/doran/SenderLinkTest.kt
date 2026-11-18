package com.jeiel85.doran

import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.model.Link
import com.jeiel85.doran.model.Member
import com.jeiel85.doran.sync.Pairing
import com.jeiel85.doran.sync.Relay
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 보내는 가족 기기를 사람 이름과 짝짓기 (DECISIONS.md D-22): 링크의 초대 id ↔ 중계가 알려 준 기기의 초대 id. */
class SenderLinkTest {
    private val mom = Member("mom", "어머니", role = "sender")
    private val link = Link("l1", "mom", "a2V5", 1L, inviteId = "I".repeat(43))
    private val state = LedgerState(members = mapOf(mom.id to mom), links = mapOf(link.id to link))
    private fun device(role: String, invite: String?) = Relay.SpaceMember("d1", role, 0, 0, push = false, me = false, invite = invite)

    @Test fun linkKeepsInviteIdThroughJson() {
        assertEquals(link, Link.from("l1", link.json()))
        // 이 기능 전에 만든 링크는 초대 id 없이
        val old = JSONObject().put("memberId", "mom").put("key", "a2V5").put("createdAt", 1L).put("revoked", false)
        assertNull(Link.from("l0", old).inviteId)
        assertEquals(false, link.copy(inviteId = null).json().has("inviteId"))
    }

    @Test fun senderDeviceMapsToTheInvitedMember() {
        assertEquals("어머니", Pairing.senderOf(device("sender", "I".repeat(43)), state)?.name)
    }

    @Test fun reinvitingTheSameNameReusesTheSender() {
        // 끊은 어머니를 다시 초대해도 같은 구성원(사람별 지출이 둘로 나뉘지 않게, 하드닝 #8)
        val removed = state.copy(members = mapOf(mom.id to mom.copy(removed = true)))
        assertEquals("mom", Pairing.senderNamed(removed, " 어머니 ")?.id)
        assertNull(Pairing.senderNamed(state, "아버지"))
        // 같은 이름이라도 장부를 함께 쓰는 구성원은 보내는 가족으로 쓰지 않음
        val wife = Member("w", "어머니")
        assertNull(Pairing.senderNamed(LedgerState(members = mapOf(wife.id to wife)), "어머니"))
    }

    @Test fun senderWithoutTxnsOrLiveLinksIsUnused() {
        assertEquals(false, Pairing.unusedSender(state, "mom")) // 살아 있는 링크
        val revoked = state.copy(links = mapOf(link.id to link.copy(revoked = true)))
        assertEquals(true, Pairing.unusedSender(revoked, "mom"))
        val withTxn = revoked.copy(txns = mapOf("t" to com.jeiel85.doran.model.Txn(id = "t", kind = com.jeiel85.doran.model.Kind.EXPENSE, amount = 1000, at = 0, memberId = "mom")))
        assertEquals(false, Pairing.unusedSender(withTxn, "mom")) // 보낸 내역이 있으면 남김
    }

    @Test fun senderPhoneSendsOnlyPickedIssuers() {
        // 부모님 폰의 "보낼 알림"(D-6, #10): 비어 있으면 전부, 고르면 그 카드사·은행만 — 표기가 달라도 같은 곳이면
        val all = com.jeiel85.doran.data.OutLink(spaceId = "s", key = ByteArray(32), memberId = "mom", label = "우리집", createdAt = 0)
        assertEquals(true, com.jeiel85.doran.capture.CaptureProcessor.sendsTo(all, "KB국민은행"))
        val cards = all.copy(issuers = listOf("현대카드M", "KB Pay"))
        assertEquals(true, com.jeiel85.doran.capture.CaptureProcessor.sendsTo(cards, "현대카드"))
        assertEquals(true, com.jeiel85.doran.capture.CaptureProcessor.sendsTo(cards, "KB국민카드"))
        assertEquals(false, com.jeiel85.doran.capture.CaptureProcessor.sendsTo(cards, "KB국민은행"))
        assertEquals(false, com.jeiel85.doran.capture.CaptureProcessor.sendsTo(cards, "신한카드"))
    }

    @Test fun unknownOrMemberDevicesStayUnnamed() {
        assertNull(Pairing.senderOf(device("sender", "J".repeat(43)), state)) // 다른 초대
        assertNull(Pairing.senderOf(device("sender", null), state))           // 옛 중계·옛 기기
        assertNull(Pairing.senderOf(device("member", "I".repeat(43)), state)) // 같은 장부를 쓰는 기기는 구성원의 deviceIds로
    }
}
