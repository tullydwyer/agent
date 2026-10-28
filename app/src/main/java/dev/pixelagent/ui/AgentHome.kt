package dev.pixelagent.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import dev.pixelagent.conversation.AgentState
import dev.pixelagent.conversation.AudioState
import dev.pixelagent.model.ModelLabState
import dev.pixelagent.wakeword.HandsFreeState

@Composable
fun AgentHome(
    agent: AgentState, cloud: ModelLabState, session: HandsFreeState, keywordActive: Boolean,
    recovery: String, modifier: Modifier = Modifier,
    onSettings: () -> Unit, onTalk: () -> Unit, onStartSession: () -> Unit,
    onStopSession: () -> Unit, onCancel: () -> Unit, onSend: (String) -> Unit,
    duration: Long, onDuration: (Long) -> Unit, wakeWord: String,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val connected = cloud.selectedModel != null && !cloud.busy
    val idle = !agent.awaitingTranscript && !agent.busy && agent.audio == AudioState.IDLE && !keywordActive
    val canTalk = connected && idle && !session.active && agent.localRecognitionAvailable
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val label = when {
        agent.audio == AudioState.SPEAKING -> "Speaking"
        agent.audio == AudioState.LISTENING -> "I’m listening"
        agent.audio == AudioState.TRANSCRIBING -> "Hearing you"
        agent.busy -> "Thinking"
        session.listening -> "Say $wakeWord"
        session.active -> "Getting ready"
        cloud.busy -> "Connecting"
        !connected -> "Let’s get connected"
        else -> "Ready when you are"
    }
    Column(modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("PIXEL AGENT", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("Your companion", style = MaterialTheme.typography.titleLarge)
            }
            TextButton(onClick = onSettings) { Text("Settings") }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            AgentFace(label, agent.audio == AudioState.SPEAKING, agent.busy, session.listening || agent.audio == AudioState.LISTENING, if (keyboard) 76.dp else 170.dp)
            Text(label, style = MaterialTheme.typography.headlineSmall)
            Text("Camera off · Mic ${if (session.listening || agent.audio == AudioState.LISTENING || agent.audio == AudioState.TRANSCRIBING) "on" else "off"}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (connected) agent.notice.takeUnless { it == "Select an account model to start a conversation." } ?: "Talk to me or start a hands-free session." else cloud.result,
            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        if (!keyboard) Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (session.active) Text(session.notice, style = MaterialTheme.typography.titleSmall)
                        Text(if (session.active) "${session.detections} wakes · ${session.elapsedMs / 60000} / ${session.durationMs / 60000} min"
                            else "Say “$wakeWord” once, then talk after each tone. I’ll listen again after replying. Silence returns to the wake word.", style = MaterialTheme.typography.bodyMedium)
                        if (session.active) Text("Keep talking after the tone. Silence returns to $wakeWord. Stop here or in the notification.", style = MaterialTheme.typography.bodySmall)
                        if (!session.active) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(15L, 60L, 480L).forEach { minutes ->
                                    FilterChip(selected = duration == minutes * 60000, onClick = { onDuration(minutes * 60000) }, label = { Text(if (minutes < 60) "$minutes min" else "${minutes / 60} hr") })
                                }
                            }
                            Button(onClick = onStartSession, enabled = canTalk && agent.localVoice != null, modifier = Modifier.fillMaxWidth()) { Text("Start hands-free") }
                        } else Button(onClick = onStopSession, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) { Text("Stop hands-free") }
                        if (!session.active && session.notice != HandsFreeState().notice) Text(session.notice, style = MaterialTheme.typography.bodySmall)
                        if (!session.active && recovery.isNotBlank() && session.notice == HandsFreeState().notice) Text(recovery, style = MaterialTheme.typography.bodySmall)
                    }
                }

        val list = rememberLazyListState()
        LaunchedEffect(agent.messages.size, agent.partial.length) {
            if ((agent.messages.isNotEmpty() || agent.partial.isNotBlank()) && list.layoutInfo.totalItemsCount > 0) list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                if (!connected) {
                    Text("Open Settings to sign in with ChatGPT or refresh your account models. Your selection is restored when you return.")
                } else if (agent.messages.isEmpty()) Text("Talk to me or write a message below. No skills are needed to get started.")
                if (agent.transcript.isNotBlank()) Text("Heard: ${agent.transcript}", style = MaterialTheme.typography.bodySmall)
            }
            itemsIndexed(agent.messages) { _, message ->
                ChatBubble(if (message.role == "user") "You" else "Pixel", message.text, message.role == "user")
            }
            if (agent.partial.isNotBlank()) item { ChatBubble("Pixel · replying", agent.partial, false) }
            if (agent.toolEvents.isNotEmpty()) item { Text(agent.toolEvents.joinToString("\n"), style = MaterialTheme.typography.bodySmall) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onTalk, enabled = canTalk, modifier = Modifier.weight(1f)) { Text("Talk to Pixel") }
            OutlinedButton(onClick = onCancel, enabled = agent.busy || agent.audio != AudioState.IDLE || session.active) { Text("Stop") }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(draft, { draft = it.take(4000) }, modifier = Modifier.weight(1f), placeholder = { Text("Message Pixel…") }, maxLines = 3, enabled = !session.active)
            TextButton(onClick = { onSend(draft); draft = "" }, enabled = connected && idle && !session.active && draft.isNotBlank()) { Text("Send") }
        }
    }
}

