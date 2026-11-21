package hu.fokusz

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader

/** A jóváhagyott webes Fókusz Edzőt a telepített APK-ból tölti be.
 * Nem kell GitHub Pages vagy külön szerver; a kamera csak helyben dolgozik.
 * A MediaPipe JS és a modell első indításkor internetkapcsolatot igényel.
 */
private const val WORKOUT_ORIGIN="appassets.androidplatform.net"
private const val WORKOUT_URL="https://appassets.androidplatform.net/assets/edzes/index.html"

/** A WebView csak egy „rep” eseményt küldhet az Android reward-rendszerének.
 * Nem kap hozzáférést a Firebase, tárhely vagy egyéb személyes adatok API-jához.
 */
private class WorkoutRepBridge(private val onRep: (Int) -> Unit) {
    private val handler=Handler(Looper.getMainLooper())
    private var lastRepAt=0L
    private var active = true

    @JavascriptInterface @Synchronized fun rep(count: Int) {
        val now=SystemClock.elapsedRealtime()
        if (!active || count <= 0 || now - lastRepAt < 450L) return
        lastRepAt = now
        handler.post { onRep(count) }
    }

    @Synchronized fun close() {
        active = false
        handler.removeCallbacksAndMessages(null)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable fun WebPushupCamera(questId:Int,onRep:()->Unit) {
    val context=LocalContext.current
    var permission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)
            ==PackageManager.PERMISSION_GRANTED)
    }
    val ask=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){
        permission=it
    }
    val latest=rememberUpdatedState(onRep)
    if(!permission){
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text("A kamerás edzéshez engedélyezd a kamerát. A videót az alkalmazás nem tölti fel.",
                color=Color(0xFFA7B4CC))
            Button(onClick={ask.launch(Manifest.permission.CAMERA)}){
                Text("KAMERA ENGEDÉLYEZÉSE")
            }
        }
        return
    }

    val speech = rememberWorkoutSpeech()
    var voiceEnabled by remember(questId) { mutableStateOf(false) }
    val speakLatest = rememberUpdatedState(voiceEnabled)
    val bridge=remember(questId, speech) {
        WorkoutRepBridge { count ->
            if (speakLatest.value) speech.speak(count)
            latest.value()
        }
    }
    val browser=remember(questId, bridge) {
        val loader=WebViewAssetLoader.Builder()
            .addPathHandler("/assets/",WebViewAssetLoader.AssetsPathHandler(context.applicationContext))
            .build()
        WebView(context).apply {
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=true
            settings.mediaPlaybackRequiresUserGesture=false
            settings.allowFileAccess=false
            settings.allowContentAccess=false
            settings.setSupportMultipleWindows(false)
            isVerticalScrollBarEnabled=true
            addJavascriptInterface(bridge,"FokuszAndroid")
            webViewClient=object:WebViewClient(){
                override fun shouldInterceptRequest(view:WebView?,request:WebResourceRequest?)=
                    request?.url?.let{loader.shouldInterceptRequest(it)}
                override fun shouldOverrideUrlLoading(view:WebView?,request:WebResourceRequest):Boolean =
                    request.url.scheme!="https" || request.url.host!=WORKOUT_ORIGIN
            }
            webChromeClient=object:WebChromeClient(){
                override fun onPermissionRequest(request:PermissionRequest){
                    Handler(Looper.getMainLooper()).post {
                        val video=request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                        val trusted=request.origin.scheme=="https" &&
                            request.origin.host==WORKOUT_ORIGIN
                        val androidAllowed=ContextCompat.checkSelfPermission(context,
                            Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
                        if(trusted&&video&&androidAllowed &&
                            request.resources.size==1) {
                            request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                        }else request.deny()
                    }
                }
            }
            loadUrl(WORKOUT_URL)
        }
    }
    DisposableEffect(browser){
        onDispose{
            bridge.close()
            browser.stopLoading()
            browser.removeJavascriptInterface("FokuszAndroid")
            browser.destroy()
        }
    }
    WorkoutSpeechToggle(speech, voiceEnabled) { voiceEnabled = it }
    key(browser) {
        AndroidView(factory={browser},modifier=Modifier.fillMaxWidth().height(715.dp))
    }
    Text("Fekvőtámaszhoz egy karod látszódjon oldalról. Engedd le magad 120°-ig, majd nyomd ki 145°-ig.",
        color=Color(0xFFA7B4CC))
}
