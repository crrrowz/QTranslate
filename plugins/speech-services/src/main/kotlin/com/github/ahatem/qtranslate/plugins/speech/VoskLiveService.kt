package com.github.ahatem.qtranslate.plugins.speech

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceMetadata
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent
import com.github.ahatem.qtranslate.api.speech.LiveSpeechRecognizer
import com.github.ahatem.qtranslate.api.speech.LiveSpeechSession
import com.github.ahatem.qtranslate.api.speech.RecognizedWord
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Live transcription through a Vosk server over WebSocket — the protocol of the Vosk STT
 * extension's `vosk_server.py`: `{"action":"configure","lang":..,"sampleRate":..}`, then raw PCM
 * frames, answered with `partial` and `result` messages; `{"action":"stop"}` flushes the last words.
 *
 * Runs entirely on the user's own machine or network, and answers in a fraction of a second,
 * which is why it pairs well with a slower, more accurate recognizer in hybrid mode.
 *
 * Off until a server URL is set: a live recognizer is an optional extra, and trying to reach a
 * server nobody runs would only produce errors.
 */
class VoskLiveService(private val settings: () -> SpeechSettings) : LiveSpeechRecognizer {
    override val metadata = ServiceMetadata()
    override val key: String = "vosk-live"
    override val name: String = "Vosk (live)"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/speech.svg"
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.Dynamic

    override suspend fun openSession(language: LanguageCode): Result<LiveSpeechSession, ServiceError> {
        val current = settings()
        val url = current.liveServerUrl.trim()
        if (url.isEmpty()) {
            return Err(ServiceError.ConfigurationError("No live (Vosk) server is set in Settings > Plugins > Speech Recognition."))
        }
        val lang = SpeechClient.primaryTag(language) ?: current.liveAutoLanguage.trim().ifEmpty { "en" }
        return VoskSession.open(url, lang)
    }

    /** Answers whether the server is there and which models it has, without recognising anything. */
    override suspend fun validate(): Result<Unit, ServiceError> {
        val url = settings().liveServerUrl.trim()
        if (url.isEmpty()) return Ok(Unit)
        return VoskSession.ping(url)
    }
}

/** One connection to a Vosk server. */
internal class VoskSession private constructor() : LiveSpeechSession {
    private val channel = Channel<LiveSpeechEvent>(Channel.UNLIMITED)
    override val events: Flow<LiveSpeechEvent> = channel.receiveAsFlow()

    private var socket: WebSocket? = null
    private val ready = CompletableDeferred<Result<Unit, ServiceError>>()
    private val stopped = CompletableDeferred<Unit>()
    private val pong = CompletableDeferred<List<String>>()

    // The JDK socket refuses a send while the previous one is still in flight; sends are chained.
    private var sendChain: CompletableFuture<*> = CompletableFuture.completedFuture(null)

    @Volatile
    private var ended = false

    override fun sendAudio(pcm: ByteArray) {
        if (ended || pcm.isEmpty()) return
        enqueue { it.sendBinary(ByteBuffer.wrap(pcm), true) }
    }

    override suspend fun finish() {
        if (ended) return
        enqueue { it.sendText("""{"action":"stop"}""", true) }
        withTimeoutOrNull(FINISH_TIMEOUT_MILLIS) { stopped.await() }
        end(failure = null)
        runCatching { socket?.sendClose(WebSocket.NORMAL_CLOSURE, "")?.await() }
    }

    override fun cancel() {
        end(failure = null)
        runCatching { socket?.abort() }
    }

    private fun enqueue(send: (WebSocket) -> CompletableFuture<WebSocket>) {
        val ws = socket ?: return
        synchronized(this) {
            sendChain = sendChain.thenCompose { send(ws) }.exceptionally { error ->
                end(ServiceError.NetworkError("Live server connection lost: ${error.message}", error))
                null
            }
        }
    }

    private fun end(failure: ServiceError?) {
        if (ended) return
        ended = true
        failure?.let { channel.trySend(LiveSpeechEvent.Failed(it)) }
        channel.close()
        stopped.complete(Unit)
    }

