cmake_minimum_required(VERSION 3.16)
project(realworld_backend CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

find_package(Drogon CONFIG REQUIRED)
find_package(OpenSSL REQUIRED)
find_package(SQLite3 REQUIRED)
set(RW_SQLITE_LIB SQLite3::SQLite3)
if(NOT TARGET SQLite::SQLite3)
  set(RW_SQLITE_LIB sqlite3)
else()
  set(RW_SQLITE_LIB SQLite::SQLite3)
endif()

add_executable(realworld
  src/main.cc
  src/api.cc)
target_link_libraries(realworld PRIVATE Drogon::Drogon OpenSSL::Crypto ${RW_SQLITE_LIB})
