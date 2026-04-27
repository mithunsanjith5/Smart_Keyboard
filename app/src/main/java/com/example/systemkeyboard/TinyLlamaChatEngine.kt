package com.example.systemkeyboard

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.codeshipping.llamakotlin.LlamaModel
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

class TinyLlamaChatEngine(private val context: Context) : Closeable {

    interface ChatCallback {
        fun onSuccess(reply: String)
        fun onError(errorMessage: String)
    }

    private val scope = CoroutineScope(Job() + Dispatchers.IO)
    private var currentModelPath: String? = null
    private var model: LlamaModel? = null
    private val modelLocks = ConcurrentHashMap<String, Any>()

    fun generateReply(modelPath: String, userPrompt: String, systemPrompt: String, callback: ChatCallback) {
        scope.launch {
            try {
                val loadedModel = ensureModel(modelPath)
                val prompt = buildChatPrompt(systemPrompt, userPrompt)
                val reply = loadedModel.generate(prompt).trim()
                callback.onSuccess(if (reply.isNotEmpty()) reply else "No response produced.")
            } catch (exception: Throwable) {
                callback.onError(exception.message ?: "Generation failed.")
            }
        }
    }

    private suspend fun ensureModel(modelPath: String): LlamaModel {
        val existing = model
        if (existing != null && currentModelPath == modelPath) {
            return existing
        }

        synchronized(modelLocks.getOrPut(modelPath) { Any() }) {
            val cached = model
            if (cached != null && currentModelPath == modelPath) {
                return cached
            }
        }

        val loaded = LlamaModel.load(modelPath) {
            contextSize = 1024
            batchSize = 256
            threads = maxOf(2, Runtime.getRuntime().availableProcessors() / 2)
            threadsBatch = threads
            temperature = 0.7f
            topP = 0.9f
            topK = 40
            repeatPenalty = 1.1f
            maxTokens = 128
            useMmap = true
            useMlock = false
            gpuLayers = 0
        }

        model?.close()
        model = loaded
        currentModelPath = modelPath
        return loaded
    }

    private fun buildChatPrompt(systemPrompt: String, userPrompt: String): String {
        return buildString {
            append("<|im_start|>system\n")
            append(systemPrompt.trim())
            append("\n<|im_end|>\n")
            append("<|im_start|>user\n")
            append(userPrompt.trim())
            append("\n<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
    }

    override fun close() {
        scope.launch {
            model?.close()
            model = null
            currentModelPath = null
        }
    }
}