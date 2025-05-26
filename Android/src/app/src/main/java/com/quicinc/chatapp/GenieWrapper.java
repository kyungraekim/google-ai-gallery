// ---------------------------------------------------------------------
// Copyright (c) 2024 Qualcomm Innovation Center, Inc. All rights reserved.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.quicinc.chatapp;

// Placeholder for the StringCallback interface.
// The user will provide the actual definition later.
interface StringCallback {
    void onResponse(String token);
    void onError(String errorMessage); // Assuming an error callback might be needed
    void onComplete(); // Assuming a completion callback might be needed
}

/**
 * GenieWrapper: Class to connect JNI GenieWrapper and Java code
 */
public class GenieWrapper {
    long genieWrapperNativeHandle;

    /**
     * GenieWrapper: Loads model at provided path with provided htp config
     *
     * @param modelDirPath directory path on system pointing to model bundle
     * @param htpConfigPath HTP config file to use
     */
    public GenieWrapper(String modelDirPath, String htpConfigPath) {
        // TODO: Add error handling if loadModel fails (e.g., if it returns 0 or negative)
        genieWrapperNativeHandle = loadModel(modelDirPath, htpConfigPath);
        if (genieWrapperNativeHandle == 0) { // Assuming 0 indicates failure
            throw new RuntimeException("Failed to load Genie model. Handle: " + genieWrapperNativeHandle);
        }
    }

    /**
     * getResponseForPrompt: Generates response for provided user input
     *
     * @param userInput user input to generate response for
     * @param callback callback to tunnel each generated token to
     */
    public void getResponseForPrompt(String userInput, StringCallback callback) {
        if (genieWrapperNativeHandle == 0) {
            // Or call callback.onError()
            throw new IllegalStateException("Genie model not loaded or handle is invalid.");
        }
        getResponseForPrompt(genieWrapperNativeHandle, userInput, callback);
    }

    /**
     * close: Explicitly frees the loaded model resources.
     */
    public void close() {
        if (genieWrapperNativeHandle != 0) {
            freeModel(genieWrapperNativeHandle);
            genieWrapperNativeHandle = 0; // Mark as closed
        }
    }

    /**
     * finalize: Free previously loaded model.
     * It's better to call close() explicitly.
     */
    @Override
    protected void finalize() {
        // In case close() was not called, try to free the model.
        // Add a log message here if you want to track if finalize is being relied upon.
        if (genieWrapperNativeHandle != 0) {
            // Log.w("GenieWrapper", "GenieWrapper.finalize() called. Resources should be released via close().");
            close();
        }
    }

    /**
     * loadModel: JNI method to load model using Genie C++ APIs
     *
     * @param modelDirPath directory path on system pointing to model bundle
     * @param htpConfigPath HTP config file to use
     * @return pointer to Genie C++ Wrapper to generate future responses, or 0 on failure.
     */
    private native long loadModel(String modelDirPath, String htpConfigPath);

    /**
     * getResponseForPrompt: JNI method to generate response for provided user input
     *
     * @param nativeHandle native handle captured before with LoadModel
     * @param userInput user input to generate response for
     * @param callback callback to tunnel each generated token to
     */
    private native void getResponseForPrompt(long nativeHandle, String userInput, StringCallback callback);

    /**
     * FreeModel: JNI method to free previously loaded model
     *
     * @param nativeHandle native handle captured before with LoadModel
     */
    private native void freeModel(long nativeHandle);
}
