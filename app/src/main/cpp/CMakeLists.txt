cmake_minimum_required(VERSION 3.22)
project("andagent" C)

add_library(memexec SHARED memexec.c)
target_link_libraries(memexec log)

add_library(scriptinterrupt SHARED scriptinterrupt.c)
target_link_libraries(scriptinterrupt dl)
target_link_options(scriptinterrupt PRIVATE "-Wl,-z,max-page-size=16384")
