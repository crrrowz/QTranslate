package com.github.ahatem.qtranslate.plugins.speech

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import com.github.michaelbull.result.getError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.swing.SwingUtilities

/**
 * Metadata for a downloadable Vosk speech recognition model.
 *
 * Hosted on alphacephei.com/vosk/models. Small models (~30-50 MB) run fast with low memory;
 * large models offer higher accuracy at the cost of more disk and memory.
 */
data class VoskModel(
    val name: String,
    val language: String,
    val label: String,
    val sizeBytes: Long,
    val url: String,
    val isLarge: Boolean = false
) {
    val sizeMb: Int get() = (sizeBytes / (1024 * 1024)).toInt()
}

/**
 * Manages local Vosk speech models: listing available models, checking disk installation,
 * downloading archives from alphacephei.com with progress, unzipping, verifying, and deleting.
 *
 * Models are stored inside the plugin's dedicated data directory under `vosk-models/`.
 */
class VoskModelManager(
    private val dataDirectory: () -> File,
    private val downloader: suspend (url: String, targetZip: File, onProgress: ((Long, Long) -> Unit)?) -> Result<Unit, ServiceError> = ::defaultDownload
) {
    val modelsDirectory: File
        get() = File(dataDirectory(), "vosk-models").apply { if (!exists()) mkdirs() }

    fun isInstalled(modelName: String): Boolean {
        val dir = File(modelsDirectory, modelName)
        return dir.isDirectory && (dir.list()?.isNotEmpty() == true)
    }

    fun listInstalledModels(): List<File> {
        val root = modelsDirectory
        if (!root.exists() || !root.isDirectory) return emptyList()
        return root.listFiles { file -> file.isDirectory && file.list()?.isNotEmpty() == true }?.toList() ?: emptyList()
    }

    /**
     * Resolves an installed model directory for [language] (e.g. "en", "ar").
     *
     * If [preferredModelName] is installed, it is returned first. Otherwise returns any installed
     * model matching the language prefix.
     */
    fun findModelForLanguage(language: String, preferredModelName: String? = null): File? {
        val clean = language.trim().lowercase()
        val isAuto = clean == "auto" || clean.isEmpty()
        val lang = if (isAuto) "auto" else clean.substringBefore('-').substringBefore('_').take(2)

        if (!preferredModelName.isNullOrBlank()) {
            val preferredDir = File(modelsDirectory, preferredModelName)
            if (preferredDir.isDirectory && preferredDir.list()?.isNotEmpty() == true) {
                val info = MODELS_BY_NAME[preferredModelName]
                if (info == null || isAuto || info.language == lang) {
                    return preferredDir
                }
            }
        }

        val installed = listInstalledModels()
        if (isAuto) {
            return installed.firstOrNull()
        }

        val matchingInstalled = installed.firstOrNull { dir ->
            val info = MODELS_BY_NAME[dir.name]
            info?.language == lang || dir.name.contains("-$lang-") || dir.name.endsWith("-$lang")
        }
        return matchingInstalled
    }

    /**
     * Downloads [model] archive from alphacephei.com, extracts it into [modelsDirectory],
     * verifies the extracted files, and removes the archive.
     */
    suspend fun downloadModel(
        model: VoskModel,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File, ServiceError> = withContext(Dispatchers.IO) {
        val targetDir = File(modelsDirectory, model.name)
        if (isInstalled(model.name)) {
            return@withContext Ok(targetDir)
        }

        val tempZip = File(modelsDirectory, "${model.name}.zip.tmp")
        try {
            val downloadError = downloader(model.url, tempZip, onProgress).getError()
            if (downloadError != null) {
                tempZip.delete()
                return@withContext Err(downloadError)
            }

            extractZip(tempZip, modelsDirectory)
            tempZip.delete()

            if (!targetDir.isDirectory) {
                // If the archive unzipped into an inner or slightly different folder name
                val found = modelsDirectory.listFiles()?.firstOrNull { it.isDirectory && it.name.startsWith(model.name) }
                if (found != null && found != targetDir) {
                    found.renameTo(targetDir)
                }
            }

            if (!isInstalled(model.name)) {
                return@withContext Err(ServiceError.ValidationError("Extracted model directory is empty or invalid: ${targetDir.name}"))
            }

            Ok(targetDir)
        } catch (e: Exception) {
            tempZip.delete()
            targetDir.deleteRecursively()
            Err(ServiceError.NetworkError("Failed to download or extract model '${model.name}': ${e.message}", e))
        }
    }

    fun deleteModel(modelName: String): Boolean {
        val dir = File(modelsDirectory, modelName)
        return if (dir.exists()) dir.deleteRecursively() else false
    }

    /** Message for the settings "Delete Model" action. */
    fun deleteAction(modelName: String): String {
        val model = MODELS_BY_NAME[modelName]
            ?: return "Unknown model '$modelName'."
        if (!isInstalled(model.name)) {
            return "Model '${model.label}' is not downloaded on this machine."
        }
        val deleted = deleteModel(model.name)
        return if (deleted) {
            "Model '${model.label}' has been successfully deleted from your computer."
        } else {
            "Failed to delete model '${model.label}'. Please check file permissions."
        }
    }

    /** Message for the settings "Check Models" action. */
    fun statusAction(selectedModelName: String? = null): String {
        val installed = listInstalledModels()
        val selected = selectedModelName?.let { MODELS_BY_NAME[it] }
        val sb = StringBuilder()
        if (selected != null) {
            val isInst = isInstalled(selected.name)
            sb.append("Selected model: ${selected.label}\nStatus: ${if (isInst) "✓ Installed and ready to use" else "✗ Not downloaded yet"}\n\n")
        }
        if (installed.isEmpty()) {
            sb.append("No built-in Vosk models downloaded yet.\nChoose a model from the dropdown and click 'Download Model'.")
        } else {
            sb.append("Installed Vosk models (${installed.size}):\n")
            installed.forEach { dir ->
                val info = MODELS_BY_NAME[dir.name]
                val desc = info?.label ?: dir.name
                sb.append("• $desc (${dir.name})\n")
            }
        }
        return sb.toString().trimEnd()
    }

    /**
     * Resumable downloader supporting HTTP Range headers, pausing, resuming, cancellation,
     * transfer speed computation, and estimated time remaining.
     */
    fun downloadModelResumable(
        model: VoskModel,
        controller: VoskDownloadController,
        onProgress: (downloaded: Long, total: Long, speed: Long, etaSeconds: Long) -> Unit,
        onExtracting: () -> Unit
    ): Result<File, ServiceError> {
        val targetDir = File(modelsDirectory, model.name)
        if (isInstalled(model.name)) return Ok(targetDir)

        val partFile = File(modelsDirectory, "${model.name}.zip.part")
        val finalZip = File(modelsDirectory, "${model.name}.zip")

        return try {
            var downloaded = if (partFile.exists()) partFile.length() else 0L
            var totalLength = model.sizeBytes

            while (!controller.isCancelled.get() && (totalLength <= 0 || downloaded < totalLength)) {
                if (controller.isPaused.get()) {
                    Thread.sleep(200)
                    continue
                }

                downloaded = if (partFile.exists()) partFile.length() else 0L
                val (conn, code) = openConnectionWithRedirects(model.url, rangeStart = downloaded)

                val append = (code == HttpURLConnection.HTTP_PARTIAL)
                if (code !in 200..299 && code != HttpURLConnection.HTTP_PARTIAL) {
                    return Err(ServiceError.NetworkError("HTTP $code from server while downloading ${model.name}"))
                }

                val serverContentLength = conn.contentLengthLong
                if (append) {
                    if (serverContentLength > 0) totalLength = downloaded + serverContentLength
                } else {
                    if (serverContentLength > 0) totalLength = serverContentLength
                    downloaded = 0L
                }

                partFile.parentFile?.mkdirs()
                val fos = FileOutputStream(partFile, append)

                conn.inputStream.use { input ->
                    fos.use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        var speedAccumulator = 0L
                        var lastSpeedTime = System.currentTimeMillis()
                        var currentSpeed = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (controller.isCancelled.get()) {
                                partFile.delete()
                                return Err(ServiceError.UnknownError("Download cancelled by user."))
                            }
                            if (controller.isPaused.get()) {
                                break
                            }

                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            speedAccumulator += bytesRead

                            val now = System.currentTimeMillis()
                            val elapsed = now - lastSpeedTime
                            if (elapsed >= 400) {
                                currentSpeed = (speedAccumulator * 1000) / elapsed
                                speedAccumulator = 0L
                                lastSpeedTime = now
                                val remaining = if (totalLength > downloaded) totalLength - downloaded else 0L
                                val eta = if (currentSpeed > 0) remaining / currentSpeed else -1L
                                onProgress(downloaded, totalLength, currentSpeed, eta)
                            }
                        }
                    }
                }

                if (controller.isCancelled.get()) {
                    partFile.delete()
                    return Err(ServiceError.UnknownError("Download cancelled by user."))
                }
            }

            if (controller.isCancelled.get()) {
                partFile.delete()
                return Err(ServiceError.UnknownError("Download cancelled by user."))
            }

            onExtracting()
            if (finalZip.exists()) finalZip.delete()
            if (!partFile.renameTo(finalZip)) {
                partFile.copyTo(finalZip, overwrite = true)
                partFile.delete()
            }

            extractZip(finalZip, modelsDirectory)
            finalZip.delete()

            if (!targetDir.isDirectory) {
                val found = modelsDirectory.listFiles()?.firstOrNull { it.isDirectory && it.name.startsWith(model.name) }
                if (found != null && found != targetDir) {
                    found.renameTo(targetDir)
                }
            }

            if (!isInstalled(model.name)) {
                return Err(ServiceError.ValidationError("Extracted model directory is empty or invalid: ${targetDir.name}"))
            }

            Ok(targetDir)
        } catch (e: Exception) {
            if (controller.isCancelled.get()) {
                partFile.delete()
                return Err(ServiceError.UnknownError("Download cancelled by user."))
            }
            Err(ServiceError.NetworkError("Download failed: ${e.message}", e))
        }
    }

    /**
     * Downloads [modelName] with a real-time progress dialog on Swing, supporting pause, resume,
     * and ETA, or silently if in a headless environment.
     */
    fun downloadWithProgressDialog(modelName: String): String {
        val model = MODELS_BY_NAME[modelName]
            ?: return "Unknown model '$modelName'. Please choose a model from the list."

        if (isInstalled(model.name)) {
            return "Model '${model.label}' is already installed and ready to use."
        }

        val controller = VoskDownloadController()
        var dialog: VoskDownloadDialog? = null

        if (!GraphicsEnvironment.isHeadless()) {
            try {
                val parentWindow = Window.getWindows().firstOrNull { it.isFocused }
                    ?: Window.getWindows().firstOrNull { it.isVisible }

                SwingUtilities.invokeAndWait {
                    dialog = VoskDownloadDialog(parentWindow, model, controller).apply {
                        isVisible = true
                    }
                }
            } catch (_: Exception) {
                // Ignore if GUI initialization fails
            }
        }

        val result = downloadModelResumable(
            model = model,
            controller = controller,
            onProgress = { downloaded, total, speed, eta ->
                dialog?.updateProgress(downloaded, total, speed, eta)
            },
            onExtracting = {
                dialog?.notifyExtracting()
            }
        )

        return result.fold(
            success = {
                dialog?.notifyCompleted()
                "Model '${model.label}' (~${model.sizeMb} MB) downloaded and installed successfully."
            },
            failure = { error ->
                dialog?.notifyError(error.message)
                "Download error: ${error.message}"
            }
        )
    }

    /** Message for the settings "Download Model" action (called synchronously via reflection). */
    fun downloadAction(modelName: String): String = downloadWithProgressDialog(modelName)

    companion object {
        val AVAILABLE_MODELS = listOf(
            VoskModel(
                name = "vosk-model-small-en-us-0.15",
                language = "en",
                label = "English (US) small ~40MB",
                sizeBytes = 40 * 1024 * 1024L,
                url = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
                isLarge = false
            ),
            VoskModel(
                name = "vosk-model-en-us-0.22-lgraph",
                language = "en",
                label = "English (US) large ~128MB",
                sizeBytes = 128 * 1024 * 1024L,
                url = "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip",
                isLarge = true
            ),
            VoskModel(
                name = "vosk-model-ar-mgb2-0.4",
                language = "ar",
                label = "Arabic ~318MB",
                sizeBytes = 333_241_610L,
                url = "https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip",
                isLarge = false
            ),
            VoskModel(
                name = "vosk-model-ar-0.22-linto-1.1.0",
                language = "ar",
                label = "Arabic large ~1.3GB",
                sizeBytes = 1_377_395_170L,
                url = "https://alphacephei.com/vosk/models/vosk-model-ar-0.22-linto-1.1.0.zip",
                isLarge = true
            ),
            VoskModel(
                name = "vosk-model-small-de-0.15",
                language = "de",
                label = "German small ~45MB",
                sizeBytes = 45 * 1024 * 1024L,
                url = "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip",
                isLarge = false
            ),
            VoskModel(
                name = "vosk-model-small-fr-0.22",
                language = "fr",
                label = "French small ~41MB",
                sizeBytes = 41 * 1024 * 1024L,
                url = "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip",
                isLarge = false
            ),
            VoskModel(
                name = "vosk-model-small-es-0.42",
                language = "es",
                label = "Spanish small ~39MB",
                sizeBytes = 39 * 1024 * 1024L,
                url = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip",
                isLarge = false
            ),
            VoskModel(
                name = "vosk-model-small-it-0.22",
                language = "it",
                label = "Italian small ~48MB",
                sizeBytes = 48 * 1024 * 1024L,
                url = "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip",
                isLarge = false
            ),
            VoskModel(
                name = "vosk-model-small-ru-0.22",
                language = "ru",
                label = "Russian small ~45MB",
                sizeBytes = 45 * 1024 * 1024L,
                url = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip",
                isLarge = false
            ),
            VoskModel(
                name = "vosk-model-small-tr-0.3",
                language = "tr",
                label = "Turkish small ~35MB",
                sizeBytes = 35 * 1024 * 1024L,
                url = "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip",
                isLarge = false
            ),
            VoskModel(
                name = "vosk-model-small-pt-0.3",
                language = "pt",
                label = "Portuguese small ~31MB",
                sizeBytes = 31 * 1024 * 1024L,
                url = "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip",
                isLarge = false
            ),
            VoskModel(
                name = "vosk-model-small-ja-0.22",
                language = "ja",
                label = "Japanese small ~48MB",
                sizeBytes = 48 * 1024 * 1024L,
                url = "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip",
                isLarge = false
            ),
            VoskModel(
                name = "vosk-model-small-cn-0.22",
                language = "zh",
                label = "Chinese small ~42MB",
                sizeBytes = 42 * 1024 * 1024L,
                url = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip",
                isLarge = false
            ),
            VoskModel(
                name = "vosk-model-small-uk-v3-nano",
                language = "uk",
                label = "Ukrainian small ~73MB",
                sizeBytes = 73 * 1024 * 1024L,
                url = "https://alphacephei.com/vosk/models/vosk-model-small-uk-v3-nano.zip",
                isLarge = false
            )
        )

        val MODELS_BY_NAME: Map<String, VoskModel> = AVAILABLE_MODELS.associateBy { it.name }
        const val DEFAULT_MODEL_NAME = "vosk-model-small-en-us-0.15"

        const val DROPDOWN_OPTIONS: String =
            "vosk-model-small-en-us-0.15,vosk-model-en-us-0.22-lgraph,vosk-model-ar-mgb2-0.4,vosk-model-ar-0.22-linto-1.1.0,vosk-model-small-de-0.15,vosk-model-small-fr-0.22,vosk-model-small-es-0.42,vosk-model-small-it-0.22,vosk-model-small-ru-0.22,vosk-model-small-tr-0.3,vosk-model-small-pt-0.3,vosk-model-small-ja-0.22,vosk-model-small-cn-0.22,vosk-model-small-uk-v3-nano"

        internal fun openConnectionWithRedirects(
            urlStr: String,
            rangeStart: Long = 0L,
            readTimeoutMillis: Int = 30_000
        ): Pair<HttpURLConnection, Int> {
            var url = URI(urlStr).toURL()
            var conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMillis
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "QTranslate/1.5.0")
            if (rangeStart > 0) {
                conn.setRequestProperty("Range", "bytes=$rangeStart-")
            }

            var code = conn.responseCode
            var redirects = 0
            while (code in listOf(301, 302, 303, 307, 308) && redirects < 5) {
                val location = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                url = URI(location).toURL()
                conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = readTimeoutMillis
                conn.setRequestProperty("User-Agent", "QTranslate/1.5.0")
                if (rangeStart > 0) {
                    conn.setRequestProperty("Range", "bytes=$rangeStart-")
                }
                code = conn.responseCode
                redirects++
            }
            return conn to code
        }

        private suspend fun defaultDownload(
            urlStr: String,
            targetZip: File,
            onProgress: ((Long, Long) -> Unit)?
        ): Result<Unit, ServiceError> = withContext(Dispatchers.IO) {
            try {
                val (redirectedConn, code) = openConnectionWithRedirects(urlStr, readTimeoutMillis = 60_000)

                if (code !in 200..299) {
                    return@withContext Err(ServiceError.NetworkError("HTTP $code while downloading $urlStr"))
                }

                val totalLength = redirectedConn.contentLengthLong
                targetZip.parentFile?.mkdirs()

                redirectedConn.inputStream.use { input ->
                    FileOutputStream(targetZip).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var bytesRead: Int
                        var accumulated = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            accumulated += bytesRead
                            onProgress?.invoke(accumulated, totalLength)
                        }
                    }
                }
                Ok(Unit)
            } catch (e: Exception) {
                Err(ServiceError.NetworkError("Download failed: ${e.message}", e))
            }
        }

        fun extractZip(zipFile: File, destinationDir: File) {
            val destCanonical = destinationDir.canonicalPath
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val destFile = File(destinationDir, entry.name)
                    val canonicalPath = destFile.canonicalPath
                    if (!canonicalPath.startsWith(destCanonical + File.separator) && canonicalPath != destCanonical) {
                        throw SecurityException("Zip traversal detected: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        destFile.mkdirs()
                    } else {
                        destFile.parentFile?.mkdirs()
                        FileOutputStream(destFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
    }
}
