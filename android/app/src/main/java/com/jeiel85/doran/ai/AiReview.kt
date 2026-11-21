package com.jeiel85.doran.ai

import android.util.Log
import com.jeiel85.doran.capture.Issuer
import com.jeiel85.doran.capture.Parser
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.Db
import com.jeiel85.doran.data.LocalSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * "AI로 한 번 더 보기"(DECISIONS.md D-25): 수집 기록(금액 알림만 남는 "최근 받은 알림")을 사용자가 고른 AI에게도 물어
 * 판정을 capture_log.ai에 남깁니다. 기록·장부는 바꾸지 않습니다(비교용). 원문은 계좌·카드번호를 가린 뒤 보냅니다.
 */
class AiReview(private val db: Db, private val local: LocalSettings) {
    private val mutex = Mutex()

    data class Setup(val provider: AiProvider, val model: String, val key: String)

    /** 마지막으로 설정·연결 문제로 멈춘 이유(알림을 받을 때 뒤에서 돈 것도). 설정 화면에 보여 줍니다. 판정이 하나라도 되면 지웁니다. */
    @Volatile var lastProblem: String? = null
        private set

    fun setup(): Setup? {
        val p = AiProvider.of(local.value.aiProvider) ?: return null
        val key = local.aiKey(p.code) ?: return null
        return Setup(p, local.value.aiModel.ifBlank { p.defaultModel }, key)
    }

    val enabled: Boolean get() = setup() != null

    /**
     * 알림을 받은 직후: 방금 남은 기록(15분 안, 최대 5건)만 묻습니다. 모든 앱 알림마다 불리므로, 꺼져 있거나 물을 새 기록이 없으면
     * 키 저장소를 열지 않고 돌아갑니다(#45 — 전에는 알림마다 키를 복호화했어요).
     */
    suspend fun afterCapture() {
        if (AiProvider.of(local.value.aiProvider) == null) return
        val since = System.currentTimeMillis() - 15 * 60_000L
        if (withContext(Dispatchers.IO) { db.capturesWithoutAi(since, 1) }.isEmpty()) return
        if (enabled) review(since, 5)
    }

    /** 최근 [days]일에 AI가 판정한 알림 수(회사별). 설정 화면에서 대략의 사용량을 보려고(#45). 수집 기록이 30일만 남아 그 안에서 셉니다. */
    fun judgedSince(days: Int = 30): Map<String, Int> {
        val since = System.currentTimeMillis() - days * 86_400_000L
        return db.captureAis().mapNotNull(AiVerdict::from).filter { it.error == null && it.at >= since }
            .groupingBy { it.provider }.eachCount()
    }

    /** "최근 받은 알림"의 "AI로 보기": 아직 묻지 않은 최근 30일 기록 [limit]건. 물은 건수와, 멈췄으면 그 이유. */
    suspend fun reviewRecent(limit: Int = 20): Pair<Int, String?> = review(System.currentTimeMillis() - 30L * 86_400_000, limit)

    private suspend fun review(since: Long, limit: Int): Pair<Int, String?> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val s = setup() ?: return@withContext 0 to "AI를 고르고 API 키를 저장해 주세요"
            var done = 0
            for (row in db.capturesWithoutAi(since, limit)) {
                // 도는 사이에 끄거나 키·회사·모델을 바꿨으면 남은 알림을 보내지 않습니다(사용자가 거둔 동의, #44 리뷰).
                if (setup() != s) return@withContext done to "AI 설정이 바뀌어 멈췄어요"
                val (verdict, err) = judge(s, appOf(row), bodyOf(row))
                if (err != null && !err.belongsToNotice) {
                    lastProblem = err.message
                    return@withContext done to err.message
                }
                db.setCaptureAi(row.id, verdict.json())
                done++
                if (verdict.error == null) lastProblem = null
            }
            done to null
        }
    }

    /** "연결 시험": 합성 예정 안내([AiPrompt.SAMPLE])를 물어 봅니다. 기록에는 남기지 않습니다. */
    suspend fun test(): AiVerdict = withContext(Dispatchers.IO) {
        val s = setup() ?: return@withContext AiVerdict("", "", System.currentTimeMillis(), error = "AI를 고르고 API 키를 저장해 주세요")
        judge(s, AiPrompt.SAMPLE_APP, AiPrompt.SAMPLE).first.also { lastProblem = it.error }
    }

    private fun judge(s: Setup, app: String?, text: String): Pair<AiVerdict, AiError?> {
        val at = System.currentTimeMillis()
        return try {
            val user = AiPrompt.user(app, text)
            val out = when (s.provider) {
                AiProvider.CLAUDE -> ClaudeClient.ask(s.key, s.model, AiPrompt.SYSTEM, user, AiPrompt.schema)
                AiProvider.OPENAI -> OpenAiClient.ask(s.key, s.model, AiPrompt.SYSTEM, user, AiPrompt.schema)
            }
            AiPrompt.parse(s.provider.code, s.model, at, out) to null
        } catch (e: AiError) {
            Log.w("doran", "AI 판정 실패(${s.provider.code})", e)
            AiVerdict(s.provider.code, s.model, at, error = e.message) to e
        } catch (e: RuntimeException) {
            // SDK의 예상 밖 예외(응답 처리 등). 삼키지 않고 그 알림의 판정 실패 이유로 남겨 화면에 보입니다.
            Log.w("doran", "AI 판정 중 예외(${s.provider.code})", e)
            AiVerdict(s.provider.code, s.model, at, error = "${e.javaClass.simpleName}: ${e.message?.take(80)}") to null
        }
    }

    /** 지금 규칙 해석기가 이 기록을 어떻게 읽는지: 판정 종류(못 읽으면 null)와 금액. AI 판정과 견주는 데 씁니다. */
    fun ruleView(row: Db.CaptureRow): Pair<AiKind?, Long> {
        val body = bodyOf(row)
        val at = Fmt.dateTime(row.at)
        val custom = local.ruleFor(row.source, row.pkg)?.let { Issuer(it.issuer, it.card, listOf(it.issuer)) }
        // 카드·은행 기록에 묶은 간편결제 알림(D-5)은 그 앱 이름으로 읽어야 결제로 보입니다.
        val parsed = Parser.parse(body, at, row.label, custom)
            ?: if (row.result.startsWith("같은 결제")) Parser.payNotice(body, at, row.label) else null
        return AiPrompt.ruleKind(parsed, if (parsed == null) Parser.unspent(body) else null) to (parsed?.amount ?: 0L)
    }

    private fun bodyOf(row: Db.CaptureRow) = listOfNotNull(row.title, row.text).joinToString("\n")
    private fun appOf(row: Db.CaptureRow) = row.label ?: if (row.source == "sms") "문자" else row.pkg
}
