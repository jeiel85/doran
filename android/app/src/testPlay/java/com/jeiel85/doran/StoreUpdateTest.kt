package com.jeiel85.doran

import com.google.android.play.core.install.model.UpdateAvailability
import com.jeiel85.doran.update.SelfUpdate
import com.jeiel85.doran.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Play 빌드의 새 버전 알림(DECISIONS.md D-21): Play가 알려 주는 versionCode를 화면용 버전으로. */
class StoreUpdateTest {
    @Test fun versionCodeBecomesVersionName() {
        assertEquals("0.1.7", SelfUpdate.versionName(107))
        assertEquals("0.2.0", SelfUpdate.versionName(200))
        assertEquals("1.2.3", SelfUpdate.versionName(10203))
    }

    /** Play가 이 설치본보다 높은 버전을 내놓았을 때만 알림(스토어보다 새로 빌드한 설치본에는 안 띄움). */
    @Test fun onlyAStrictlyHigherVersionIsOffered() {
        assertTrue(SelfUpdate.offered(UpdateAvailability.UPDATE_AVAILABLE, 304, 303))
        assertFalse(SelfUpdate.offered(UpdateAvailability.UPDATE_AVAILABLE, 303, 304))
        assertFalse(SelfUpdate.offered(UpdateAvailability.UPDATE_NOT_AVAILABLE, 0, 303))
        // Play가 이미 설치하는 중인 업데이트는 다시 띄우지 않음
        assertFalse(SelfUpdate.offered(UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS, 304, 303))
    }

    /** Play가 잠깐 답하지 않아도 이미 알던 새 버전 배너는 남고, 모를 때는 '최신'이라고 하지 않음. */
    @Test fun noAnswerKeepsWhatWeKnew() {
        val known = UpdateState.StoreUpdate("0.3.4")
        assertEquals(known, SelfUpdate.next(known, null, userInitiated = false))
        assertEquals(known, SelfUpdate.next(known, null, userInitiated = true))
        assertEquals(UpdateState.Idle, SelfUpdate.next(null, null, userInitiated = false))
        assertEquals(UpdateState.Failed(SelfUpdate.NO_ANSWER), SelfUpdate.next(null, null, userInitiated = true))
        // 분명한 답은 늘 그대로: 업데이트 뒤 '최신'이면 배너가 사라짐
        assertEquals(UpdateState.UpToDate, SelfUpdate.next(known, UpdateState.UpToDate, userInitiated = false))
        assertEquals(UpdateState.StoreUpdate("0.3.5"), SelfUpdate.next(known, UpdateState.StoreUpdate("0.3.5"), userInitiated = false))
    }
}
