package com.bookgpt.android.domain.tts

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

data class TtsVoiceOption(
    val name: String,
    val label: String,
    val localeTag: String,
    val quality: Int,
    val requiresNetwork: Boolean,
)

@Singleton
class BookTtsController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var tts: TextToSpeech? = null
    private val ready = AtomicBoolean(false)
    private var speechRate = 1f
    private var onUtteranceDone: ((String) -> Unit)? = null
    private var onUtteranceError: ((String) -> Unit)? = null
    private var onEngineReady: ((Boolean) -> Unit)? = null
    private var preferredVoiceName: String? = null

    /** When true, completion callbacks from stop()/flush are ignored. */
    private val suppressCompletion = AtomicBoolean(false)
    private val activeBaseUtteranceId = AtomicReference<String?>(null)
    private val pendingParts = AtomicInteger(0)

    fun initialize(onReady: (Boolean) -> Unit) {
        onEngineReady = onReady
        if (tts != null) {
            onReady(ready.get())
            return
        }
        val listener = TextToSpeech.OnInitListener { status ->
            val ok = status == TextToSpeech.SUCCESS
            ready.set(ok)
            if (ok) {
                configureEngine()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        dispatchPartDone(utteranceId)
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        if (interrupted) {
                            suppressCompletion.set(true)
                            pendingParts.set(0)
                            activeBaseUtteranceId.set(null)
                            return
                        }
                        dispatchPartDone(utteranceId)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        dispatchError(utteranceId)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        dispatchError(utteranceId)
                    }
                })
            }
            onEngineReady?.invoke(ok)
        }

        // Prefer Google's engine — its neural/network voices sound far more natural.
        tts = if (isPackageInstalled(GOOGLE_TTS_PACKAGE)) {
            TextToSpeech(context, listener, GOOGLE_TTS_PACKAGE)
        } else {
            TextToSpeech(context, listener)
        }
    }

    fun setListeners(
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        onUtteranceDone = onDone
        onUtteranceError = onError
    }

    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.5f, 2f)
        tts?.setSpeechRate(speechRate)
    }

    fun listVoices(): List<TtsVoiceOption> {
        val engine = tts ?: return emptyList()
        if (!ready.get()) return emptyList()
        val locale = preferredLocale()
        return engine.voices.orEmpty()
            .asSequence()
            .filter { voice ->
                voice.locale.language.equals(locale.language, ignoreCase = true)
            }
            .map { voice ->
                TtsVoiceOption(
                    name = voice.name,
                    label = voiceLabel(voice),
                    localeTag = voice.locale.toLanguageTag(),
                    quality = voice.quality,
                    requiresNetwork = voice.isNetworkConnectionRequired,
                )
            }
            .sortedWith(
                compareByDescending<TtsVoiceOption> { it.requiresNetwork }
                    .thenByDescending { it.quality }
                    .thenBy { it.label },
            )
            .toList()
    }

    fun currentVoiceName(): String? = tts?.voice?.name ?: preferredVoiceName

    fun setPreferredVoice(voiceName: String?): Boolean {
        preferredVoiceName = voiceName
        val engine = tts ?: return false
        if (!ready.get()) return false
        if (voiceName.isNullOrBlank()) {
            return selectBestVoice()
        }
        val match = engine.voices?.firstOrNull { it.name == voiceName } ?: return false
        val result = engine.setVoice(match)
        return result == TextToSpeech.SUCCESS
    }

    fun speak(text: String, utteranceId: String): Boolean {
        val engine = tts ?: return false
        if (!ready.get()) return false
        val parts = splitForSpeech(text)
        if (parts.isEmpty()) return false

        suppressCompletion.set(false)
        activeBaseUtteranceId.set(utteranceId)
        pendingParts.set(parts.size)

        var queued = 0
        for ((index, part) in parts.withIndex()) {
            val partId = "$utteranceId#$index"
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val params = Bundle()
            // Hint network synthesis when the selected voice supports it.
            if (engine.voice?.isNetworkConnectionRequired == true) {
                params.putString(
                    TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS,
                    "true",
                )
            }
            val result = engine.speak(part, mode, params, partId)
            if (result != TextToSpeech.SUCCESS) {
                if (queued == 0) {
                    activeBaseUtteranceId.compareAndSet(utteranceId, null)
                    pendingParts.set(0)
                    return false
                }
                pendingParts.addAndGet(-(parts.size - index))
                break
            }
            queued += 1
        }
        return queued > 0
    }

    fun stop() {
        suppressCompletion.set(true)
        pendingParts.set(0)
        activeBaseUtteranceId.set(null)
        tts?.stop()
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    fun shutdown() {
        onUtteranceDone = null
        onUtteranceError = null
        onEngineReady = null
        suppressCompletion.set(true)
        pendingParts.set(0)
        activeBaseUtteranceId.set(null)
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready.set(false)
    }

    private fun configureEngine() {
        val engine = tts ?: return
        engine.setSpeechRate(speechRate)
        engine.setPitch(1.0f)
        val locale = preferredLocale()
        val langResult = engine.setLanguage(locale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.language = Locale.US
        }
        if (!preferredVoiceName.isNullOrBlank()) {
            if (!setPreferredVoice(preferredVoiceName)) {
                selectBestVoice()
            }
        } else {
            selectBestVoice()
        }
    }

    private fun selectBestVoice(): Boolean {
        val engine = tts ?: return false
        val locale = preferredLocale()
        val best = engine.voices.orEmpty()
            .asSequence()
            .filter { it.locale.language.equals(locale.language, ignoreCase = true) }
            .sortedWith(
                compareByDescending<Voice> { scoreVoice(it) }
                    .thenByDescending { it.quality }
                    .thenBy { it.name },
            )
            .firstOrNull()
            ?: engine.voices?.maxByOrNull { it.quality }
        if (best == null) return false
        preferredVoiceName = best.name
        return engine.setVoice(best) == TextToSpeech.SUCCESS
    }

    private fun scoreVoice(voice: Voice): Int {
        var score = voice.quality
        // Neural / network Google voices are usually the most natural.
        if (voice.isNetworkConnectionRequired) score += 1_000
        val name = voice.name.lowercase()
        when {
            "studio" in name -> score += 800
            "neural" in name || "wavenet" in name || "journey" in name -> score += 700
            "network" in name || name.endsWith("-n") -> score += 500
            "local" in name || "compact" in name || "lite" in name -> score -= 300
        }
        if (voice.locale.country.equals(preferredLocale().country, ignoreCase = true)) {
            score += 50
        }
        if (voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true) {
            score -= 2_000
        }
        return score
    }

    private fun voiceLabel(voice: Voice): String {
        val localeLabel = voice.locale.getDisplayName(preferredLocale())
        val kind = when {
            voice.isNetworkConnectionRequired -> "Natural"
            "studio" in voice.name.lowercase() -> "Studio"
            voice.quality >= Voice.QUALITY_VERY_HIGH -> "High quality"
            voice.quality >= Voice.QUALITY_HIGH -> "High quality"
            else -> "On-device"
        }
        val shortName = voice.name
            .substringAfterLast('/')
            .substringAfterLast('.')
            .replace('_', ' ')
        return "$localeLabel · $kind · $shortName"
    }

    private fun preferredLocale(): Locale {
        val default = Locale.getDefault()
        return if (default.language.isNullOrBlank()) Locale.US else default
    }

    private fun splitForSpeech(text: String): List<String> {
        val cleaned = text
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (cleaned.isEmpty()) return emptyList()
        // Shorter clauses give TTS better pacing than one long monologue.
        val rough = cleaned.split(Regex("""(?<=[.!?…])\s+|(?<=[:;])\s+|\n+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (rough.isEmpty()) return listOf(cleaned)

        val parts = mutableListOf<String>()
        val buffer = StringBuilder()
        for (sentence in rough) {
            if (sentence.length > MAX_PART_CHARS) {
                if (buffer.isNotEmpty()) {
                    parts += buffer.toString().trim()
                    buffer.clear()
                }
                parts += chunkLongSentence(sentence)
                continue
            }
            if (buffer.isNotEmpty() && buffer.length + 1 + sentence.length > MAX_PART_CHARS) {
                parts += buffer.toString().trim()
                buffer.clear()
            }
            if (buffer.isNotEmpty()) buffer.append(' ')
            buffer.append(sentence)
        }
        if (buffer.isNotEmpty()) {
            parts += buffer.toString().trim()
        }
        return parts.filter { it.isNotEmpty() }.ifEmpty { listOf(cleaned) }
    }

    private fun chunkLongSentence(sentence: String): List<String> {
        val words = sentence.split(Regex("""\s+"""))
        val parts = mutableListOf<String>()
        val buffer = StringBuilder()
        for (word in words) {
            if (buffer.isNotEmpty() && buffer.length + 1 + word.length > MAX_PART_CHARS) {
                parts += buffer.toString()
                buffer.clear()
            }
            if (buffer.isNotEmpty()) buffer.append(' ')
            buffer.append(word)
        }
        if (buffer.isNotEmpty()) parts += buffer.toString()
        return parts
    }

    private fun dispatchPartDone(utteranceId: String?) {
        if (utteranceId == null) return
        if (suppressCompletion.get()) return
        val base = utteranceId.substringBefore('#')
        if (activeBaseUtteranceId.get() != base) return
        val left = pendingParts.decrementAndGet()
        if (left > 0) return
        if (left < 0) {
            pendingParts.set(0)
            return
        }
        activeBaseUtteranceId.compareAndSet(base, null)
        onUtteranceDone?.invoke(base)
    }

    private fun dispatchError(utteranceId: String?) {
        if (utteranceId == null) return
        if (suppressCompletion.get()) return
        val base = utteranceId.substringBefore('#')
        if (activeBaseUtteranceId.get() != base) return
        pendingParts.set(0)
        activeBaseUtteranceId.compareAndSet(base, null)
        onUtteranceError?.invoke(base)
    }

    private fun isPackageInstalled(packageName: String): Boolean =
        try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    companion object {
        private const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
        private const val MAX_PART_CHARS = 280
    }
}
