#include <jni.h>
#include <android/log.h>
#include <fmod_studio.hpp>
#include <fmod.hpp>
#include <fmod_errors.h>
#include <thread>
#include <atomic>
#include <mutex>

#define LOG_TAG "FMOD_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global variables
FMOD::Studio::System* studioSystem = nullptr;
FMOD::Studio::EventInstance* eventInstance = nullptr;
std::thread* updateThread = nullptr;
std::atomic<bool> isRunning(false);
std::mutex fmodMutex;

// Helper function to check FMOD errors
bool checkFMODError(FMOD_RESULT result, const char* function) {
    if (result != FMOD_OK) {
        LOGE("%s failed: %s", function, FMOD_ErrorString(result));
        return false;
    }
    return true;
}

// Background thread function to update FMOD
void fmodUpdateThread() {
    LOGI("FMOD update thread started");
    while (isRunning) {
        {
            std::lock_guard<std::mutex> lock(fmodMutex);
            if (studioSystem) {
                studioSystem->update();
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(16)); // ~60 fps
    }
    LOGI("FMOD update thread stopped");
}

extern "C" {

// Updated function with two jstring parameters
JNIEXPORT void JNICALL
Java_com_app_musicbike_ui_activities_MainActivity_startFMODPlayback(JNIEnv *env, jobject obj,
                                                                    jstring masterBankPath,
                                                                    jstring stringsBankPath) {
    const char *masterBankPathCStr = env->GetStringUTFChars(masterBankPath, 0);
    const char *stringsBankPathCStr = env->GetStringUTFChars(stringsBankPath, 0);

    LOGI("startFMODPlayback called with Master bank path: %s", masterBankPathCStr);

    std::lock_guard<std::mutex> lock(fmodMutex);
    FMOD_RESULT result;

    // Initialize FMOD system if not already
    if (studioSystem == nullptr) {
        result = FMOD::Studio::System::create(&studioSystem);
        if (!checkFMODError(result, "System::create")) return;

        result = studioSystem->initialize(128, FMOD_STUDIO_INIT_NORMAL, FMOD_INIT_NORMAL, nullptr);
        if (!checkFMODError(result, "System::initialize")) {
            studioSystem->release();
            studioSystem = nullptr;
            return;
        }
    }

    // Stop and release existing event instance
    if (eventInstance) {
        FMOD_STUDIO_PLAYBACK_STATE state;
        eventInstance->getPlaybackState(&state);
        if (state == FMOD_STUDIO_PLAYBACK_PLAYING) {
            eventInstance->stop(FMOD_STUDIO_STOP_IMMEDIATE);
        }
        eventInstance->release();
        eventInstance = nullptr;
    }

    // Unload all previously loaded banks
    int bankCount = 0;
    FMOD::Studio::Bank *loadedBanks[16];  // adjust size if needed
    studioSystem->getBankList(loadedBanks, 16, &bankCount);
    for (int i = 0; i < bankCount; ++i) {
        loadedBanks[i]->unload();
    }

    // Load master bank
    FMOD::Studio::Bank *bank = nullptr;
    result = studioSystem->loadBankFile(masterBankPathCStr, FMOD_STUDIO_LOAD_BANK_NORMAL, &bank);
    if (!checkFMODError(result, "loadBankFile (Master)")) {
        studioSystem->release();
        studioSystem = nullptr;
        return;
    }

    // Load optional strings bank
    FMOD::Studio::Bank *stringsBank = nullptr;
    result = studioSystem->loadBankFile(stringsBankPathCStr, FMOD_STUDIO_LOAD_BANK_NORMAL, &stringsBank);
    if (result != FMOD_OK) {
        LOGI("No strings bank loaded (optional)");
    }

    // Load event
    FMOD::Studio::EventDescription *eventDescription = nullptr;
    result = studioSystem->getEvent("event:/Bike", &eventDescription);
    if (!checkFMODError(result, "getEvent")) {
        studioSystem->release();
        studioSystem = nullptr;
        return;
    }

    result = eventDescription->createInstance(&eventInstance);
    if (!checkFMODError(result, "createInstance")) {
        studioSystem->release();
        studioSystem = nullptr;
        return;
    }

    if (!isRunning) {
        isRunning = true;
        if (updateThread) {
            delete updateThread;
        }
        updateThread = new std::thread(fmodUpdateThread);
    }

    env->ReleaseStringUTFChars(masterBankPath, masterBankPathCStr);
    env->ReleaseStringUTFChars(stringsBankPath, stringsBankPathCStr);
}



JNIEXPORT void JNICALL
Java_com_app_musicbike_ui_activities_MainActivity_setFMODParameter(JNIEnv *env, jobject obj, jstring paramName, jfloat value) {
    const char *paramNameCStr = env->GetStringUTFChars(paramName, 0);

    std::lock_guard<std::mutex> lock(fmodMutex);
    if (studioSystem) { // NOTE: use studioSystem, not eventInstance
        FMOD_RESULT result = studioSystem->setParameterByName(paramNameCStr, value);
        if (result != FMOD_OK) {
            LOGE("Failed to set FMOD parameter '%s': %s", paramNameCStr, FMOD_ErrorString(result));
        } else {
            LOGI("FMOD global parameter '%s' set to %f", paramNameCStr, value);
        }
    } else {
        LOGE("No studio system to set parameter on");
    }

    env->ReleaseStringUTFChars(paramName, paramNameCStr);
}

JNIEXPORT void JNICALL
Java_com_app_musicbike_ui_activities_MainActivity_toggleFMODPlayback(JNIEnv *, jobject) {
    bool isPlaying = false;
    FMOD_STUDIO_PLAYBACK_STATE state;
    if (eventInstance && eventInstance->getPlaybackState(&state) == FMOD_OK) {
        isPlaying = (state == FMOD_STUDIO_PLAYBACK_PLAYING);
    }

    if (!isPlaying) {
        eventInstance->start(); // cold start if it hasn't played yet
    } else {
        bool isPaused = false;
        if (eventInstance) {
            eventInstance->getPaused(&isPaused);
            eventInstance->setPaused(!isPaused);
        }

        // ✅ Pause/resume the entire master bus
        FMOD::Studio::Bus* masterBus = nullptr;
        if (studioSystem->getBus("bus:/", &masterBus) == FMOD_OK && masterBus) {
            masterBus->setPaused(!isPaused);
            LOGI("Master bus paused state set to %d", !isPaused);
        }
    }
}


JNIEXPORT void JNICALL
Java_com_app_musicbike_ui_activities_MainActivity_playFMODEvent(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(fmodMutex);

    if (!eventInstance) {
        LOGE("Cannot play event: eventInstance is null.");
        return;
    }

    FMOD_RESULT result = eventInstance->start();
    if (result != FMOD_OK) {
        LOGE("Failed to start FMOD event: %s", FMOD_ErrorString(result));
    } else {
        LOGI("FMOD event started (play).");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_app_musicbike_ui_activities_MainActivity_isFMODPaused(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(fmodMutex);

    if (!eventInstance) {
        LOGE("isFMODPaused: eventInstance is null.");
        return JNI_TRUE; // Assume paused if unknown
    }

    bool isPaused = true;
    FMOD_RESULT result = eventInstance->getPaused(&isPaused);
    if (result != FMOD_OK) {
        LOGE("getPaused failed: %s", FMOD_ErrorString(result));
        return JNI_TRUE; // Assume paused on error
    }

    return isPaused ? JNI_TRUE : JNI_FALSE;
}

}