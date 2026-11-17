package com.jeiel85.doran.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 종단 암호화 도구 (DECISIONS.md D-3, design/PROTOCOL.md).
 *
 * 봉투 = base64url(0x01 ‖ kid(8) ‖ nonce(12) ‖ AES-256-GCM(key, plain, aad)).
 * kid는 키의 SHA-256 앞 8바이트라 받는 폰이 키 고리에서 맞는 키를 고를 수 있고, 서버는 키를 모릅니다.
 */
object Crypto {
    private const val VERSION: Byte = 1
    private const val KID = 8
    private const val NONCE = 12
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)
    fun newKey(): ByteArray = randomBytes(32)

    fun kid(key: ByteArray): String = b64(sha256(key).copyOf(KID))

    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray): String {
        val nonce = randomBytes(NONCE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
        }
        return b64(byteArrayOf(VERSION) + sha256(key).copyOf(KID) + nonce + cipher.doFinal(plain))
    }

    /** 봉투에 적힌 kid. 형식이 틀리면 null. */
    fun kidOf(envelope: String): String? = try {
        val bytes = unb64(envelope)
        if (bytes.size < 1 + KID + NONCE + 16 || bytes[0] != VERSION) null else b64(bytes.copyOfRange(1, 1 + KID))
    } catch (_: Exception) {
        null
    }

    /** 다른 키이거나 변조됐거나 형식이 틀리면 null. */
    fun open(key: ByteArray, envelope: String, aad: ByteArray): ByteArray? = try {
        val bytes = unb64(envelope)
        require(bytes.size > 1 + KID + NONCE + 16 && bytes[0] == VERSION)
        val off = 1 + KID
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes, off, NONCE))
            updateAAD(aad)
            doFinal(bytes, off + NONCE, bytes.size - off - NONCE)
        }
    } catch (_: Exception) {
        null
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = hmac(prk, t + info + byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** 백업 파일 비밀번호 → 키 (DECISIONS.md D-11). */
    fun passwordKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    // --- 동기화 전용 파생값 -------------------------------------------------------------------

    /** 엔티티 키: 서버가 같은 엔티티의 옛 op를 지울 수 있게 하되, 무엇인지는 모르게 합니다. */
    fun entityKey(key: ByteArray, type: String, id: String): String =
        b64(hmac(hkdf(key, ByteArray(0), "doran ek v1".toByteArray()), "$type:$id".toByteArray()))

    fun opAad(spaceId: String, ek: String): ByteArray = "doran1|$spaceId|$ek".toByteArray()

    /** 초대: QR 비밀값 → (서버가 아는 id, 묶음을 감싸는 키). */
    fun inviteId(secret: ByteArray): String = b64(sha256(secret))
    fun inviteKey(secret: ByteArray): ByteArray = hkdf(secret, ByteArray(0), "doran invite v1".toByteArray())
    val INVITE_AAD = "doran1|invite".toByteArray()

    fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
}
