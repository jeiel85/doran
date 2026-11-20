package com.jeiel85.doran.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeiel85.doran.ai.AiKind
import com.jeiel85.doran.ai.AiProvider
import com.jeiel85.doran.ai.AiVerdict
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.core.Hub
import kotlinx.coroutines.launch

/**
 * 알림 자동 기록 화면의 "AI로 한 번 더 보기(시험)"(DECISIONS.md D-25): 회사 고르기, API 키(이 폰에만 암호화), 모델, 연결 시험.
 * 판정은 기록을 바꾸지 않고 "최근 받은 알림"에 규칙 결과와 나란히 보입니다.
 */
@Composable
fun AiCheckSection() {
    val settings by Hub.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf(AiProvider.of(settings.aiProvider)) }
    var keyInput by remember { mutableStateOf("") }
    var model by remember(picked) { mutableStateOf(if (picked?.code == settings.aiProvider) settings.aiModel else "") }
    var tick by remember { mutableIntStateOf(0) }
    val tail = remember(picked, tick) { picked?.let { Hub.local.aiKeyTail(it.code) } }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val on = AiProvider.of(settings.aiProvider)

    Spacer(Modifier.height(20.dp))
    SectionTitle("AI로 한 번 더 보기 (시험)")
    SoftCard(padding = PaddingValues(16.dp)) {
        Text(
            on?.let { "켜짐 · ${it.label} · ${settings.aiModel.ifBlank { it.defaultModel }}" } ?: "꺼짐",
            style = MaterialTheme.typography.titleSmall, color = if (on != null) P.c.pine else P.c.inkSoft,
        )
        Spacer(Modifier.height(4.dp))
        Text("기록은 지금처럼 규칙이 정하고, 같은 알림을 AI에게도 물어 '최근 받은 알림'에 나란히 보여 줘요. 둘이 다르면 표시돼요.",
            style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
        Spacer(Modifier.height(10.dp))
        ChipRow {
            Chip("끔", picked == null, {
                picked = null; message = null
                Hub.local.update { it.copy(aiProvider = "") }
            })
            AiProvider.entries.forEach { p -> Chip(p.label, picked == p, { picked = p; message = null }) }
        }
        picked?.let { p ->
            Spacer(Modifier.height(10.dp))
            Text(
                "켜면 '최근 받은 알림'에 남는 금액 알림의 원문이 ${p.company}로 가요(계좌·카드·전화번호는 가려서). " +
                    "보낸 사람 이름은 가려지지 않을 수 있어요. 요금은 넣은 API 키의 ${p.company} 계정으로 나가요. " +
                    "키는 이 폰에만 암호화해 두고 가족 폰이나 둥지 서버로 보내지 않아요.",
                style = MaterialTheme.typography.bodySmall, color = P.c.apricot,
            )
            Spacer(Modifier.height(10.dp))
            Field("${p.company} API 키", keyInput, { keyInput = it.trim() },
                tail?.let { "저장됨 · …$it (바꾸려면 새로 붙여 넣기)" } ?: "키를 붙여 넣어 주세요", secret = true)
            Spacer(Modifier.height(8.dp))
            Field("모델", model, { model = it.trim().take(60) }, "비우면 ${p.defaultModel}")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("저장하고 켜기", enabled = !busy && (keyInput.isNotBlank() || tail != null)) {
                    if (keyInput.isNotBlank()) Hub.local.setAiKey(p.code, keyInput)
                    Hub.local.update { it.copy(aiProvider = p.code, aiModel = model) }
                    keyInput = ""; tick++
                    message = "켰어요. 이제 받는 금액 알림을 ${p.label}에게도 물어요. '연결 시험'으로 키를 확인해 보세요"
                }
                SmallButton(if (busy) "묻는 중…" else "연결 시험", enabled = !busy && on == p && tail != null, color = P.c.apricot) {
                    busy = true; message = null
                    scope.launch {
                        message = describeTest(Hub.ai.test())
                        busy = false
                    }
                }
                if (tail != null) SmallButton("키 지우기", enabled = !busy, color = P.c.danger) {
                    Hub.local.setAiKey(p.code, null)
                    if (settings.aiProvider == p.code) Hub.local.update { it.copy(aiProvider = "") }
                    tick++; message = "${p.company} 키를 지웠어요"
                }
            }
            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = P.c.ink)
            }
            if (on == p) Hub.ai.lastProblem?.let {
                Spacer(Modifier.height(6.dp))
                Text("최근 멈춘 이유: $it", style = MaterialTheme.typography.bodySmall, color = P.c.danger)
            }
        }
    }
}

/** 연결 시험의 결과 문장. 시험 문구는 예정 안내라, 맞게 읽으면 '예정·청구 안내'가 나와야 합니다. */
private fun describeTest(v: AiVerdict): String {
    v.error?.let { return "판정하지 못했어요: $it" }
    val kind = v.kind ?: return "판정하지 못했어요"
    val head = if (kind == AiKind.SCHEDULED) "연결됐어요. 시험 문구를 맞게 읽었어요" else "연결됐지만 시험 문구를 다르게 읽었어요"
    return "$head: ${kind.label}" + (if (v.amount > 0) " ${Fmt.won(v.amount)}" else "") + " — ${v.reason}"
}
