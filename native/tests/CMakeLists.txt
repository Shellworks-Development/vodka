add_executable(vodka_container_test container_test.cpp)
target_link_libraries(vodka_container_test PRIVATE vodka_container)

add_executable(vodka_archive_test archive_test.cpp)
target_link_libraries(vodka_archive_test PRIVATE vodka_archive)

add_executable(vodka_runtime_test runtime_test.cpp)
target_link_libraries(vodka_runtime_test PRIVATE vodka_runtime)

if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
  target_compile_options(vodka_container_test PRIVATE -Wall -Wextra)
  target_compile_options(vodka_archive_test PRIVATE -Wall -Wextra)
  target_compile_options(vodka_runtime_test PRIVATE -Wall -Wextra)
endif()

add_test(NAME container_test COMMAND vodka_container_test)
add_test(NAME archive_test COMMAND vodka_archive_test)
add_test(NAME runtime_test COMMAND vodka_runtime_test)
