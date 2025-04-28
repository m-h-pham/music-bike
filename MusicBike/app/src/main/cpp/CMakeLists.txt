cmake_minimum_required(VERSION 3.4.1)

# Assuming CMakeLists.txt is in app/src/main/cpp/
# and jniLibs is in app/
set(FMOD_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../jniLibs)
set(FMOD_INC ${CMAKE_CURRENT_SOURCE_DIR}/../../../inc)

# Set paths based on the current ABI being built
set(LIB_FMOD ${FMOD_ROOT}/${ANDROID_ABI}/libfmod.so)
set(LIB_FMODSTUDIO ${FMOD_ROOT}/studio/${ANDROID_ABI}/libfmodstudio.so)

# Import the FMOD library
add_library(fmod SHARED IMPORTED)
set_target_properties(
        fmod PROPERTIES
        IMPORTED_LOCATION ${LIB_FMOD}
        INTERFACE_INCLUDE_DIRECTORIES ${FMOD_INC}
)

add_library(fmodstudio SHARED IMPORTED)
set_target_properties(
        fmodstudio PROPERTIES
        IMPORTED_LOCATION ${LIB_FMODSTUDIO}
        INTERFACE_INCLUDE_DIRECTORIES ${FMOD_INC}
)

# Add your own native code (musicbike)
add_library(musicbike SHARED
        musicbike.cpp)

# Link everything together
target_link_libraries(musicbike PRIVATE
        fmod
        fmodstudio
        android
        log
        "-Wl,-z,max-page-size=16384")