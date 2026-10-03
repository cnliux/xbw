# 前端 JNI 宿主：libxbwcore.so。
# 不链接核心 —— 核心（libfceumm.so 等）运行时 dlopen，方便增删核心不改前端。
cmake_minimum_required(VERSION 3.22.1)
project(xbwcore C)

add_library(xbwcore SHARED xbw_core.c)

target_include_directories(xbwcore PRIVATE
  # cpp → main → src → app → 仓库根
  ${CMAKE_CURRENT_SOURCE_DIR}/../../../../vendor/libretro-common/include
)

target_link_libraries(xbwcore PRIVATE
  android          # ANativeWindow
  log
  dl               # dlopen（bionic 里 libc 自带，声明无害）
)

target_compile_options(xbwcore PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter)
target_link_options(xbwcore PRIVATE "-Wl,-z,max-page-size=16384")
