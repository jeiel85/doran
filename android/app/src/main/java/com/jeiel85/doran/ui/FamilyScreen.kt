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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.AlertDialog
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
import com.jeiel85.doran.sync.Pairing
import com.jeiel85.doran.sync.Relay
import com.jeiel85.doran.sync.RelayError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 가족: 함께 쓰는 사람·보내 주는 가족·연결된 기기, 초대(QR·코드)와 합류. */
@Composable
fun FamilyScreen(nav: Nav) {
    val s by Hub.ledger.state.collectAsStateWithLifecycle()
    val id by Hub.identity.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var invite by remember { mutableStateOf<Pairing.Invite?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) } // "member" "sender" "join"
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var devices by remember { mutableStateOf<Relay.SpaceInfo?>(null) }
    var removeTarget by remember { mutableStateOf<Relay.SpaceMember?>(null) }

    LaunchedEffect(id.spaceId) {
        val space = id.spaceId ?: return@LaunchedEffect
        devices = withContext(Dispatchers.IO) { runCatching { Relay { Hub.identity.token }.space(space) }.getOrNull() }
    }

    fun startInvite(block: suspend () -> Pairing.Invite) {
        busy = true; error = null
        scope.launch {
            invite = runCatching { block() }.onFailure { error = friendly(it) }.getOrNull()
            busy = false
        }
    }

    SubScreen("가족", { invite?.let(Pairing::cancel); nav.back() }) {
        item {
            SoftCard(padding = PaddingValues(18.dp)) {
                Image(painterResource(R.drawable.art_family), null, Modifier.fillMaxWidth().height(140.dp))
                Spacer(Modifier.height(10.dp))
                Text(s.household.name, style = MaterialTheme.typography.titleLarge, color = P.c.ink)
                Text("함께 쓰는 사람은 장부 전체를, 보내 주는 가족은 자기 카드 알림만 우리 장부로 보내요. 내용은 우리 폰에서만 열려요.",
                    style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft)
            }
        }
        if (id.inHousehold) {
            item { Spacer(Modifier.height(20.dp)); SectionTitle("함께 쓰는 사람") }
            item {
                SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                    s.activeMembers.filter { !it.isSender }.forEach { m ->
                        ListRow(m.name + if (m.id == id.myMemberId) " (나)" else "", if (m.allowance > 0) "용돈 ${Fmt.won(m.allowance)}" else "장부 전체",
                            leading = { MemberAvatar(m, 40.dp) })
                    }
                    ListRow("함께 쓸 사람 초대", "배우자 폰에서 이 QR을 찍으면 같은 장부를 써요", Icons.Rounded.GroupAdd, P.c.pine,
                        onClick = { startInvite { Pairing.inviteMember(600) } })
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
                        ListRow(m.name, "보낸 내역 ${count}건", leading = { MemberAvatar(m, 40.dp) }) {
                            SmallButton("끊기", color = P.c.danger) { Pairing.revokeSender(m.id) }
                        }
                    }
                    ListRow("보내 줄 가족 초대", "그 가족 폰에 도란을 깔고 QR이나 코드로 연결해요", Icons.Rounded.Send, P.c.apricot,
                        onClick = { dialog = "sender" })
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
                ListRow("QR 찍기·코드 넣기", if (id.inHousehold) "다른 가구에 카드 알림을 보내거나, 다른 장부에 합류해요" else "가족이 보여 준 QR이나 받은 코드로 연결해요",
                    Icons.Rounded.QrCodeScanner, P.c.pine, onClick = { dialog = "join" })
                id.outLinks.forEach { l ->
                    ListRow("${l.label}에 보내는 중", if (l.issuers.isEmpty()) "읽은 카드·은행 알림 전부" else l.issuers.joinToString(),
                        Icons.Rounded.Send, P.c.apricot) {
                        SmallButton("끊기", color = P.c.danger) { scope.launch { Pairing.leaveLink(l.spaceId) } }
                    }
                }
            }
        }
        if (busy) item { Text("준비하는 중…", color = P.c.inkSoft, modifier = Modifier.padding(16.dp)) }
        error?.let { e -> item { Text(e, color = P.c.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) } }
    }

    invite?.let { inv -> InviteDialog(inv) { Pairing.cancel(inv); invite = null } }
    when (dialog) {
        "sender" -> NameDialog("보내 줄 가족 이름", "예: 어머니, 아버지", onDismiss = { dialog = null }) { name ->
            dialog = null
            startInvite { Pairing.inviteSender(name, 1800) }
        }
        "join" -> JoinDialog(needName = !id.inHousehold) { dialog = null }
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
    e is RelayError && e.code == "invite_expired" -> "초대 시간이 지났어요. 새 QR을 만들어 달라고 해 주세요"
    e is RelayError && e.code == "already_member" -> "이미 이 가계부에 연결된 폰이에요"
    e is RelayError -> "서버가 요청을 받지 않았어요 (${e.code})"
    e is java.io.IOException -> "인터넷 연결을 확인해 주세요"
    else -> e.message ?: "잠시 뒤 다시 시도해 주세요"
}

