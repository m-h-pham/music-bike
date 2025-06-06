    cmake_minimum_required( VERSION 3.4.1 )

    set( LIB_FMOD ${FMOD_API_ROOT}/core/lib/${ANDROID_ABI}/libfmod${FMOD_LIB_SUFFIX}.so )
    set( LIB_FMODSTUDIO ${FMOD_API_ROOT}/studio/lib/${ANDROID_ABI}/libfmodstudio${FMOD_LIB_SUFFIX}.so )
    
add_library( fmod SHARED IMPORTED )
set_target_properties(
        fmod PROPERTIES
        IMPORTED_LOCATION ${LIB_FMOD}
        INTERFACE_INCLUDE_DIRECTORIES ${FMOD_API_ROOT}/core/inc
        )

add_library( fmodstudio SHARED IMPORTED )
set_target_properties(
        fmodstudio PROPERTIES
        IMPORTED_LOCATION ${LIB_FMODSTUDIO}
        INTERFACE_INCLUDE_DIRECTORIES ${FMOD_API_ROOT}/studio/inc
        )
    
add_library(
        example SHARED
        common.cpp
        common_platform.cpp
        3d_multi.cpp
        )

target_link_libraries( example PRIVATE fmod fmodstudio "-Wl,-z,max-page-size=16384" )
