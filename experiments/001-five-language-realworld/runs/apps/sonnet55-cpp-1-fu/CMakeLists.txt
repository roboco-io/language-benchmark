cmake_minimum_required(VERSION 3.16)
project(realworld_app CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

find_package(Drogon CONFIG REQUIRED)
find_library(SQLITE3_LIB NAMES sqlite3 REQUIRED)
find_path(SQLITE3_INCLUDE NAMES sqlite3.h REQUIRED)
find_package(OpenSSL REQUIRED)

add_executable(realworld src/main.cc)
target_link_libraries(realworld PRIVATE Drogon::Drogon OpenSSL::Crypto ${SQLITE3_LIB})
target_include_directories(realworld PRIVATE ${SQLITE3_INCLUDE})
