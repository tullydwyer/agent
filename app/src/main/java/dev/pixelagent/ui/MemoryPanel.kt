package dev.pixelagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pixelagent.conversation.AgentState
import dev.pixelagent.memory.MemoryFact
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun MemoryPanel(state: AgentState, enabled: Boolean,
    onSave: (Long?, String, String, Long?, Boolean) -> Unit, onForget: (Long) -> Unit,
    onExport: () -> Unit, onDelete: () -> Unit) {
    var editing by remember { mutableStateOf<MemoryFact?>(null) }
    var text by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("Entered by user in Memory settings") }
    var expiry by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var delete by remember { mutableStateOf(false) }
    fun select(fact: MemoryFact?) {
        editing = fact; text = fact?.text.orEmpty()
        source = fact?.provenance ?: "Entered by user in Memory settings"
        expiry = fact?.expiresAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() }.orEmpty()
        error = null
    }
    fun save(confirmed: Boolean) {
        val expires = if (expiry.isBlank()) null else runCatching {
            LocalDate.parse(expiry).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrElse { error = "Use YYYY-MM-DD or leave expiry blank."; return }
        if (expires != null && expires <= System.currentTimeMillis()) { error = "Choose a future expiry date."; return }
        onSave(editing?.id, text, source, expires, confirmed)
        select(null)
    }
    Text(state.memoryNotice)
    Text("Up to 200 facts. Drafts stay local until you confirm them. Relevant confirmed facts are sent with your messages. Editing, forgetting or deleting clears conversation history so earlier replies cannot reintroduce removed facts. Exports contain private text.")
    OutlinedTextField(text, { text = it.take(1000) }, label = { Text(if (editing == null) "New fact" else "Edit fact") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(source, { source = it.take(300) }, label = { Text("Source / provenance") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(expiry, { expiry = it.take(10) }, label = { Text("Expires on YYYY-MM-DD (optional)") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { save(true) }, enabled = enabled && text.isNotBlank() && source.isNotBlank()) { Text("Confirm and remember") }
        TextButton(onClick = { save(false) }, enabled = enabled && text.isNotBlank() && source.isNotBlank()) { Text("Save draft") }
    }
    if (editing != null) TextButton(onClick = { select(null) }) { Text("Cancel edit") }
    state.memories.forEach { fact ->
        HorizontalDivider()
        Text(fact.text, style = MaterialTheme.typography.bodyLarge)
        Text("${if (fact.confirmed) "Confirmed" else "Draft"} · ${fact.provenance}")
        Text("Created ${Instant.ofEpochMilli(fact.createdAt)} · Updated ${Instant.ofEpochMilli(fact.updatedAt)}" +
            (fact.expiresAt?.let { " · Expires ${Instant.ofEpochMilli(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = { select(fact) }, enabled = enabled) { Text("Edit / confirm") }
            TextButton(onClick = { onForget(fact.id); if (editing?.id == fact.id) select(null) }, enabled = enabled) { Text("Forget") }
        }
    }
    OutlinedButton(onClick = onExport, enabled = enabled) { Text("Export memory and persona") }
    TextButton(onClick = { delete = true }, enabled = enabled) { Text("Delete all memory, persona and chat history") }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("Delete local memory?") },
        text = { Text("Remove all facts and chat history and reset the persona on this phone. Previously uploaded content and exported files are outside this local deletion.") },
        confirmButton = { TextButton(onClick = { onDelete(); select(null); delete = false }) { Text("Delete local data") } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("Cancel") } })
}
