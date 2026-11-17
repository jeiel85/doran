package com.jeiel85.doran.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.jeiel85.doran.R
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.model.Member
import com.jeiel85.doran.sync.Pairing
import com.jeiel85.doran.sync.Relay
import com.jeiel85.doran.sync.RelayError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 이름 고르기 칩. 장부·알림에 "남편이 현대카드로 …"처럼 그대로 쓰입니다. */
val SELF_NAMES = listOf("남편", "아내", "엄마", "아빠")
val SENDER_NAMES = listOf("어머니", "아버지", "장모님", "장인어른", "시어머니", "시아버지")

@Composable
fun NamePicker(value: String, onChange: (String) -> Unit, suggestions: List<String>, label: String = "이름") {
    ChipRow {
        suggestions.forEach { n -> Chip(n, value == n, { onChange(n) }) }
    }
    Spacer(Modifier.height(10.dp))
    Field(label, value, { onChange(it.take(12)) }, "직접 쓰기 (예: 지은)")
}

/** 가족: 함께 쓰는 사람·보내 주는 가족·연결된 기기. 초대는 InviteScreen, 합류는 JoinScreen. */
@Composable
fun FamilyScreen(nav: Nav) {
    val s by Hub.ledger.state.collectAsStateWithLifecycle()
    val id by Hub.identity.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var devices by remember { mutableStateOf<Relay.SpaceInfo?>(null) }
    var removeTarget by remember { mutableStateOf<Relay.SpaceMember?>(null) }
    var rename by remember { mutableStateOf<Member?>(null) }

    LaunchedEffect(id.spaceId) {
        val space = id.spaceId ?: return@LaunchedEffect
        devices = withContext(Dispatchers.IO) { runCatching { Relay { Hub.identity.token }.space(space) }.getOrNull() }
    }

    SubScreen("가족", { nav.back() }) {
        item {
            SoftCard(padding = PaddingValues(18.dp)) {
                Image(painterResource(R.drawable.art_family), null, Modifier.fillMaxWidth().height(140.dp))
                Spacer(Modifier.height(10.dp))
                Text(s.household.name, style = MaterialTheme.typography.titleLarge, color = P.c.ink)
                Text("가족 폰에서 QR만 찍으면 연결돼요. 내 카드 알림은 내 폰의 도란이 읽어서, 가족 폰에는 \"남편이 현대카드로 12,300원 썼어요\"처럼 도란 알림으로 가요.",
                    style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft)
            }
        }
        if (id.inHousehold) {
            item { Spacer(Modifier.height(20.dp)); SectionTitle("함께 쓰는 사람") }
            item {
                SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                    s.activeMembers.filter { !it.isSender }.forEach { m ->
                        ListRow(m.name + if (m.id == id.myMemberId) " (나)" else "",
                            if (m.allowance > 0) "용돈 ${Fmt.won(m.allowance)} · 눌러서 이름 바꾸기" else "장부 전체 · 눌러서 이름 바꾸기",
                            leading = { MemberAvatar(m, 40.dp) }, onClick = { rename = m }) {
                            Icon(Icons.Rounded.Edit, "이름 바꾸기", tint = P.c.inkFaint, modifier = Modifier.size(18.dp))
                        }
                    }
                    ListRow("함께 쓸 사람 초대", "배우자 폰에서 QR을 찍으면 같은 장부를 써요", Icons.Rounded.GroupAdd, P.c.pine,
                        onClick = { nav.go(Route.Invite("member")) })
                }
            }
            item { Spacer(Modifier.height(20.dp)); SectionTitle("보내 주는 가족") }
            item {
                SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                    val senders = s.activeMembers.filter { it.isSender }
                    if (senders.isEmpty()) Text("부모님처럼 카드 알림만 보내 줄 가족을 연결할 수 있어요. 보낸 내역은 사람별로 보이고 우리 합계에 더해져요.",
                        style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft, modifier = Modifier.padding(8.dp))
                    senders.forEach { m ->
                        val count = s.txns.values.count { it.memberId == m.id }
                        ListRow(m.name, "보낸 내역 ${count}건 · 눌러서 이름 바꾸기", leading = { MemberAvatar(m, 40.dp) }, onClick = { rename = m }) {
                            SmallButton("끊기", color = P.c.danger) { Pairing.revokeSender(m.id) }
                        }
                    }
                    ListRow("보내 줄 가족 초대", "그 가족 폰에 도란을 깔고 QR을 찍으면 돼요", Icons.Rounded.Send, P.c.apricot,
                        onClick = { nav.go(Route.Invite("sender")) })
                }
            }
            devices?.let { info ->
                item { Spacer(Modifier.height(20.dp)); SectionTitle("연결된 기기") }
                item {
                    SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                        info.members.forEach { d ->
                            val who = s.members.values.firstOrNull { d.deviceId in it.deviceIds }?.name
                            ListRow(
                                (who ?: if (d.role == "sender") "보내는 가족 폰" else "가족 폰") + if (d.me) " · 이 폰" else "",
                                "연결 ${Fmt.date(d.createdAt)} · 최근 ${Fmt.relative(d.seenAt)}" + if (!d.push) " · 실시간 알림 없음" else "",
                                Icons.Rounded.Devices, if (d.role == "sender") P.c.apricot else P.c.pine,
                            ) { if (!d.me) SmallButton("내보내기", color = P.c.danger) { removeTarget = d } }
                        }
                        Text("영수증 사진 ${"%.1f".format(info.blobBytes / 1_048_576.0)}MB / ${info.blobQuota / 1_048_576}MB",
                            style = MaterialTheme.typography.bodySmall, color = P.c.inkFaint, modifier = Modifier.padding(8.dp))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)); SectionTitle("초대받았어요") }
        item {
            SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                ListRow("QR 찍어서 연결", if (id.inHousehold) "다른 가구에 내 카드 알림을 보내거나, 다른 장부에 합류해요" else "가족 폰에 뜬 QR을 찍어요",
                    Icons.Rounded.QrCodeScanner, P.c.pine, onClick = { nav.go(Route.Join()) })
                id.outLinks.forEach { l ->
                    ListRow("${l.label}에 보내는 중", (l.memberName?.let { "'$it' 이름으로 · " } ?: "") +
                        if (l.issuers.isEmpty()) "읽은 카드·은행 알림 전부" else l.issuers.joinToString(),
                        Icons.Rounded.Send, P.c.apricot) {
                        SmallButton("끊기", color = P.c.danger) { scope.launch { Pairing.leaveLink(l.spaceId) } }
                    }
                }
            }
        }
        error?.let { e -> item { Text(e, color = P.c.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) } }
    }

    rename?.let { m ->
        NameDialog(if (m.id == id.myMemberId) "내 이름" else "${m.name} 이름 바꾸기", if (m.isSender) SENDER_NAMES else SELF_NAMES, m.name,
            onDismiss = { rename = null }) { n -> Hub.ledger.saveMember(m.copy(name = n.trim())); rename = null }
    }
    removeTarget?.let { d ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("이 기기를 내보낼까요?") },
            text = { Text("잃어버린 폰이나 모르는 기기라면 내보내세요. 그 기기는 더 이상 장부를 받거나 보낼 수 없어요.") },
            confirmButton = { TextButton({
                scope.launch {
                    runCatching { Pairing.removeDevice(d.deviceId) }.onFailure { error = friendly(it) }
                    devices = withContext(Dispatchers.IO) { runCatching { Relay { Hub.identity.token }.space(id.spaceId!!) }.getOrNull() }
                }
                removeTarget = null
            }) { Text("내보내기", color = P.c.danger) } },
            dismissButton = { TextButton({ removeTarget = null }) { Text("취소") } },
        )
    }
}

