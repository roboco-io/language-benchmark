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
  src/db.cc
  src/auth.cc
  src/util.cc
  src/users.cc
  src/articles.cc)
target_link_libraries(realworld PRIVATE Drogon::Drogon OpenSSL::Crypto)
