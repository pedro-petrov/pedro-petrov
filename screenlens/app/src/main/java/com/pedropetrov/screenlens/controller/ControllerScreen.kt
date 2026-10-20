package com.pedropetrov.screenlens.controller

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pedropetrov.screenlens.ai.Mode
import com.pedropetrov.screenlens.ai.Tier
import com.pedropetrov.screenlens.net.DEFAULT_PORT
import com.pedropetrov.screenlens.ui.MarkdownText

/** Controller-phone mode: drives the camera phone and shows Claude's answer. */
@Composable
fun ControllerScreen(vm: ControllerViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()

    // Keep the newest answer text in view while it streams.
    LaunchedEffect(state.turns.lastOrNull()?.answer?.length) {
        if (state.turns.isNotEmpty()) scroll.animateScrollTo(scroll.maxValue)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.error?.let { ErrorCard(it, vm::dismissError) }
        when {
            state.connectedTo == null -> ConnectPanel(vm, state)
            state.captured == null -> LivePanel(vm, state)
            else -> AnswerPanel(vm, state)
        }
    }
}

@Composable
private fun ConnectPanel(vm: ControllerViewModel, state: ControllerState) {
    var address by remember { mutableStateOf(vm.prefs.lastHost) }
    var pin by remember { mutableStateOf(vm.prefs.lastPin) }
    var apiKey by remember { mutableStateOf(vm.prefs.apiKey) }

    Text("Controller", style = MaterialTheme.typography.headlineSmall)
    Text("Open ScreenLens on the camera phone, choose Camera, and connect both phones to the same Wi-Fi.")

    Text("Cameras found on this Wi-Fi", style = MaterialTheme.typography.titleSmall)
    if (state.discovered.isEmpty()) {
        Text("Searching…", style = MaterialTheme.typography.bodySmall)
    }
    state.discovered.forEach { cam ->
        Card(
            Modifier
                .fillMaxWidth()
                .clickable { address = "${cam.host}:${cam.port}" },
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(cam.name, fontWeight = FontWeight.SemiBold)
                Text("${cam.host}:${cam.port}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    OutlinedTextField(
        value = address,
        onValueChange = { address = it },
        label = { Text("Camera address (IP:port)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = pin,
        onValueChange = { pin = it.filter(Char::isDigit).take(6) },
        label = { Text("PIN shown on the camera phone") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = {
            val host = address.substringBeforeLast(':').ifBlank { address }.trim()
            val port = address.substringAfterLast(':', "").toIntOrNull() ?: DEFAULT_PORT
            vm.connect(host, port, pin)
        },
        enabled = address.isNotBlank() && pin.length == 6,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Connect") }

    HorizontalDivider()
    Text("Settings", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = apiKey,
        onValueChange = {
            apiKey = it
            vm.prefs.apiKey = it
        },
        label = { Text("Claude API key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "Create a key at console.anthropic.com. It is stored only on this phone.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text("Model", style = MaterialTheme.typography.titleSmall)
    TierPicker(state.tier, vm::setTier)
}

@Composable
private fun TierPicker(selected: Tier, onSelect: (Tier) -> Unit) {
    Column {
        Tier.entries.forEach { tier ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(tier) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = tier == selected, onClick = { onSelect(tier) })
                Column {
                    Text(tier.label, fontWeight = FontWeight.SemiBold)
                    Text(tier.hint, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ConnectedHeader(vm: ControllerViewModel, state: ControllerState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(state.deviceName.ifBlank { "Camera" }, style = MaterialTheme.typography.titleMedium)
            Text(state.connectedTo.orEmpty(), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = vm::disconnect) { Text("Disconnect") }
    }
}

@Composable
private fun LivePanel(vm: ControllerViewModel, state: ControllerState) {
    ConnectedHeader(vm, state)
    val frame = state.livePreview
    if (frame != null) {
        Image(
            frame.asImageBitmap(),
            contentDescription = "Live view from the camera phone",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp),
        )
    } else {
        Text("Waiting for the camera picture…")
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Torch")
        Spacer(Modifier.width(8.dp))
        Switch(checked = state.torch, onCheckedChange = vm::setTorch)
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = vm::focus) { Text("Focus") }
    }
    if (state.maxZoom > state.minZoom) {
        val maxUsable = minOf(state.maxZoom, 10f)
        Text("Zoom ${"%.1f".format(state.zoom)}×")
        Slider(
            value = state.zoom.coerceIn(state.minZoom, maxUsable),
            onValueChange = vm::previewZoom,
            onValueChangeFinished = vm::applyZoom,
            valueRange = state.minZoom..maxUsable,
        )
    }
    Button(
        onClick = vm::capture,
        enabled = !state.capturing,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.capturing) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(if (state.capturing) "Capturing…" else "Capture the screen")
    }
}

@Composable
private fun AnswerPanel(vm: ControllerViewModel, state: ControllerState) {
    ConnectedHeader(vm, state)
    state.captured?.let {
        Image(
            it.asImageBitmap(),
            contentDescription = "Captured photo",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp),
        )
    }
    OutlinedButton(onClick = vm::retake, modifier = Modifier.fillMaxWidth()) { Text("New photo") }

    if (state.askMode) PresentPrompt(vm, state.tier)

    state.turns.forEach { turn ->
        HorizontalDivider()
        Text(turn.question, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        MarkdownText(turn.answer)
        turn.costNote?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
    if (state.thinking) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Claude is reading the photo…")
        }
    }
    if (state.turns.isNotEmpty() && !state.thinking) FollowUpBox(vm)
}

@Composable
private fun PresentPrompt(vm: ControllerViewModel, tier: Tier) {
    var extra by remember { mutableStateOf("") }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What should I show you?", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = extra,
                onValueChange = { extra = it },
                label = { Text("Anything specific? (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )
            TierPicker(tier, vm::setTier)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mode.entries.forEach { mode ->
                    Button(onClick = { vm.ask(mode, extra) }, modifier = Modifier.weight(1f)) { Text(mode.label) }
                }
            }
        }
    }
}

@Composable
private fun FollowUpBox(vm: ControllerViewModel) {
    var question by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = question,
            onValueChange = { question = it },
            label = { Text("Ask a follow-up") },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                vm.followUp(question)
                question = ""
            },
            enabled = question.isNotBlank(),
        ) { Text("Send") }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}
