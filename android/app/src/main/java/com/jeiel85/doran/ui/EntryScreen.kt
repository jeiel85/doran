package com.jeiel85.doran.ui

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
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeiel85.doran.capture.Categorizer
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.core.newId
import com.jeiel85.doran.feature.Receipts
import com.jeiel85.doran.model.AccountType
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn
import kotlinx.coroutines.launch
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
    var accountId by remember { mutableStateOf(original?.accountId ?: s.accounts.values.firstOrNull { it.ownerId == id.myMemberId && it.type == AccountType.CASH }?.id) }
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
    val value = amount.toLongOrNull() ?: 0L
    val valid = value > 0 && (kind != Kind.TRANSFER || (accountId != null && toAccountId != null && accountId != toAccountId))

    fun save() {
        val t = (original ?: Txn(id = newId(), kind = kind, amount = 0, at = at, source = Source.MANUAL,
            createdBy = Hub.identity.installId, createdAt = System.currentTimeMillis())).copy(
            kind = kind, amount = value, at = at, merchant = merchant.trim(), memo = memo.trim(),
            categoryId = if (kind == Kind.TRANSFER) null else categoryId ?: Categorizer.categorize(s, merchant, kind),
            accountId = accountId, toAccountId = if (kind == Kind.TRANSFER) toAccountId else null, memberId = memberId,
            installment = if (kind == Kind.EXPENSE) installment else 0, private = private && canEditPrivate,
            receiptIds = receipts.toList(), needsReview = false,
        )
        Hub.ledger.saveTxn(t)
        scope.launch { receipts.forEach { Receipts.upload(context, it) } }
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

    Column(Modifier.fillMaxSize().background(P.c.bg).imePadding()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로", tint = P.c.ink) }
            Text(if (original == null) "새 내역" else "내역", style = MaterialTheme.typography.titleLarge, color = P.c.ink, modifier = Modifier.weight(1f))
            if (original != null) IconButton(onClick = { picker = "delete" }) { Icon(Icons.Rounded.DeleteOutline, "지우기", tint = P.c.danger) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Segmented(listOf("지출", "수입", "이체"), kind.ordinal) { kind = Kind.entries[it]; if (!touchedCategory) categoryId = null }
            Spacer(Modifier.height(18.dp))
            // 금액
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).clickable { keypad = true }.padding(vertical = 6.dp)) {
                Text(when (kind) { Kind.EXPENSE -> "얼마를 썼나요?"; Kind.INCOME -> "얼마가 들어왔나요?"; Kind.TRANSFER -> "얼마를 옮겼나요?" },
                    style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft)
                Text(if (value > 0) Fmt.won(value) else "0원", style = MaterialTheme.typography.displaySmall.merge(Money),
                    color = if (value > 0) kindColor(kind, P.c).takeIf { kind != Kind.EXPENSE } ?: P.c.ink else P.c.inkFaint)
                original?.let { o ->
                    val info = listOfNotNull(
                        o.source.label,
                        o.cardLabel,
                        o.cumulative?.let { "카드사 누적 ${Fmt.won(it)}" },
                        o.foreign?.let { "해외 $it" },
                        cancels.takeIf { it.isNotEmpty() }?.let { "취소 ${Fmt.won(it.sumOf { c -> c.amount })}" },
                        s.member(s.members.values.firstOrNull { m -> o.createdBy != null && m.deviceIds.contains(o.createdBy) }?.id)?.name?.let { "$it 폰에서 기록" },
                    ).joinToString(" · ")
                    if (info.isNotBlank()) Text(info, style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                    if (o.needsReview) Text(if (o.amount == 0L) "원화 금액이 확정되면 넣어 주세요" else "확인이 필요한 내역이에요",
                        style = MaterialTheme.typography.bodySmall, color = P.c.apricot)
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
                ListRow(if (kind == Kind.TRANSFER) "보내는 계좌" else if (kind == Kind.INCOME) "들어온 곳" else "결제 수단",
                    s.account(accountId)?.name ?: "선택 안 함", leading = { CatTile(if (s.account(accountId)?.type == AccountType.CARD) "card" else "bank", 40.dp) },
                    onClick = { picker = "account" })
                if (kind == Kind.TRANSFER) ListRow("받는 계좌", s.account(toAccountId)?.name ?: "선택해 주세요", leading = { CatTile("transfer", 40.dp) },
                    onClick = { picker = "to" })
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
            ChipRow {
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
                    PhotoButton(Icons.Rounded.AddAPhoto, "찍기") { takePhoto.launch(cameraUri) }
                    PhotoButton(Icons.Rounded.PhotoLibrary, "앨범") {
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        if (keypad) {
            Keypad(
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
                original?.let { o -> Hub.ledger.deleteTxn(o.id); scope.launch { o.receiptIds.forEach { Receipts.delete(context, it) } } }
                picker = null; nav.back()
            }) { Text("지우기", color = P.c.danger) } },
            dismissButton = { TextButton({ picker = null }) { Text("두기") } },
        )
    }
}

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
fun Field(label: String, value: String, onChange: (String) -> Unit, placeholder: String = "", singleLine: Boolean = true, number: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, placeholder = { Text(placeholder, color = P.c.inkFaint) },
        singleLine = singleLine, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = P.c.pine, unfocusedBorderColor = P.c.line, focusedContainerColor = P.c.surface,
            unfocusedContainerColor = P.c.surface, focusedLabelColor = P.c.pine, cursorColor = P.c.pine,
        ),
    )
}

/** 금액 자판. 000 단추로 큰 금액을 빨리 넣습니다. */
@Composable
fun Keypad(onDigit: (String) -> Unit, onBack: () -> Unit, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(P.c.surface).padding(horizontal = 12.dp, vertical = 8.dp)) {
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("000", "0", "<")).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { k ->
                    Box(Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(14.dp)).clickable {
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
