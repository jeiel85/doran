package com.jeiel85.doran.sync

import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.core.newId
import com.jeiel85.doran.crypto.Crypto
import com.jeiel85.doran.data.OutLink
import com.jeiel85.doran.model.Link
import com.jeiel85.doran.model.Member
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 가족 연결 (DECISIONS.md D-3·D-6, design/PROTOCOL.md "초대").
 * QR/코드 = "doran:1:" + 32바이트 비밀값. 서버에는 SHA-256(비밀값)과, 비밀값에서 만든 키로 봉한 묶음만 맡깁니다.
 */
object Pairing {
    private const val PREFIX = "doran:1:"

    data class Invite(val code: String, val inviteId: String, val expiresAt: Long, val role: String)

    fun parseCode(text: String): ByteArray? {
        val t = text.trim()
        val i = t.indexOf(PREFIX)
        if (i < 0) return null
        val raw = t.substring(i + PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
        return runCatching { Crypto.unb64(raw) }.getOrNull()?.takeIf { it.size == 32 }
    }

    /** 가구가 아직 서버에 없으면 만들고, 이 기기를 등록합니다. */
    private suspend fun ensureSpace(): String {
        if (Hub.identity.value.spaceId == null || !Hub.identity.value.registered) Hub.sync.sync()
        return Hub.identity.value.spaceId ?: throw IllegalStateException("동기화 서버에 연결하지 못했어요")
    }

    /** 배우자처럼 장부를 함께 쓸 사람을 초대. */
    suspend fun inviteMember(ttlSec: Int): Invite = withContext(Dispatchers.IO) {
        val space = ensureSpace()
        val hk = Hub.identity.householdKey ?: error("no household key")
        val bundle = JSONObject().put("v", 1).put("role", "member").put("space", space).put("hk", Crypto.b64(hk))
            .put("name", Hub.ledger.value.household.name)
            .put("inviter", Hub.ledger.value.member(Hub.identity.value.myMemberId)?.name ?: "")
        create(space, "member", bundle, ttlSec)
    }

    /** 부모님처럼 카드 알림만 보내 줄 가족을 초대. 그 사람의 구성원과 링크 키를 먼저 장부에 만듭니다. */
    suspend fun inviteSender(name: String, ttlSec: Int): Invite = withContext(Dispatchers.IO) {
        val space = ensureSpace()
        val s = Hub.ledger.value
        val member = Member(id = newId(), name = name.trim().ifEmpty { "가족" }, role = "sender",
            color = (s.members.size + 2) % com.jeiel85.doran.model.Defaults.MEMBER_COLORS, order = s.members.size)
        val key = Crypto.newKey()
        Hub.ledger.saveMember(member)
        Hub.ledger.saveLink(Link(id = newId(), memberId = member.id, key = Crypto.b64(key), createdAt = System.currentTimeMillis()))
        Hub.sync.sync() // 링크가 받는 쪽 폰들에 먼저 가 있도록
        val bundle = JSONObject().put("v", 1).put("role", "sender").put("space", space).put("lk", Crypto.b64(key))
            .put("memberId", member.id).put("memberName", member.name).put("label", s.household.name)
            .put("inviter", s.member(Hub.identity.value.myMemberId)?.name ?: "")
        create(space, "sender", bundle, ttlSec)
    }

    private fun create(space: String, role: String, bundle: JSONObject, ttlSec: Int): Invite {
        val secret = Crypto.randomBytes(32)
        val inviteId = Crypto.inviteId(secret)
        val sealed = Crypto.seal(Crypto.inviteKey(secret), bundle.toString().toByteArray(), Crypto.INVITE_AAD)
        val relay = Relay { Hub.identity.token }
        val expiresAt = relay.createInvite(space, inviteId, role, sealed, ttlSec)
        return Invite(PREFIX + Crypto.b64(secret), inviteId, expiresAt, role)
    }

    fun cancel(invite: Invite) {
        val space = Hub.identity.value.spaceId ?: return
        runCatching { Relay { Hub.identity.token }.cancelInvite(space, invite.inviteId) }
    }

    sealed interface Joined {
        data class Household(val name: String, val inviter: String) : Joined
        data class Sender(val label: String, val memberName: String) : Joined
    }

    /**
     * 코드로 합류. member면 내 구성원(myName)을 만들고 이 폰의 장부를 가구 장부와 합칩니다.
     * sender면 보내기 링크만 더합니다(이 폰의 가계부는 그대로).
     */
    suspend fun join(secret: ByteArray, myName: String): Joined = withContext(Dispatchers.IO) {
        Hub.sync.sync() // 기기 등록
        if (!Hub.identity.value.registered) throw IllegalStateException("동기화 서버에 연결하지 못했어요")
        val relay = Relay { Hub.identity.token }
        val claimed = relay.claim(Crypto.inviteId(secret))
        val plain = Crypto.open(Crypto.inviteKey(secret), claimed.bundle, Crypto.INVITE_AAD)
            ?: throw IllegalStateException("초대 코드를 확인하지 못했어요")
        val b = JSONObject(String(plain))
        if (b.getString("space") != claimed.spaceId) throw IllegalStateException("초대 정보가 맞지 않아요")
        when (claimed.role) {
            "member" -> {
                val old = Hub.identity.value.spaceId
                if (old != null && old != claimed.spaceId && Hub.identity.value.deviceId != null) {
                    runCatching { relay.removeMember(old, Hub.identity.value.deviceId!!) }
                }
                val existing = Hub.identity.value.myMemberId?.let { Hub.ledger.value.member(it) }
                val me = (existing ?: Member(id = newId(), name = myName.trim().ifEmpty { "나" }, color = 1))
                    .let { m -> m.copy(deviceIds = (m.deviceIds + Hub.identity.value.deviceId!!).distinct()) }
                Hub.identity.joinHousehold(me.id, Crypto.unb64(b.getString("hk")), Hub.identity.personalKey, claimed.spaceId)
                Hub.db.writableDatabase.execSQL("DELETE FROM kv WHERE k LIKE 'sync.cursor.%'")
                Hub.ledger.saveMember(me)
                Hub.sync.enqueueAll()
                Hub.sync.sync()
                // 가구 이름은 초대한 쪽 것을 따릅니다(받은 장부에 들어 있음).
                Joined.Household(b.optString("name", "우리집"), b.optString("inviter"))
            }
            "sender" -> {
                Hub.identity.addOutLink(OutLink(spaceId = claimed.spaceId, key = Crypto.unb64(b.getString("lk")),
                    memberId = b.getString("memberId"), label = b.optString("label", "가족"),
                    memberName = b.optString("memberName").ifEmpty { null }, createdAt = System.currentTimeMillis()))
                Hub.identity.markOnboarded()
                Joined.Sender(b.optString("label", "가족"), b.optString("memberName"))
            }
            else -> throw IllegalStateException("알 수 없는 초대예요")
        }
    }

    /** 가계부를 이 폰에서 새로 시작(서버 연결은 동기화가 뒤이어 함). */
    fun startHousehold(myName: String, householdName: String) {
        val me = Member(id = newId(), name = myName.trim().ifEmpty { "나" }, color = 0,
            deviceIds = listOfNotNull(Hub.identity.value.deviceId))
        Hub.identity.joinHousehold(me.id, Crypto.newKey(), null, null)
        Hub.ledger.saveMember(me)
        Hub.ledger.saveHousehold(Hub.ledger.value.household.copy(name = householdName.trim().ifEmpty { "우리집" }))
        Hub.requestSync()
    }

    /** 보내기 링크 끊기(부모님 폰 쪽). */
    suspend fun leaveLink(spaceId: String) = withContext(Dispatchers.IO) {
        val dev = Hub.identity.value.deviceId
        if (dev != null) runCatching { Relay { Hub.identity.token }.removeMember(spaceId, dev) }
        Hub.db.dropForwards(spaceId)
        Hub.identity.removeOutLink(spaceId)
    }

    /** 가구에서 기기 내보내기(잃어버린 폰 등). 보낸 가족이면 링크도 끊긴 것으로 표시합니다. */
    suspend fun removeDevice(deviceId: String) = withContext(Dispatchers.IO) {
        val space = Hub.identity.value.spaceId ?: return@withContext
        Relay { Hub.identity.token }.removeMember(space, deviceId)
    }

    fun revokeSender(memberId: String) {
        val s = Hub.ledger.value
        s.links.values.filter { it.memberId == memberId && !it.revoked }.forEach { Hub.ledger.saveLink(it.copy(revoked = true)) }
        s.member(memberId)?.let { Hub.ledger.saveMember(it.copy(removed = true)) }
    }
}
