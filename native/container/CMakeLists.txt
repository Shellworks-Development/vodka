add_library(vodka_container STATIC
  src/capability.cpp
  src/container.cpp
)

target_include_directories(vodka_container PUBLIC
  $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
)

if(CMAKE_SYSTEM_NAME STREQUAL "Linux" OR CMAKE_SYSTEM_NAME STREQUAL "Android")
  target_compile_definitions(vodka_container PRIVATE _GNU_SOURCE)
endif()

if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
  target_compile_options(vodka_container PRIVATE -Wall -Wextra -Wpedantic)
endif()
