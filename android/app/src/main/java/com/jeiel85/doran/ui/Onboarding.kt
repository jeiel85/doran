package com.jeiel85.doran.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jeiel85.doran.R
import com.jeiel85.doran.sync.Pairing

/** 처음 실행: 새 가계부 시작 / 가족 가계부 합류 / 보내기만(부모님 폰). */
@Composable
fun Onboarding(pendingInvite: String? = null) {
    val invited = remember(pendingInvite) { Pairing.parse(pendingInvite) }
    var step by remember(pendingInvite) { mutableStateOf(if (invited == null) "welcome" else if (invited.role == "sender") "sender" else "join") }
    var myName by remember { mutableStateOf("") }
    var homeName by remember { mutableStateOf("우리집") }

    Column(Modifier.fillMaxSize().background(P.c.bg).statusBarsPadding().navigationBarsPadding().imePadding()
        .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(24.dp))
        Image(painterResource(R.drawable.art_hero), null, Modifier.fillMaxWidth().height(250.dp), contentScale = ContentScale.Fit)
        Spacer(Modifier.height(12.dp))
        when (step) {
            "welcome" -> {
                Text("도란", style = MaterialTheme.typography.displaySmall, color = P.c.pine)
                Text("우리 가족 돈 이야기, 한 장부에서 도란도란", style = MaterialTheme.typography.titleMedium, color = P.c.ink)
                Spacer(Modifier.height(16.dp))
                Feature("art_capture", "카드·은행 알림이 오면 바로 장부에", "앱 알림과 문자를 읽어 같은 결제는 한 번만 기록해요")
                Feature("art_family", "부부가 같은 장부를 실시간으로", "부모님 카드 알림도 사람별로 받아 합계에 더해요")
                Feature("art_cat_bank", "달력·자산·예산·통계 한눈에", "내용은 우리 폰에서만 열리게 암호화해요")
                Spacer(Modifier.height(24.dp))
                PrimaryButton("새 가계부 시작하기") { step = "start" }
                Spacer(Modifier.height(10.dp))
                GhostButton("가족 초대로 합류하기", Modifier.fillMaxWidth(), color = P.c.pine) { step = "join" }
                Spacer(Modifier.height(10.dp))
                Text("부모님 폰처럼 카드 알림을 보내기만 할 거예요 →", style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { step = "sender" }.padding(12.dp))
            }
            "start" -> {
                Text("가계부를 만들어요", style = MaterialTheme.typography.headlineSmall, color = P.c.ink)
                Text("배우자는 다음 화면에서 QR을 띄워 바로 초대할 수 있어요", style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft)
                Spacer(Modifier.height(20.dp))
                Text("나는 누구인가요?", style = MaterialTheme.typography.titleMedium, color = P.c.ink)
                Text("가족 폰 알림에 \"남편이 현대카드로 12,300원 썼어요\"처럼 이 이름이 나와요",
                    style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                Spacer(Modifier.height(10.dp))
                NamePicker(myName, { myName = it }, SELF_NAMES, "내 이름")
                Spacer(Modifier.height(16.dp))
                Field("가계부 이름", homeName, { homeName = it.take(16) }, "예: 우리집, 도란이네")
                Spacer(Modifier.height(24.dp))
                PrimaryButton("시작하기", enabled = myName.isNotBlank()) { Pairing.startHousehold(myName, homeName) }
                Spacer(Modifier.height(10.dp))
                GhostButton("뒤로", Modifier.fillMaxWidth()) { step = "welcome" }
            }
            "join", "sender" -> {
                Text(if (step == "join") "가족 가계부에 합류해요" else "가족에게 카드 알림을 보내요", style = MaterialTheme.typography.headlineSmall, color = P.c.ink)
                Text(if (invited != null) "이름을 고르고 아래 버튼만 누르면 연결돼요"
                else if (step == "join") "가족 폰에 뜬 '함께 쓸 사람 초대' QR을 찍으면 바로 연결돼요"
                else "가족 폰에 뜬 '보내 줄 가족 초대' QR을 찍으면 바로 연결돼요. 이름은 가족이 정해 둬요(예: 어머니)",
                    style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft)
                Spacer(Modifier.height(20.dp))
                JoinPanel(needName = step == "join", preset = pendingInvite) { }
                Spacer(Modifier.height(8.dp))
                GhostButton("뒤로", Modifier.fillMaxWidth()) { step = "welcome" }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Feature(art: String, title: String, sub: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
        val res = when (art) { "art_capture" -> R.drawable.art_capture; "art_family" -> R.drawable.art_family; else -> R.drawable.art_cat_bank }
        Image(painterResource(res), null, Modifier.size(52.dp), contentScale = ContentScale.Fit)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = P.c.ink)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
        }
    }
}
