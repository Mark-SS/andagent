cmake_minimum_required(VERSION 3.22)
project("andagent" C)

# NDK 26 links with 4 KB LOAD segments by default; Android 15+ 16 KB page-size
# devices require every shared object to use 16 KB LOAD alignment.
add_link_options("-Wl,-z,max-page-size=16384")

add_library(memexec SHARED memexec.c)
target_link_libraries(memexec log)

add_library(scriptinterrupt SHARED scriptinterrupt.c)
target_link_libraries(scriptinterrupt dl)