fun friendly(e: Throwable): String = when {
    e is RelayError && e.code == "invite_not_found" -> "없는 초대예요. 이미 쓰였거나 코드가 틀렸어요"
    e is RelayError && e.code == "invite_expired" -> "초대 시간이 지났어요. 새 QR을 띄워 달라고 해 주세요"
    e is RelayError && e.code == "already_member" -> "이미 이 가계부에 연결된 폰이에요"
    e is RelayError -> "서버가 요청을 받지 않았어요 (${e.code})"
    e is java.io.IOException -> "인터넷 연결을 확인해 주세요"
    else -> e.message ?: "잠시 뒤 다시 시도해 주세요"
}

/**
 * 초대 QR을 화면 가득 띄웁니다. member는 바로, sender는 먼저 그 가족을 뭐라고 부를지 고릅니다.
 * 상대 폰은 "QR 찍어서 연결" 한 번이면 끝납니다.
 */
@Composable
fun InviteScreen(role: String, nav: Nav) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var senderName by remember { mutableStateOf("") }
    var askName by remember { mutableStateOf(role == "sender") }
    var invite by remember { mutableStateOf<Pairing.Invite?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var left by remember { mutableLongStateOf(0L) }

    LaunchedEffect(askName, attempt) {
        if (askName) return@LaunchedEffect
        error = null
        invite = runCatching {
            if (role == "sender") Pairing.inviteSender(senderName, 1800) else Pairing.inviteMember(1800)
        }.onFailure { error = friendly(it) }.getOrNull()
    }
    LaunchedEffect(invite) {
        val inv = invite ?: return@LaunchedEffect
        while (true) {
            left = ((inv.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            if (left == 0L) break
            delay(1000)
        }
    }

    SubScreen(if (role == "sender") "보내 줄 가족 초대" else "함께 쓸 사람 초대", { invite?.let(Pairing::cancel); nav.back() }) {
        if (askName) {
            item {
                Text("이 가족을 뭐라고 부를까요?", style = MaterialTheme.typography.titleLarge, color = P.c.ink)
                Text("알림과 장부에 \"어머니가 현대카드로 21,000원 썼어요\"처럼 나와요", style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft)
                Spacer(Modifier.height(16.dp))
                NamePicker(senderName, { senderName = it }, SENDER_NAMES)
                Spacer(Modifier.height(20.dp))
                PrimaryButton("QR 만들기", enabled = senderName.isNotBlank()) { askName = false }
            }
            return@SubScreen
        }
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (role == "sender") "${senderName}의 폰에서 QR을 찍어 주세요" else "배우자 폰에서 QR을 찍어 주세요",
                    style = MaterialTheme.typography.titleLarge, color = P.c.ink, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text("폰 기본 카메라로 찍으면 돼요. 도란이 없으면 설치부터 안내하고, 설치하면 바로 이어서 연결돼요.",
                    style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                val inv = invite
                Box(Modifier.size(300.dp).clip(RoundedCornerShape(28.dp)).background(Color.White).padding(18.dp), contentAlignment = Alignment.Center) {
                    when {
                        inv != null && left > 0 -> {
                            val qr: Bitmap = remember(inv.code) { BarcodeEncoder().encodeBitmap(inv.code, BarcodeFormat.QR_CODE, 800, 800) }
                            Image(qr.asImageBitmap(), "초대 QR", Modifier.size(264.dp))
                        }
                        error != null -> Text(error!!, color = P.c.danger, textAlign = TextAlign.Center)
                        inv != null -> Text("시간이 지났어요", color = P.c.inkSoft)
                        else -> Text("QR 만드는 중…", color = P.c.inkSoft)
                    }
                }
                Spacer(Modifier.height(14.dp))
                if (inv != null && left > 0) {
                    Text("${left / 60}분 ${"%02d".format(left % 60)}초 동안 한 번 쓸 수 있어요", style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                } else if (inv != null || error != null) {
                    SmallButton("새 QR 만들기") { invite = null; attempt++ }
                }
                Spacer(Modifier.height(20.dp))
                if (inv != null && left > 0) {
                    Text("멀리 있으면 링크를 카톡·문자로 보내도 돼요. 누르면 똑같이 이어져요.", style = MaterialTheme.typography.bodySmall, color = P.c.inkFaint)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton("링크 복사") { clipboard.setText(AnnotatedString(inv.code)) }
                        SmallButton("링크 보내기") {
                            val who = if (role == "sender") "카드 알림을 우리 가계부로 보내 주세요" else "같이 가계부를 써요"
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, "도란 가족 가계부 초대예요. $who!\n아래 링크를 누르면 설치와 연결을 안내해요(30분 동안, 한 번만).\n${inv.code}"),
                                "초대 링크 보내기"))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 합류: 이름 고르기(장부를 함께 쓸 때) → QR 찍기 → 바로 연결. 코드는 접어 둔 두 번째 방법.
 * 온보딩과 가족 화면이 같은 화면을 씁니다.
 */
@Composable
fun JoinPanel(needName: Boolean, preset: String? = null, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var showCode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var joined by remember { mutableStateOf<Pairing.Joined?>(null) }

    fun connect(text: String) {
        val secret = Pairing.parseCode(text)
        if (secret == null) { error = "도란 초대 QR·링크가 아니에요"; return }
        busy = true; error = null
        scope.launch {
            runCatching { Pairing.join(secret, name) }.onSuccess { joined = it }.onFailure { error = friendly(it) }
            busy = false
        }
    }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { connect(it) } }
    val invited = remember(preset) { Pairing.parse(preset) }
    // 보내기 전용 초대면 이름은 초대한 가족이 이미 정해 두었습니다(예: 어머니).
    val askName = needName && invited?.role != "sender"

    when (val j = joined) {
        is Pairing.Joined.Household -> Column {
            Text("연결했어요!", style = MaterialTheme.typography.headlineSmall, color = P.c.pine)
            Text("${j.inviter.ifBlank { "가족" }}님의 '${j.name}' 가계부에 '${name.ifBlank { "나" }}'(으)로 합류했어요. 내역을 받아 오는 중이에요.",
                style = MaterialTheme.typography.bodyLarge, color = P.c.ink)
            Spacer(Modifier.height(20.dp))
            PrimaryButton("가계부 보기", onClick = onDone)
        }
        is Pairing.Joined.Sender -> Column {
            Text("연결했어요!", style = MaterialTheme.typography.headlineSmall, color = P.c.pine)
            Text("이제 이 폰에 오는 카드·은행 알림을 '${j.label}'에 '${j.memberName}' 이름으로 보내요. 알림 자동 기록 화면에서 알림 접근을 켜 주세요.",
                style = MaterialTheme.typography.bodyLarge, color = P.c.ink)
            Spacer(Modifier.height(20.dp))
            PrimaryButton("확인", onClick = onDone)
        }
        null -> Column {
            if (invited != null) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(P.c.pineSoft).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.art_family), null, Modifier.size(56.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${invited.inviter?.let { "${it}님이 보낸 " } ?: ""}초대를 받았어요", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
                        Text(if (invited.role == "sender") "이 폰의 카드·은행 알림을 가족 가계부로 보내요" else "같은 가계부를 함께 써요",
                            style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
            if (askName) {
                Text("나는 누구인가요?", style = MaterialTheme.typography.titleMedium, color = P.c.ink)
                Text("가족 폰 알림에 \"아내가 신한카드로 …\"처럼 이 이름이 나와요", style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                Spacer(Modifier.height(10.dp))
                NamePicker(name, { name = it }, SELF_NAMES, "내 이름")
                Spacer(Modifier.height(20.dp))
            }
            if (invited != null) {
                PrimaryButton(if (busy) "연결하는 중…" else "연결하기", enabled = !busy && (!askName || name.isNotBlank())) { connect(preset!!) }
                error?.let { Text(it, color = P.c.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp)) }
                return@Column
            }
            PrimaryButton(if (busy) "연결하는 중…" else "QR 찍어서 연결하기", enabled = !busy && (!askName || name.isNotBlank())) {
                scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("가족 폰에 뜬 도란 QR을 비춰 주세요")
                    .setBeepEnabled(false).setOrientationLocked(true))
            }
            error?.let { Text(it, color = P.c.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp)) }
            Spacer(Modifier.height(8.dp))
            TextButton({ showCode = !showCode }) { Text(if (showCode) "링크 입력 닫기" else "QR 대신 받은 링크로 연결", color = P.c.inkSoft) }
            if (showCode) {
                Field("초대 링크", code, { code = it }, "https://jeiel85.github.io/doran/join/#…")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ clipboard.getText()?.text?.let { code = it } }) { Text("붙여 넣기") }
                    Spacer(Modifier.width(8.dp))
                    SmallButton("연결", enabled = !busy && code.isNotBlank() && (!askName || name.isNotBlank())) { connect(code) }
                }
            }
        }
    }
}

@Composable
fun JoinScreen(link: String?, nav: Nav) {
    val id by Hub.identity.state.collectAsStateWithLifecycle()
    val needName = remember { id.myMemberId == null }
    SubScreen(if (link != null) "가족 초대" else "QR 찍어서 연결", { nav.back() }) {
        item {
            if (link == null) {
                Image(painterResource(R.drawable.art_family), null, Modifier.fillMaxWidth().height(150.dp))
                Spacer(Modifier.height(12.dp))
            }
            JoinPanel(needName, link) { nav.back() }
        }
    }
}

@Composable
fun NameDialog(title: String, suggestions: List<String>, initial: String = "", onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = P.c.bg,
        title = { Text(title) },
        text = { Column { NamePicker(name, { name = it }, suggestions) } },
        confirmButton = { TextButton({ onOk(name) }, enabled = name.isNotBlank()) { Text("저장") } },
        dismissButton = { TextButton(onDismiss) { Text("취소") } },
    )
}
