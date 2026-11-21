cmake_minimum_required(VERSION 3.22.1)
project(materialpods)
set(CMAKE_CXX_STANDARD 23)

# Exempts hidden APIs so core can build the L2CAP BluetoothSocket to the AirPods.
add_library(hiddenapi SHARED hiddenapi.cpp)
target_compile_options(hiddenapi PRIVATE -O2 -fvisibility=hidden)
target_link_libraries(hiddenapi android log)
