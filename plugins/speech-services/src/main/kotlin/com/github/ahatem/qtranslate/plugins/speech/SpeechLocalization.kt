package com.github.ahatem.qtranslate.plugins.speech

import java.util.Locale

/**
 * Provides localized UI strings for speech-services dialogs and messages.
 */
internal object SpeechLocalization {
    private val englishStrings = mapOf(
        "dialog_title" to "Download Vosk Model",
        "model_label" to "Model: %s",
        "source_label" to "Source: alphacephei.com (local offline storage)",
        "connecting" to "Connecting to server...",
        "pause" to "Pause",
        "resume" to "Resume",
        "cancel" to "Cancel",
        "close" to "Close",
        "resuming" to "Resuming download...",
        "cancelling" to "Cancelling...",
        "paused_stats" to "Paused at %s / %s (%d%%). Click Resume to continue.",
        "extracting" to "Extracting model files into local storage...",
        "done" to "Done! Model installed successfully.",
        "error" to "Error: %s",
        "remaining_sec" to "%ds remaining",
        "remaining_min_sec" to "%dm %ds remaining",
        "disk_space_error" to "Not enough free disk space to download and extract '%s'. Required: ~%s, available: %s.",
        "installed_marker" to "%s ✓ (installed)",
        "download_model_button" to "Download Model",
        "downloading_model_label" to "Downloading (%s)..."
    )

    private val arabicStrings = mapOf(
        "dialog_title" to "تنزيل نموذج Vosk",
        "model_label" to "النموذج: %s",
        "source_label" to "المصدر: alphacephei.com (تخزين محلي بدون إنترنت)",
        "connecting" to "جارٍ الاتصال بالخادم...",
        "pause" to "إيقاف مؤقت",
        "resume" to "استئناف",
        "cancel" to "إلغاء",
        "close" to "إغلاق",
        "resuming" to "جارٍ استئناف التنزيل...",
        "cancelling" to "جارٍ الإلغاء...",
        "paused_stats" to "متوقف عند %s / %s (%d%%). انقر استئناف للمتابعة.",
        "extracting" to "جارٍ فك ضغط ملفات النموذج في التخزين المحلي...",
        "done" to "اكتمل التنزيل! تم تثبيت النموذج بنجاح.",
        "error" to "خطأ: %s",
        "remaining_sec" to "متبقي %d ثانية",
        "remaining_min_sec" to "متبقي %d دقيقة و %d ثانية",
        "disk_space_error" to "لا توجد مساحة كافية على القرص لتنزيل وفك ضغط '%s'. المطلوب: ~%s، المتوفر: %s.",
        "installed_marker" to "%s ✓ (منصّب)",
        "download_model_button" to "تنزيل النموذج",
        "downloading_model_label" to "جارٍ التنزيل (%s)..."
    )

    fun get(key: String): String {
        val lang = Locale.getDefault().language.lowercase()
        val strings = if (lang == "ar") arabicStrings else englishStrings
        return strings[key] ?: englishStrings[key] ?: key
    }

    fun get(key: String, vararg args: Any): String =
        String.format(Locale.getDefault(), get(key), *args)
}
