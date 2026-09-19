find_package(ZLIB REQUIRED)

add_library(vodka_archive STATIC
  src/archive.cpp
)

target_include_directories(vodka_archive PUBLIC
  $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
)

target_link_libraries(vodka_archive PUBLIC ZLIB::ZLIB)

if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
  target_compile_options(vodka_archive PRIVATE -Wall -Wextra -Wpedantic)
endif()
