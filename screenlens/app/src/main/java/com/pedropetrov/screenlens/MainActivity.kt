package com.pedropetrov.screenlens

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pedropetrov.screenlens.camera.CameraHostScreen
import com.pedropetrov.screenlens.controller.ControllerScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ScreenLensTheme { App() } }
    }
}

private enum class Role { CAMERA, CONTROLLER }

@Composable
private fun App() {
    var role by rememberSaveable { mutableStateOf<Role?>(null) }
    BackHandler(enabled = role != null) { role = null }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            when (role) {
                null -> RolePicker { role = it }
                Role.CAMERA -> CameraHostScreen()
                Role.CONTROLLER -> ControllerScreen()
            }
        }
    }
}

@Composable
private fun RolePicker(onPick: (Role) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("ScreenLens", style = MaterialTheme.typography.headlineMedium)
        Text("Point one phone at a screen, book or notes. Control it and read the answer on the other phone.")
        Button(onClick = { onPick(Role.CONTROLLER) }, modifier = Modifier.fillMaxWidth()) {
            Text("Controller (the phone in your hand)")
        }
        OutlinedButton(onClick = { onPick(Role.CAMERA) }, modifier = Modifier.fillMaxWidth()) {
            Text("Camera (the phone pointed at the screen)")
        }
    }
}

@Composable
private fun ScreenLensTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
