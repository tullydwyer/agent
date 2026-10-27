package dev.pixelagent

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pixelagent.conversation.AgentViewModel
import dev.pixelagent.conversation.AudioState
import dev.pixelagent.model.ModelLabViewModel
import dev.pixelagent.wakeword.KeywordService

class MainActivity : ComponentActivity() {
    private val agent: AgentViewModel by viewModels()
    private val modelLab: ModelLabViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val state by agent.state.collectAsStateWithLifecycle()
                val cloud by modelLab.state.collectAsStateWithLifecycle()
                val keyword by KeywordService.state.collectAsStateWithLifecycle()
                var keywordPermissionPending by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) startKeywordSession()
                }
                val microphonePermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) {
                        if (keywordPermissionPending) {
                            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else startKeywordSession()
                        } else agent.listen()
                    } else agent.permissionDenied()
                    keywordPermissionPending = false
                }
                Scaffold { insets ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(insets),
                        contentPadding = PaddingValues(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        item {
                            Text("Pixel Agent", style = MaterialTheme.typography.headlineLarge)
                            Text("Feasibility lab · 0.2.0", style = MaterialTheme.typography.labelLarge)
                        }
                        item {
                            LabCard("Status") {
                                Text(state.audio.label, style = MaterialTheme.typography.titleLarge)
                                if (cloud.busy) Text("Online model experiment in progress")
                                Text(state.notice)
                                Text("Camera off · Keyword ${if (keyword.active) "active" else "off"} · ${cloud.connection}")
                            }
                        }
                        item {
                            LabCard("This phone") {
                                Text("${Build.MANUFACTURER} ${Build.MODEL}")
                                Text("Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
                                Text("Language: ${java.util.Locale.getDefault().toLanguageTag()}")
                                Text("Local recognizer: ${if (state.localRecognitionAvailable) "available" else "unavailable"}")
                                Text("Offline English voice: ${if (state.ttsInitializing) "checking…" else state.localVoice ?: "not installed"}")
                            }
                        }
                        item {
                            LabCard("Try local speech") {
                                Button(
                                    onClick = {
                                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                            agent.listen()
                                        } else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                                    },
                                    enabled = state.localRecognitionAvailable && state.audio == AudioState.IDLE && !keyword.active,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Listen to a phrase") }
                                Button(
                                    onClick = agent::speak,
                                    enabled = state.localVoice != null && state.audio == AudioState.IDLE && !keyword.active,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(if (state.transcript.isBlank()) "Test local voice" else "Read transcript aloud") }
                                OutlinedButton(
                                    onClick = agent::stopAudio,
                                    enabled = state.audio != AudioState.IDLE,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Stop audio") }
                                Text("Speech tests run while this app is visible. Install voice/language data first, then repeat in airplane mode to verify offline operation.")
                            }
                        }
                        item {
                            LabCard("Transcript") {
                                SelectionContainer { Text(state.transcript.ifBlank { "Your words will appear here." }) }
                            }
                        }
                        item {
                            LabCard("ChatGPT plan experiment") {
                                Text("${cloud.connection} · Subscription only")
                                Text("Tests use your ChatGPT plan allowance. The image test uploads a generated red square and blue circle; the function test only echoes a fixed value.")
                                Button(onClick = { modelLab.signIn { uri -> startActivity(Intent(Intent.ACTION_VIEW, uri)) } }, enabled = !cloud.busy) { Text("Continue with ChatGPT") }
                                cloud.accounts.forEach { (id, label) ->
                                    OutlinedButton(onClick = { modelLab.selectAccount(id) }, enabled = !cloud.busy) { Text("Use $label") }
                                    OutlinedButton(onClick = { modelLab.signIn(id) { uri -> startActivity(Intent(Intent.ACTION_VIEW, uri)) } }, enabled = !cloud.busy) { Text("Reconnect $label") }
                                }
                                OutlinedButton(onClick = modelLab::fetchModels, enabled = !cloud.busy && cloud.connection != "Not connected") { Text("Fetch account models") }
                                cloud.models.forEach { model ->
                                    OutlinedButton(onClick = { modelLab.selectModel(model.slug) }, enabled = !cloud.busy) {
                                        Text("${if (model.slug == cloud.selectedModel) "✓ " else ""}${model.name}")
                                    }
                                }
                                Button(onClick = modelLab::textTrial, enabled = !cloud.busy && cloud.selectedModel != null) { Text("Test text turn") }
                                Button(onClick = modelLab::imageTrial, enabled = !cloud.busy && cloud.selectedModel != null) { Text("Upload diagnostic image") }
                                Button(onClick = modelLab::toolTrial, enabled = !cloud.busy && cloud.selectedModel != null) { Text("Test harmless function") }
                                OutlinedButton(onClick = modelLab::refreshTokens, enabled = !cloud.busy && cloud.connection != "Not connected") { Text("Test token renewal") }
                                OutlinedButton(onClick = modelLab::signOut, enabled = !cloud.busy && cloud.connection != "Not connected") { Text("Sign out") }
                                OutlinedButton(onClick = modelLab::cancel, enabled = cloud.busy) { Text("Cancel experiment") }
                                SelectionContainer { Text(cloud.result) }
                                Text("Credentials stay encrypted on this phone. Experiments and transcripts are not saved. If Android kills the app during sign-in, return and start a fresh attempt.")
                            }
                        }
                        item {
                            LabCard("Five-minute keyword experiment") {
                                Text("sherpa-onnx 1.12.14 · English GigaSpeech 3.3M · keyword: hello world")
                                Text("${keyword.notice} · ${keyword.detections} detections · ${keyword.elapsedMs / 1000} s")
                                Button(onClick = {
                                    agent.stopAudio()
                                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                        keywordPermissionPending = true
                                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                                    } else if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else startKeywordSession()
                                }, enabled = !keyword.active) { Text("Start local keyword session") }
                                OutlinedButton(onClick = ::stopKeywordSession, enabled = keyword.active) { Text("Stop keyword session") }
                                Text("Microphone stays active with the screen off until Stop or the five-minute timeout. Audio is discarded locally. Detections only increment the counter; they do not call AI or read private information. Notification permission is required for the visible Stop action.")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startKeywordSession() {
        agent.stopAudio()
        try { startForegroundService(Intent(this, KeywordService::class.java)) }
        catch (_: RuntimeException) { agent.permissionDenied() }
    }

    private fun stopKeywordSession() { stopService(Intent(this, KeywordService::class.java)) }

    override fun onStop() {
        agent.stopAudio()
        super.onStop()
    }
}

@Composable
private fun LabCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
