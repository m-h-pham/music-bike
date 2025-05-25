#include <jni.h>
#include <android/log.h>
#include <fmod_studio.hpp>
#include <fmod.hpp>
#include <fmod_errors.h>
#include <thread>
#include <atomic>
#include <mutex>
#include <string> // Required for std::string

#define LOG_TAG "FMOD_JNI_MusicService"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Global variables
FMOD::Studio::System* studioSystem = nullptr;
FMOD::Studio::EventInstance* eventInstance = nullptr;
std::thread* updateThread = nullptr;
std::atomic<bool> isRunning(false);
std::mutex fmodMutex;

// Helper function to check FMOD errors
bool checkFMODError(FMOD_RESULT result, const char* function) {
    if (result != FMOD_OK) {
        LOGE("%s failed: %s (%d)", function, FMOD_ErrorString(result), result);
        return false;
    }
    return true;
}

// Background thread function to update FMOD
void fmodUpdateThread() {
    LOGI("FMOD update thread started.");
    while (isRunning.load(std::memory_order_relaxed)) {
        {
            std::lock_guard<std::mutex> lock(fmodMutex);
            if (studioSystem) {
                studioSystem->update();
            } else {
                LOGW("FMOD update thread: studioSystem is null, exiting thread.");
                break;
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(16));
    }
    LOGI("FMOD update thread finished.");
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_app_musicbike_services_MusicService_nativeStartFMODPlayback(
        JNIEnv *env,
        jobject thiz,
        jstring masterBankPathJava,
        jstring stringsBankPathJava) {
    const char *masterBankPathCStr = env->GetStringUTFChars(masterBankPathJava, 0);
    const char *stringsBankPathCStr = env->GetStringUTFChars(stringsBankPathJava, 0);

    LOGI("nativeStartFMODPlayback: Called with Master: %s", masterBankPathCStr);

    std::lock_guard<std::mutex> lock(fmodMutex);
    FMOD_RESULT result;

    if (studioSystem == nullptr) {
        LOGI("nativeStartFMODPlayback: Studio System is null, creating and initializing.");
        result = FMOD::Studio::System::create(&studioSystem);
        if (!checkFMODError(result, "FMOD::Studio::System::create")) {
            env->ReleaseStringUTFChars(masterBankPathJava, masterBankPathCStr);
            env->ReleaseStringUTFChars(stringsBankPathJava, stringsBankPathCStr);
            return;
        }
        result = studioSystem->initialize(128, FMOD_STUDIO_INIT_NORMAL, FMOD_INIT_NORMAL, nullptr);
        if (!checkFMODError(result, "studioSystem->initialize")) {
            studioSystem->release();
            studioSystem = nullptr;
            env->ReleaseStringUTFChars(masterBankPathJava, masterBankPathCStr);
            env->ReleaseStringUTFChars(stringsBankPathJava, stringsBankPathCStr);
            return;
        }
        LOGI("nativeStartFMODPlayback: Studio System initialized.");
    } else {
        LOGI("nativeStartFMODPlayback: Studio System already exists.");
    }

    if (eventInstance) {
        LOGI("nativeStartFMODPlayback: Releasing previous event instance.");
        eventInstance->stop(FMOD_STUDIO_STOP_IMMEDIATE);
        eventInstance->release();
        eventInstance = nullptr;
    }

    int bankCount = 0;
    FMOD::Studio::Bank *loadedBanks[32];
    result = studioSystem->getBankList(loadedBanks, 32, &bankCount);
    if (checkFMODError(result, "studioSystem->getBankList")) {
        for (int i = 0; i < bankCount; ++i) {
            loadedBanks[i]->unload();
        }
        if (bankCount > 0) LOGI("Unloaded %d existing bank(s).", bankCount);
    }

    FMOD::Studio::Bank *masterBank = nullptr;
    result = studioSystem->loadBankFile(masterBankPathCStr, FMOD_STUDIO_LOAD_BANK_NORMAL, &masterBank);
    if (!checkFMODError(result, "studioSystem->loadBankFile (Master)")) {
        env->ReleaseStringUTFChars(masterBankPathJava, masterBankPathCStr);
        env->ReleaseStringUTFChars(stringsBankPathJava, stringsBankPathCStr);
        return;
    }
    LOGI("Master bank loaded: %s", masterBankPathCStr);

    FMOD::Studio::Bank *stringsBankLoaded = nullptr;
    result = studioSystem->loadBankFile(stringsBankPathCStr, FMOD_STUDIO_LOAD_BANK_NORMAL, &stringsBankLoaded);
    if (result != FMOD_OK) {
        LOGI("No strings bank loaded (optional or error: %s)", FMOD_ErrorString(result));
    } else {
        LOGI("Strings bank loaded: %s", stringsBankPathCStr);
    }

    FMOD::Studio::EventDescription *eventDescription = nullptr;
    result = studioSystem->getEvent("event:/Bike", &eventDescription);
    if (!checkFMODError(result, "studioSystem->getEvent(\"event:/Bike\")")) {
        env->ReleaseStringUTFChars(masterBankPathJava, masterBankPathCStr);
        env->ReleaseStringUTFChars(stringsBankPathJava, stringsBankPathCStr);
        return;
    }
    result = eventDescription->createInstance(&eventInstance);
    if (!checkFMODError(result, "eventDescription->createInstance for event:/Bike")) {
        env->ReleaseStringUTFChars(masterBankPathJava, masterBankPathCStr);
        env->ReleaseStringUTFChars(stringsBankPathJava, stringsBankPathCStr);
        return;
    }
    LOGI("Instance created for event:/Bike");

    if (studioSystem && !isRunning.load(std::memory_order_relaxed)) {
        if (updateThread != nullptr) {
            LOGW("nativeStartFMODPlayback: Old updateThread object found while not running. Deleting.");
            delete updateThread;
            updateThread = nullptr;
        }
        isRunning.store(true, std::memory_order_relaxed);
        updateThread = new std::thread(fmodUpdateThread);
        LOGI("FMOD update thread started by nativeStartFMODPlayback.");
    } else if (studioSystem && isRunning.load(std::memory_order_relaxed) && updateThread == nullptr) {
        LOGW("nativeStartFMODPlayback: isRunning was true, but updateThread was null. Recreating thread.");
        updateThread = new std::thread(fmodUpdateThread);
    } else if (studioSystem) {
        LOGI("nativeStartFMODPlayback: FMOD update thread likely already running.");
    }

    env->ReleaseStringUTFChars(masterBankPathJava, masterBankPathCStr);
    env->ReleaseStringUTFChars(stringsBankPathJava, stringsBankPathCStr);
    LOGI("nativeStartFMODPlayback: Finished.");
}

JNIEXPORT void JNICALL
Java_com_app_musicbike_services_MusicService_nativeSetFMODParameter(
        JNIEnv *env,
        jobject thiz,
        jstring paramNameJava,
        jfloat value) {
    const char *paramNameCStr = env->GetStringUTFChars(paramNameJava, 0);
    std::string paramNameStdStr = paramNameCStr;

    std::lock_guard<std::mutex> lock(fmodMutex);
    FMOD_RESULT result;

    // Handle event-specific parameters first
    if (paramNameStdStr == "Hall Direction" || paramNameStdStr == "Event") {
        if (eventInstance) {
            result = eventInstance->setParameterByName(paramNameCStr, value);
            if (checkFMODError(result, ("eventInstance->setParameterByName for " + paramNameStdStr).c_str())) {
                LOGI("FMOD event parameter '%s' set to %f", paramNameCStr, value);
            }
        } else {
            LOGE("Cannot set event parameter '%s': eventInstance is null.", paramNameCStr);
        }
    }
        // Handle global parameters (like Wheel Speed, Pitch, if they are indeed global)
        // You might need to list your known global parameters here
    else if (paramNameStdStr == "Wheel Speed" || paramNameStdStr == "Pitch") {
        if (studioSystem) {
            result = studioSystem->setParameterByName(paramNameCStr, value);
            if (checkFMODError(result, ("studioSystem->setParameterByName for " + paramNameStdStr).c_str())) {
                LOGI("FMOD global parameter '%s' set to %f", paramNameCStr, value);
            }
        } else {
            LOGE("Cannot set global parameter '%s': FMOD Studio System is null.", paramNameCStr);
        }
    } else {
        LOGW("Unknown FMOD parameter name: %s. Not set.", paramNameCStr);
    }
    env->ReleaseStringUTFChars(paramNameJava, paramNameCStr);
}

JNIEXPORT void JNICALL
Java_com_app_musicbike_services_MusicService_nativeToggleFMODPlayback(
        JNIEnv *env,
        jobject thiz) {
    std::lock_guard<std::mutex> lock(fmodMutex);
    LOGI("NATIVE nativeToggleFMODPlayback: Entered.");

    if (!studioSystem || !eventInstance) {
        LOGE("NATIVE nativeToggleFMODPlayback: Cannot toggle playback: FMOD system or event instance is null.");
        return;
    }

    bool isCurrentlyPaused = false;
    FMOD_RESULT result = eventInstance->getPaused(&isCurrentlyPaused);
    if (!checkFMODError(result, "NATIVE nativeToggleFMODPlayback: eventInstance->getPaused")) {
        LOGE("NATIVE nativeToggleFMODPlayback: Failed to get paused state, cannot toggle.");
        return;
    }
    LOGI("NATIVE nativeToggleFMODPlayback: Current event isPaused state (from getPaused()): %s", isCurrentlyPaused ? "true" : "false");

    FMOD_STUDIO_PLAYBACK_STATE currentPlaybackState;
    result = eventInstance->getPlaybackState(&currentPlaybackState);
    if (!checkFMODError(result, "NATIVE nativeToggleFMODPlayback: eventInstance->getPlaybackState")) {
        LOGE("NATIVE nativeToggleFMODPlayback: Failed to get playback state, cannot toggle reliably.");
        return;
    }
    LOGI("NATIVE nativeToggleFMODPlayback: Current event playback state (from getPlaybackState()): %d", currentPlaybackState);

    if (currentPlaybackState == FMOD_STUDIO_PLAYBACK_STOPPED || currentPlaybackState == FMOD_STUDIO_PLAYBACK_STOPPING) {
        LOGI("NATIVE nativeToggleFMODPlayback: Event was STOPPED/STOPPING. Attempting to start.");
        result = eventInstance->start();
        if(checkFMODError(result, "NATIVE nativeToggleFMODPlayback: eventInstance->start (from stopped state)")) {
            LOGI("NATIVE nativeToggleFMODPlayback: eventInstance->start() successful.");
        } else {
            LOGE("NATIVE nativeToggleFMODPlayback: eventInstance->start() FAILED.");
        }
    } else { // Event is PLAYING, PAUSED, STARTING, or SUSTAINING
        LOGI("NATIVE nativeToggleFMODPlayback: Event is not stopped. Will attempt to setPaused(%s).", !isCurrentlyPaused ? "true (pause)" : "false (unpause)");
        result = eventInstance->setPaused(!isCurrentlyPaused); // Toggle the current paused state
        if(checkFMODError(result, "NATIVE nativeToggleFMODPlayback: eventInstance->setPaused")) {
            LOGI("NATIVE nativeToggleFMODPlayback: eventInstance->setPaused(!%s) successful.", isCurrentlyPaused ? "true" : "false");
        } else {
            LOGE("NATIVE nativeToggleFMODPlayback: eventInstance->setPaused(!%s) FAILED.", isCurrentlyPaused ? "true" : "false");
        }
    }
    LOGI("NATIVE nativeToggleFMODPlayback: Exiting.");
}

JNIEXPORT void JNICALL
Java_com_app_musicbike_services_MusicService_nativePlayFMODEvent(
        JNIEnv *env,
        jobject thiz) {
    std::lock_guard<std::mutex> lock(fmodMutex);
    if (!eventInstance) {
        LOGE("nativePlayFMODEvent: Cannot play event: eventInstance for 'event:/Bike' is null.");
        return;
    }
    LOGI("nativePlayFMODEvent: Attempting to start 'event:/Bike'");
    FMOD_RESULT result = eventInstance->start();
    if(checkFMODError(result, "nativePlayFMODEvent: eventInstance->start")) {
        LOGI("nativePlayFMODEvent: 'event:/Bike' start command issued.");
    }
}

// Corrected nativeIsFMODPaused
JNIEXPORT jboolean JNICALL
Java_com_app_musicbike_services_MusicService_nativeIsFMODPaused(
        JNIEnv *env,
        jobject thiz) {
    std::lock_guard<std::mutex> lock(fmodMutex);
    if (!eventInstance) {
        LOGW("nativeIsFMODPaused: eventInstance is null. Returning true (assumed paused).");
        return JNI_TRUE;
    }

    bool isPausedQuery = true; // Default to true (paused)
    FMOD_RESULT result = eventInstance->getPaused(&isPausedQuery);

    if (!checkFMODError(result, "nativeIsFMODPaused: eventInstance->getPaused()")) {
        // If getting paused state fails, assume it's paused to be safe or prevent unexpected play.
        return JNI_TRUE;
    }
    // LOGI("nativeIsFMODPaused: eventInstance->getPaused() returned: %s", isPausedQuery ? "true" : "false");
    return isPausedQuery ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_app_musicbike_services_MusicService_nativeStopFMODUpdateThread(
        JNIEnv *env,
        jobject thiz) {
    LOGI("nativeStopFMODUpdateThread: Called.");
    if (isRunning.load(std::memory_order_relaxed)) {
        isRunning.store(false, std::memory_order_relaxed);
        if (updateThread != nullptr) {
            if (updateThread->joinable()) {
                LOGI("nativeStopFMODUpdateThread: Joining FMOD update thread...");
                updateThread->join();
                LOGI("nativeStopFMODUpdateThread: FMOD update thread joined.");
            } else {
                LOGW("nativeStopFMODUpdateThread: Update thread existed but was not joinable.");
            }
            delete updateThread;
            updateThread = nullptr;
            LOGI("nativeStopFMODUpdateThread: Custom FMOD update thread object deleted.");
        } else {
            LOGW("nativeStopFMODUpdateThread: isRunning was true, but updateThread object was null.");
        }
    } else {
        if (updateThread != nullptr) {
            LOGW("nativeStopFMODUpdateThread: isRunning was false, but updateThread object existed. Deleting.");
            if (updateThread->joinable()) { // Should not be joinable if not running, but just in case
                updateThread->join();
            }
            delete updateThread;
            updateThread = nullptr;
        }
        LOGI("nativeStopFMODUpdateThread: Custom FMOD update thread was not running or already signaled to stop.");
    }
}

} // extern "C"