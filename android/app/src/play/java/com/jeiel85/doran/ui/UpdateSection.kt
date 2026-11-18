package com.jeiel85.doran.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.runtime.Composable
import com.jeiel85.doran.BuildConfig

/** Play 빌드: 업데이트는 Google Play가 합니다 (DECISIONS.md D-16). */
@Composable
@Suppress("UNUSED_PARAMETER")
fun UpdateSection(autoUpdate: Boolean) {
    ListRow("Google Play에서 받은 도란 · v${BuildConfig.VERSION_NAME}",
        "업데이트는 Play가 해요. GitHub APK로 받은 도란과는 서명이 달라 덮어 설치할 수 없어요 — 바꾸려면 백업 → 지우기 → 설치 → 되살리기",
        Icons.Rounded.SystemUpdate, P.c.inkSoft)
}
