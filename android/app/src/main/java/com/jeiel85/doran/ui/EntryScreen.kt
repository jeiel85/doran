package com.jeiel85.doran.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeiel85.doran.capture.Categorizer
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.core.newId
import com.jeiel85.doran.feature.Receipts
import com.jeiel85.doran.logic.Moves
import com.jeiel85.doran.model.AccountType
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 내역 쓰기·보기·고치기. 숫자는 화면 아래 자판으로 넣습니다(키보드가 금액을 가리지 않게). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EntryScreen(route: Route.Entry, nav: Nav) {
    val s by Hub.ledger.state.collectAsStateWithLifecycle()
    val id by Hub.identity.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val original = remember(route.txnId) { route.txnId?.let { s.txns[it] } }
    if (route.txnId != null && original == null) {
        SubScreen("내역", { nav.back() }) { item { EmptyState(com.jeiel85.doran.R.drawable.art_empty, "지워진 내역이에요", "다른 가족이 지웠을 수 있어요") } }
        return
    }
    LaunchedEffect(route.txnId) { route.txnId?.let { Hub.local.markSeen(listOf(it)) } }

    var kind by remember { mutableStateOf(original?.kind ?: route.kind) }
    var amount by remember { mutableStateOf(original?.amount?.toString() ?: "") }
    var merchant by remember { mutableStateOf(original?.merchant ?: "") }
    var memo by remember { mutableStateOf(original?.memo ?: "") }
    var categoryId by remember { mutableStateOf(original?.categoryId) }
    // 내 현금 계좌는 새로 쓰는 내역에만 미리 고릅니다. 계좌 없는 기록(범위 밖 통장의 용돈 내역·옮긴 돈, D-19·D-26)을 열 때 채우면
    // 옮긴 돈은 받는 계좌를 고르기 전까지 저장이 막히고, 저장하면 범위 밖 출금이 가계부 현금 잔액에 섞입니다(#49 리뷰).
    var accountId by remember {
        mutableStateOf(if (original != null) original.accountId else s.accounts.values.firstOrNull { it.ownerId == id.myMemberId && it.type == AccountType.CASH }?.id)
    }
    var toAccountId by remember { mutableStateOf(original?.toAccountId) }
    var memberId by remember { mutableStateOf(original?.memberId ?: id.myMemberId) }
    var installment by remember { mutableStateOf(original?.installment ?: 0) }
    var private by remember { mutableStateOf(original?.private ?: false) }
    val receipts = remember { mutableStateListOf<String>().apply { addAll(original?.receiptIds ?: emptyList()) } }
    var at by remember {
        mutableStateOf(original?.at ?: route.date?.let { Fmt.millis(LocalDateTime.of(it, LocalTime.now(Fmt.zone))) } ?: System.currentTimeMillis())
    }
    var keypad by remember { mutableStateOf(original == null) }
    var picker by remember { mutableStateOf<String?>(null) } // "date" "time" "account" "to" "delete"
    var touchedCategory by remember { mutableStateOf(original != null) }

    // 내용을 쓰면 카테고리를 자동으로 골라 줍니다(사람이 직접 고르기 전까지).
    LaunchedEffect(merchant, kind) {
        if (!touchedCategory && kind != Kind.TRANSFER && merchant.isNotBlank()) categoryId = Categorizer.categorize(s, merchant, kind)
    }

    val cancels = original?.let { o -> s.txns.values.filter { it.cancelOf == o.id } } ?: emptyList()
    val canEditPrivate = memberId == id.myMemberId && id.myMemberId != null && kind == Kind.EXPENSE
    // 나만 보는 용돈 내역은 저장해도 개인으로 남습니다(#43). 가족과 나누려면 지출에서 "내 용돈으로 쓰기"를 끄는 것뿐이고,
    // 그동안은 다른 사람 내역으로 바꾸지 못하게 사람 고르기를 감춥니다(남의 내역을 내 개인 키로 봉하지 않게).
    val lockedPrivate = original?.private == true && private
    // 옮긴 돈으로 본 은행 출금(D-26)은 계좌 없는 이체라, 계좌를 고르지 않아도 저장됩니다.
    val accountlessMove = lockedPrivate && kind == Kind.TRANSFER && accountId == null && toAccountId == null
    val value = amount.toLongOrNull() ?: 0L
    val valid = value > 0 && (kind != Kind.TRANSFER || accountlessMove || (accountId != null && toAccountId != null && accountId != toAccountId))
    // 은행 출금이면 "옮긴 돈 ↔ 용돈에서 쓴 돈"을 바꿀 수 있게 합니다(D-26). 판정 전 기록은 알림 원문을 읽어 가리므로 DB를 봅니다.
    val bankOut by produceState(false, original?.id) {
        this.value = original?.let { o -> withContext(Dispatchers.IO) { Hub.capture.isBankWithdrawal(o) } } == true
    }
    var rememberMove by remember { mutableStateOf(false) }

    /** 화면의 고침을 장부에 씁니다(닫지 않음). */
    fun persist() {
        val t = (original ?: Txn(id = newId(), kind = kind, amount = 0, at = at, source = Source.MANUAL,
            createdBy = Hub.identity.installId, createdAt = System.currentTimeMillis())).copy(
            kind = kind, amount = value, at = at, merchant = merchant.trim(), memo = memo.trim(),
            categoryId = if (kind == Kind.TRANSFER) null else categoryId ?: Categorizer.categorize(s, merchant, kind),
            accountId = accountId, toAccountId = if (kind == Kind.TRANSFER) toAccountId else null, memberId = memberId,
            installment = if (kind == Kind.EXPENSE) installment else 0,
            private = if (original?.private == true) private else private && canEditPrivate,
            receiptIds = receipts.toList(), needsReview = false,
            // 종류를 손으로 바꾼 은행 출금은 사용자가 정한 것이라 다시 자동 판정하지 않습니다(D-26).
            move = original?.move?.let { m -> if (kind == original.kind) m else if (kind == Kind.TRANSFER) Moves.USER else Moves.KEPT },
        )
        Hub.ledger.saveTxn(t)
        // 가족 카드에서 용돈으로 옮겼던 결제를 다시 공유로 돌리면, 대신 남겨 둔 금액 자리를 지웁니다(D-20).
        if (original?.private == true && !t.private) stubId(t.id).takeIf { s.txns.containsKey(it) }?.let { Hub.ledger.delete(com.jeiel85.doran.model.T.TXN, it) }
        scope.launch { receipts.forEach { Receipts.upload(context, it) } }
    }

    fun save() {
        persist()
        // 내역 탭에서 왔으면 저장한 날을 고른 채로 돌아갑니다(날짜를 바꿨으면 바뀐 날로).
        if (nav.tab == Tab.LEDGER && nav.stack.size == 2) nav.ledger.show(Fmt.date(at))
        nav.back()
    }

    val cameraFile = remember { File(context.cacheDir, "camera").apply { mkdirs() }.let { File(it, "shot.jpg") } }
    val cameraUri = remember { FileProvider.getUriForFile(context, "${context.packageName}.files", cameraFile) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch { receipts += Receipts.import(context, uri) }
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) scope.launch { receipts += Receipts.import(context, cameraUri); cameraFile.delete() }
    }
    // QR 스캐너 라이브러리가 CAMERA 권한을 선언하므로, 권한 없이 카메라 앱을 부르면 안드로이드가 막습니다(SecurityException).
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) takePhoto.launch(cameraUri)
    }
    fun shoot() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) takePhoto.launch(cameraUri)
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    Column(Modifier.fillMaxSize().background(P.c.bg).imePadding()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로", tint = P.c.ink) }
            Text(if (original == null) "새 내역" else "내역", style = MaterialTheme.typography.titleLarge, color = P.c.ink, modifier = Modifier.weight(1f))
            if (original != null) IconButton(onClick = { picker = "delete" }) { Icon(Icons.Rounded.DeleteOutline, "지우기", tint = P.c.danger) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Segmented(listOf("지출", "수입", "이체"), kind.ordinal) { kind = Kind.entries[it]; if (!touchedCategory) categoryId = null }
            Spacer(Modifier.height(12.dp))
            // 날짜는 맨 위에 둡니다. 새로 쓸 때는 키패드가 아래 '날짜' 줄을 가려, 어느 날로 저장되는지 모른 채 기록하게 됐어요.
            DateChip(at, onClick = { picker = "date" })
            Spacer(Modifier.height(12.dp))
            // 금액
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).clickable { keypad = true }.padding(vertical = 6.dp)) {
                Text(when (kind) { Kind.EXPENSE -> "얼마를 썼나요?"; Kind.INCOME -> "얼마가 들어왔나요?"; Kind.TRANSFER -> "얼마를 옮겼나요?" },
                    style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft)
                FitText(if (value > 0) Fmt.won(value) else "0원", MaterialTheme.typography.displaySmall.merge(Money),
                    if (value > 0) kindColor(kind, P.c).takeIf { kind != Kind.EXPENSE } ?: P.c.ink else P.c.inkFaint, minSize = 22.sp)
                original?.let { o ->
                    if (o.source.captured || o.cumulative != null || cancels.isNotEmpty() || o.foreign != null) {
                        Spacer(Modifier.height(10.dp))
                        val who = s.member(o.memberId)?.name
                        val rows = listOfNotNull(
                            "기록" to when (o.source) {
                                Source.PUSH -> "${who?.let { "$it 폰이 " } ?: ""}카드·은행 앱 알림으로 자동 기록"
                                Source.SMS -> "${who?.let { "$it 폰이 " } ?: ""}문자로 자동 기록"
                                Source.FAMILY -> "${who ?: "가족"} 폰이 보내 줌"
                                else -> com.jeiel85.doran.logic.Fixed.source(o)
                            },
                            o.cardLabel?.let { "카드" to it },
                            o.cumulative?.let { "카드사 누적" to Fmt.won(it) },
                            o.foreign?.let { "해외 결제" to it },
                            cancels.takeIf { it.isNotEmpty() }?.let { "취소" to Fmt.won(it.sumOf { c -> c.amount }) },
                        )
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.c.surface2).padding(horizontal = 14.dp, vertical = 10.dp)) {
                            rows.forEach { (k, v) ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                    Text(k, style = MaterialTheme.typography.bodySmall, color = P.c.inkFaint, modifier = Modifier.width(76.dp))
                                    Text(v, style = MaterialTheme.typography.bodySmall, color = P.c.ink)
                                }
                            }
                        }
                    }
                    if (!o.private && o.kind == Kind.EXPENSE && o.cancelOf == null && o.memberId == id.myMemberId && id.myMemberId != null &&
                        s.account(o.accountId)?.family == true) {
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.c.apricotSoft).padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("가족 카드로 산 내 물건이면 내 용돈으로 옮겨요. 가족 가계부에서는 빠져요.",
                                style = MaterialTheme.typography.bodySmall, color = P.c.ink, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            SmallButton("내 용돈으로", color = P.c.apricot) {
                                // 내용은 내 용돈(개인 키)으로, 가족 카드에는 카드값이 맞도록 금액 자리만 남깁니다(D-20).
                                val net = (o.amount - cancels.sumOf { it.amount }).coerceAtLeast(0)
                                val stub = Txn(id = stubId(o.id), kind = Kind.EXPENSE, amount = net, at = o.at, accountId = o.accountId,
                                    categoryId = com.jeiel85.doran.model.Defaults.ALLOWANCE, memberId = o.memberId, installment = o.installment,
                                    merchant = "${s.member(o.memberId)?.name ?: "가족"} 개인 결제(용돈)", personal = true,
                                    memo = "가족 카드로 낸 개인 결제예요. 카드값에는 들어가고 가계부 합계에는 안 들어가요",
                                    createdBy = Hub.identity.installId, createdAt = System.currentTimeMillis())
                                Hub.ledger.putAll((listOf(o) + cancels).map { c -> Triple(com.jeiel85.doran.model.T.TXN, c.id, c.copy(private = true).json()) } +
                                    Triple(com.jeiel85.doran.model.T.TXN, stub.id, stub.json()))
                                nav.back()
                            }
                        }
                    }
                    if (bankOut) {
                        // 은행 출금: 용돈에서 쓴 돈인지, 다른 통장으로 옮긴 돈인지(D-26). 누르면 바로 바꾸고 닫습니다.
                        val moved = o.kind == Kind.TRANSFER
                        Spacer(Modifier.height(10.dp))
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (moved) P.c.pineSoft else P.c.apricotSoft)
                            .padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Text(if (moved && Moves.fromOf(o) != null) "가족 돈에서 나갔어요 · 용돈에서 빼지 않았어요"
                                else if (moved) "옮긴 돈이에요 · 용돈에서 빼지 않았어요" else "용돈에서 쓴 돈으로 셌어요",
                                style = MaterialTheme.typography.titleSmall, color = P.c.ink)
                            Moves.reason(s, o, id.myMemberId)?.takeIf { moved || o.move != Moves.AUTO }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                            }
                            // 이 통장 내역은 판정과 관계없이 가계부 합계에 들어가지 않습니다. "이체가 아니라 지출이어야 하지 않나"를 미리 풀어 줍니다(D-32).
                            Text(Moves.householdNote(s, o, id.myMemberId), style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                            Text(if (moved) "용돈으로 실제로 쓴 돈이면 바꿔 주세요. 남은 용돈과 가족에게 가는 용돈 합계에 들어가요."
                                else "급여를 다른 통장으로 보냈거나 적금처럼 옮긴 돈, 가족이 보내 준 돈으로 낸 관리비·대출이면 바꿔 주세요. 남은 용돈과 가족에게 가는 용돈 합계에서 빠져요.",
                                style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                            // 금액이 달라 앱이 잇지 못한 가족 돈(D-32): 받은 돈으로 낸 출금이면 골라서 잇습니다.
                            val choices = if (Moves.fromOf(o) == null) Moves.fundChoices(s, o) else emptyList()
                            if (choices.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text("가족이 보내 준 돈으로 냈으면 골라 주세요", style = MaterialTheme.typography.bodySmall, color = P.c.ink)
                                Spacer(Modifier.height(6.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    choices.take(3).forEach { d ->
                                        val src = Moves.fundOf(d)?.let { s.txns[it] }
                                        val who = src?.let { if (it.memberId == id.myMemberId) "내" else s.member(it.memberId)?.name } ?: "가족"
                                        Chip("${Fmt.date(d.at).let { "${it.monthValue}/${it.dayOfMonth}" }} $who ${Fmt.won(d.amount)}", false, {
                                            if (valid && kind == o.kind) persist()
                                            Hub.capture.pickFund(o, d)
                                            nav.back()
                                        })
                                    }
                                }
                            }
                            if (Moves.ruleKey(o.issuer, o.merchant) != null) {
                                Spacer(Modifier.height(8.dp))
                                Chip("${listOfNotNull(o.issuer, o.merchant.ifBlank { null }).joinToString(" · ")} 출금은 앞으로도 이렇게", rememberMove,
                                    { rememberMove = !rememberMove })
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.weight(1f))
                                SmallButton(if (moved) "용돈으로 쓴 돈이에요" else "옮긴 돈·가족 돈이에요", color = if (moved) P.c.apricot else P.c.pine) {
                                    // 먼저 고친 내용·메모·날짜가 있으면 함께 저장하고 판정만 바꿉니다(#50 — 전에는 판정만 저장하고 닫아 다른 고침을 버렸어요).
                                    // 위에서 종류를 손으로 바꿨으면 그 고침이 판정이라 여기서는 쓰지 않습니다.
                                    if (valid && kind == o.kind) persist()
                                    Hub.capture.setMoved(o, moved = !moved, remember = rememberMove)
                                    nav.back()
                                }
                            }
                        }
                    } else if (o.needsReview) Text(if (o.amount == 0L) "원화 금액이 확정되면 넣어 주세요" else "확인이 필요한 내역이에요",
                        style = MaterialTheme.typography.bodySmall, color = P.c.apricot)
                    // 가계부 통장끼리 옮긴 돈(D-28): 짝지은 것, 또는 따로 세기로 둔 것 중 다시 묶을 짝이 있는 것.
                    if (!o.private && (Moves.linked(o) || o.move == Moves.APART && Moves.relink(s, o).isNotEmpty())) {
                        Spacer(Modifier.height(10.dp))
                        LinkCard(s, o) { nav.back() }
                    }
                    // 가족이 가계부 통장에서 보낸 돈(D-28): 나만 보는 은행 입금.
                    if (o.private && o.memberId == id.myMemberId && Moves.fundOf(o) != null) {
                        Spacer(Modifier.height(10.dp))
                        FundCard(s, o, id.myMemberId) { nav.back() }
                    }
                    // 입금: 출금 알림이 없는 내 통장(토스뱅크의 내 이체)에서 옮겨 온 돈이면 수입에서 뺍니다(D-31). 나만 보는 입금은 내 폰에서만.
                    if ((Moves.canMarkSelf(o) || Moves.selfIn(o)) && (!o.private || o.memberId == id.myMemberId)) {
                        Spacer(Modifier.height(10.dp))
                        SelfCard(o) {
                            // 먼저 고친 내용·메모·날짜가 있으면 함께 저장하고 표시만 바꿉니다(출금 판정 버튼과 같은 방식, #50).
                            if (valid && kind == o.kind) persist()
                            Hub.ledger.value.txns[o.id]?.let { cur -> Hub.ledger.saveTxn(Moves.markSelf(cur, on = !Moves.selfIn(o))) }
                            nav.back()
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Field("내용", merchant, { merchant = it }, if (kind == Kind.INCOME) "예: 월급, 용돈" else "예: 스타벅스, 장보기")

            if (kind != Kind.TRANSFER) {
                Spacer(Modifier.height(18.dp))
                Text("카테고리", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
                Spacer(Modifier.height(10.dp))
                val cats = s.categories.values.filter { it.kind == kind && !it.hidden }.sortedBy { it.order }
                FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = 4, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cats.forEach { c ->
                        val sel = c.id == categoryId
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                                .background(if (sel) P.c.pineSoft else P.c.surface)
                                .border(if (sel) 2.dp else 1.dp, if (sel) P.c.pine else P.c.line, RoundedCornerShape(18.dp))
                                .clickable { categoryId = c.id; touchedCategory = true }.padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ArtImage(c.icon, 38.dp)
                            Spacer(Modifier.height(4.dp))
                            Text(c.name, style = MaterialTheme.typography.labelMedium, color = if (sel) P.c.pine else P.c.inkSoft, maxLines = 1, textAlign = TextAlign.Center)
                        }
                    }
                    repeat((4 - cats.size % 4) % 4) { Spacer(Modifier.weight(1f)) }
                }
            }

            Spacer(Modifier.height(18.dp))
            SoftCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                ListRow("날짜", Fmt.full(at), Icons.Rounded.CalendarToday, P.c.pine, onClick = { picker = "date" })
                // 옮긴 돈으로 본 범위 밖 은행 출금은 가계부에 계좌를 두지 않는 개인 내역이라(D-19·D-26) 계좌를 고르지 않습니다.
                if (!(accountlessMove && original?.move != null)) {
                    ListRow(if (kind == Kind.TRANSFER) "보내는 계좌" else if (kind == Kind.INCOME) "들어온 곳" else "결제 수단",
                        s.account(accountId)?.name ?: "선택 안 함", leading = { CatTile(if (s.account(accountId)?.type == AccountType.CARD) "card" else "bank", 40.dp) },
                        onClick = { picker = "account" })
                    if (kind == Kind.TRANSFER) ListRow("받는 계좌", s.account(toAccountId)?.name ?: "선택해 주세요", leading = { CatTile("transfer", 40.dp) },
                        onClick = { picker = "to" })
                }
                if (kind == Kind.EXPENSE && s.account(accountId)?.type == AccountType.CARD) {
                    Text("할부", style = MaterialTheme.typography.titleSmall, color = P.c.ink, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp))
                    ChipRow(Modifier.padding(start = 8.dp, bottom = 10.dp)) {
                        listOf(0, 2, 3, 6, 10, 12, 24).forEach { m ->
                            Chip(if (m == 0) "일시불" else "${m}개월", installment == m || (m == 0 && installment <= 1), { installment = m })
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("누구의 내역인가요?", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
            Spacer(Modifier.height(10.dp))
            if (lockedPrivate) {
                Text("나만 보는 용돈 내역이라 가족에게는 보이지 않아요" + if (canEditPrivate) ". 가족과 나누려면 아래 '내 용돈으로 쓰기'를 꺼 주세요" else "",
                    style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
            } else ChipRow {
                Chip("공동", memberId == null, { memberId = null })
                s.activeMembers.forEach { m ->
                    Chip(m.name, memberId == m.id, { memberId = m.id }, leading = { MemberAvatar(m, 18.dp) }, color = memberColor(m.color))
                }
            }
            if (canEditPrivate) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(P.c.apricotSoft).clickable { private = !private }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, tint = P.c.apricot)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("내 용돈으로 쓰기", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
                        Text("가족에게는 월 합계만 보이고 이 내역은 보이지 않아요", style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                    }
                    Switch(private, { private = it }, colors = SwitchDefaults.colors(checkedTrackColor = P.c.apricot, checkedThumbColor = Color.White))
                }
            }

            Spacer(Modifier.height(18.dp))
            Field("메모", memo, { memo = it }, "남기고 싶은 말", singleLine = false)

            Spacer(Modifier.height(18.dp))
            Text("영수증", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
            Spacer(Modifier.height(10.dp))
            val thumbs = remember { mutableStateMapOf<String, Bitmap?>() }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                receipts.forEach { rid ->
                    LaunchedEffect(rid) { if (!thumbs.containsKey(rid)) thumbs[rid] = Receipts.load(context, rid) }
                    Box(Modifier.size(84.dp).clip(RoundedCornerShape(16.dp)).background(P.c.surface2)) {
                        thumbs[rid]?.let { Image(it.asImageBitmap(), "영수증", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape).background(Color(0x99000000))
                            .clickable { receipts.remove(rid) }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Close, "빼기", tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                if (receipts.size < 4) {
                    PhotoButton(Icons.Rounded.AddAPhoto, "찍기") { shoot() }
                    PhotoButton(Icons.Rounded.PhotoLibrary, "앨범") {
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        if (keypad) {
            Keypad(
                onAdd = { n -> amount = ((amount.toLongOrNull() ?: 0L) + n).coerceAtMost(99_999_999_999L).toString() },
                onDigit = { d -> if (amount.length < 11) amount = (amount + d).trimStart('0') },
                onBack = { amount = amount.dropLast(1) },
                onDone = { keypad = false },
            )
        }
        Box(Modifier.fillMaxWidth().background(P.c.bg).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
            PrimaryButton(if (original == null) "기록하기" else "고친 내용 저장", enabled = valid) { save() }
        }
    }

    when (picker) {
        "date" -> {
            val st = rememberDatePickerState(initialSelectedDateMillis = Fmt.millis(Fmt.date(at)) + Fmt.zone.rules.getOffset(java.time.Instant.ofEpochMilli(at)).totalSeconds * 1000L)
            DatePickerDialog(
                onDismissRequest = { picker = null },
                confirmButton = { TextButton({
                    st.selectedDateMillis?.let { ms ->
                        val d = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        at = Fmt.millis(LocalDateTime.of(d, Fmt.dateTime(at).toLocalTime()))
                    }
                    picker = "time"
                }) { Text("다음: 시간") } },
                dismissButton = { TextButton({ picker = null }) { Text("취소") } },
            ) { DatePicker(st) }
        }
        "time" -> {
            val t = Fmt.dateTime(at)
            val st = rememberTimePickerState(t.hour, t.minute, false)
            AlertDialog(
                onDismissRequest = { picker = null },
                confirmButton = { TextButton({ at = Fmt.millis(t.withHour(st.hour).withMinute(st.minute)); picker = null }) { Text("확인") } },
                dismissButton = { TextButton({ picker = null }) { Text("그대로") } },
                text = { TimePicker(st) },
            )
        }
        "account", "to" -> ModalBottomSheet(onDismissRequest = { picker = null }, containerColor = P.c.bg) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                Text(if (picker == "to") "받는 계좌" else "계좌·카드 고르기", style = MaterialTheme.typography.titleMedium, color = P.c.ink,
                    modifier = Modifier.padding(8.dp))
                val target = picker
                if (target == "account") ListRow("선택 안 함", null, leading = { CatTile("etc", 40.dp) }, onClick = { accountId = null; picker = null })
                s.accounts.values.filter { !it.hidden }.sortedWith(compareBy({ it.ownerId != memberId }, { it.order })).forEach { a ->
                    ListRow(a.name, listOfNotNull(s.member(a.ownerId)?.name, a.type.label).joinToString(" · "),
                        leading = { CatTile(if (a.type == AccountType.CARD || a.type == AccountType.CHECK) "card" else if (a.type == AccountType.CASH) "cash" else "bank", 40.dp) },
                        onClick = { if (target == "to") toAccountId = a.id else accountId = a.id; picker = null })
                }
                ListRow("새 계좌·카드 만들기", null, leading = { CatTile("bank", 40.dp, P.c.pineSoft) }, onClick = { picker = null; nav.go(Route.AccountEdit(null)) })
            }
        }
        "delete" -> AlertDialog(
            onDismissRequest = { picker = null },
            title = { Text("이 내역을 지울까요?") },
            text = { Text("가족 모두의 장부에서 지워져요. 카드 알림으로 들어온 내역은 같은 알림이 다시 와도 새로 생기지 않아요.") },
            confirmButton = { TextButton({
                original?.let { o -> Hub.ledger.deleteTxn(o.id); Hub.scope.launch { o.receiptIds.forEach { Receipts.delete(context, it) } } }
                picker = null; nav.back()
            }) { Text("지우기", color = P.c.danger) } },
            dismissButton = { TextButton({ picker = null }) { Text("두기") } },
        )
    }
}

/** 가족 카드에서 용돈으로 옮긴 결제 대신 남기는 금액 자리의 id. */
private fun stubId(txnId: String) = "stub.$txnId"

@Composable
private fun PhotoButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.size(84.dp).clip(RoundedCornerShape(16.dp)).background(P.c.surface).border(1.dp, P.c.line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = P.c.inkSoft)
        Text(label, style = MaterialTheme.typography.labelMedium, color = P.c.inkSoft)
    }
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, placeholder: String = "", singleLine: Boolean = true, number: Boolean = false,
          /** 비밀값(API 키 등): 가려 보이고 자판이 기억·추천하지 않게. */
          secret: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, placeholder = { Text(placeholder, color = P.c.inkFaint) },
        singleLine = singleLine, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        keyboardOptions = when {
            secret -> KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
            number -> KeyboardOptions(keyboardType = KeyboardType.Number)
            else -> KeyboardOptions.Default
        },
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = P.c.pine, unfocusedBorderColor = P.c.line, focusedContainerColor = P.c.surface,
            unfocusedContainerColor = P.c.surface, focusedLabelColor = P.c.pine, cursorColor = P.c.pine,
        ),
    )
}

/** 금액 자판. 000 단추로 큰 금액을 빨리 넣습니다. */
@Composable
fun Keypad(onAdd: (Long) -> Unit, onDigit: (String) -> Unit, onBack: () -> Unit, onDone: () -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun tick() = haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
    Column(Modifier.fillMaxWidth().background(P.c.surface).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1_000L to "+1천", 10_000L to "+1만", 50_000L to "+5만", 100_000L to "+10만").forEach { (n, label) ->
                Box(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(12.dp)).background(P.c.surface2)
                    .clickable { tick(); onAdd(n) }, contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = P.c.pine)
                }
            }
        }
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("000", "0", "<")).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { k ->
                    Box(Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(14.dp)).clickable {
                        tick()
                        if (k == "<") onBack() else onDigit(k)
                    }, contentAlignment = Alignment.Center) {
                        if (k == "<") Icon(Icons.AutoMirrored.Rounded.Backspace, "지우기", tint = P.c.ink)
                        else Text(k, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = P.c.ink, style = Money)
                    }
                }
            }
        }
        TextButton(onClick = onDone, modifier = Modifier.align(Alignment.End)) { Text("자판 닫기", color = P.c.inkSoft) }
    }
}

