package com.berelson.smsaudit

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.*
import kotlinx.coroutines.tasks.await

class OfflineModel : AutoCloseable {
    private val manager = RemoteModelManager.getInstance()
    private val client = Translation.getClient(TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.HEBREW)
        .setTargetLanguage(TranslateLanguage.RUSSIAN).build())

    suspend fun ready(): Boolean {
        return listOf(TranslateLanguage.HEBREW, TranslateLanguage.RUSSIAN).all {
            manager.isModelDownloaded(TranslateRemoteModel.Builder(it).build()).await()
        }
    }

    // The only explicit model-download path; triggered by the user's download button.
    suspend fun download(wifiOnly: Boolean) {
        client.downloadModelIfNeeded(DownloadConditions.Builder().apply {
            if (wifiOnly) requireWifi()
        }.build()).await()
        check(ready()) { "MODELS_NOT_READY" }
    }

    suspend fun translate(text: String): String = client.translate(text).await()
    override fun close() = client.close()
}
