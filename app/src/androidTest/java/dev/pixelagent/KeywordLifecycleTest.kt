package dev.pixelagent

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pixelagent.wakeword.KeywordService
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeywordLifecycleTest {
    @Test fun userStartedSessionSurvivesScreenOffAndReleasesOnStop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeFalse("Unlock the Pixel before this foreground-service trial", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val wasInteractive = context.getSystemService(PowerManager::class.java).isInteractive
        val permissions = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        val initiallyGranted = permissions.associateWith { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).close() }
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertTrue("Timed out: ${KeywordService.state.value}", condition())
        }
        try {
            permissions.forEach { shell("pm grant dev.pixelagent $it") }
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            SystemClock.sleep(1000)
            context.startForegroundService(Intent(context, KeywordService::class.java))
            await { KeywordService.state.value.notice.startsWith("Listening locally") }
            shell("input keyevent 223") // Sleep, not a keyguard bypass.
            SystemClock.sleep(5000)
            assertTrue(KeywordService.state.value.active)
            assertTrue(KeywordService.state.value.notice.startsWith("Listening locally"))
            context.startService(Intent(context, KeywordService::class.java).setAction("stop"))
            await { !KeywordService.state.value.active }
        } finally {
            context.stopService(Intent(context, KeywordService::class.java))
            if (wasInteractive) shell("input keyevent 224")
            initiallyGranted.filterValues { !it }.keys.forEach { shell("pm revoke dev.pixelagent $it") }
        }
    }
}
