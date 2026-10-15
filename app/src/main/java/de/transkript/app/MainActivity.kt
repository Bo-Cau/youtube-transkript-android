package de.transkript.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var urlInput: EditText
    private lateinit var langInput: EditText
    private lateinit var timestampBox: CheckBox
    private lateinit var pasteButton: Button
    private lateinit var transcribeButton: Button
    private lateinit var saveButton: Button
    private lateinit var shareButton: Button
    private lateinit var copyButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private var lastResult: TranscriptResult? = null
    private var documentText: String = ""
    private var documentTitle: String = ""
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        langInput = findViewById(R.id.langInput)
        timestampBox = findViewById(R.id.timestampBox)
        pasteButton = findViewById(R.id.pasteButton)
        transcribeButton = findViewById(R.id.transcribeButton)
        saveButton = findViewById(R.id.saveButton)
        shareButton = findViewById(R.id.shareButton)
        copyButton = findViewById(R.id.copyButton)
        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.statusText)
        resultText = findViewById(R.id.resultText)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        langInput.setText(prefs.getString(KEY_LANGUAGES, DEFAULT_LANGUAGES) ?: DEFAULT_LANGUAGES)
        timestampBox.isChecked = prefs.getBoolean(KEY_TIMESTAMPS, false)

        pasteButton.setOnClickListener { pasteFromClipboard() }
        transcribeButton.setOnClickListener { startTranscription() }
        saveButton.setOnClickListener { saveAsFile() }
        shareButton.setOnClickListener { shareText() }
        copyButton.setOnClickListener { copyText() }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                startTranscription()
                true
            } else {
                false
            }
        }
        timestampBox.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_TIMESTAMPS, checked)
                .apply()
            lastResult?.let { showResult(it) }
        }

        if (savedInstanceState != null) {
            documentText = savedInstanceState.getString(STATE_TEXT, "") ?: ""
            documentTitle = savedInstanceState.getString(STATE_TITLE, "") ?: ""
            resultText.text = documentText
            statusText.text = savedInstanceState.getString(STATE_STATUS, "") ?: ""
        }
        setResultButtonsEnabled(documentText.isNotEmpty())

        if (savedInstanceState == null) handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TEXT, documentText)
        outState.putString(STATE_TITLE, documentTitle)
        outState.putString(STATE_STATUS, statusText.text.toString())
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    /** Link, der über "Teilen" aus der YouTube-App kommt. */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND) return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        urlInput.setText(findUrl(shared) ?: shared.trim())
        startTranscription()
    }

    // ------------------------------------------------------------------ Transkribieren

    private fun startTranscription() {
        if (busy) return
        val input = urlInput.text.toString()
        val videoId = YouTubeTranscripts.extractVideoId(input)
        if (videoId == null) {
            toast(getString(R.string.error_no_link))
            return
        }
        val languagesText = langInput.text.toString().trim()
        val languages = TranscriptFormatter.parseLanguages(languagesText)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_LANGUAGES, languagesText.ifEmpty { DEFAULT_LANGUAGES })
            .apply()

        hideKeyboard()
        setBusy(true)
        lastResult = null
        documentText = ""
        documentTitle = ""
        resultText.text = ""
        setResultButtonsEnabled(false)
        statusText.text = getString(R.string.status_loading)

        executor.execute {
            val outcome = runCatching { YouTubeTranscripts.fetch(videoId, languages) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setBusy(false)
                outcome.onSuccess { showResult(it) }
                outcome.onFailure { showError(it) }
            }
        }
    }

    private fun showResult(result: TranscriptResult) {
        lastResult = result
        documentTitle = result.title
        documentText = TranscriptFormatter.document(result, timestampBox.isChecked)
        resultText.text = documentText

        val words = TranscriptFormatter.wordCount(TranscriptFormatter.body(result.segments, false))
        val kind = if (result.track.isGenerated) getString(R.string.kind_generated) else getString(R.string.kind_manual)
        statusText.text = getString(R.string.status_done, words, result.track.name, kind)
        setResultButtonsEnabled(true)
    }

    private fun showError(error: Throwable) {
        val message = when (error) {
            is TranscriptException -> error.message ?: getString(R.string.error_unknown)
            is UnknownHostException -> getString(R.string.error_offline)
            is SocketTimeoutException -> getString(R.string.error_timeout)
            is IOException -> getString(R.string.error_network, error.message ?: "")
            else -> getString(R.string.error_unexpected, error.javaClass.simpleName, error.message ?: "")
        }
        statusText.text = getString(R.string.status_failed)
        AlertDialog.Builder(this)
            .setTitle(R.string.status_failed)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ------------------------------------------------------------------ Ergebnis weiterverwenden

    private fun saveAsFile() {
        if (documentText.isEmpty()) return
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, TranscriptFormatter.fileName(documentTitle))
        }
        try {
            startActivityForResult(intent, REQUEST_SAVE)
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.error_no_file_app))
        }
    }

    @Deprecated("Activity-API, für diese einfache App ausreichend")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SAVE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        writeToUri(uri, documentText)
    }

    private fun writeToUri(uri: Uri, text: String) {
        executor.execute {
            val outcome = runCatching {
                val stream = contentResolver.openOutputStream(uri, "w")
                    ?: throw IOException("Datei konnte nicht geöffnet werden")
                stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (outcome.isSuccess) {
                    toast(getString(R.string.saved))
                } else {
                    toast(getString(R.string.error_save, outcome.exceptionOrNull()?.message ?: ""))
                }
            }
        }
    }

    private fun shareText() {
        if (documentText.isEmpty()) return
        if (documentText.length > MAX_SHARE_CHARS) {
            toast(getString(R.string.error_too_long_to_share))
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, documentTitle)
            putExtra(Intent.EXTRA_TEXT, documentText)
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_title)))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.error_no_share_app))
        }
    }

    private fun copyText() {
        if (documentText.isEmpty()) return
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), documentText))
        // Ab Android 13 zeigt das System selbst eine Bestätigung an
        if (Build.VERSION.SDK_INT < 33) toast(getString(R.string.copied))
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = clipboard?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0)?.coerceToText(this)?.toString()
        } else {
            null
        }
        if (text.isNullOrBlank()) {
            toast(getString(R.string.clipboard_empty))
            return
        }
        urlInput.setText(findUrl(text) ?: text.trim())
        urlInput.setSelection(urlInput.text.length)
    }

    // ------------------------------------------------------------------ Hilfen

    private fun setBusy(value: Boolean) {
        busy = value
        progress.visibility = if (value) View.VISIBLE else View.GONE
        transcribeButton.isEnabled = !value
        pasteButton.isEnabled = !value
    }

    private fun setResultButtonsEnabled(enabled: Boolean) {
        saveButton.isEnabled = enabled
        shareButton.isEnabled = enabled
        copyButton.isEnabled = enabled
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(urlInput.windowToken, 0)
    }

    private fun findUrl(text: String): String? = URL_IN_TEXT.find(text)?.value

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val PREFS_NAME = "einstellungen"
        private const val KEY_LANGUAGES = "sprachen"
        private const val KEY_TIMESTAMPS = "zeitstempel"
        private const val DEFAULT_LANGUAGES = "de, en, fr, es, it, pt"

        private const val STATE_TEXT = "text"
        private const val STATE_TITLE = "titel"
        private const val STATE_STATUS = "status"

        private const val REQUEST_SAVE = 1
        private const val MAX_SHARE_CHARS = 400_000

        private val URL_IN_TEXT = Regex("https?://\\S+")
    }
}
