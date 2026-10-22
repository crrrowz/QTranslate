package com.github.ahatem.qtranslate.plugins.speech

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoskModelManagerTest {

    @TempDir
    lateinit var tempDir: File

    private fun createZipWithModel(zipFile: File, modelName: String) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("$modelName/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("$modelName/am/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("$modelName/am/final.mdl"))
            zos.write("fake-model-binary".toByteArray())
            zos.closeEntry()
        }
    }

    @Test
    fun `model manager detects installed models and resolves for language`() {
        val manager = VoskModelManager({ tempDir })
        val modelsRoot = manager.modelsDirectory

        assertFalse(manager.isInstalled("vosk-model-small-en-us-0.15"))
        assertNull(manager.findModelForLanguage("en"))

        // Create a model folder
        val enModelDir = File(modelsRoot, "vosk-model-small-en-us-0.15").apply { mkdirs() }
        File(enModelDir, "model.conf").writeText("sample_rate=16000")

        assertTrue(manager.isInstalled("vosk-model-small-en-us-0.15"))
        assertEquals(1, manager.listInstalledModels().size)
        assertEquals(enModelDir.absolutePath, manager.findModelForLanguage("en")?.absolutePath)
        assertEquals(enModelDir.absolutePath, manager.findModelForLanguage("en-US")?.absolutePath)
        assertNull(manager.findModelForLanguage("ar"))
    }

    @Test
    fun `model manager downloads unzips and verifies model archive`() = runBlocking {
        val fakeModel = VoskModel(
            name = "vosk-model-test-fake",
            language = "en",
            label = "Test Model",
            sizeBytes = 1024L,
            url = "https://example.com/fake.zip"
        )

        val manager = VoskModelManager(
            dataDirectory = { tempDir },
            downloader = { _, targetZip, onProgress ->
                createZipWithModel(targetZip, fakeModel.name)
                onProgress?.invoke(targetZip.length(), targetZip.length())
                Ok(Unit)
            }
        )

        assertFalse(manager.isInstalled(fakeModel.name))

        val result = manager.downloadModel(fakeModel)
        val extractedDir = result.get()
        assertNotNull(extractedDir)
        assertTrue(extractedDir.isDirectory)
        assertTrue(File(extractedDir, "am/final.mdl").isFile)
        assertTrue(manager.isInstalled(fakeModel.name))

        // Check status action
        val status = manager.statusAction()
        assertTrue(status.contains(fakeModel.name))

        // Delete model
        assertTrue(manager.deleteModel(fakeModel.name))
        assertFalse(manager.isInstalled(fakeModel.name))
    }

    @Test
    fun `resumable download controller supports pause resume and cancel`() {
        val controller = VoskDownloadController()
        assertFalse(controller.isPaused.get())
        assertFalse(controller.isCancelled.get())

        controller.pause()
        assertTrue(controller.isPaused.get())

        controller.resume()
        assertFalse(controller.isPaused.get())

        controller.cancel()
        assertTrue(controller.isCancelled.get())
    }
}
