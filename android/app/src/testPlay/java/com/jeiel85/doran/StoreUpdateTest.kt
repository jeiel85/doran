package com.jeiel85.doran

import com.jeiel85.doran.update.SelfUpdate
import org.junit.Assert.assertEquals
import org.junit.Test

/** Play 빌드의 새 버전 알림(DECISIONS.md D-21): Play가 알려 주는 versionCode를 화면용 버전으로. */
class StoreUpdateTest {
    @Test fun versionCodeBecomesVersionName() {
        assertEquals("0.1.7", SelfUpdate.versionName(107))
        assertEquals("0.2.0", SelfUpdate.versionName(200))
        assertEquals("1.2.3", SelfUpdate.versionName(10203))
    }
}