/** 가계부 통장끼리 옮긴 돈의 한쪽(D-28): 왜 짝지었는지와 "따로 세기", 또는 따로 세던 것을 "옮긴 돈으로 묶기". */
@Composable
private fun LinkCard(s: com.jeiel85.doran.data.LedgerState, o: Txn, onDone: () -> Unit) {
    val linked = Moves.linked(o)
    val other = if (linked) Moves.linkOf(o)?.let { s.txns[it] } else Moves.linkPartner(s, o, again = true)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (linked) P.c.pineSoft else P.c.surface2)
        .padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(if (linked) "옮긴 돈이에요 · 가계부 합계에서 빠져요" else "따로 세고 있어요 · 가계부 합계에 들어가요",
            style = MaterialTheme.typography.titleSmall, color = P.c.ink)
        other?.let { x ->
            val where = listOfNotNull(s.member(x.memberId)?.name, s.account(x.accountId)?.name).joinToString(" ")
            val what = if (x.kind == Kind.INCOME) "입금" else "출금"
            Text("$where $what(${Fmt.time(x.at)})과 같은 돈이라 가계부 통장끼리 옮긴 것으로 봤어요. 통장 잔액에는 그대로 들어가요.",
                style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            if (linked) SmallButton("따로 세기", color = P.c.apricot) {
                Moves.apart(s, o).forEach { Hub.ledger.saveTxn(it) }
                onDone()
            } else SmallButton("옮긴 돈으로 묶기", color = P.c.pine) {
                Moves.relink(s, o).forEach { Hub.ledger.saveTxn(it) }
                onDone()
            }
        }
    }
}

