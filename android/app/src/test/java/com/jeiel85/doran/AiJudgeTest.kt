package com.jeiel85.doran

import com.jeiel85.doran.ai.AiError
import com.jeiel85.doran.ai.AiKind
import com.jeiel85.doran.ai.AiPrompt
import com.jeiel85.doran.ai.AiVerdict
import com.jeiel85.doran.ai.OpenAiClient
import com.jeiel85.doran.capture.Parser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime

/** "AI로 한 번 더 보기"(DECISIONS.md D-25)의 순수 로직: 보내기 전 가리기, 응답 읽기, 규칙과 견주기. 네트워크는 부르지 않습니다. */
class AiJudgeTest {
    private val now = LocalDateTime.of(2026, 11, 20, 11, 25)

    // --- 밖으로 보내기 전에 가리기 -------------------------------------------------------------------------

    @Test fun masksAccountCardAndPhoneNumbersButKeepsAmountsAndDates() {
        val masked = AiPrompt.mask(
            "[신한] 110-123-456789 입금 3,200,000 잔액 5,600,000\n" +
                "현대카드 1234-56**-****-7890 승인 12,300원 2026-11-20 11:25\n" +
                "토스뱅크 통장 (1234)에서 출금 · 문의 010-1234-5678 · 123456**789 · 1234567890",
        )
        listOf("110-123-456789", "1234-56**-****-7890", "(1234)", "010-1234-5678", "123456**789", "1234567890").forEach {
            assertFalse(it, masked.contains(it))
        }
        listOf("3,200,000", "5,600,000", "12,300원", "2026-11-20", "11:25", "(****)").forEach { assertTrue(it, masked.contains(it)) }
    }

    @Test fun bareAmountsInBankTextsAreKept() {
        // 은행 문자는 "입금 3200000"처럼 쉼표 없이 금액을 쓰기도 함(9자리 이하는 금액으로 둠)
        assertEquals("[KB] 입금 3200000 잔액 120000000", AiPrompt.mask("[KB] 입금 3200000 잔액 120000000"))
    }

    // --- 응답 읽기 ---------------------------------------------------------------------------------------

    @Test fun parsesAVerdict() {
        val v = AiPrompt.parse("claude", "claude-opus-5-5", 1L,
            """{"kind":"scheduled","amount":30000,"merchant":"동네헬스","reason":"출금할 예정이라는 안내예요"}""")
        assertEquals(AiKind.SCHEDULED, v.kind)
        assertEquals(30_000L, v.amount)
        assertEquals("동네헬스", v.merchant)
        assertNull(v.error)
        // 음수 금액은 0으로
        assertEquals(0L, AiPrompt.parse("openai", "m", 1L, """{"kind":"other","amount":-5,"merchant":"","reason":"광고"}""").amount)
    }

    @Test fun rejectsMalformedVerdicts() {
        for (text in listOf("""{"kind":"maybe","amount":0,"merchant":"","reason":""}""", "예정 안내입니다", "")) {
            try {
                AiPrompt.parse("openai", "m", 1L, text)
                fail(text)
            } catch (e: AiError) {
                assertTrue(e.belongsToNotice) // 그 알림의 판정 실패로 남김(설정 문제가 아님)
            }
        }
    }

    @Test fun verdictJsonRoundTrips() {
        val v = AiVerdict("claude", "claude-opus-5-5", 42L, AiKind.PAYMENT, 12_300, "스타벅스", "카드 승인 완료")
        assertEquals(v, AiVerdict.from(v.json()))
        val e = AiVerdict("openai", "gpt-6.2-astra", 43L, error = "ChatGPT가 이 알림의 판정을 거절했어요")
        val back = AiVerdict.from(e.json())!!
        assertNull(back.kind)
        assertEquals(e.error, back.error)
        assertNull(AiVerdict.from("not json"))
        assertNull(AiVerdict.from(null))
    }

    @Test fun schemaIsStrictForBothCompanies() {
        // strict 스키마: 모든 속성이 required, 추가 속성 없음, kind는 판정 목록 그대로
        val schema = JSONObject(AiPrompt.schema)
        val props = schema.getJSONObject("properties").keySet()
        val required = (0 until schema.getJSONArray("required").length()).map { schema.getJSONArray("required").getString(it) }.toSet()
        assertEquals(props, required)
        assertFalse(schema.getBoolean("additionalProperties"))
        val kinds = schema.getJSONObject("properties").getJSONObject("kind").getJSONArray("enum")
        assertEquals(AiKind.entries.map { it.code }, (0 until kinds.length()).map { kinds.getString(it) })
    }

    // --- OpenAI Responses API 응답 ----------------------------------------------------------------------

