cmake_minimum_required(VERSION 3.16)
project(realworld_drogon CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

find_package(Drogon CONFIG REQUIRED)
find_package(OpenSSL REQUIRED)

add_executable(realworld
  src/main.cc
  src/common.cc
  src/auth.cc
  src/users.cc
  src/profiles.cc
  src/articles.cc
  src/comments.cc
)
target_link_libraries(realworld PRIVATE Drogon::Drogon OpenSSL::Crypto)
# Drogon's own FindSQLite3 module exports the SQLite3_lib target.
if(TARGET SQLite3_lib)
  target_link_libraries(realworld PRIVATE SQLite3_lib)
else()
  find_package(SQLite3 REQUIRED)
  target_link_libraries(realworld PRIVATE SQLite::SQLite3)
endif()
