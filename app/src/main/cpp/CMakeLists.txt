cmake_minimum_required(VERSION 3.22.1)

project("pjm")

add_library(
    pjm
    SHARED
    native-lib.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    pjm
    ${log-lib}
)