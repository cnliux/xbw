# ─────────────────────────────────────────────────────────────────────
# mGBA（GBA libretro 核心）NDK 构建桥。
# 上游 CMake 自带 libretro 目标（STATIC/SHARED + link.T version script），
# 这里只做选项裁剪：关前端/GL/工具，开 BUILD_LIBRETRO，库静态进核心。
# 产物：libmgba.so（上游前缀为空，需改回 lib 前缀 AGP 才会打包）。
# ─────────────────────────────────────────────────────────────────────
cmake_minimum_required(VERSION 3.22.1)
project(mgba_bridge C)

set(MGBA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../vendor/mgba)
if(NOT EXISTS ${MGBA_DIR}/CMakeLists.txt)
  message(FATAL_ERROR "vendor/mgba 源码缺失，请先 git clone libretro/mgba")
endif()

# 前端与一切可选依赖关闭；静态核心 + libretro 目标
foreach(opt IN ITEMS QT SDL PERF TEST SUITE CINEMA HEADLESS EXAMPLE PYTHON
                    DOCGEN MAINTAINER_TOOLS PGO LTO GL GLES2 GLES3 UPDATER)
  set(BUILD_${opt} OFF CACHE BOOL "" FORCE)
endforeach()
set(BUILD_LIBRETRO ON CACHE BOOL "" FORCE)
set(BUILD_STATIC ON CACHE BOOL "" FORCE)
set(BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(BUILD_TEST OFF CACHE BOOL "" FORCE)
set(USE_GDB_STUB OFF CACHE BOOL "" FORCE)
set(BUILD_PERF OFF CACHE BOOL "" FORCE)

add_subdirectory(${MGBA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/mgba EXCLUDE_FROM_ALL)

# 上游 libretro 目标名 mgba_libretro 且 PREFIX ""，AGP 只收 lib*.so
set_target_properties(mgba_libretro PROPERTIES
  PREFIX "lib"
  OUTPUT_NAME "mgba"
  POSITION_INDEPENDENT_CODE ON)
