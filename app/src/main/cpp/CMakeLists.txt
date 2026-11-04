cmake_minimum_required(VERSION 3.22)
project("andagent" C)

add_library(memexec SHARED memexec.c)
target_link_libraries(memexec log)
