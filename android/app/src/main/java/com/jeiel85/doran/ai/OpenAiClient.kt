package com.jeiel85.doran.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * ChatGPT(OpenAI)에 판정을 묻습니다 — Responses API(`POST /v1/responses`)를 HTTP로 바로 부릅니다(의존성 없음).
 * 응답은 strict JSON 스키마(text.format)로 고정합니다(DECISIONS.md D-25). 사용자가 넣은 API 키로 폰에서 바로 부릅니다.
 */
object OpenAiClient {
    private const val URL_RESPONSES = "https://api.openai.com/v1/responses"

    fun ask(apiKey: String, model: String, system: String, user: String, schema: Map<String, Any>): String {
        val body = JSONObject()
            .put("model", model)
            .put("input", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
            .put("text", JSONObject().put("format", JSONObject()
                .put("type", "json_schema")
                .put("name", "notice_verdict")
                .put("schema", JSONObject(schema))
                .put("strict", true)))
            .put("max_output_tokens", 4000)
        val conn = (URL(URL_RESPONSES).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw errorOf(code, text, model)
            return textOf(text)
        } catch (e: IOException) {
            throw AiError("ChatGPT에 연결하지 못했어요: ${e.message?.take(80)}", retry = true)
        } finally {
            conn.disconnect()
        }
    }

    /** 응답 본문에서 생성된 글을 꺼냅니다: output 안의 message → content의 output_text. 거절·잘림은 [AiError]. */
    fun textOf(responseBody: String): String {
        val o = runCatching { JSONObject(responseBody) }.getOrElse { throw AiError("응답을 읽지 못했어요") }
        if (o.optString("status") == "incomplete") {
            val why = o.optJSONObject("incomplete_details")?.optString("reason").orEmpty()
            throw AiError(if (why == "max_output_tokens") "응답이 길어 잘렸어요" else "응답이 끝나지 않았어요($why)")
        }
        val output = o.optJSONArray("output") ?: throw AiError("응답에 output이 없어요")
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val c = content.optJSONObject(j) ?: continue
                when (c.optString("type")) {
                    "output_text" -> return c.optString("text").ifBlank { throw AiError("빈 응답이에요") }
                    "refusal" -> throw AiError("ChatGPT가 이 알림의 판정을 거절했어요")
                }
            }
        }
        throw AiError("응답에 글이 없어요")
    }

    /** 오류 응답({"error": {"message": …}})을 사람이 읽을 말로. 키·결제·모델 문제는 다른 알림도 같으니 멈춥니다. */
    fun errorOf(code: Int, body: String, model: String): AiError {
        val msg = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty().take(120)
        return when (code) {
            401 -> AiError("API 키가 맞지 않아요(401)", fatal = true)
            403 -> AiError("이 키로는 쓸 수 없어요(403)", fatal = true)
            404 -> AiError("모델 '$model'을 찾지 못했어요(404)", fatal = true)
            429 -> AiError("요청이 많거나 잔액이 없어요(429): $msg", fatal = true)
            in 400..499 -> AiError("ChatGPT 오류 $code: $msg", fatal = true)
            else -> AiError("ChatGPT 서버 오류 $code: $msg", retry = true)
        }
    }
}
