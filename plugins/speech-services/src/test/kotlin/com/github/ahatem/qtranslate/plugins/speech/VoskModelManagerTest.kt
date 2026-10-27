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

    private fun writeFakeModelFiles(dir: File) {
        File(dir, "am").mkdirs()
        File(dir, "am/final.mdl").writeText("fake-model-binary")
        File(dir, "conf").mkdirs()
        File(dir, "conf/model.conf").writeText("sample_rate=16000")
    }

    @Test
    fun `half-extracted model folder is not reported as installed`() {
        val manager = VoskModelManager(dataDirectory = { tempDir }, persistentDirectoryProvider = { File(tempDir, "persistent") })
        val partial = File(manager.modelsDirectory, "vosk-model-small-en-us-0.15").apply { mkdirs() }
        File(partial, "conf").mkdirs()
        File(partial, "conf/model.conf").writeText("sample_rate=16000")

        assertFalse(manager.isInstalled("vosk-model-small-en-us-0.15"))
        assertTrue(manager.listInstalledModels().isEmpty())
        assertNull(manager.findModelForLanguage("en"))

        File(partial, "am").mkdirs()
        File(partial, "am/final.mdl").writeText("fake-model-binary")
        assertTrue(manager.isInstalled("vosk-model-small-en-us-0.15"))
    }

    @Test
    fun `model manager detects installed models and resolves for language`() {
        val isolatedDir = File(tempDir, "isolated").apply { mkdirs() }
        val manager = VoskModelManager(dataDirectory = { tempDir }, persistentDirectoryProvider = { isolatedDir })
        val modelsRoot = manager.modelsDirectory

        assertFalse(manager.isInstalled("vosk-model-small-en-us-0.15"))
        assertNull(manager.findModelForLanguage("en"))

        // Create a model folder
        val enModelDir = File(modelsRoot, "vosk-model-small-en-us-0.15").apply { mkdirs() }
        writeFakeModelFiles(enModelDir)

        assertTrue(manager.isInstalled("vosk-model-small-en-us-0.15"))
        assertEquals(1, manager.listInstalledModels().size)
        assertEquals(enModelDir.absolutePath, manager.findModelForLanguage("en")?.absolutePath)
        assertEquals(enModelDir.absolutePath, manager.findModelForLanguage("en-US")?.absolutePath)
        assertEquals(enModelDir.absolutePath, manager.findModelForLanguage("auto")?.absolutePath)
        assertNull(manager.findModelForLanguage("ar"))
        assertNull(manager.findModelForLanguage("de"))

        // Create an Arabic model folder
        val arModelDir = File(modelsRoot, "vosk-model-ar-mgb2-0.4").apply { mkdirs() }
        writeFakeModelFiles(arModelDir)
        assertEquals(arModelDir.absolutePath, manager.findModelForLanguage("ar")?.absolutePath)
        // Auto mode prefers English when preferred model is not specified
        assertEquals(enModelDir.absolutePath, manager.findModelForLanguage("auto")?.absolutePath)
        // When preferred model is Arabic, auto mode respects it
        assertEquals(arModelDir.absolutePath, manager.findModelForLanguage("auto", "vosk-model-ar-mgb2-0.4")?.absolutePath)
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

        val isolatedPersistentDir = File(tempDir, "persistent").apply { mkdirs() }
        val manager = VoskModelManager(
            dataDirectory = { tempDir },
            persistentDirectoryProvider = { isolatedPersistentDir },
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
        assertEquals(manager.modelsDirectory.absolutePath, extractedDir.parentFile.absolutePath, "Model should be saved in modelsDirectory")
        assertFalse(File(isolatedPersistentDir, fakeModel.name).exists(), "Model should NOT be copied to persistent directory")

        // Check status action
        val status = manager.statusAction()
        assertTrue(status.contains(fakeModel.name))

        // Delete model
        assertTrue(manager.deleteModel(fakeModel.name))
        assertFalse(manager.isInstalled(fakeModel.name))
    }

    @Test
    fun `import model from zip file installs model successfully into modelsDirectory`() {
        val isolatedPersistentDir = File(tempDir, "persistent").apply { mkdirs() }
        val manager = VoskModelManager(dataDirectory = { tempDir }, persistentDirectoryProvider = { isolatedPersistentDir })
        val importZip = File(tempDir, "vosk-model-imported.zip")
        createZipWithModel(importZip, "vosk-model-imported")

        val result = manager.importModelFromFile(importZip)
        val dir = result.get()
        assertNotNull(dir)
        assertTrue(dir.isDirectory)
        assertEquals(manager.modelsDirectory.absolutePath, dir.parentFile.absolutePath, "Import should extract into modelsDirectory")
        assertFalse(File(isolatedPersistentDir, "vosk-model-imported").exists(), "Import should NOT copy to persistent directory")
        assertTrue(manager.isInstalled("vosk-model-imported"))
        assertTrue(manager.deleteModel("vosk-model-imported"))
    }

    @Test
    fun `persistent directory acts as read fallback for existing models`() {
        val isolatedPersistentDir = File(tempDir, "persistent").apply { mkdirs() }
        val manager = VoskModelManager(dataDirectory = { tempDir }, persistentDirectoryProvider = { isolatedPersistentDir })

        // Place a model only in the persistent directory
        val legacyModelDir = File(isolatedPersistentDir, "vosk-model-small-en-us-0.15").apply { mkdirs() }
        writeFakeModelFiles(legacyModelDir)

        assertTrue(manager.isInstalled("vosk-model-small-en-us-0.15"), "Should detect model in persistent fallback directory")
        assertEquals(legacyModelDir.absolutePath, manager.findModelForLanguage("en")?.absolutePath, "Should find model in persistent fallback directory")
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
