package com.pedropetrov.screenlens.camera

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pedropetrov.screenlens.Prefs
import com.pedropetrov.screenlens.net.DEFAULT_PORT
import com.pedropetrov.screenlens.net.NsdAdvertiser
import com.pedropetrov.screenlens.net.localIpAddress

/** Camera-phone mode: shows the viewfinder and serves it to the controller phone. */
@Composable
fun CameraHostScreen() {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }

    if (!hasPermission) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("ScreenLens needs the camera to read screens, books and notes.")
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, Modifier.padding(top = 16.dp)) {
                Text("Allow camera")
            }
        }
        return
    }

    CameraHostContent()
}

@Composable
private fun CameraHostContent() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { Prefs(context) }
    val pin = remember { prefs.cameraPin }
    val engine = remember { CameraEngine(context.applicationContext) }
    var status by remember { mutableStateOf("Waiting for the controller phone…") }
    val ip = remember { localIpAddress(context) }

    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val main = Handler(Looper.getMainLooper())
        val server = CameraHttpServer(DEFAULT_PORT, pin, engine) { msg -> main.post { status = msg } }
        val advertiser = NsdAdvertiser(context)
        val startError = runCatching { server.start(5_000, false) }.exceptionOrNull()
        if (startError != null) {
            status = "Could not start server: ${startError.message}"
        } else {
            advertiser.register("ScreenLens ${Build.MODEL}", DEFAULT_PORT)
        }
        onDispose {
            advertiser.unregister()
            server.stop()
            engine.release()
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx -> PreviewView(ctx).also { engine.bind(lifecycleOwner, it) } },
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(16.dp),
        ) {
            Text("CAMERA MODE", color = Color.White, style = MaterialTheme.typography.labelMedium)
            Text(
                "Address: ${ip ?: "not on Wi-Fi"}:$DEFAULT_PORT",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text("PIN: $pin", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(status, color = Color(0xFFB3E5FC), modifier = Modifier.padding(top = 4.dp))
        }
    }
}
