package com.jeiel85.doran.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeiel85.doran.R
import com.jeiel85.doran.capture.CaptureProcessor
import com.jeiel85.doran.capture.CaptureService
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.core.Hub

/** 알림·문자 자동 기록 설정과 권한 점검 (DECISIONS.md D-4). */
@Composable
fun CaptureScreen(nav: Nav) {
    val context = LocalContext.current
    val settings by Hub.settings.collectAsStateWithLifecycle()
    val tick = rememberResumeTick()
    val listener = remember(tick) { CaptureService.isEnabled(context) }
    val sms = remember(tick) { ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED }
    val notify = remember(tick) {
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val battery = remember(tick) { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
    val apps = remember(tick) { Hub.db.captures(500).mapNotNull { r -> r.pkg?.takeIf { r.source == "push" } }.distinct() }
    val askSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var sample by remember { mutableStateOf("") }

    SubScreen("알림 자동 기록", { nav.back() }) {
        item {
            SoftCard(padding = PaddingValues(18.dp)) {
                Image(painterResource(R.drawable.art_capture), null, Modifier.fillMaxWidth().height(130.dp))
                Spacer(Modifier.height(8.dp))
                Text("카드사·은행 알림과 문자를 읽어 바로 장부에 넣어요", style = MaterialTheme.typography.titleMedium, color = P.c.ink)
                Text("금액·승인 형식이 아닌 알림은 읽자마자 버리고, 원문은 이 폰에만 30일 남겨요. 같은 결제가 앱 알림과 문자로 함께 와도 한 건으로 합쳐요.",
                    style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
            }
        }
        item { Spacer(Modifier.height(20.dp)); SectionTitle("꼭 필요한 권한") }
        item {
            SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                PermRow("알림 접근", if (listener) "켜짐 · 카드 앱·카카오톡 알림톡을 읽어요" else "켜야 카드 앱 알림을 기록할 수 있어요",
                    Icons.Rounded.NotificationsActive, listener) {
                    val i = if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, CaptureService.component(context).flattenToString())
                    else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    runCatching { context.startActivity(i) }.onFailure { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                }
                if (!listener && Build.VERSION.SDK_INT >= 33) {
                    ListRow("'제한된 설정'이라며 안 켜질 때", "앱 정보 → 오른쪽 위 ⋮ → '제한된 설정 허용' 뒤 다시 켜 주세요 (직접 받은 앱이라 안드로이드가 한 번 막아요)",
                        Icons.Rounded.Shield, P.c.apricot, onClick = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        })
                }
                if (com.jeiel85.doran.BuildConfig.SMS_RECEIVER) PermRow("문자 받기", if (sms) "켜짐 · 카드·은행 문자를 읽어요" else "카드사 문자로만 알림이 오면 켜 주세요", Icons.Rounded.Sms, sms) {
                    askSms.launch(Manifest.permission.RECEIVE_SMS)
                }
                PermRow("도란 알림 표시", if (notify) "켜짐 · 가족 내역·예산 알림을 보여요" else "가족이 쓴 내역 알림을 받으려면 켜 주세요",
                    Icons.Rounded.NotificationsActive, notify) {
                    if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                PermRow("배터리 사용 제한 없음", if (battery) "켜짐 · 잠든 폰에서도 놓치지 않아요" else "삼성 폰은 오래 안 쓴 앱을 재워서 알림을 놓칠 수 있어요",
                    Icons.Rounded.BatteryChargingFull, battery) {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)); SectionTitle("읽을 것") }
        item {
            SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                ToggleRow("앱 알림 읽기", "카드사·은행 앱, 카카오톡 알림톡", Icons.Rounded.NotificationsActive, P.c.pine, settings.capturePush) { on ->
                    Hub.local.update { it.copy(capturePush = on) }
                }
                ToggleRow("문자 읽기", if (com.jeiel85.doran.BuildConfig.SMS_RECEIVER) "카드사·은행 문자" else "문자 앱에 뜨는 카드·은행 문자 알림", Icons.Rounded.Sms, P.c.pine, settings.captureSms) { on ->
                    Hub.local.update { it.copy(captureSms = on) }
                }
                apps.forEach { pkg ->
                    val label = remember(pkg) {
                        runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                    }
                    ToggleRow(label, pkg, Icons.Rounded.CheckCircle, P.c.apricot, pkg !in settings.mutedPackages) { on ->
                        Hub.local.update { it.copy(mutedPackages = if (on) it.mutedPackages - pkg else it.mutedPackages + pkg) }
                    }
                }
                ListRow("최근 받은 알림", "기록 결과 보기 · 기록 안 된 금액 알림을 골라 보내기", Icons.Rounded.History, P.c.inkSoft, onClick = { nav.go(Route.CaptureLog) })
            }
        }
        item { ForwardRulesSection() }
        item { Spacer(Modifier.height(20.dp)); SectionTitle("문구 시험") }
        item {
            SoftCard {
                Text("카드 알림 문구를 붙여 넣으면 어떻게 읽는지 미리 보여 줘요. 장부에는 넣지 않아요.",
                    style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                Spacer(Modifier.height(10.dp))
                Field("알림 문구", sample, { sample = it }, "현대카드 승인 홍*동 12,300원 일시불 …", singleLine = false)
                if (sample.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    val r = remember(sample) { CaptureProcessor.preview(sample) }
                    if (r == null) Text("거래로 읽지 못했어요. 이 문구를 개발자에게 알려 주시면 형식을 더할게요.", color = P.c.apricot, style = MaterialTheme.typography.bodyMedium)
                    else Column {
                        listOfNotNull(
                            "종류" to when (r.type) {
                                com.jeiel85.doran.capture.Parsed.Type.APPROVAL -> "승인(지출)"
                                com.jeiel85.doran.capture.Parsed.Type.CANCEL -> "승인 취소"
                                com.jeiel85.doran.capture.Parsed.Type.DEPOSIT -> "입금(수입)"
                                com.jeiel85.doran.capture.Parsed.Type.WITHDRAWAL -> if (r.cardPaymentTo != null) "카드값 출금(이체)" else "출금(지출)"
                            },
                            "카드사·은행" to r.issuer + (r.last4?.let { " ($it)" } ?: ""),
                            "금액" to (if (r.amount > 0) Fmt.won(r.amount) else r.foreign ?: "-"),
                            "가맹점" to (r.merchant ?: "-"),
                            "시각" to (r.at?.toString()?.replace('T', ' ') ?: "알림 받은 시각"),
                            r.cumulative?.let { "카드사 누적" to Fmt.won(it) },
                            r.balance?.let { "잔액" to Fmt.won(it) },
                            if (r.installment > 1) "할부" to "${r.installment}개월" else null,
                        ).forEach { (k, v) ->
                            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text(k, style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft, modifier = Modifier.weight(0.35f))
                                Text(v, style = MaterialTheme.typography.bodyMedium, color = P.c.ink, modifier = Modifier.weight(0.65f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermRow(title: String, sub: String, icon: androidx.compose.ui.graphics.vector.ImageVector, ok: Boolean, onFix: () -> Unit) {
    ListRow(title, sub, icon, if (ok) P.c.pine else P.c.apricot, onClick = if (ok) null else onFix) {
        if (ok) Icon(Icons.Rounded.CheckCircle, "켜짐", tint = P.c.pine) else SmallButton("켜기", color = P.c.apricot, onClick = onFix)
    }
}
