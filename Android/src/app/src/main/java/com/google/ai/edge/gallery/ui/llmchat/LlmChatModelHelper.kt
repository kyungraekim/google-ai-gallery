/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKey
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.common.cleanUpMediapipeTaskErrorMessage
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.quicinc.chatapp.GenieWrapper
import com.quicinc.chatapp.StringCallback

private const val TAG = "AGLlmChatModelHelper"

typealias ResultListener = (partialResult: String, done: Boolean) -> Unit
typealias CleanUpListener = () -> Unit

// Updated to hold Any type for instance
data class LlmModelInstance(val engine: Any, var session: LlmInferenceSession?)

object LlmChatModelHelper {
  // Indexed by model name.
  private val cleanUpListeners: MutableMap<String, CleanUpListener> = mutableMapOf()

  fun initialize(
    context: Context, model: Model, onDone: (String) -> Unit
  ) {
    // Prepare options.
    val maxTokens =
      model.getIntConfigValue(key = ConfigKey.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
    val topK = model.getIntConfigValue(key = ConfigKey.TOPK, defaultValue = DEFAULT_TOPK)
    val topP = model.getFloatConfigValue(key = ConfigKey.TOPP, defaultValue = DEFAULT_TOPP)
    val temperature =
      model.getFloatConfigValue(key = ConfigKey.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
    val accelerator =
      model.getStringConfigValue(key = ConfigKey.ACCELERATOR, defaultValue = Accelerator.GPU.label)
    Log.d(TAG, "Initializing...")
    val preferredBackend = when (accelerator) {
      Accelerator.CPU.label -> LlmInference.Backend.CPU
      Accelerator.GPU.label -> LlmInference.Backend.GPU
      else -> LlmInference.Backend.GPU
    }
    Log.d(TAG, "Initializing for accelerator: $accelerator")
    if (accelerator == Accelerator.GENIE.label) {
      try {
        // TODO: Replace placeholder paths with actual configuration from model assets
        val modelDir = model.getPath(context, "model_dir_placeholder") // Assuming model path can give a base
        val htpConfig = model.getPath(context, "htp_config_placeholder") // Assuming model path can give a base

        Log.d(TAG, "Attempting to load Genie model from $modelDir with HTP config $htpConfig")
        val genieWrapper = GenieWrapper(modelDir, htpConfig)
        model.instance = genieWrapper // Store GenieWrapper instance directly
        Log.d(TAG, "GenieWrapper initialized successfully.")
      } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize GenieWrapper: ${e.message}", e)
        onDone("Failed to load Genie model: ${e.localizedMessage}")
        return
      }
    } else {
      val preferredBackend = when (accelerator) {
        Accelerator.CPU.label -> LlmInference.Backend.CPU
        Accelerator.GPU.label -> LlmInference.Backend.GPU
        else -> LlmInference.Backend.GPU // Default or throw error
      }
      val options =
        LlmInference.LlmInferenceOptions.builder().setModelPath(model.getPath(context = context))
          .setMaxTokens(maxTokens).setPreferredBackend(preferredBackend)
          .setMaxNumImages(if (model.llmSupportImage) 1 else 0)
          .build()
      try {
        val llmInference = LlmInference.createFromOptions(context, options)
        val session = LlmInferenceSession.createFromOptions(
          llmInference,
          LlmInferenceSession.LlmInferenceSessionOptions.builder().setTopK(topK).setTopP(topP)
            .setTemperature(temperature)
            .setGraphOptions(
              GraphOptions.builder().setEnableVisionModality(model.llmSupportImage).build()
            ).build()
        )
        // Note: Storing LlmInference in 'engine' and session separately for LlmModelInstance
        // For Genie, the GenieWrapper itself is stored in model.instance
        model.instance = LlmModelInstance(engine = llmInference, session = session)
        Log.d(TAG, "LlmInference initialized successfully for $accelerator.")
      } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize LlmInference: ${e.message}", e)
        onDone(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error initializing LlmInference"))
        return
      }
    }
    onDone("")
  }

  fun resetSession(model: Model) {
    Log.d(TAG, "Resetting session for model '${model.name}'")
    val modelInstance = model.instance ?: return

    when (modelInstance) {
      is LlmModelInstance -> {
        val session = modelInstance.session
        session?.close() // Close existing session

        val inference = modelInstance.engine as LlmInference // Assuming engine is LlmInference here
        val topK = model.getIntConfigValue(key = ConfigKey.TOPK, defaultValue = DEFAULT_TOPK)
        val topP = model.getFloatConfigValue(key = ConfigKey.TOPP, defaultValue = DEFAULT_TOPP)
        val temperature =
          model.getFloatConfigValue(key = ConfigKey.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
        val newSession = LlmInferenceSession.createFromOptions(
          inference,
          LlmInferenceSession.LlmInferenceSessionOptions.builder().setTopK(topK).setTopP(topP)
            .setTemperature(temperature)
            .setGraphOptions(
              GraphOptions.builder().setEnableVisionModality(model.llmSupportImage).build()
            ).build()
        )
        modelInstance.session = newSession // Assign new session
        Log.d(TAG, "LlmInference session reset.")
      }
      is GenieWrapper -> {
        Log.i(TAG, "Resetting session is not applicable for GenieWrapper. Model: ${model.name}")
        // No specific reset action for GenieWrapper in this context
      }
      else -> {
        Log.w(TAG, "Unsupported instance type for reset: ${modelInstance.javaClass.name}")
      }
    }
    Log.d(TAG, "Resetting done")
  }

  fun cleanUp(model: Model) {
    val currentInstance = model.instance ?: return
    Log.d(TAG, "Cleaning up model '${model.name}' with instance type ${currentInstance.javaClass.simpleName}")

    try {
      when (currentInstance) {
        is LlmModelInstance -> {
          currentInstance.session?.close()
          (currentInstance.engine as? LlmInference)?.close()
          Log.d(TAG, "LlmInference instance cleaned up.")
        }
        is GenieWrapper -> {
          currentInstance.close()
          Log.d(TAG, "GenieWrapper instance cleaned up.")
        }
        else -> {
          Log.w(TAG, "Unsupported instance type for cleanUp: ${currentInstance.javaClass.name}")
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error during cleanUp for model ${model.name}: ${e.message}", e)
      // ignore further
    } finally {
      val onCleanUp = cleanUpListeners.remove(model.name)
      onCleanUp?.invoke()
      model.instance = null // Clear the instance from the model
      Log.d(TAG, "Clean up process finished for model '${model.name}'.")
    }
  }

  fun runInference(
    model: Model,
    input: String,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener,
    image: Bitmap? = null,
  ) {
    val currentInstance = model.instance
    if (currentInstance == null) {
      Log.e(TAG, "Model instance is null for ${model.name}. Cannot run inference.")
      resultListener("Error: Model not initialized or already cleaned up.", true)
      return
    }

    // Set listener.
    if (!cleanUpListeners.containsKey(model.name)) {
      cleanUpListeners[model.name] = cleanUpListener
    }

    Log.d(TAG, "Running inference for model '${model.name}' with instance ${currentInstance.javaClass.simpleName}")
    when (currentInstance) {
      is LlmModelInstance -> {
        val session = currentInstance.session
        if (session == null) {
            resultListener("Error: LlmInference session is null.", true)
            return
        }
        // For a model that supports image modality, we need to add the text query chunk before adding image.
        session.addQueryChunk(input)
        if (image != null && model.llmSupportImage) { // Check llmSupportImage before adding
          session.addImage(BitmapImageBuilder(image).build())
        }
        session.generateResponseAsync(resultListener)
        Log.d(TAG, "LlmInference.generateResponseAsync called.")
      }
      is GenieWrapper -> {
        if (image != null) {
            Log.w(TAG, "GenieWrapper currently does not support image input. Image will be ignored.")
        }
        val stringCallbackAdapter = object : StringCallback {
          override fun onResponse(token: String) {
            resultListener(token, false)
          }
          override fun onError(errorMessage: String) {
            Log.e(TAG, "GenieWrapper error: $errorMessage")
            resultListener("Error from Genie: $errorMessage", true)
          }
          override fun onComplete() {
            Log.i(TAG, "GenieWrapper inference complete for model ${model.name}.")
            // Signaling completion: Assuming the last token implies "done",
            // or rely on onError for actual errors.
            // If Genie has a specific "stream finished" token, handle it here.
            // For now, we might need a way for Genie to signal actual completion
            // if onResponse(token) is called multiple times.
            // Let's assume for now that an empty token or a specific signal from Genie means done.
            // If not, the client might need to infer completion based on context or timeout.
             resultListener("", true) // Signal completion.
          }
        }
        currentInstance.getResponseForPrompt(input, stringCallbackAdapter)
        Log.d(TAG, "GenieWrapper.getResponseForPrompt called.")
      }
      else -> {
        Log.e(TAG, "Unknown model instance type: ${currentInstance.javaClass.name}")
        resultListener("Error: Unknown model type for inference.", true)
      }
    }
  }
}
