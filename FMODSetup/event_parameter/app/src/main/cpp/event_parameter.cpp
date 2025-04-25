#include "fmod_studio.hpp"
#include "fmod.hpp"
#include "common.h"

int FMOD_Main()
{
    void *extraDriverData = NULL;
    Common_Init(&extraDriverData);

    FMOD::Studio::System* system = NULL;
    ERRCHECK(FMOD::Studio::System::create(&system));
    ERRCHECK(system->initialize(1024, FMOD_STUDIO_INIT_NORMAL, FMOD_INIT_NORMAL, extraDriverData));

    FMOD::Studio::Bank* masterBank = NULL;
    ERRCHECK(system->loadBankFile(Common_MediaPath("Master.bank"), FMOD_STUDIO_LOAD_BANK_NORMAL, &masterBank));

    FMOD::Studio::Bank* stringsBank = NULL;
    ERRCHECK(system->loadBankFile(Common_MediaPath("Master.strings.bank"), FMOD_STUDIO_LOAD_BANK_NORMAL, &stringsBank));

    FMOD::Studio::Bank* sfxBank = NULL;
    ERRCHECK(system->loadBankFile(Common_MediaPath("SFX.bank"), FMOD_STUDIO_LOAD_BANK_NORMAL, &sfxBank));

    FMOD::Studio::EventDescription* eventDescription = NULL;
    ERRCHECK(system->getEvent("event:/Bike", &eventDescription));

    // Make sure 'eventInstance' is the correct instance of your event
    FMOD::Studio::EventInstance* eventInstance = NULL;
    ERRCHECK(eventDescription->createInstance(&eventInstance));

    // Make the event audible to start with
    float wheelspeedParameterValue = 0.0f;

    // Create the event instance but don't modify the Wheel Speed here as it's a global parameter
    ERRCHECK(eventInstance->start());

    do
    {
        Common_Update();

        if (Common_BtnPress(BTN_MORE))
        {
            // Start the event playback
            ERRCHECK(eventInstance->start());
        }

        if (Common_BtnPress(BTN_ACTION1))
        {
            // Decrease the wheel speed parameter
            wheelspeedParameterValue = Common_Max(0.0f, wheelspeedParameterValue - 5.0f);
            // Set the global parameter 'Wheel Speed' using FMOD::Studio::System
            ERRCHECK(system->setParameterByName("Wheel Speed", wheelspeedParameterValue));
        }

        if (Common_BtnPress(BTN_ACTION2))
        {
            // Increase the wheel speed parameter
            wheelspeedParameterValue = Common_Min(100.0f, wheelspeedParameterValue + 5.0f);
            // Set the global parameter 'Wheel Speed' using FMOD::Studio::System
            ERRCHECK(system->setParameterByName("Wheel Speed", wheelspeedParameterValue));
        }

        ERRCHECK(system->update());

        float userValue = 0.0f;
        float finalValue = 0.0f;
        // We don't need to call getParameterByID for the global parameter.
        // But you can get the current value using system->getParameterByName.
        ERRCHECK(system->getParameterByName("Wheel Speed", &userValue, &finalValue));

        Common_Draw("==================================================");
        Common_Draw("Event Parameter Example.");
        Common_Draw("Wheel Speed = (user: %1.1f, final: %1.1f)", userValue, finalValue);
        Common_Draw("Press %s to play event", Common_BtnStr(BTN_MORE));
        Common_Draw("Press %s to decrease value", Common_BtnStr(BTN_ACTION1));
        Common_Draw("Press %s to increase value", Common_BtnStr(BTN_ACTION2));
        Common_Draw("Press %s to quit", Common_BtnStr(BTN_QUIT));
        Common_Sleep(50);

    } while (!Common_BtnPress(BTN_QUIT));

    ERRCHECK(system->release());
    Common_Close();

    return 0;
}