/** 입금(D-31): 수입으로 셀지, 내 다른 통장에서 옮겨 온 돈으로 볼지. */
@Composable
private fun SelfCard(o: Txn, onToggle: () -> Unit) {
    val self = Moves.selfIn(o)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (self) P.c.pineSoft else P.c.surface2)
        .padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(if (self) "내 통장끼리 옮긴 돈이에요 · 수입에서 빠졌어요" else "수입으로 적었어요", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
        Text(when {
            self -> "내 다른 통장에서 옮겨 온 돈으로 봤어요. 통장 잔액에는 그대로 들어가요."
            o.private -> "토스뱅크처럼 출금 알림이 없는 내 다른 통장에서 옮겨 온 돈이면 바꿔 주세요. 내역에 옮긴 돈으로 표시돼요."
            else -> "토스뱅크처럼 출금 알림이 없는 내 다른 통장에서 옮겨 온 돈이면 바꿔 주세요. 수입과 가계부 합계에서 빠져요."
        }, style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            SmallButton(if (self) "수입이 맞아요" else "내 통장끼리 옮긴 돈이에요", color = if (self) P.c.apricot else P.c.pine) { onToggle() }
        }
    }
}

/** 가족이 보내 준 돈(D-28): 누가 보냈는지, 그 돈으로 나간 출금, "가족 돈 아니에요". */
@Composable
private fun FundCard(s: com.jeiel85.doran.data.LedgerState, o: Txn, me: String?, onDone: () -> Unit) {
    val src = Moves.fundOf(o)?.let { s.txns[it] }
    val spentBy = s.txns.values.firstOrNull { Moves.fromOf(it) == o.id }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.c.pineSoft).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text("가족 돈이에요 · 용돈이 아니에요", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
        val who = src?.let { listOfNotNull(if (it.memberId == me) "내" else s.member(it.memberId)?.name, s.account(it.accountId)?.name).joinToString(" ") }
        Text("${who ?: "가족 통장"} 출금${src?.let { "(${Fmt.time(it.at)})" } ?: ""}과 같은 돈이라 가족이 보내 준 돈으로 봤어요. " +
            (spentBy?.let { "${Fmt.date(it.at).let { d -> "${d.monthValue}/${d.dayOfMonth}" }} ${it.merchant.ifBlank { "출금" }}이 이 돈으로 나가 용돈에서 뺐어요." }
                ?: "${Moves.FUND_DAYS}일 안에 이 통장에서 같은 금액이 나가면 용돈에서 빼요. 금액이 다르면 그 출금 화면에서 이 돈을 골라 이어 주세요."),
            style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            SmallButton("가족 돈 아니에요", color = P.c.apricot) {
                Hub.capture.setNotFund(o)
                onDone()
            }
        }
    }
}

/** 맨 위 날짜 표시. 오늘이 아니면 눈에 띄게 칠해 다른 날로 쓰고 있음을 알립니다. 누르면 날짜를 고릅니다. */
@Composable
private fun DateChip(at: Long, onClick: () -> Unit) {
    val d = Fmt.date(at)
    val today = d == LocalDate.now(Fmt.zone)
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(if (today) P.c.surface else P.c.pineSoft)
            .border(1.dp, if (today) P.c.line else P.c.pine, RoundedCornerShape(14.dp))
            .clickable(onClickLabel = "날짜 바꾸기", onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CalendarToday, null, tint = P.c.pine, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(Fmt.dayLabel(d), style = MaterialTheme.typography.labelLarge, color = if (today) P.c.ink else P.c.pine)
    }
}
