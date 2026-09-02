cmake_minimum_required(VERSION 3.22.1)

project("videorecognition_native")

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -frtti -fexceptions")
set(ANDROID_STL c++_shared)

# 本模块自包含的 OpenCV 4 预编译库（与 imageCVdeal 相同的项目结构：
# include/ 头文件 + libs/<abi>/libopencv_java4.so），
# .so 通过本模块 jniLibs 打包进 APK
set(OPENCV_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/include")
set(OPENCV_SO_PATH "${CMAKE_CURRENT_SOURCE_DIR}/libs/${ANDROID_ABI}/libopencv_java4.so")

add_library(opencv_java4 SHARED IMPORTED)
set_target_properties(opencv_java4 PROPERTIES
        IMPORTED_LOCATION "${OPENCV_SO_PATH}"
        INTERFACE_INCLUDE_DIRECTORIES "${OPENCV_INCLUDE_DIR}"
)

add_library(${CMAKE_PROJECT_NAME} SHARED
        FaceDetector.cpp
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${OPENCV_INCLUDE_DIR}
)

target_link_libraries(${CMAKE_PROJECT_NAME}
        opencv_java4
        android
        log
        jnigraphics
)
