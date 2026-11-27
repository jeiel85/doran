package com.jeiel85.doran.feature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.core.newId
import com.jeiel85.doran.crypto.Crypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 영수증 사진 (DECISIONS.md D-12): 긴 변 1600px JPEG로 줄여 가구 키로 봉한 뒤 서버 blob에 올립니다.
 * 이 폰에는 원본 대신 줄인 사진을 앱 내부 저장소에 둡니다(갤러리에 남기지 않음).
 */
object Receipts {
    private const val MAX_SIDE = 1600
    private fun aad(id: String) = "doran1|blob|$id".toByteArray()
    private fun dir(context: Context) = File(context.filesDir, "receipts").apply { mkdirs() }
    fun file(context: Context, id: String) = File(dir(context), "$id.jpg")

    /** 고른 사진을 줄여 저장하고 id를 돌려줍니다. 올리기는 save 뒤 upload로 따로 합니다. */
    suspend fun import(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
            val (w, h) = info.size.width to info.size.height
            val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(w, h))
            d.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val bytes = ByteArrayOutputStream().use { src.compress(Bitmap.CompressFormat.JPEG, 78, it); it.toByteArray() }
        src.recycle()
        val id = newId()
        file(context, id).writeBytes(bytes)
        id
    }

    /** 가구에 연결돼 있으면 서버에 올립니다. 실패는 다음 동기화 때 다시 시도합니다. */
    suspend fun upload(context: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        val space = Hub.identity.value.spaceId ?: return@withContext false
        val key = Hub.identity.householdKey ?: return@withContext false
        val f = file(context, id).takeIf { it.exists() } ?: return@withContext false
        runCatching {
            com.jeiel85.doran.sync.Relay { Hub.identity.token }.putBlob(space, id, Crypto.seal(key, f.readBytes(), aad(id)))
            Hub.db.setKv("blob.up.$id", "1")
        }.isSuccess
    }

    /** 이 폰에 없으면 받아서 엽니다. */
    suspend fun load(context: Context, id: String): Bitmap? = withContext(Dispatchers.IO) {
        val f = file(context, id)
        if (!f.exists()) {
            val space = Hub.identity.value.spaceId ?: return@withContext null
            val key = Hub.identity.householdKey ?: return@withContext null
            val env = runCatching { com.jeiel85.doran.sync.Relay { Hub.identity.token }.getBlob(space, id) }.getOrNull()
                ?: return@withContext null
            val plain = Crypto.open(key, env, aad(id)) ?: return@withContext null
            f.writeBytes(plain)
            Hub.db.setKv("blob.up.$id", "1")
        }
        BitmapFactory.decodeFile(f.path)
    }

    suspend fun delete(context: Context, id: String) = withContext(Dispatchers.IO) {
        file(context, id).delete()
        val space = Hub.identity.value.spaceId ?: return@withContext
        // 중계에서 못 지웠으면 기억해 두고 다시 지웁니다 — 남으면 가구 사진 용량(450MB)을 계속 차지해요(하드닝 #8).
        if (!deleteRemote(space, id)) pending.withLock { Hub.db.setKv(DEL_PENDING, (pendingDeletes() + id).distinct().joinToString(",")) }
    }

    private const val DEL_PENDING = "blob.del"
    private val pending = Mutex()
    fun pendingDeletes(): List<String> = Hub.db.kv(DEL_PENDING)?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    private fun deleteRemote(space: String, id: String): Boolean =
        runCatching { com.jeiel85.doran.sync.Relay { Hub.identity.token }.deleteBlob(space, id) }
            .fold({ true }, { e -> e is com.jeiel85.doran.sync.RelayError && e.status == 404 })

    /**
     * 못 다 한 사진 일 마저 하기(#7): 지우지 못한 사진 지우기, 올리지 못한 사진 올리기.
     * 앱을 열 때와 주기 동기화(15분)가 성공한 뒤 부릅니다.
     */
    suspend fun retryPending(context: Context) = withContext(Dispatchers.IO) {
        val space = Hub.identity.value.spaceId ?: return@withContext
        pending.withLock {
            val left = pendingDeletes().filterNot { deleteRemote(space, it) }
            Hub.db.setKv(DEL_PENDING, left.joinToString(","))
        }
        retryUploads(context)
        sweepOrphans(context)
    }

    /**
     * 어느 내역도 가리키지 않는 사진 파일(하루 넘은 것)을 지웁니다(#93): 지운 뒤 '되돌리기' 띠가 떠 있는 동안 앱이 꺼지면 뒷정리가 돌지 않아
     * 파일이 남았습니다. 저장하지 않은 새 내역에 막 붙인 사진은 하루가 안 돼 건드리지 않습니다.
     */
    private suspend fun sweepOrphans(context: Context, now: Long = System.currentTimeMillis()) {
        val files = dir(context).listFiles().orEmpty().filter { it.name.endsWith(".jpg") }
        val referenced = Hub.ledger.value.txns.values.flatMapTo(HashSet()) { it.receiptIds }
        for (id in orphans(files.associate { it.name.removeSuffix(".jpg") to it.lastModified() }, referenced, now)) delete(context, id)
    }

    /** [files]: 사진 id → 마지막으로 고친 시각. 내역이 가리키지 않고 [ORPHAN_AGE]보다 오래된 것. */
    fun orphans(files: Map<String, Long>, referenced: Set<String>, now: Long): List<String> =
        files.filter { (id, at) -> id !in referenced && now - at > ORPHAN_AGE }.keys.sorted()

    const val ORPHAN_AGE = 86_400_000L

    /** 올리지 못한 사진 다시 올리기. */
    private suspend fun retryUploads(context: Context) {
        val ids = Hub.ledger.value.txns.values.flatMap { it.receiptIds }.filter { Hub.db.kv("blob.up.$it") == null && file(context, it).exists() }
        for (id in ids) upload(context, id)
    }
}
