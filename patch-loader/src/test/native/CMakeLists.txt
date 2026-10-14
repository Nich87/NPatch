cmake_minimum_required(VERSION 3.20)
project(sig_bypass_paths_test LANGUAGES CXX)

add_executable(sig_bypass_paths_test sig_bypass_paths_test.cpp)
target_compile_features(sig_bypass_paths_test PRIVATE cxx_std_23)

enable_testing()
add_test(NAME sig_bypass_paths COMMAND sig_bypass_paths_test)