    @Test fun openAiOutputTextIsFoundAfterOtherItems() {
        val body = JSONObject().put("status", "completed").put("output", JSONArray()
            .put(JSONObject().put("type", "reasoning").put("summary", JSONArray()))
            .put(JSONObject().put("type", "message").put("content", JSONArray()
                .put(JSONObject().put("type", "output_text").put("text", """{"kind":"payment"}""")))))
        assertEquals("""{"kind":"payment"}""", OpenAiClient.textOf(body.toString()))
    }

    @Test fun openAiRefusalAndTruncationAreErrors() {
        val refusal = JSONObject().put("status", "completed").put("output", JSONArray()
            .put(JSONObject().put("type", "message").put("content", JSONArray()
                .put(JSONObject().put("type", "refusal").put("refusal", "no")))))
        val truncated = JSONObject().put("status", "incomplete")
            .put("incomplete_details", JSONObject().put("reason", "max_output_tokens")).put("output", JSONArray())
        for (body in listOf(refusal, truncated)) {
            try {
                OpenAiClient.textOf(body.toString())
                fail(body.toString())
            } catch (e: AiError) {
                assertTrue(e.belongsToNotice)
            }
        }
    }

    @Test fun openAiHttpErrorsStopOrRetry() {
        val bad = """{"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}"""
        assertTrue(OpenAiClient.errorOf(401, bad, "m").fatal)
        assertTrue(OpenAiClient.errorOf(404, bad, "no-such-model").fatal)
        assertTrue(OpenAiClient.errorOf(429, bad, "m").fatal)
        val server = OpenAiClient.errorOf(503, "", "m")
        assertTrue(server.retry)
        assertFalse(server.belongsToNotice) // 서버 오류는 그 알림에 남기지 않고 다음에 다시 물음
    }

    // --- 규칙 해석기와 견주기 -------------------------------------------------------------------------------

    private fun rule(text: String, hint: String? = null): Pair<AiKind?, Long> {
        val parsed = Parser.parse(text, now, hint = hint)
        return AiPrompt.ruleKind(parsed, if (parsed == null) Parser.unspent(text) else null) to (parsed?.amount ?: 0L)
    }

    private fun ai(kind: AiKind, amount: Long = 0) = AiVerdict("claude", "m", 1L, kind, amount)

    @Test fun ruleKindsFollowTheParser() {
        assertEquals(AiKind.PAYMENT to 12_300L, rule("현대카드 승인 홍*동 12,300원 일시불 11/17 12:30 스타벅스"))
        assertEquals(AiKind.SCHEDULED, rule(AiPrompt.SAMPLE, hint = "토스").first)
        assertEquals(AiKind.FAILED, rule("9,900원 결제 실패\n토스뱅크 체크카드 | 구글플레이 잔액이 부족해요.", hint = "토스").first)
        assertEquals(AiKind.DEPOSIT, rule("[Web발신]\n[신한]11/25 10:00\n110-***-123456\n입금 3,200,000\n잔액 5,600,000\n(주)동네컴퍼니").first)
        // 규칙이 못 읽는 금액 알림(카드사 이름 없음)
        assertNull(rule("동네마트에서 12,300원 결제되었습니다").first)
    }

    @Test fun agreementCompares() {
        // 규칙과 같은 판정
        assertEquals(true, AiPrompt.agrees(AiKind.SCHEDULED, 0, ai(AiKind.SCHEDULED, 30_000)))
        // 0.2.2까지의 규칙(출금으로 읽음)과 AI(예정 안내)가 다르면 표시됨 — 이번에 고친 오인식
        assertEquals(false, AiPrompt.agrees(AiKind.WITHDRAWAL, 30_000, ai(AiKind.SCHEDULED, 30_000)))
        // 체크카드 결제를 은행 출금으로 읽어도 같은 '돈이 나감'
        assertEquals(true, AiPrompt.agrees(AiKind.PAYMENT, 9_900, ai(AiKind.WITHDRAWAL, 9_900)))
        // 금액이 다르면 다름, AI가 금액을 못 찾은 0은 견주지 않음
        assertEquals(false, AiPrompt.agrees(AiKind.PAYMENT, 12_300, ai(AiKind.PAYMENT, 1_230)))
        assertEquals(true, AiPrompt.agrees(AiKind.PAYMENT, 12_300, ai(AiKind.PAYMENT, 0)))
        // 규칙이 못 읽은 알림: AI도 거래가 아니라고 해야 같음
        assertEquals(true, AiPrompt.agrees(null, 0, ai(AiKind.OTHER)))
        assertEquals(false, AiPrompt.agrees(null, 0, ai(AiKind.PAYMENT, 12_300)))
        // 판정하지 못했으면 견주지 않음
        assertNull(AiPrompt.agrees(AiKind.PAYMENT, 1, AiVerdict("claude", "m", 1L, error = "거절")))
    }
}
