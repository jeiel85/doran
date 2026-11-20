package com.jeiel85.doran.ai

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import java.time.Duration

/**
 * Claude(Anthropic)에 판정을 묻습니다 — 공식 Java SDK(com.anthropic:anthropic-java). 사용자가 넣은 API 키로 폰에서 바로 부릅니다.
 * 짧은 분류라 effort는 low, 응답은 JSON 스키마로 고정합니다(DECISIONS.md D-25).
 */
object ClaudeClient {
    /** 서버 쪽 대체 모델("fallbacks": "default")을 받는 모델. 안전 분류기가 거절하면 서버가 알맞은 모델로 다시 답합니다. */
    private val FALLBACK_MODELS = listOf("claude-opus-5", "claude-sonnet-5-5", "claude-fable-5")

    fun ask(apiKey: String, model: String, system: String, user: String, schema: Map<String, Any>): String {
        val client = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(Duration.ofSeconds(60))
            .maxRetries(1)
            .build()
        try {
            val params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(4000L)
                .system(system)
                .outputConfig(
                    OutputConfig.builder()
                        .effort(OutputConfig.Effort.LOW)
                        .format(
                            JsonOutputFormat.builder()
                                .schema(JsonOutputFormat.Schema.builder()
                                    .putAllAdditionalProperties(schema.mapValues { JsonValue.from(it.value) })
                                    .build())
                                .build(),
                        )
                        .build(),
                )
                .addUserMessage(user)
                .apply {
                    if (FALLBACK_MODELS.any { model.startsWith(it) }) {
                        putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                        putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                    }
                }
                .build()
            val message = client.messages().create(params)
            when (message.stopReason().orElse(null)) {
                StopReason.REFUSAL -> throw AiError("Claude가 이 알림의 판정을 거절했어요")
                StopReason.MAX_TOKENS -> throw AiError("응답이 길어 잘렸어요")
                else -> Unit
            }
            return message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
                .ifBlank { throw AiError("빈 응답이에요") }
        } catch (e: UnauthorizedException) {
            throw AiError("API 키가 맞지 않아요(401)", fatal = true)
        } catch (e: PermissionDeniedException) {
            throw AiError("이 키로는 쓸 수 없어요(403)", fatal = true)
        } catch (e: NotFoundException) {
            throw AiError("모델 '$model'을 찾지 못했어요(404)", fatal = true)
        } catch (e: RateLimitException) {
            throw AiError("요청이 많아 잠시 막혔어요(429). 나중에 다시 해 주세요", fatal = true)
        } catch (e: AnthropicServiceException) {
            // 402(잔액 없음) 등 나머지 4xx는 다른 알림도 같은 이유로 실패하니 멈추고, 5xx(과부하 등)는 다음에 다시 묻습니다.
            val code = e.statusCode()
            throw AiError("Claude 오류 $code: ${e.message?.take(120)}", fatal = code in 400..499, retry = code >= 500)
        } catch (e: AnthropicIoException) {
            throw AiError("Claude에 연결하지 못했어요: ${e.message?.take(80)}", retry = true)
        } finally {
            client.close()
        }
    }
}
