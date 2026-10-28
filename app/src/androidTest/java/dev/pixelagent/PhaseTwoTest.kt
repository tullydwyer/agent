package dev.pixelagent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import androidx.camera.core.CameraState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import dev.pixelagent.camera.SnapshotEncoder
import dev.pixelagent.conversation.ConversationContext
import dev.pixelagent.memory.*
import dev.pixelagent.skills.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhaseTwoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun open(name: String) = Room.databaseBuilder(context, MemoryDatabase::class.java, name).build()
    @Test fun cameraReviewCancelAndBackgroundReleaseResources() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cameraUi") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        assumeTrue(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        fun click(label: String) {
            val button = device.wait(Until.findObject(By.text(label)), 15000) ?: error("Missing $label")
            assertTrue("Disabled $label", button.isEnabled); button.click()
        }
        fun cameraClosed() {
            val provider = ProcessCameraProvider.getInstance(context).get()
            val deadline = SystemClock.elapsedRealtime() + 10000
            var closed = false
            while (!closed && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { closed = provider.availableCameraInfos.all { it.cameraState.value?.type == CameraState.Type.CLOSED } }
                if (!closed) SystemClock.sleep(100)
            }
            assertTrue("Camera resources still open", closed)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
                assertTrue("Wait for model selection, catalog refresh and memory startup", device.wait(Until.hasObject(By.text("Ready when you are")), 30000))
                click("Camera")
                assertTrue("Camera screen did not open", device.wait(Until.hasObject(By.text("Cancel and discard")), 5000))
                assertTrue("Rear preview failed to open", device.wait(Until.hasObject(By.text("Camera active · Preview stays on this phone.")), 15000))
                click("Use front camera")
                assertTrue(device.wait(Until.hasObject(By.text("Use rear camera")), 5000))
                // A successful preview alone precedes hardware readiness for capture.
                SystemClock.sleep(1500)
                click("Take snapshot")
                assertTrue(device.wait(Until.hasObject(By.text("Review snapshot · Camera off")), 20000))
                cameraClosed()
                assertTrue(File(context.cacheDir, "snapshots").listFiles().orEmpty().isEmpty())
                click("Cancel and discard")
                click("Camera")
                assertTrue(device.wait(Until.hasObject(By.text("Camera active · Preview stays on this phone.")), 15000))
                device.pressHome()
                cameraClosed()
                context.startActivity(android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                assertTrue(device.wait(Until.hasObject(By.text("Your companion")), 15000))
                assertFalse(device.hasObject(By.text("Take snapshot")))
                assertFalse(device.hasObject(By.text("Send snapshot to model")))
        }
    }
    @Test fun confirmedMemorySurvivesReopenEditsExpiryAndForgettingRemoveRetrieval() = runBlocking {
        val name = "phase-two-test-only.db"
        context.deleteDatabase(name)
        var db = open(name)
        var now = 1_000_000L
        try {
            var repository = MemoryRepository(db) { now }
            assertEquals("Legacy identity", repository.initializePersona("Legacy identity"))
            repository.save(null, "Favourite colour is ultramarine", "User confirmed in test", null, false)
            assertTrue(repository.retrieve("ultramarine").isEmpty())
            val draft = db.memories().all().single()
            repository.save(draft.id, draft.text, draft.provenance, null, true)
            repository.setPersona("Durable identity")
            db.close()
            db = open(name)
            repository = MemoryRepository(db) { now }
            assertEquals("Durable identity", repository.initializePersona("Wrong legacy value"))
            val fact = repository.retrieve("ultramarine").single()
            val registry = SkillRegistry(AssetSkillSource(context.assets))
            fun prompt() = ConversationContext.build("Durable identity", emptyList(), "ultramarine", registry, SkillSession(registry), false,
                MemoryRetrieval.context(runBlocking { repository.retrieve("ultramarine") })).toString()
            assertTrue(prompt().contains("Favourite colour"))
            repository.save(fact.id, "Favourite colour is emerald", "Edited by user", null, true)
            assertTrue(repository.retrieve("ultramarine").isEmpty())
            assertEquals(1, repository.retrieve("emerald").size)
            repository.forget(fact.id)
            assertTrue(repository.retrieve("emerald").isEmpty())
            assertFalse(prompt().contains("Favourite colour"))
            db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM memory_search WHERE memory_search MATCH 'emerald'").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            repository.save(null, "Temporary saffron fact", "Expiry test", now + 500, true)
            now += 501
            assertTrue(repository.retrieve("saffron").isEmpty())
            assertEquals(1, repository.purgeExpired())
            assertTrue(db.memories().all().isEmpty())
            repository.save(null, "Exported cobalt fact", "Export test", null, true)
            val export = JSONObject(repository.export())
            assertEquals("Durable identity", export.getString("persona"))
            assertEquals("Export test", export.getJSONArray("memories").getJSONObject(0).getString("provenance"))
            repository.deleteAll()
            assertTrue(db.memories().all().isEmpty())
            assertNull(db.memories().persona())
            assertTrue(repository.retrieve("cobalt").isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun retrievalAndCapacityAreBoundedAndDraftsNeverEnterContext() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java).build()
        try {
            val repository = MemoryRepository(db)
            repeat(200) { repository.save(null, "Bounded violet fact $it", "Fixture", null, it % 2 == 0) }
            assertEquals(8, repository.retrieve("violet").size)
            assertTrue(repository.retrieve("violet").all { it.confirmed })
            try { repository.save(null, "Overflow", "Fixture", null, true); fail("Exceeded memory cap") }
            catch (_: IllegalArgumentException) { }
        } finally { db.close() }
    }

    @Test fun snapshotResizeOrientationMetadataAndTemporaryCleanup() {
        val directory = File(context.cacheDir, "phase-two-encoder-test").apply { mkdirs() }
        val file = File(directory, "source.jpg")
        try {
            val bitmap = Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.RED)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle()
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_USER_COMMENT, "Private metadata")
                saveAttributes()
            }
            val snapshot = SnapshotEncoder.fromFile(file)
            assertEquals(512, snapshot.width); assertEquals(1024, snapshot.height)
            assertTrue(snapshot.jpeg.size <= 180000)
            assertTrue(snapshot.dataUrl().length <= 240023)
            val decoded = BitmapFactory.decodeByteArray(snapshot.jpeg, 0, snapshot.jpeg.size)
            assertEquals(snapshot.width, decoded.width); decoded.recycle()
            snapshot.jpeg.inputStream().use { assertNull(ExifInterface(it).getAttribute(ExifInterface.TAG_USER_COMMENT)) }
            val cache = File(directory, "snapshots").apply { mkdirs() }
            File(cache, "stale.jpg").writeText("stale")
            SnapshotEncoder.clearTemporaryFiles(directory)
            assertTrue(cache.listFiles()!!.isEmpty())
            cache.delete()
        } finally { file.delete(); directory.delete() }
    }
}
