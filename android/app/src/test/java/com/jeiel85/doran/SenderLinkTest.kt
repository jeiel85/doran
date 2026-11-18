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

    @Test fun unknownOrMemberDevicesStayUnnamed() {
        assertNull(Pairing.senderOf(device("sender", "J".repeat(43)), state)) // 다른 초대
        assertNull(Pairing.senderOf(device("sender", null), state))           // 옛 중계·옛 기기
        assertNull(Pairing.senderOf(device("member", "I".repeat(43)), state)) // 같은 장부를 쓰는 기기는 구성원의 deviceIds로
    }
}
