# Builds adrenotools from a copy of the submodule with extras/adrenotools-patches/*.patch applied.
# The submodule checkout is not changed. A patch that no longer applies stops the configure step.

set(_adrenotools_src ${CMAKE_CURRENT_LIST_DIR}/adrenotools)
set(_adrenotools_dst ${CMAKE_BINARY_DIR}/adrenotools-patched)

if(NOT EXISTS ${_adrenotools_src}/lib/linkernsbypass/android_linker_ns.cpp)
    message(FATAL_ERROR "extras/adrenotools is empty: run git submodule update --init --recursive app/src/main/cpp/extras/adrenotools")
endif()

find_program(PATCH_EXECUTABLE patch REQUIRED)

file(GLOB _adrenotools_patches ${CMAKE_CURRENT_LIST_DIR}/adrenotools-patches/*.patch)
list(SORT _adrenotools_patches)

file(REMOVE_RECURSE ${_adrenotools_dst})
file(COPY ${_adrenotools_src}/ DESTINATION ${_adrenotools_dst} PATTERN ".git" EXCLUDE)

foreach(_patch ${_adrenotools_patches})
    execute_process(COMMAND ${PATCH_EXECUTABLE} -p1 --forward --batch --fuzz=0 -i ${_patch}
                    WORKING_DIRECTORY ${_adrenotools_dst}
                    RESULT_VARIABLE _patch_result
                    OUTPUT_VARIABLE _patch_output
                    ERROR_VARIABLE _patch_output)
    if(NOT _patch_result EQUAL 0)
        message(FATAL_ERROR "${_patch} does not apply to extras/adrenotools:\n${_patch_output}")
    endif()
endforeach()

set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${_adrenotools_patches})

add_subdirectory(${_adrenotools_dst} ${CMAKE_BINARY_DIR}/adrenotools-build)