    private fun handle(text: String) {
        when (val message = parseVoskMessage(text)) {
            is VoskMessage.Ready -> ready.complete(Ok(Unit))
            is VoskMessage.Error -> {
                val error = ServiceError.ServiceUnavailableError("Vosk server: ${message.text}")
                if (!ready.complete(Err(error))) end(error)
            }
            is VoskMessage.Partial -> channel.trySend(LiveSpeechEvent.Partial(message.text))
            is VoskMessage.Result -> channel.trySend(LiveSpeechEvent.Final(message.text, message.words))
            is VoskMessage.Stopped -> stopped.complete(Unit)
            is VoskMessage.Pong -> pong.complete(message.models)
            VoskMessage.Other -> Unit
        }
    }

    private val listener = object : WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                val message = buffer.toString()
                buffer.setLength(0)
                runCatching { handle(message) }
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            val error = ServiceError.NetworkError("Live server closed the connection ($statusCode${if (reason.isNotEmpty()) ": $reason" else ""}).")
            ready.complete(Err(error))
            end(if (ended) null else error)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            val failure = ServiceError.NetworkError("Live server connection failed: ${error.message}", error)
            ready.complete(Err(failure))
            end(failure)
        }
    }

    private suspend fun connect(url: String): Result<Unit, ServiceError> {
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.scheme !in setOf("ws", "wss")) {
            return Err(ServiceError.ConfigurationError("The live server URL must start with ws:// or wss://, was: $url"))
        }
        return try {
            socket = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                .buildAsync(uri, listener).await()
            Ok(Unit)
        } catch (e: Exception) {
            Err(ServiceError.NetworkError("Could not reach the live server at $url: ${e.cause?.message ?: e.message}", e))
        }
    }

    companion object {
        private const val READY_TIMEOUT_MILLIS = 30_000L // the first load of a large model is slow
        private const val FINISH_TIMEOUT_MILLIS = 3_000L
        private const val PING_TIMEOUT_MILLIS = 5_000L
        const val SAMPLE_RATE = 16_000

        suspend fun open(url: String, lang: String): Result<LiveSpeechSession, ServiceError> {
            val session = VoskSession()
            session.connect(url).getError()?.let { return Err(it) }
            session.enqueue { it.sendText("""{"action":"configure","lang":"$lang","sampleRate":$SAMPLE_RATE}""", true) }
            val outcome = withTimeoutOrNull(READY_TIMEOUT_MILLIS) { session.ready.await() }
                ?: Err(ServiceError.TimeoutError("The live server did not load a '$lang' model in time."))
            outcome.getError()?.let { error ->
                session.cancel()
                return Err(error)
            }
            return Ok(session)
        }

        suspend fun ping(url: String): Result<Unit, ServiceError> {
            val session = VoskSession()
            session.connect(url).getError()?.let { return Err(it) }
            session.enqueue { it.sendText("""{"action":"ping"}""", true) }
            val models = withTimeoutOrNull(PING_TIMEOUT_MILLIS) { session.pong.await() }
            session.cancel()
            return if (models != null) Ok(Unit)
            else Err(ServiceError.TimeoutError("The live server at $url did not answer."))
        }
    }
}

internal sealed interface VoskMessage {
    data object Ready : VoskMessage
    data class Error(val text: String) : VoskMessage
    data class Partial(val text: String) : VoskMessage
    data class Result(val text: String, val words: List<RecognizedWord>) : VoskMessage
    data object Stopped : VoskMessage
    data class Pong(val models: List<String>) : VoskMessage
    data object Other : VoskMessage
}

private val voskJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Reads one message from the server. A `result` may carry Vosk's word list (`"result": [{word,
 * start, end, conf}]`) when the server enables word times; its absence is normal.
 */
internal fun parseVoskMessage(text: String): VoskMessage {
    val json = runCatching { voskJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return VoskMessage.Other
    fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    return when (json.string("type")) {
        "status" -> when (json.string("status")) {
            "ready" -> VoskMessage.Ready
            "error" -> VoskMessage.Error(json.string("msg"))
            "stopped" -> VoskMessage.Stopped
            "pong" -> VoskMessage.Pong(runCatching { json["models"]!!.jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(emptyList()))
            else -> VoskMessage.Other
        }
        "partial" -> VoskMessage.Partial(json.string("text").trim())
        "result" -> VoskMessage.Result(
            json.string("text").trim(),
            runCatching {
                json["result"]!!.jsonArray.mapNotNull { item ->
                    val word = item.jsonObject
                    val start = word["start"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    val end = word["end"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    RecognizedWord(word.string("word"), (start * 1000).toLong(), (end * 1000).toLong())
                }
            }.getOrDefault(emptyList())
        )
        else -> VoskMessage.Other
    }
}
