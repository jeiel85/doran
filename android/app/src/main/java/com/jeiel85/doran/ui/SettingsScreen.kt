package com.jeiel85.doran.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Wallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeiel85.doran.BuildConfig
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.feature.Backup
import com.jeiel85.doran.feature.Xlsx
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.sync.SyncWorker
import com.jeiel85.doran.update.UpdateState
import com.jeiel85.doran.update.UpdateWorker
import com.jeiel85.doran.update.Updater
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun SettingsScreen(nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by Hub.ledger.state.collectAsStateWithLifecycle()
    val id by Hub.identity.state.collectAsStateWithLifecycle()
    val settings by Hub.settings.collectAsStateWithLifecycle()
    val sync by Hub.sync.status.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<String?>(null) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val backupTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) { pendingUri = uri; dialog = "backupPw" }
    }
    val restoreFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { pendingUri = uri; dialog = "restorePw" }
    }
    var exportRange by remember { mutableStateOf(0) }
    val exportTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        if (uri != null) scope.launch {
            Updater.busyWork++
            val cur = Stats.current(s)
            val periods = when (exportRange) {
                0 -> listOf(cur)
                1 -> (0 until 12).map { Stats.period(cur.label.minusMonths(it.toLong()), s.household.monthStartDay) }.reversed()
                else -> {
                    val first = s.txns.values.minOfOrNull { it.at }?.let { Fmt.date(it) } ?: LocalDate.now(Fmt.zone)
                    var p = Stats.periodOf(first, s.household.monthStartDay)
                    buildList { while (!p.label.isAfter(cur.label)) { add(p); p = Stats.period(p.label.plusMonths(1), s.household.monthStartDay) } }
                }
            }
            message = runCatching { Xlsx.exportLedger(context, uri, periods); "엑셀 파일을 저장했어요" }.getOrElse { "저장하지 못했어요: ${it.message}" }
            Updater.busyWork--
        }
    }

    SubScreen("설정", { nav.back() }) {
        if (id.inHousehold) {
            item { SectionTitle("가계부") }
            item {
                SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                    ListRow("가계부 이름", s.household.name, Icons.Rounded.Home, onClick = { dialog = "name" })
                    ListRow("한 달 시작일", "매달 ${s.household.monthStartDay}일부터 · 월급날에 맞추면 편해요", Icons.Rounded.CalendarMonth, onClick = { dialog = "startDay" })
                    ListRow("예산", if (s.household.budget > 0) "월 ${Fmt.won(s.household.budget)}" else "정하지 않음", Icons.Rounded.Wallet, onClick = { nav.go(Route.Budget) })
                    ListRow("고정지출·구독", "${s.recurrings.size}개", Icons.Rounded.EventRepeat, onClick = { nav.go(Route.Recurring) })
                    ListRow("저축 목표", "${s.goals.values.count { !it.done }}개 진행 중", Icons.Rounded.Flag, onClick = { nav.go(Route.Goals) })
                    ListRow("내 용돈", s.member(id.myMemberId)?.allowance?.takeIf { it > 0 }?.let { "월 ${Fmt.won(it)}" } ?: "쓰지 않음",
                        Icons.Rounded.Savings, P.c.apricot, onClick = { nav.go(Route.Allowance) })
                    ListRow("카테고리", "${s.categories.values.count { !it.hidden }}개", Icons.Rounded.Category, onClick = { nav.go(Route.Categories) })
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)); SectionTitle("가족·자동 기록") }
        item {
            SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                ListRow("가족 연결", "${s.activeMembers.size}명 · 초대·합류·기기 관리", Icons.Rounded.FamilyRestroom, onClick = { nav.go(Route.Family) })
                ListRow("알림 자동 기록", "카드·은행 알림과 문자", Icons.Rounded.NotificationsActive, P.c.apricot, onClick = { nav.go(Route.Capture) })
                if (id.inHousehold) ToggleRow("보내 준 가족 내역도 합계에", "끄면 홈·내역 합계에서 부모님 등이 보낸 내역을 뺍니다", Icons.Rounded.Payments,
                    P.c.pine, settings.showSenders) { on -> Hub.local.update { it.copy(showSenders = on) } }
            }
        }
        item { Spacer(Modifier.height(16.dp)); SectionTitle("알림") }
        item {
            SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                ToggleRow("가족이 쓴 내역", "다른 가족 카드 알림이 들어오면", Icons.Rounded.NotificationsActive, P.c.pine, settings.notifyFamily) { on ->
                    Hub.local.update { it.copy(notifyFamily = on) }
                }
                ToggleRow("예산 80%·100%", "한 달에 한 번씩만", Icons.Rounded.Wallet, P.c.pine, settings.notifyBudget) { on -> Hub.local.update { it.copy(notifyBudget = on) } }
                ToggleRow("고정지출 기록", "정해진 날 장부에 넣을 때", Icons.Rounded.EventRepeat, P.c.pine, settings.notifyRecurring) { on ->
                    Hub.local.update { it.copy(notifyRecurring = on) }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)); SectionTitle("보안·화면") }
        item {
            SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                val canLock = remember {
                    BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL) ==
                        BiometricManager.BIOMETRIC_SUCCESS
                }
                ToggleRow("앱 잠금", if (canLock) "지문·얼굴 또는 폰 잠금 PIN으로 열어요" else "폰에 화면 잠금을 먼저 설정해 주세요",
                    Icons.Rounded.Lock, P.c.pine, settings.appLock, enabled = canLock) { on -> Hub.local.update { it.copy(appLock = on) } }
                ListRow("화면 테마", when (settings.theme) { "light" -> "밝게"; "dark" -> "어둡게"; else -> "폰 설정 따라"}, Icons.Rounded.DarkMode, onClick = {
                    Hub.local.update { it.copy(theme = when (it.theme) { "system" -> "light"; "light" -> "dark"; else -> "system" }) }
                })
            }
        }
        if (id.inHousehold) {
            item { Spacer(Modifier.height(16.dp)); SectionTitle("백업·내보내기") }
            item {
                SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                    val stale = System.currentTimeMillis() - settings.lastBackupAt > 30L * 86_400_000
                    ListRow("백업 파일 만들기", "마지막 백업: ${Fmt.relative(settings.lastBackupAt)} · 두 폰을 다 잃어도 이 파일로 되살려요",
                        Icons.Rounded.Backup, if (stale) P.c.apricot else P.c.pine, onClick = {
                            backupTo.launch("doran-backup-${LocalDate.now(Fmt.zone)}.doran")
                        })
                    ListRow("백업에서 되살리기", "이 폰의 장부와 합쳐요(더 최근에 고친 것이 남아요)", Icons.Rounded.Restore, onClick = { restoreFrom.launch(arrayOf("*/*")) })
                    ListRow("엑셀로 내보내기", "내역·카테고리별·자산 시트", Icons.Rounded.TableChart, onClick = { dialog = "export" })
                }
            }
            item { Spacer(Modifier.height(16.dp)); SectionTitle("동기화") }
            item {
                SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                    ListRow("지금 동기화", buildString {
                        append("마지막 ${Fmt.relative(sync.lastOkAt)}")
                        if (sync.pending > 0) append(" · 대기 ${sync.pending}건")
                        sync.error?.let { append(" · $it") }
                    }, Icons.Rounded.Sync, onClick = { Hub.requestSync(); SyncWorker.now(context) })
                    ListRow("실시간 알림", if (Hub.sync.realtime) "켜짐 · 가족이 쓰면 바로 받아요"
                    else if (!BuildConfig.HAS_FIREBASE) "이 빌드에는 실시간 알림(FCM)이 없어 15분마다 동기화해요" else "알림 서버에 아직 연결되지 않았어요",
                        Icons.Rounded.NotificationsActive, if (Hub.sync.realtime) P.c.pine else P.c.apricot)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)); SectionTitle("앱") }
        item {
            SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                UpdateSection(settings.autoUpdate)
                ListRow("개인정보", "장부·알림 원문은 서버에 평문으로 저장되지 않아요. 서버는 암호문과 기기 연결 정보만 가져요.", Icons.Rounded.Security)
            }
        }
        item {
            Text("도란 ${BuildConfig.VERSION_NAME} · 우리 가족 가계부", style = MaterialTheme.typography.bodySmall, color = P.c.inkFaint,
                modifier = Modifier.padding(16.dp))
        }
        message?.let { m -> item { Text(m, color = P.c.pine, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) } }
    }

    when (dialog) {
        "name" -> NameDialog("가계부 이름", "예: 우리집", s.household.name, { dialog = null }) { n ->
            Hub.ledger.saveHousehold(s.household.copy(name = n.trim())); dialog = null
        }
        "startDay" -> {
            var day by remember { mutableStateOf(s.household.monthStartDay.toString()) }
            AlertDialog(onDismissRequest = { dialog = null }, containerColor = P.c.bg,
                title = { Text("한 달 시작일") },
                text = { Column {
                    Text("월급날이 25일이면 25로 두세요. 그러면 '11월'은 11월 25일 ~ 12월 24일이에요.", style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                    Spacer(Modifier.height(10.dp))
                    Field("날짜 (1~28)", day, { day = it.filter(Char::isDigit).take(2) }, "1", number = true)
                } },
                confirmButton = { TextButton({
                    day.toIntOrNull()?.takeIf { it in 1..28 }?.let { Hub.ledger.saveHousehold(s.household.copy(monthStartDay = it)) }
                    dialog = null
                }) { Text("저장") } },
                dismissButton = { TextButton({ dialog = null }) { Text("취소") } })
        }
        "export" -> AlertDialog(onDismissRequest = { dialog = null }, containerColor = P.c.bg,
            title = { Text("엑셀로 내보내기") },
            text = { Column {
                Text("어느 기간을 내보낼까요? 사람이 보는 파일이라 암호화하지 않아요.", style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                Spacer(Modifier.height(10.dp))
                ChipRow {
                    listOf("이번 달", "최근 12개월", "전체").forEachIndexed { i, l -> Chip(l, exportRange == i, { exportRange = i }) }
                }
            } },
            confirmButton = { TextButton({
                dialog = null
                exportTo.launch("도란-${YearMonth.now(Fmt.zone)}.xlsx")
            }) { Text("저장 위치 고르기") } },
            dismissButton = { TextButton({ dialog = null }) { Text("취소") } })
        "backupPw", "restorePw" -> {
            val restore = dialog == "restorePw"
            var pw by remember { mutableStateOf("") }
            var pw2 by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            var err by remember { mutableStateOf<String?>(null) }
            AlertDialog(onDismissRequest = { if (!busy) dialog = null }, containerColor = P.c.bg,
                title = { Text(if (restore) "백업 비밀번호" else "백업 비밀번호 정하기") },
                text = { Column {
                    Text(if (restore) "백업을 만들 때 정한 비밀번호를 넣어 주세요."
                    else "이 비밀번호를 잊으면 백업을 열 수 없어요. 개발자도 되찾아 드릴 수 없어요.",
                        style = MaterialTheme.typography.bodySmall, color = if (restore) P.c.inkSoft else P.c.apricot)
                    Spacer(Modifier.height(10.dp))
                    PwField("비밀번호", pw) { pw = it }
                    if (!restore) { Spacer(Modifier.height(8.dp)); PwField("한 번 더", pw2) { pw2 = it } }
                    err?.let { Text(it, color = P.c.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                    if (busy) Text(if (restore) "되살리는 중…" else "암호화해서 저장하는 중…", color = P.c.inkSoft, modifier = Modifier.padding(top = 8.dp))
                } },
                confirmButton = { TextButton({
                    val uri = pendingUri ?: return@TextButton
                    if (!restore && pw.length < 6) { err = "6자 이상으로 정해 주세요"; return@TextButton }
                    if (!restore && pw != pw2) { err = "두 비밀번호가 달라요"; return@TextButton }
                    busy = true; err = null
                    scope.launch {
                        Updater.busyWork++
                        try {
                            if (restore) {
                                val r = Backup.restore(context, uri, pw.toCharArray())
                                message = "되살렸어요: 내역 ${r.txns}건 (백업 ${Fmt.full(r.at)})"
                            } else {
                                Backup.export(context, uri, pw.toCharArray())
                                message = "백업 파일을 저장했어요. 안전한 곳(구글 드라이브 등)에 두세요"
                            }
                            dialog = null
                        } catch (e: Backup.WrongPassword) {
                            err = e.message
                        } catch (e: Exception) {
                            err = e.message ?: "실패했어요"
                        } finally {
                            busy = false
                            Updater.busyWork--
                        }
                    }
                }, enabled = !busy && pw.isNotEmpty()) { Text(if (restore) "되살리기" else "저장") } },
                dismissButton = { TextButton({ dialog = null }, enabled = !busy) { Text("취소") } })
        }
    }
}

@Composable
private fun PwField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
}

/** GitHub 빌드: GitHub Releases에서 스스로 업데이트 (DECISIONS.md D-8). 포너디 UpdateSection과 같은 흐름. */
@Composable
fun UpdateSection(autoUpdate: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updater.state.collectAsStateWithLifecycle()
    val tick = rememberResumeTick()
    val canInstall = remember(tick) { context.packageManager.canRequestPackageInstalls() }
    val checkedAt = remember(tick, state) { Updater.lastCheckedAt(context) }

    if (!Updater.supported) {
        ListRow("이 빌드는 직접 업데이트해요", "디버그 빌드는 자동 업데이트를 하지 않아요 · v${BuildConfig.VERSION_NAME}", Icons.Rounded.SystemUpdate, P.c.inkSoft)
        return
    }
    ToggleRow("자동 업데이트", "하루 두 번 새 버전을 확인하고 설치해요", Icons.Rounded.SystemUpdate, P.c.pine, autoUpdate) { on ->
        Hub.local.update { it.copy(autoUpdate = on) }
        UpdateWorker.schedule(context, on)
    }
    val (title, sub) = when (val st = state) {
        UpdateState.Idle -> "현재 버전 ${BuildConfig.VERSION_NAME}" to "마지막 확인: ${Fmt.relative(checkedAt)}"
        UpdateState.Checking -> "확인하는 중…" to "GitHub에서 최신 릴리스를 보고 있어요"
        UpdateState.UpToDate -> "최신 버전이에요" to "${BuildConfig.VERSION_NAME} · 마지막 확인 ${Fmt.relative(checkedAt)}"
        is UpdateState.Available -> "새 버전 ${st.release.version}이 있어요" to "지금 ${BuildConfig.VERSION_NAME}"
        is UpdateState.Downloading -> "내려받는 중 ${st.percent}%" to "새 버전 ${st.release.version}"
        is UpdateState.Installing -> "설치하는 중…" to "잠시 뒤 앱이 다시 시작돼요"
        is UpdateState.NeedsConfirm -> "설치를 허락해 주세요" to "안드로이드가 한 번 확인을 요청해요"
        is UpdateState.Failed -> st.reason to "현재 ${BuildConfig.VERSION_NAME} · 다시 시도해 주세요"
    }
    val busy = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing
    ListRow(title, sub, Icons.Rounded.InstallMobile, if (state is UpdateState.Failed) P.c.danger else P.c.pine) {
        when (val st = state) {
            is UpdateState.Available -> SmallButton("설치") { scope.launch { Updater.run(context, userInitiated = true) } }
            is UpdateState.NeedsConfirm -> SmallButton("설치") { runCatching { context.startActivity(st.confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            else -> SmallButton("확인", enabled = !busy) { scope.launch { Updater.run(context, userInitiated = true) } }
        }
    }
    if (!canInstall) {
        ListRow("업데이트 설치 허용", "처음 한 번, 도란이 새 버전을 설치할 수 있게 해 주세요", Icons.Rounded.Security, P.c.apricot, onClick = {
            context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        }) { Text("설정", color = P.c.apricot, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
    }
}