@Composable
private fun InviteDialog(inv: Pairing.Invite, onClose: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val qr: Bitmap = remember(inv.code) { BarcodeEncoder().encodeBitmap(inv.code, BarcodeFormat.QR_CODE, 720, 720) }
    var left by remember { mutableLongStateOf((inv.expiresAt - System.currentTimeMillis()) / 1000) }
    LaunchedEffect(inv) { while (left > 0) { delay(1000); left = (inv.expiresAt - System.currentTimeMillis()) / 1000 } }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = P.c.bg,
        title = { Text(if (inv.role == "member") "함께 쓸 사람 초대" else "보내 줄 가족 초대") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White).padding(12.dp)) {
                    Image(qr.asImageBitmap(), "초대 QR", Modifier.size(220.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text(if (left > 0) "${left / 60}분 ${left % 60}초 안에 상대 폰의 도란 → 가족 → QR 찍기" else "시간이 지났어요. 다시 만들어 주세요",
                    style = MaterialTheme.typography.bodySmall, color = if (left > 0) P.c.inkSoft else P.c.danger, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text("멀리 있으면 코드를 메시지로 보내도 돼요. 한 번만 쓸 수 있어요.", style = MaterialTheme.typography.bodySmall, color = P.c.inkFaint,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("코드 복사") { clipboard.setText(AnnotatedString(inv.code)) }
                    SmallButton("보내기") {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "도란 가족 가계부 초대 코드예요. 도란 → 가족 → QR 찍기·코드 넣기에 붙여 넣어 주세요.\n${inv.code}"), "초대 코드 보내기"))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("닫기") } },
    )
}

@Composable
fun NameDialog(title: String, placeholder: String, initial: String = "", onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = P.c.bg,
        title = { Text(title) },
        text = { Field("이름", name, { name = it.take(12) }, placeholder) },
        confirmButton = { TextButton({ onOk(name) }, enabled = name.isNotBlank()) { Text("다음") } },
        dismissButton = { TextButton(onDismiss) { Text("취소") } },
    )
}

/** QR 찍기 또는 코드 붙여 넣기 → 합류. */
@Composable
fun JoinDialog(needName: Boolean, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var joined by remember { mutableStateOf<Pairing.Joined?>(null) }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { code = it } }

    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        containerColor = P.c.bg,
        title = { Text(if (joined != null) "연결했어요" else "초대로 연결하기") },
        text = {
            Column {
                when (val j = joined) {
                    is Pairing.Joined.Household -> Text("${j.inviter.ifBlank { "가족" }}님의 '${j.name}' 가계부에 합류했어요. 내역을 받아 오는 중이에요.")
                    is Pairing.Joined.Sender -> Text("'${j.label}'에 ${j.memberName} 님 이름으로 카드·은행 알림을 보내요. 알림 접근 권한을 켜 두세요.")
                    null -> {
                        SmallButton("QR 찍기") {
                            scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("가족 폰의 초대 QR을 비춰 주세요")
                                .setBeepEnabled(false).setOrientationLocked(true))
                        }
                        Spacer(Modifier.height(10.dp))
                        Field("초대 코드", code, { code = it }, "doran:1:…")
                        Row { TextButton({ clipboard.getText()?.text?.let { code = it } }) { Text("붙여 넣기") } }
                        if (needName) { Spacer(Modifier.height(6.dp)); Field("내 이름", name, { name = it.take(12) }, "예: 엄마, 지은") }
                        error?.let { Text(it, color = P.c.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                        if (busy) Text("연결하는 중…", color = P.c.inkSoft, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            if (joined != null) TextButton(onDone) { Text("확인") }
            else TextButton({
                val secret = Pairing.parseCode(code)
                if (secret == null) { error = "코드 모양이 맞지 않아요"; return@TextButton }
                busy = true; error = null
                scope.launch {
                    runCatching { Pairing.join(secret, name) }.onSuccess { joined = it }.onFailure { error = friendly(it) }
                    busy = false
                }
            }, enabled = !busy && code.isNotBlank()) { Text("연결") }
        },
        dismissButton = { if (joined == null) TextButton(onDone, enabled = !busy) { Text("취소") } },
    )
}

