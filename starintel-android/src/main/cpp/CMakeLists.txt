cmake_minimum_required(VERSION 3.22.1)
project(starintel_lisp LANGUAGES CXX)

add_library(starintel_lisp SHARED starintel_lisp_jni.cpp)
target_compile_features(starintel_lisp PRIVATE cxx_std_17)
target_compile_options(starintel_lisp PRIVATE -Wall -Wextra -Werror)

# The ECL/Tek9 image is supplied by the reproducible native build as:
#   <root>/<android-abi>/libstarintel_ecl_adapter.so
# Keeping the adapter optional lets host CI exercise the Kotlin/JNI contract before those
# ABI artifacts exist; the app then reports a precise unavailable status.
set(_adapter "${STARINTEL_ECL_ADAPTER_ROOT}/${ANDROID_ABI}/libstarintel_ecl_adapter.so")
if(STARINTEL_ECL_ADAPTER_ROOT AND EXISTS "${_adapter}")
    add_library(starintel_ecl_adapter SHARED IMPORTED)
    set_target_properties(starintel_ecl_adapter PROPERTIES IMPORTED_LOCATION "${_adapter}")
    target_compile_definitions(starintel_lisp PRIVATE STARINTEL_HAS_ECL_ADAPTER=1)
    target_link_libraries(starintel_lisp PRIVATE starintel_ecl_adapter)
endif()
