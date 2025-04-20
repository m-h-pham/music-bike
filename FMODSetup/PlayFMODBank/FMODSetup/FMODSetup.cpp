#include <iostream>
#include <conio.h>
#include <windows.h>
#include <thread>
#include <fmod_studio.hpp>
#include <fmod.hpp>
#include <fmod_errors.h>

void checkError(FMOD_RESULT result) {
    if (result != FMOD_OK) {
        std::cerr << "FMOD error: " << FMOD_ErrorString(result) << "\n";
        exit(-1);
    }
}

int main()
{
    HRESULT hr = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    if (FAILED(hr)) {
        std::cerr << "COM initialization failed.\n";
        return -1;
    }

    FMOD::Studio::System* studioSystem = nullptr;
    FMOD_RESULT result;

    result = FMOD::Studio::System::create(&studioSystem);
    checkError(result);

    result = studioSystem->initialize(32, FMOD_STUDIO_INIT_NORMAL, FMOD_INIT_NORMAL, nullptr);
    checkError(result);

    // Load your bank
    FMOD::Studio::Bank* bank = nullptr;
    result = studioSystem->loadBankFile("C:\\dev\\bike_app\\music-bike\\FMODSetup\\PlayFMODBank\\Build\\Desktop\\Master.bank", FMOD_STUDIO_LOAD_BANK_NORMAL, &bank);
    checkError(result);

    // OPTIONAL: if you also have a strings bank (recommended)
    FMOD::Studio::Bank* stringsBank = nullptr;
    result = studioSystem->loadBankFile("C:\\dev\\bike_app\\music-bike\\FMODSetup\\PlayFMODBank\\Build\\Desktop\\Master.strings.bank", FMOD_STUDIO_LOAD_BANK_NORMAL, &stringsBank);
    if (result != FMOD_OK) std::cout << "[Warning] No strings bank loaded (optional).\n";

    // Get the event
    FMOD::Studio::EventDescription* eventDescription = nullptr;
    result = studioSystem->getEvent("event:/Bike", &eventDescription); // Change to match your event name
    checkError(result);

    FMOD::Studio::EventInstance* eventInstance = nullptr;

    std::cout << "FMOD Bank Player\n";
    std::cout << "============================\n";
    std::cout << "Press P: Play 'event:/Playbike'\n";
    std::cout << "Press Q: Quit\n";
    std::cout << "============================\n";

    bool running = true;

    while (running) {
        if (_kbhit()) {
            char key = _getch();
            switch (key) {
            case 'P':
            case 'p':
                result = eventDescription->createInstance(&eventInstance);
                checkError(result);

                result = eventInstance->start();
                checkError(result);

                std::cout << "[Playing] event:/Playbike\n";

                // Optional: release after playing (for one-shots)
                result = eventInstance->release();
                checkError(result);
                break;

            case 'Q':
            case 'q':
                running = false;
                break;
            }
        }

        studioSystem->update();
        Sleep(50);
    }

    studioSystem->unloadAll();
    studioSystem->release();
    CoUninitialize();

    std::cout << "Exited. Goodbye!\n";
    return 0;
}
