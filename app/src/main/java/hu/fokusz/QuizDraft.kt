package hu.fokusz

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

internal data class QuizShare(val text: String?, val images: List<Uri>, val id: String = UUID.randomUUID().toString()) {
    companion object {
        fun from(intent: Intent?): QuizShare? {
            if (intent == null) return null
            if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return null
            val type = intent.type.orEmpty()
            if (type == "text/plain" && intent.action == Intent.ACTION_SEND) {
                return intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.take(QuizDraft.MAX_TEXT)
                    ?.takeIf { it.isNotBlank() }?.let { QuizShare(it, emptyList()) }
            }
            if (!type.startsWith("image/")) return null
            val attachments = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            } else listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            val clip = intent.clipData
            val images = (attachments + (0 until minOf(clip?.itemCount ?: 0, QuizDraft.MAX_IMAGES))
                .mapNotNull { clip?.getItemAt(it)?.uri })
                .filter { it.scheme == "content" }.distinct().take(QuizDraft.MAX_IMAGES)
            return images.takeIf { it.isNotEmpty() }?.let { QuizShare(null, it) }
        }
    }
}

internal data class QuizCrop(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object { val Full = QuizCrop(0f, 0f, 1f, 1f) }
}

internal data class QuizPhoto(val id: String, val crop: QuizCrop = QuizCrop.Full)

internal object QuizDraft {
    const val MAX_IMAGES = 20
    const val MAX_TEXT = 80_000
    private const val MAX_IMAGE_BYTES = 32L * 1024L * 1024L
    @Volatile private var cameraAccess: Pair<Long, Set<String>>? = null
    private fun prefs(context: Context) = context.getSharedPreferences("fokusz_quiz_draft", Context.MODE_PRIVATE)
    private fun folder(context: Context) = File(context.filesDir, "quiz_draft").apply { mkdirs() }
    private fun cameraFolder(context: Context) = File(folder(context), "camera").apply { mkdirs() }
    private fun file(context: Context, id: String) = File(folder(context), "$id.img")

    fun isCameraActive(packageName: String): Boolean {
        val (until, packages) = cameraAccess ?: return false
        return SystemClock.elapsedRealtime() < until && packageName in packages
    }

    fun subject(context: Context): String = prefs(context).getString("subject", "").orEmpty()
    fun setSubject(context: Context, subject: String) {
        prefs(context).edit().putString("subject", subject.take(60)).apply()
    }

    fun text(context: Context): String = prefs(context).getString("text", "").orEmpty()
    fun setText(context: Context, text: String) {
        prefs(context).edit().putString("text", text.take(MAX_TEXT)).apply()
    }

    fun targetMinutes(context: Context): String = prefs(context).getString("target", "").orEmpty()
    fun setTarget(context: Context, target: String) {
        prefs(context).edit().putString("target", target).apply()
    }

    @Synchronized
    fun photos(context: Context): List<QuizPhoto> {
        val array = runCatching { JSONArray(prefs(context).getString("photos", "[]")) }.getOrElse { JSONArray() }
        return (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            val id = entry.optString("id")
            if (id.length != 36 || !id.all { it.isLetterOrDigit() || it == '-' } || !file(context, id).isFile) {
                return@mapNotNull null
            }
            QuizPhoto(id, QuizCrop(
                entry.optDouble("left", 0.0).toFloat(), entry.optDouble("top", 0.0).toFloat(),
                entry.optDouble("right", 1.0).toFloat(), entry.optDouble("bottom", 1.0).toFloat()
            ))
        }
    }

    private fun savePhotos(context: Context, photos: List<QuizPhoto>): Boolean {
        val rows = photos.map { shot -> JSONObject().put("id", shot.id)
            .put("left", shot.crop.left.toDouble()).put("top", shot.crop.top.toDouble())
            .put("right", shot.crop.right.toDouble()).put("bottom", shot.crop.bottom.toDouble()) }
        return prefs(context).edit().putString("photos", JSONArray(rows).toString()).commit()
    }

    @Synchronized
    fun setCrop(context: Context, id: String, crop: QuizCrop) {
        if (crop.right - crop.left < 0.04f || crop.bottom - crop.top < 0.04f) return
        savePhotos(context, photos(context).map { if (it.id == id) it.copy(crop = crop) else it })
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        val before = photos(context)
        if (before.none { it.id == id }) return
        if (savePhotos(context, before.filterNot { it.id == id })) file(context, id).delete()
    }

