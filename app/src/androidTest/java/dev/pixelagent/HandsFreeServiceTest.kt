package dev.pixelagent

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pixelagent.conversation.AgentRuntime
import dev.pixelagent.model.AgentConnection
import dev.pixelagent.wakeword.HandsFreeService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real local mic/native model and service lifecycle, without intentional model inference.
 * Run in a quiet room on an unlocked signed-in phone; preserves credentials and history. */
@RunWith(AndroidJUnit4::class)
class HandsFreeServiceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).close() }
    private fun await(timeoutMs: Long = 20000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Timed out: ${HandsFreeService.state.value}", condition())
    }
    private fun trial(block: () -> Unit) {
        assertFalse("Unlock the Pixel before this test", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val interactive = context.getSystemService(PowerManager::class.java).isInteractive
        val permissions = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        val previous = permissions.associateWith { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        try {
            permissions.forEach { shell("pm grant dev.pixelagent $it") }
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            await(45000) {
                AgentConnection.get(context).provider.isConfigured &&
                    AgentRuntime.get(context.applicationContext as Application).state.value.localVoice != null
            }
            block()
        } finally {
            context.stopService(Intent(context, HandsFreeService::class.java))
            await { !HandsFreeService.state.value.active }
            if (interactive) shell("input keyevent 224")
            previous.filterValues { !it }.keys.forEach { shell("pm revoke dev.pixelagent $it") }
        }
    }
    private fun start(durationMs: Long) {
        context.startForegroundService(Intent(context, HandsFreeService::class.java).putExtra(HandsFreeService.DURATION, durationMs))
    }
    private fun assertMicReleased() {
        await { context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty() }
    }
    @Test fun expiryExplicitRestartScreenOffAndNotificationStop() = trial {
        start(2000)
        await { HandsFreeService.state.value.active }
        await { !HandsFreeService.state.value.active }
        assertTrue(HandsFreeService.state.value.notice.contains("time limit"))
        assertMicReleased()
        SystemClock.sleep(500)
        assertFalse(HandsFreeService.state.value.active)
        start(30000)
        await { HandsFreeService.state.value.listening }
        shell("input keyevent 223")
        SystemClock.sleep(2000)
        assertTrue(HandsFreeService.state.value.active)
        assertTrue(HandsFreeService.state.value.listening)
        assertEquals(0, HandsFreeService.state.value.detections)
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single { it.id == 3 }
        assertEquals("Stop", notification.notification.actions.single().title.toString())
        notification.notification.actions.single().actionIntent.send()
        await { !HandsFreeService.state.value.active }
        assertMicReleased()
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == 3 })
    }
}
