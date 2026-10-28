package dev.pixelagent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pixelagent.conversation.AgentViewModel
import dev.pixelagent.conversation.AudioState
import dev.pixelagent.model.ModelLabViewModel
import dev.pixelagent.ui.AgentHome
import dev.pixelagent.wakeword.HandsFreeService
import dev.pixelagent.wakeword.KeywordService
import dev.pixelagent.wakeword.SessionPolicy
import dev.pixelagent.wakeword.WakeWordSettings

class MainActivity : ComponentActivity() {
    private val agentModel: AgentViewModel by viewModels()
    private val agent get() = agentModel.runtime
    private val modelLab: ModelLabViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF85E8D5), onPrimary = Color(0xFF052C27),
                background = Color(0xFF0B1219), surface = Color(0xFF131F2A),
                surfaceContainer = Color(0xFF182733),
            )) {
                val state by agent.state.collectAsStateWithLifecycle()
                val cloud by modelLab.state.collectAsStateWithLifecycle()
                val session by HandsFreeService.state.collectAsStateWithLifecycle()
                val keyword by KeywordService.state.collectAsStateWithLifecycle()
                var settings by rememberSaveable { mutableStateOf(false) }
                val wakeSettings = remember { WakeWordSettings(this@MainActivity) }
                var wakeWord by remember { mutableStateOf(wakeSettings.phrase) }
                var wakeDraft by rememberSaveable { mutableStateOf(wakeWord) }
                var wakeError by rememberSaveable { mutableStateOf<String?>(null) }
                BackHandler(enabled = settings) { settings = false }
                var permissionAction by rememberSaveable { mutableStateOf("talk") }
                var duration by rememberSaveable { mutableLongStateOf(SessionPolicy.DEFAULT_DURATION_MS) }
                val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) startHandsFree(duration) else agent.showNotice("Allow notifications to keep the hands-free Stop control visible, then try again.")
                }
                fun startSessionWithNotifications() {
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else startHandsFree(duration)
                }
                val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) when (permissionAction) {
                        "session" -> startSessionWithNotifications()
                        "test" -> agent.listenTest()
                        else -> agent.listen(modelLab.provider)
                    } else agent.permissionDenied()
                }
                fun requestMicrophone(action: String) {
                    permissionAction = action
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    else when (action) {
                        "session" -> startSessionWithNotifications()
                        "test" -> agent.listenTest()
                        else -> agent.listen(modelLab.provider)
                    }
                }
                Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
                    if (!settings) {
                        val recovery = remember(session.active) {
                            val prefs = getSharedPreferences("hands_free", 0)
                            if (prefs.getBoolean("interrupted", false) && !session.active)
                                "The previous session ended outside the app. Listening is off; explicitly start a new session."
                            else prefs.getString("last_stop", "").orEmpty()
                        }
                        AgentHome(state, cloud, session, keyword.active, recovery, Modifier.padding(insets),
                            onSettings = { settings = true }, onTalk = { requestMicrophone("talk") },
                            onStartSession = { requestMicrophone("session") }, onStopSession = ::stopHandsFree,
                            onCancel = { if (session.active) stopHandsFree() else agent.cancel() },
                            onSend = { agent.send(it, modelLab.provider) }, duration = duration, onDuration = { duration = it }, wakeWord = wakeWord)
                    } else {
                        LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            item { TextButton(onClick = { settings = false }) { Text("← Back to agent") }; Text("Agent settings", style = MaterialTheme.typography.headlineMedium) }
                            item { SettingsCard("Wake word") {
                                OutlinedTextField(wakeDraft, { wakeDraft = it; wakeError = null },
                                    label = { Text("Wake word or phrase") }, singleLine = true,
                                    isError = wakeError != null, enabled = !session.active && !keyword.active,
                                    modifier = Modifier.fillMaxWidth())
                                Text(wakeError ?: "Saved wake word: $wakeWord. Use 1–4 English words. Changes apply to the next hands-free session.",
                                    color = if (wakeError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(onClick = {
                                    runCatching { wakeSettings.save(wakeDraft) }
                                        .onSuccess { wakeWord = it; wakeDraft = it; wakeError = null }
                                        .onFailure { wakeError = it.message ?: "Could not save this wake word." }
                                }, enabled = !session.active && !keyword.active && wakeDraft != wakeWord) { Text("Save wake word") }
                                if (session.active || keyword.active) Text("Stop the listening session to change the wake word.")
                            } }
                            item { SettingsCard("ChatGPT connection") {
                                Text("${cloud.connection} · Subscription only")
                                Text("Messages, recent history, persona and activated skill context go to the selected online model. Speech recognition and playback run on this phone.")
                                Text(cloud.result)
                                Button(onClick = { modelLab.signIn { startActivity(Intent(Intent.ACTION_VIEW, it)) } }, enabled = !state.busy && !cloud.busy && !session.active) { Text("Continue with ChatGPT") }
                                cloud.accounts.forEach { (id, label) ->
                                    OutlinedButton(onClick = { modelLab.selectAccount(id) }, enabled = !state.busy && !cloud.busy && !session.active) { Text("Use $label") }
                                    TextButton(onClick = { modelLab.signIn(id) { startActivity(Intent(Intent.ACTION_VIEW, it)) } }, enabled = !state.busy && !cloud.busy && !session.active) { Text("Reconnect $label") }
                                }
                                OutlinedButton(onClick = modelLab::fetchModels, enabled = !state.busy && !cloud.busy && !session.active && cloud.connection != "Not connected") { Text("Refresh models") }
                                cloud.selectedModel?.let { Text("Selected model: $it · Saved on this phone") }
                                cloud.models.forEach { model ->
                                    TextButton(onClick = { modelLab.selectModel(model.slug) }, enabled = !state.busy && !cloud.busy && !session.active) { Text("${if (model.slug == cloud.selectedModel) "✓ " else ""}${model.name}") }
                                }
                                TextButton(onClick = modelLab::signOut, enabled = !state.busy && !cloud.busy && !session.active && cloud.connection != "Not connected") { Text("Sign out") }
                            } }
                            item { SettingsCard("Identity and local speech") {
                                OutlinedTextField(state.persona, agent::setPersona, label = { Text("Agent persona") }, enabled = !state.busy && !session.active, modifier = Modifier.fillMaxWidth())
                                Text("Local recognizer: ${if (state.localRecognitionAvailable) "available" else "unavailable"}")
                                Text("Offline voice: ${if (state.ttsInitializing) "checking…" else state.localVoice ?: "not installed"}")
                                Text("${Build.MODEL} · Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
                                Button(onClick = { requestMicrophone("test") }, enabled = !state.busy && !cloud.busy && !session.active && !keyword.active && state.audio == AudioState.IDLE) { Text("Test local recognition") }
                                OutlinedButton(onClick = agent::speak, enabled = !state.busy && !cloud.busy && !session.active && !keyword.active && state.audio == AudioState.IDLE) { Text("Test local voice") }
                                TextButton(onClick = { if (session.active) stopHandsFree() else agent.cancel() }) { Text("Stop audio") }
                                Text(state.transcript); Text(state.notice); Text(state.latency); Text(state.textLatency)
                            } }
                            item { SettingsCard("Skills · optional") {
                                Text("You can add skills later. Conversation and hands-free sessions work without activating a skill. Imports and script runtimes are not implemented yet.")
                                OutlinedButton(onClick = { agent.setSkillsAllowed(!state.skillsAllowed) }, enabled = !state.busy && !session.active) { Text(if (state.skillsAllowed) "Disable skill loading" else "Enable skill loading") }
                                state.skillCatalog.forEach { skill ->
                                    Text("${skill.name}: ${skill.description}")
                                    skill.unavailable?.let { Text("Unavailable: $it") }
                                    TextButton(onClick = { agent.toggleSkill(skill.name) }, enabled = !state.busy && !session.active && state.skillsAllowed && skill.unavailable == null) { Text("${if (skill.name in state.activeSkills) "Deactivate" else "Activate"} ${skill.name}") }
                                }
                                state.skillIssues.forEach { Text("${it.directory}: ${it.reason}") }
                                Text("Active: ${state.activeSkills.joinToString().ifBlank { "none" }}")
                            } }
                            item { SettingsCard("Diagnostics") {
                                Text("Synthetic online tests use your plan allowance. The image is a generated red square and blue circle.")
                                Button(onClick = modelLab::textTrial, enabled = !state.busy && !cloud.busy && !session.active && cloud.selectedModel != null) { Text("Test model text") }
                                OutlinedButton(onClick = modelLab::imageTrial, enabled = !state.busy && !cloud.busy && !session.active && cloud.selectedModel != null) { Text("Upload diagnostic image") }
                                OutlinedButton(onClick = modelLab::toolTrial, enabled = !state.busy && !cloud.busy && !session.active && cloud.selectedModel != null) { Text("Test harmless function") }
                                OutlinedButton(onClick = modelLab::refreshTokens, enabled = !state.busy && !cloud.busy && !session.active && cloud.connection != "Not connected") { Text("Test token renewal") }
                                TextButton(onClick = modelLab::cancel, enabled = cloud.busy) { Text("Cancel experiment") }
                                Text(keyword.notice)
                                OutlinedButton(onClick = { stopService(Intent(this@MainActivity, KeywordService::class.java)) }, enabled = keyword.active) { Text("Stop old keyword trial") }
                                Text("Wake engine: sherpa-onnx 1.12.14 · GigaSpeech 3.3M · $wakeWord. Keyword audio is discarded locally. No camera capture.")
                            } }
                        }
                    }
                }
            }
        }
    }
    private fun startHandsFree(duration: Long) {
        try { startForegroundService(Intent(this, HandsFreeService::class.java).putExtra(HandsFreeService.DURATION, duration)) }
        catch (_: RuntimeException) { agent.showNotice("Android could not start hands-free. Keep the app visible and check microphone/notification access.") }
    }
    private fun stopHandsFree() { stopService(Intent(this, HandsFreeService::class.java)) }
    override fun onStop() {
        if (!isChangingConfigurations && !HandsFreeService.state.value.active) {
            if (agent.state.value.busy) agent.cancel() else if (agent.state.value.audio != AudioState.IDLE) agent.stopAudio()
        }
        super.onStop()
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
