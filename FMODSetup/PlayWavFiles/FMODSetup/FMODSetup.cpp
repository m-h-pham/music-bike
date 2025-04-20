#include <iostream>
#include <conio.h>        // For _kbhit() and _getch()
#include <windows.h>
#include "fmod.hpp"
#include "fmod_errors.h"

void checkError(FMOD_RESULT result) {
    if (result != FMOD_OK) {
        std::cerr << "FMOD error: " << FMOD_ErrorString(result) << "\n";
        exit(-1);
    }
}

void changeVolume(FMOD::Channel* channel, float volume) {
    if (channel != nullptr) {
        channel->setVolume(volume);
        std::cout << "[Volume] Set volume to: " << volume << "\n";
    }
}

void changePitch(FMOD::Channel* channel, float pitch) {
    if (channel != nullptr) {
        channel->setPitch(pitch);
        std::cout << "[Pitch] Set pitch to: " << pitch << "\n";
    }
}

int main()
{
    HRESULT hr = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    if (FAILED(hr)) {
        std::cerr << "COM initialization failed.\n";
        return -1;
    }

    FMOD::System* system = nullptr;
    FMOD_RESULT result = FMOD::System_Create(&system);
    checkError(result);

    result = system->init(32, FMOD_INIT_NORMAL, nullptr);
    checkError(result);

    // Load the sounds
    FMOD::Sound* drumloop = nullptr;
    FMOD::Sound* jaguar = nullptr;
    FMOD::Sound* swish = nullptr;
    FMOD::Sound* imperial_march = nullptr;

    result = system->createSound("C:\\dev\\bike_app\\music-bike\\FMODSetup\\PlayWavFiles\\sounds\\drumloop.wav", FMOD_DEFAULT, nullptr, &drumloop);
    checkError(result);

    result = system->createSound("C:\\dev\\bike_app\\music-bike\\FMODSetup\\PlayWavFiles\\sounds\\jaguar.wav", FMOD_DEFAULT, nullptr, &jaguar);
    checkError(result);

    result = system->createSound("C:\\dev\\bike_app\\music-bike\\FMODSetup\\PlayWavFiles\\sounds\\swish.wav", FMOD_DEFAULT, nullptr, &swish);
    checkError(result);

    result = system->createSound("C:\\dev\\bike_app\\music-bike\\FMODSetup\\PlayWavFiles\\sounds\\imperial_march.wav", FMOD_DEFAULT, nullptr, &imperial_march);
    checkError(result);

    std::cout << "FMOD Sound Player\n";
    std::cout << "============================\n";
    std::cout << "Press 1: Play drumloop.wav\n";
    std::cout << "Press 2: Play jaguar.wav\n";
    std::cout << "Press 3: Play swish.wav\n";
    std::cout << "Press 4: Play imperial_march.wav\n";
    std::cout << "Press V: Adjust volume\n";
    std::cout << "Press P: Adjust pitch\n";
    std::cout << "Press q: Quit\n";
    std::cout << "============================\n";

    bool running = true;
    FMOD::Channel* channel = nullptr;

    while (running) {
        if (_kbhit()) {
            char key = _getch();

            switch (key) {
            case '1':
                system->playSound(drumloop, nullptr, false, &channel);
                std::cout << "[Playing] drumloop.wav\n";
                break;
            case '2':
                system->playSound(jaguar, nullptr, false, &channel);
                std::cout << "[Playing] jaguar.wav\n";
                break;
            case '3':
                system->playSound(swish, nullptr, false, &channel);
                std::cout << "[Playing] swish.wav\n";
                break;
            case '4':
                system->playSound(imperial_march, nullptr, false, &channel);
                std::cout << "[Playing] imperial_march.wav\n";
                break;
            case 'V': case 'v':
            {
                float volume;
                std::cout << "Enter volume (0.0 to 1.0): ";
                std::cin >> volume;
                changeVolume(channel, volume);
            }
            break;
            case 'P': case 'p':
            {
                float pitch;
                std::cout << "Enter pitch (e.g., 1.0 for normal, 0.5 for half speed, 2.0 for double speed): ";
                std::cin >> pitch;
                changePitch(channel, pitch);
            }
            break;
            case 'q':
            case 'Q':
                running = false;
                break;
            default:
                break;
            }
        }

        system->update();
        Sleep(50); // Avoid CPU spinning
    }

    // Cleanup
    drumloop->release();
    jaguar->release();
    swish->release();
    system->close();
    system->release();
    CoUninitialize();

    std::cout << "Exited. Goodbye!\n";
    return 0;
}
