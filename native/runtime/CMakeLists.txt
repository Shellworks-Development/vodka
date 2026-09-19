add_library(vodka_runtime STATIC
  src/launch_plan.cpp
)

target_include_directories(vodka_runtime PUBLIC
  $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
)

if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
  target_compile_options(vodka_runtime PRIVATE -Wall -Wextra -Wpedantic)
endif()
