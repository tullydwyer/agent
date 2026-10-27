package dev.pixelagent

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pixelagent.conversation.AgentViewModel
import dev.pixelagent.conversation.AudioState

class MainActivity : ComponentActivity() {
    private val agent: AgentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val state by agent.state.collectAsStateWithLifecycle()
                val microphonePermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted -> if (granted) agent.listen() else agent.permissionDenied() }
                Scaffold { insets ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(insets),
                        contentPadding = PaddingValues(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        item {
                            Text("Pixel Agent", style = MaterialTheme.typography.headlineLarge)
                            Text("Device lab · 0.1.0", style = MaterialTheme.typography.labelLarge)
                        }
                        item {
                            LabCard("Status") {
                                Text(state.audio.label, style = MaterialTheme.typography.titleLarge)
                                Text(state.notice)
                                Text("Camera off · Wake word off · AI ${agent.model.displayName.lowercase()}")
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
                                    enabled = state.localRecognitionAvailable && state.audio == AudioState.IDLE,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Listen to a phrase") }
                                Button(
                                    onClick = agent::speak,
                                    enabled = state.localVoice != null && state.audio == AudioState.IDLE,
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
                            Text("This build has no AI connection, account access, camera capture, or saved recordings. Speech text is held in memory and clears when the app process ends.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

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
