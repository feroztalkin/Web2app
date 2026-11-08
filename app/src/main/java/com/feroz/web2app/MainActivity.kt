package com.feroz.web2app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.ImageCapture.OutputFileOptions
import androidx.camera.core.ImageCapture.OutputFileResults
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64

data class ApiProfile(val name: String, val url: String, val token: String)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var previewView: PreviewView
    private lateinit var questionEdit: EditText
    private lateinit var optionsEdit: EditText
    private lateinit var resultText: TextView
    private lateinit var profileSpinner: Spinner
    private lateinit var profileNameEdit: EditText
    private lateinit var workerUrlEdit: EditText
    private lateinit var tokenEdit: EditText
    private lateinit var configStatusText: TextView
    private var imageCapture: ImageCapture? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    private lateinit var tts: TextToSpeech
    private val profiles = mutableListOf<ApiProfile>()
    private var selectedProfileIndex = -1
    private val store by lazy { EncryptedProfileStore(this) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startCamera() else toast("Camera permission is required.") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        questionEdit = findViewById(R.id.questionEdit)
        optionsEdit = findViewById(R.id.optionsEdit)
        resultText = findViewById(R.id.resultText)
        profileSpinner = findViewById(R.id.profileSpinner)
        profileNameEdit = findViewById(R.id.profileNameEdit)
        workerUrlEdit = findViewById(R.id.workerUrlEdit)
        tokenEdit = findViewById(R.id.tokenEdit)
        configStatusText = findViewById(R.id.configStatusText)
        tts = TextToSpeech(this, this)

        loadProfiles()
        findViewById<Button>(R.id.saveProfileButton).setOnClickListener { saveProfile() }
        findViewById<Button>(R.id.deleteProfileButton).setOnClickListener { deleteProfile() }
        findViewById<Button>(R.id.testConnectionButton).setOnClickListener { testWorker() }
        findViewById<Button>(R.id.captureButton).setOnClickListener { takePhoto() }
        findViewById<Button>(R.id.solveButton).setOnClickListener { solveOnWorker() }

        profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in profiles.indices) {
                    selectedProfileIndex = position
                    val p = profiles[position]
                    profileNameEdit.setText(p.name)
                    workerUrlEdit.setText(p.url)
                    tokenEdit.setText(p.token)
                    configStatusText.text = "Loaded '${p.name}'. Credentials are encrypted on this device."
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun loadProfiles() {
        profiles.clear()
        profiles.addAll(store.readProfiles())
        if (profiles.isEmpty()) {
            profiles.add(ApiProfile("My Cloudflare Worker", "https://web2app-ai.feroz-talkin.workers.dev/", ""))
            store.writeProfiles(profiles)
        }
        refreshSpinner(0)
    }

    private fun refreshSpinner(select: Int) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, profiles.map { it.name })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        profileSpinner.adapter = adapter
        if (profiles.isNotEmpty()) profileSpinner.setSelection(select.coerceIn(0, profiles.lastIndex))
    }

    private fun saveProfile() {
        val name = profileNameEdit.text.toString().trim()
        val url = workerUrlEdit.text.toString().trim()
        val token = tokenEdit.text.toString()
        if (name.isBlank()) return toast("Enter a profile name.")
        if (!url.startsWith("https://")) return toast("Worker URL must start with https://")
        if (token.isBlank()) return toast("Enter the API token.")
        val existing = profiles.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        val profile = ApiProfile(name, url, token)
        if (existing >= 0) profiles[existing] = profile else profiles.add(profile)
        store.writeProfiles(profiles)
        val index = profiles.indexOfFirst { it.name == name }
        selectedProfileIndex = index
        refreshSpinner(index)
        configStatusText.text = "Saved '$name' securely on this device."
        toast("Profile saved.")
    }

    private fun deleteProfile() {
        if (selectedProfileIndex !in profiles.indices) return toast("Select a profile first.")
        val removed = profiles.removeAt(selectedProfileIndex)
        if (profiles.isEmpty()) profiles.add(ApiProfile("My Cloudflare Worker", "https://web2app-ai.feroz-talkin.workers.dev/", ""))
        store.writeProfiles(profiles)
        val index = (selectedProfileIndex - 1).coerceAtLeast(0).coerceAtMost(profiles.lastIndex)
        refreshSpinner(index)
        configStatusText.text = "Deleted '${removed.name}'."
    }

    private fun currentProfile(): ApiProfile? {
        val name = profileNameEdit.text.toString().trim()
        val url = workerUrlEdit.text.toString().trim()
        val token = tokenEdit.text.toString()
        if (name.isBlank() || !url.startsWith("https://") || token.isBlank()) return null
        return ApiProfile(name, url, token)
    }

    private fun testWorker() {
        val profile = currentProfile() ?: return toast("Select or enter a profile with HTTPS URL and token.")
        configStatusText.text = "Testing Worker with a sample MCQ request…"
        sendRequest(profile, "Which planet is known as the Red Planet?", listOf("Earth", "Mars", "Venus", "Jupiter")) { success, message ->
            configStatusText.text = if (success) "Worker responded: $message" else "Connection test failed: $message"
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val captureUseCase = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
            imageCapture = captureUseCase
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, captureUseCase)
            } catch (e: Exception) { toast("Camera error: ${e.message}") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return toast("Camera is starting. Try again.")
        val photo = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        capture.takePicture(OutputFileOptions.Builder(photo).build(), ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: OutputFileResults) {
                    try {
                        val original = BitmapFactory.decodeFile(photo.absolutePath) ?: throw Exception("Cannot decode photo.")
                        val enhanced = enhanceForOcr(original)
                        resultText.text = "Photo captured. Cleaning image and reading text…"
                        recognizer.process(InputImage.fromBitmap(enhanced, 0))
                            .addOnSuccessListener { text ->
                                val (question, options) = parseRecognizedText(text.text)
                                questionEdit.setText(question)
                                optionsEdit.setText(options.joinToString("\n"))
                                resultText.text = "OCR complete. Review the question and options, then solve."
                            }
                            .addOnFailureListener { toast("OCR failed: ${it.localizedMessage}") }
                            .addOnCompleteListener {
                                if (enhanced !== original) enhanced.recycle()
                                original.recycle()
                                photo.delete()
                            }
                    } catch (e: Exception) { photo.delete(); toast("Image processing failed: ${e.message}") }
                }
                override fun onError(exception: ImageCaptureException) { photo.delete(); toast("Capture failed: ${exception.message}") }
            })
    }

    private fun enhanceForOcr(source: Bitmap): Bitmap {
        val w = source.width; val h = source.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val gray = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
            val adjusted = ((gray - 128f) * 1.25f + 128f).toInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(adjusted, adjusted, adjusted)
        }
        output.setPixels(pixels, 0, w, 0, 0, w, h)
        return output
    }

    private fun parseRecognizedText(raw: String): Pair<String, List<String>> {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
            .filterNot { it.matches(Regex("(?i)^(submit|switch question|assignment|real|no negative marking)$")) }
        val qEnd = lines.indexOfFirst { it.contains("?") }.let { if (it >= 0) it else 0 }
        val question = lines.take(qEnd + 1).joinToString(" ")
        val choices = lines.drop(qEnd + 1).map {
            it.replace(Regex("^[○◯●•©®™|+]+\\s*"), "")
                .replace(Regex("^(?:O|0|©|®)\\s+(?=[A-Z])"), "").trim()
        }.filter { it.length > 1 && !it.contains("submit", true) }.take(8)
        return question to choices
    }

    private fun solveOnWorker() {
        val profile = currentProfile() ?: return toast("Enter a valid profile URL and token, then save it.")
        val question = questionEdit.text.toString().trim()
        val options = optionsEdit.text.toString().lines().map { it.trim() }.filter { it.isNotBlank() }
        if (question.isBlank() || options.size < 2) return toast("Need a question and at least two answer options.")
        resultText.text = "Sending question to ${profile.name}…"
        sendRequest(profile, question, options) { success, message ->
            resultText.text = if (success) "Answer: $message" else "Request failed: $message"
            if (success && message.isNotBlank()) tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "web2app-answer")
        }
    }

    private fun sendRequest(profile: ApiProfile, question: String, options: List<String>, callback: (Boolean, String) -> Unit) {
        val payload = JSONObject().put("question", question).put("options", JSONArray(options))
        Thread {
            try {
                val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder().url(profile.url)
                    .header("Authorization", "Bearer ${profile.token}")
                    .post(body).build()
                http.newCall(request).execute().use { response ->
                    val bodyText = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${bodyText.take(250)}")
                    val json = JSONObject(bodyText)
                    if (json.has("success") && !json.optBoolean("success")) throw Exception(json.optString("error", bodyText))
                    val answer = json.optString("answer", "")
                    if (answer.isBlank()) throw Exception("Worker response has no 'answer' field: ${bodyText.take(250)}")
                    runOnUiThread { callback(true, answer) }
                }
            } catch (e: Exception) { runOnUiThread { callback(false, e.localizedMessage ?: "Unknown error") } }
        }.start()
    }

    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts.language = Locale.US }
    private fun toast(message: String) = runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.close()
        if (::tts.isInitialized) tts.shutdown()
    }
}

/** AES/GCM encrypted profile storage. The AES key is generated and retained by Android Keystore. */
private class EncryptedProfileStore(private val activity: ComponentActivity) {
    private val prefs = activity.getSharedPreferences("web2app_secure_profiles", MODE_PRIVATE)
    private val alias = "web2app_profiles_aes_key"

    fun writeProfiles(profiles: List<ApiProfile>) {
        val array = JSONArray()
        profiles.forEach { array.put(JSONObject().put("name", it.name).put("url", it.url).put("token", it.token)) }
        val encrypted = encrypt(array.toString())
        prefs.edit().putString("profiles", encrypted).apply()
    }

    fun readProfiles(): List<ApiProfile> {
        val value = prefs.getString("profiles", null) ?: return emptyList()
        return try {
            val array = JSONArray(decrypt(value))
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                ApiProfile(item.getString("name"), item.getString("url"), item.getString("token"))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun getKey(): SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = packed.copyOfRange(0, 12)
        val ciphertext = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    companion object { private const val MODE_PRIVATE = 0 }
}