@Composable
private fun ChatBubble(who: String, message: String, user: Boolean) {
    Column(Modifier.fillMaxWidth().background(if (user) Color(0xFF203E43) else Color(0xFF182733), RoundedCornerShape(18.dp)).padding(16.dp)) {
        Text(who, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        SelectionContainer { Text(message, style = MaterialTheme.typography.bodyLarge) }
    }
}

/** Vector face: no downloaded assets, video, camera feed, or character-generation dependency. */
@Composable
private fun AgentFace(label: String, speaking: Boolean, thinking: Boolean, listening: Boolean, faceSize: Dp) {
    val transition = rememberInfiniteTransition(label = "agent")
    val breath by transition.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "breath")
    val mouth by transition.animateFloat(0.15f, 1f, infiniteRepeatable(tween(180), RepeatMode.Reverse), label = "voice")
    val blink by transition.animateFloat(1f, 0.12f, infiniteRepeatable(keyframes { durationMillis = 4800; 1f at 4400; 0.12f at 4500; 1f at 4620 }), label = "blink")
    val accent = if (thinking) Color(0xFFBBA9FF) else Color(0xFF85E8D5)
    Canvas(Modifier.size(faceSize).semantics { contentDescription = "Pixel Agent face: $label" }) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension * 0.44f * breath
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = if (listening) 0.24f else 0.12f), Color.Transparent), center, radius), radius, center)
        drawCircle(accent.copy(alpha = 0.4f), radius * 0.83f, center, style = Stroke(2.dp.toPx()))
        drawRoundRect(Color(0xFF102730), Offset(size.width * 0.2f, size.height * 0.3f), Size(size.width * 0.6f, size.height * 0.43f), CornerRadius(28.dp.toPx()))
        val eyeHeight = size.height * 0.09f * blink
        for (x in listOf(0.37f, 0.63f)) drawRoundRect(accent, Offset(size.width * x - size.width * 0.035f, size.height * 0.46f - eyeHeight / 2), Size(size.width * 0.07f, eyeHeight), CornerRadius(8.dp.toPx()))
        if (speaking) {
            for (i in -2..2) {
                val height = size.height * 0.065f * mouth * (1f - kotlin.math.abs(i) * 0.2f)
                val x = center.x + i * size.width * 0.035f
                drawLine(accent, Offset(x, size.height * 0.61f - height), Offset(x, size.height * 0.61f + height), 3.dp.toPx(), StrokeCap.Round)
            }
        } else drawArc(accent, 20f, 140f, false, Offset(size.width * 0.44f, size.height * 0.55f), Size(size.width * 0.12f, size.height * 0.08f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    }
}