    @Synchronized
    fun clear(context: Context) {
        val old = photos(context)
        if (prefs(context).edit().remove("photos").remove("text").commit()) {
            old.forEach { file(context, it.id).delete() }
        }
    }

    @Synchronized
    fun startCamera(context: Context): Uri? {
        if (photos(context).size >= MAX_IMAGES) return null
        val photo = File(cameraFolder(context), "capture.jpg")
        if (photo.exists() && !photo.delete()) return null
        if (!photo.createNewFile()) return null
        val capture = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val cameraApps = context.packageManager.queryIntentActivities(capture, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.packageName }.toSet()
        cameraAccess = SystemClock.elapsedRealtime() + 5 * 60_000L to cameraApps
        return FileProvider.getUriForFile(context, context.packageName + ".quiz_camera", photo)
    }

    @Synchronized
    fun finishCamera(context: Context, success: Boolean): Boolean {
        cameraAccess = null
        val photo = File(cameraFolder(context), "capture.jpg")
        if (!success || !looksLikeImage(photo)) {
            photo.delete()
            return false
        }
        val before = photos(context)
        val id = UUID.randomUUID().toString()
        val target = file(context, id)
        if (before.size < MAX_IMAGES && photo.renameTo(target) && savePhotos(context, before + QuizPhoto(id))) return true
        photo.delete()
        target.delete()
        return false
    }

    suspend fun import(context: Context, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var accepted = 0
        for (uri in uris.distinct().take(MAX_IMAGES - photos(context).size)) {
            val id = UUID.randomUUID().toString()
            val target = file(context, id)
            val imported = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var size = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            check(size <= MAX_IMAGE_BYTES)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                looksLikeImage(target) && synchronized(this@QuizDraft) {
                    val current = photos(context)
                    current.size < MAX_IMAGES && savePhotos(context, current + QuizPhoto(id))
                }
            }.getOrDefault(false)
            if (imported) accepted++ else target.delete()
        }
        accepted
    }

    private fun looksLikeImage(file: File): Boolean {
        if (file.length() !in 1..MAX_IMAGE_BYTES) return false
        val metadata = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, metadata)
        return metadata.outWidth > 0 && metadata.outHeight > 0
    }

    fun bitmap(context: Context, photo: QuizPhoto, longest: Int): Bitmap? {
        val source = file(context, photo.id)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > longest ||
            (options.outWidth / sample).toLong() * (options.outHeight / sample) > 8_000_000L) sample *= 2
        options.inJustDecodeBounds = false
        options.inSampleSize = sample
        val bitmap = BitmapFactory.decodeFile(source.path, options) ?: return null
        val orientation = runCatching { ExifInterface(source.path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(1)
        val transform = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transform.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> transform.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> transform.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { transform.setRotate(90f); transform.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> transform.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { transform.setRotate(-90f); transform.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> transform.setRotate(270f)
        }
        if (transform.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }

    data class Read(val texts: List<String>, val unreadPages: List<Int>)

    suspend fun readPhotos(context: Context, photos: List<QuizPhoto>): Read = withContext(Dispatchers.IO) {
        if (photos.isEmpty()) return@withContext Read(emptyList(), emptyList())
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val results = mutableListOf<String>()
        val unread = mutableListOf<Int>()
        try {
            for ((index, photo) in photos.withIndex()) {
                val bitmap = runCatching { bitmap(context, photo, 4000) }.getOrNull()
                if (bitmap == null) {
                    unread += index + 1
                    continue
                }
                val left = (photo.crop.left * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
                val top = (photo.crop.top * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
                val right = (photo.crop.right * bitmap.width).roundToInt().coerceIn(left + 1, bitmap.width)
                val bottom = (photo.crop.bottom * bitmap.height).roundToInt().coerceIn(top + 1, bitmap.height)
                val selection = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                val text = try {
                    recognizer.process(InputImage.fromBitmap(selection, 0)).await().text
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ""
                }
                if (selection !== bitmap) selection.recycle()
                bitmap.recycle()
                if (text.isBlank()) unread += index + 1 else results += text
            }
            Read(results, unread)
        } finally {
            recognizer.close()
        }
    }
}
