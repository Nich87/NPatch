project(npatch)
cmake_minimum_required(VERSION 3.31.1)

set(CMAKE_CXX_STANDARD 23)
add_compile_definitions(
        VERSION_CODE=${VERSION_CODE}
        VERSION_NAME=\"${VERSION_NAME}\")
add_subdirectory(${CORE_ROOT} core)

# CONFIGURE_DEPENDS (unlike aux_source_directory, which only globs once at configure time and
# has no way to notice a file added later) makes CMake re-check this glob on every build and
# re-run configure when it changes -- so a new .cpp dropped into src/ or src/jni/ is picked up
# on the next build instead of silently missing from SRC_LIST until something else forces a
# reconfigure (e.g. editing CMakeLists.txt itself), which then fails the link with an undefined
# symbol for whatever that new file was supposed to define.
file(GLOB SRC_LIST CONFIGURE_DEPENDS src/*.cpp src/jni/*.cpp)
list(APPEND SRC_LIST api/patch_main.cpp)

add_library(${PROJECT_NAME} SHARED ${SRC_LIST})

target_include_directories(${PROJECT_NAME} PUBLIC include)
target_include_directories(${PROJECT_NAME} PRIVATE src)
target_include_directories(${PROJECT_NAME} PRIVATE ${CORE_ROOT}/include)
target_include_directories(${PROJECT_NAME} PRIVATE ${EXTERNAL_ROOT}/fmt/include)
target_include_directories(${PROJECT_NAME} PRIVATE ${EXTERNAL_ROOT}/dobby/include)
target_include_directories(${PROJECT_NAME} PRIVATE ${EXTERNAL_ROOT}/lsplant/lsplant/src/main/jni/include)
target_compile_definitions(${PROJECT_NAME} PRIVATE
        VERSION_CODE=${VERSION_CODE}
        VERSION_NAME=\"${VERSION_NAME}\")
target_compile_options(${PROJECT_NAME} PRIVATE
        -ffunction-sections
        -fdata-sections
        -fvisibility=hidden
        -fvisibility-inlines-hidden)
target_link_options(${PROJECT_NAME} PRIVATE
        $<$<CONFIG:Release>:-Wl,--gc-sections>
        $<$<CONFIG:Release>:-Wl,--exclude-libs,ALL>
        $<$<CONFIG:RelWithDebInfo>:-Wl,--gc-sections>
        $<$<CONFIG:RelWithDebInfo>:-Wl,--exclude-libs,ALL>)

target_link_libraries(${PROJECT_NAME} native log)

if (DEFINED DEBUG_SYMBOLS_PATH)
    set(DEBUG_SYMBOLS_PATH ${DEBUG_SYMBOLS_PATH}/${API})
    message(STATUS "Debug symbols will be placed at ${DEBUG_SYMBOLS_PATH}")
    add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E make_directory ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}
            COMMAND ${CMAKE_OBJCOPY} --only-keep-debug $<TARGET_FILE:${PROJECT_NAME}>
            ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}/${PROJECT_NAME}.debug
            COMMAND ${CMAKE_STRIP} --strip-all $<TARGET_FILE:${PROJECT_NAME}>
            COMMAND ${CMAKE_OBJCOPY} --add-gnu-debuglink ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}/${PROJECT_NAME}.debug
            $<TARGET_FILE:${PROJECT_NAME}>)
endif()
