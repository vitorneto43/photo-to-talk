package com.photototalk.app

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var preview: PreviewView
    private lateinit var photo: ImageView
    private lateinit var textView: TextView
    private lateinit var status: TextView
    private lateinit var scriptSpinner: Spinner
    private lateinit var languageSpinner: Spinner
    private lateinit var db: HistoryDb
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var capture: ImageCapture? = null
    private var readingChunks = listOf<String>()
    private var index = 0
    private var paused = false
    private var stopped = true
    private var generation = 0
    private var working = false
    private var currentPhoto = ""
    private val scripts = arrayOf("Latin", "Chinese", "Japanese", "Korean", "Devanagari")
    private val languages = arrayOf("Device language", "English", "Português", "Español", "Français", "Deutsch", "Italiano", "中文", "日本語", "한국어", "हिन्दी")
    private val languageTags = arrayOf("", "en-US", "pt-BR", "es-ES", "fr-FR", "de-DE", "it-IT", "zh-CN", "ja-JP", "ko-KR", "hi-IN")

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else setStatus("Camera permission denied. You can import a photo.")
    }
    private val importPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val file = newPhotoFile()
            try {
                contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                    ?: throw IllegalStateException("Unable to open image")
                recognize(file)
            } catch (e: Exception) { file.delete(); setStatus("Unable to import image") }
        }
    }
    private val voiceInput = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val phrase = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (phrase != null) handleVoice(phrase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.previewView)
        photo = findViewById(R.id.photoPreview)
        textView = findViewById(R.id.recognizedText)
        status = findViewById(R.id.statusText)
        scriptSpinner = findViewById(R.id.scriptSpinner)
        languageSpinner = findViewById(R.id.languageSpinner)
        scriptSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, scripts)
        languageSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages)
        db = HistoryDb(this)
        tts = TextToSpeech(this, this)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                val token = utteranceId?.substringBefore('_')?.toIntOrNull() ?: return
                runOnUiThread {
                    if (token == generation && !paused && !stopped) { index++; speakChunk() }
                }
            }
            override fun onError(utteranceId: String?) { runOnUiThread { setStatus("Speech engine error") } }
        })
        findViewById<Button>(R.id.captureButton).setOnClickListener { takePhoto() }
        findViewById<Button>(R.id.importButton).setOnClickListener { importPhoto.launch("image/*") }
        findViewById<Button>(R.id.playButton).setOnClickListener { if (paused) { paused = false; stopped = false; speakChunk() } else startReading() }
        findViewById<Button>(R.id.pauseButton).setOnClickListener { if (!stopped) { paused = true; generation++; tts.stop(); setStatus("Paused") } }
        findViewById<Button>(R.id.stopButton).setOnClickListener { stopReading(); setStatus("Stopped") }
        findViewById<Button>(R.id.repeatButton).setOnClickListener { startReading() }
        findViewById<Button>(R.id.previousButton).setOnClickListener { moveChunk(-1) }
        findViewById<Button>(R.id.nextButton).setOnClickListener { moveChunk(1) }
        findViewById<Button>(R.id.historyButton).setOnClickListener { showHistory() }
        findViewById<Button>(R.id.voiceButton).setOnClickListener { listenForCommand() }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun setStatus(message: String) { status.text = message }
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, capture)
                setStatus("Camera ready")
            } catch (e: Exception) { setStatus("Camera unavailable. Use Import Photo.") }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun newPhotoFile(): File {
        val dir = File(filesDir, "photos").apply { mkdirs() }
        return File.createTempFile("scan_", ".jpg", dir)
    }
    private fun takePhoto() {
        if (working) return
        val camera = capture ?: run { setStatus("Camera not ready"); return }
        val file = newPhotoFile()
        working = true
        setStatus("Taking photo...")
        camera.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) { working = false; recognize(file) }
            override fun onError(exception: ImageCaptureException) { working = false; file.delete(); setStatus("Unable to take photo") }
        })
    }
    private fun createRecognizer(script: String): TextRecognizer = when (script) {
        "Chinese" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        "Japanese" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        "Korean" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        "Devanagari" -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private fun recognize(file: File) {
        if (working) return
        working = true
        stopReading()
        val script = scripts[scriptSpinner.selectedItemPosition]
        val recognizer = createRecognizer(script)
        setStatus("Recognizing ($script)...")
        try {
            val input = InputImage.fromFilePath(this, Uri.fromFile(file))
            recognizer.process(input)
                .addOnSuccessListener { result ->
                    val found = result.text.trim()
                    if (found.isBlank()) {
                        file.delete()
                        setStatus("No text found. Try again or choose another script.")
                    } else {
                        try {
                            db.add(found, file.absolutePath, script)
                            currentPhoto = file.absolutePath
                            textView.text = found
                            photo.setImageURI(null)
                            photo.setImageURI(Uri.fromFile(file))
                            photo.visibility = View.VISIBLE
                            setStatus("Saved to history • $script")
                            startReading()
                        } catch (e: Exception) { file.delete(); setStatus("Unable to save scan") }
                    }
                }
                .addOnFailureListener { file.delete(); setStatus("Text recognition failed") }
                .addOnCompleteListener { recognizer.close(); working = false }
        } catch (e: Exception) { recognizer.close(); file.delete(); working = false; setStatus("Invalid image") }
    }
    private fun stopReading() { generation++; tts.stop(); stopped = true; paused = false; index = 0 }
    private fun startReading() {
        val content = textView.text.toString().trim()
        if (content.isBlank()) { setStatus("Take or import a photo first"); return }
        if (!ttsReady) { setStatus("Speech engine not ready or language unavailable"); return }
        stopReading()
        val tag = languageTags[languageSpinner.selectedItemPosition]
        val locale = if (tag.isEmpty()) Locale.getDefault() else Locale.forLanguageTag(tag)
        val languageResult = tts.setLanguage(locale)
        if (languageResult == TextToSpeech.LANG_NOT_SUPPORTED || languageResult == TextToSpeech.LANG_MISSING_DATA) {
            setStatus("Voice unavailable for ${locale.displayLanguage}. Install a voice in Android settings.")
            return
        }
        readingChunks = content.split(Regex("(?<=[.!?。！？])\\s+|\\n+"))
            .flatMap { it.chunked(220) }.filter { it.isNotBlank() }
        index = 0; stopped = false; paused = false; speakChunk()
    }
    private fun speakChunk() {
        if (stopped || paused || !ttsReady) return
        if (index >= readingChunks.size) { stopped = true; setStatus("Reading complete"); return }
        setStatus("Reading ${index + 1} / ${readingChunks.size}")
        val result = tts.speak(readingChunks[index], TextToSpeech.QUEUE_FLUSH, null, "${generation}_$index")
        if (result == TextToSpeech.ERROR) { stopped = true; setStatus("Unable to start speech") }
    }
    private fun moveChunk(offset: Int) {
        if (readingChunks.isEmpty()) { startReading(); return }
        generation++; tts.stop()
        index = (index + offset).coerceIn(0, readingChunks.lastIndex)
        paused = false; stopped = false; speakChunk()
    }
    private fun showHistory() {
        val items = db.all()
        if (items.isEmpty()) { setStatus("History is empty"); return }
        val labels = items.map {
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.createdAt))
            "$date • ${it.script}\n${it.text.replace('\n', ' ').take(75)}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Saved scans").setItems(labels) { _, which ->
            val item = items[which]
            stopReading()
            textView.text = item.text
            currentPhoto = item.photoPath
            scriptSpinner.setSelection(scripts.indexOf(item.script).coerceAtLeast(0))
            val file = File(item.photoPath)
            photo.visibility = if (file.exists()) View.VISIBLE else View.GONE
            if (file.exists()) { photo.setImageURI(null); photo.setImageURI(Uri.fromFile(file)) }
            setStatus("Loaded from history")
            AlertDialog.Builder(this).setMessage("Open scan or delete it?")
                .setPositiveButton("Read") { _, _ -> startReading() }
                .setNegativeButton("Keep") { _, _ -> }
                .setNeutralButton("Delete") { _, _ -> confirmDelete(item) }.show()
        }.setNegativeButton("Close", null).show()
    }
    private fun confirmDelete(item: SavedScan) {
        AlertDialog.Builder(this).setTitle("Delete scan?").setMessage("This removes the saved text and photo from this device.")
            .setPositiveButton("Delete") { _, _ ->
                db.delete(item.id)
                File(item.photoPath).delete()
                if (currentPhoto == item.photoPath) { stopReading(); textView.text = ""; photo.visibility = View.GONE; currentPhoto = "" }
                setStatus("Scan deleted")
            }.setNegativeButton("Cancel", null).show()
    }
    private fun listenForCommand() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say: capture, play, pause, stop, repeat, next, previous, history")
        }
        try { voiceInput.launch(intent) }
        catch (e: ActivityNotFoundException) { setStatus("Speech recognition service not installed") }
    }
    private fun handleVoice(raw: String) {
        val command = raw.lowercase(Locale.ROOT).trim()
        when {
            listOf("capture", "photo", "take photo", "fotografar", "tirar foto", "foto", "capturar", "拍照", "写真", "사진", "फोटो").any { command.contains(it) } -> takePhoto()
            listOf("pause", "pausar", "pausa", "pausa", "暂停", "一時停止", "일시정지", "रुको").any { command.contains(it) } -> { if (!stopped) { paused = true; generation++; tts.stop(); setStatus("Paused") } }
            listOf("stop", "parar", "detener", "arrêter", "停止", "중지", "बंद").any { command.contains(it) } -> { stopReading(); setStatus("Stopped") }
            listOf("repeat", "repetir", "repetir", "répéter", "重复", "繰り返し", "반복", "दोहराओ").any { command.contains(it) } -> startReading()
            listOf("next", "próximo", "siguiente", "suivant", "下一个", "次", "다음", "अगला").any { command.contains(it) } -> moveChunk(1)
            listOf("previous", "anterior", "atrás", "précédent", "上一个", "前", "이전", "पिछला").any { command.contains(it) } -> moveChunk(-1)
            listOf("history", "histórico", "historial", "historique", "历史", "履歴", "기록", "इतिहास").any { command.contains(it) } -> showHistory()
            listOf("play", "read", "continue", "ler", "continuar", "leer", "lire", "播放", "読む", "읽어", "पढ़ो").any { command.contains(it) } -> { if (paused) { paused = false; stopped = false; speakChunk() } else startReading() }
            else -> setStatus("Command not recognized: $raw")
        }
    }
    override fun onInit(code: Int) {
        ttsReady = code == TextToSpeech.SUCCESS
        if (!ttsReady) setStatus("Text-to-speech unavailable")
    }
    override fun onStop() { stopReading(); super.onStop() }
    override fun onDestroy() { stopReading(); tts.shutdown(); db.close(); super.onDestroy() }
}
