package hu.fokusz

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun CameraPermissionGate(requestOnEnter: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val preferences = remember(context) { context.getSharedPreferences("fokusz_camera", Context.MODE_PRIVATE) }
    fun canUseCamera() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    var granted by remember(context) { mutableStateOf(canUseCamera()) }
    var settingsError by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    fun request() {
        preferences.edit().putBoolean("asked", true).apply()
        launcher.launch(Manifest.permission.CAMERA)
    }
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = canUseCamera()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(requestOnEnter) {
        if (requestOnEnter && !granted && !preferences.getBoolean("asked", false)) request()
    }
    if (granted) {
        content()
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("A kamera használatához engedélyezd a hozzáférést. Ha korábban letiltottad, az alkalmazás beállításaiban engedélyezheted.",
            color = Color(0xFFA7B4CC))
        Button(onClick = { request() }) { Text("Kamera engedélyezése") }
        OutlinedButton(onClick = {
            val settings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            settingsError = runCatching { context.startActivity(settings) }.isFailure
        }) { Text("Alkalmazásbeállítások megnyitása") }
        if (settingsError) Text("Nyisd meg a telefon beállításaiban a Fókusz alkalmazás engedélyeit.",
            color = Color(0xFFFFC78D))
    }
}
