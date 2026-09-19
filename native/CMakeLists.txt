cmake_minimum_required(VERSION 3.20)
project(vodka_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

add_subdirectory(container)
add_subdirectory(archive)
add_subdirectory(runtime)

option(VODKA_BUILD_TESTS "Build Vodka native tests" ON)
if(VODKA_BUILD_TESTS)
  enable_testing()
  add_subdirectory(tests)
endif()
