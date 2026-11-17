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
        runCatching { com.jeiel85.doran.sync.Relay { Hub.identity.token }.deleteBlob(space, id) }
    }

    /** 올리지 못한 사진 다시 올리기(동기화 뒤). */
    suspend fun retryUploads(context: Context) {
        val ids = Hub.ledger.value.txns.values.flatMap { it.receiptIds }.filter { Hub.db.kv("blob.up.$it") == null && file(context, it).exists() }
        for (id in ids) upload(context, id)
    }
}
