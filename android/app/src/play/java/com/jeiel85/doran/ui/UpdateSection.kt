package com.jeiel85.doran.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.runtime.Composable
import com.jeiel85.doran.BuildConfig

/** Play 빌드: 업데이트는 Google Play가 합니다 (DECISIONS.md D-16). */
@Composable
@Suppress("UNUSED_PARAMETER")
fun UpdateSection(autoUpdate: Boolean) {
    ListRow("Google Play가 업데이트해요", "Play 스토어 설정을 따라요 · v${BuildConfig.VERSION_NAME}", Icons.Rounded.SystemUpdate, P.c.inkSoft)
}
