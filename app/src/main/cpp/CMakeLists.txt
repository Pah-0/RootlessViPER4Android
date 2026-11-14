cmake_minimum_required(VERSION 3.22.1)
project(viper_rootless LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(VERSION_CODE "0" CACHE STRING "Engine version code")
set(VERSION_NAME "0.0.0" CACHE STRING "Engine version name")
add_compile_definitions(VERSION_CODE=${VERSION_CODE} VERSION_NAME="${VERSION_NAME}")

# ViPERDSP is a git submodule; its static library adds -flto, so the final
# link needs LTO too.
add_subdirectory(ViPERDSP)

add_library(viper_rootless SHARED viper_engine.cpp viper_jni.cpp)
target_link_libraries(viper_rootless PRIVATE ViPERDSP log)
target_compile_options(viper_rootless PRIVATE -O3 -flto -fvisibility=hidden)
target_link_options(viper_rootless PRIVATE -flto -Wl,--gc-sections)
