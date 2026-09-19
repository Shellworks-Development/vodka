cmake_minimum_required(VERSION 3.22)
project(vodka_app LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(VODKA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(
  ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native
  ${CMAKE_CURRENT_BINARY_DIR}/vodka_native
)

add_library(vodka_jni SHARED vodka_jni.cpp archive_jni.cpp runtime_jni.cpp)
target_link_libraries(vodka_jni PRIVATE vodka_container vodka_archive vodka_runtime)

find_library(log-lib log)
target_link_libraries(vodka_jni PRIVATE ${log-lib})
