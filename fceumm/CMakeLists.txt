# ─────────────────────────────────────────────────────────────────────
# FCEUmm（NES/FC libretro 核心）NDK 构建。
# 源清单与编译开关忠实翻译自 vendor/libretro-fceumm/Makefile.common
# 与 jni/Android.mk（HAVE_NTSC=1、WANT_32BPP 默认 ⇒ RGB565、不含 HDPACK）。
# 产物：libfceumm.so，仅导出 retro_* 符号（link.T version script）。
# ─────────────────────────────────────────────────────────────────────
cmake_minimum_required(VERSION 3.22.1)
project(fceumm C)

set(CORE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../vendor/libretro-fceumm/src)
set(LC       ${CORE_DIR}/drivers/libretro/libretro-common)

if(NOT EXISTS ${CORE_DIR}/drivers/libretro/libretro.c)
  message(FATAL_ERROR "vendor/libretro-fceumm 源码缺失，请先 git clone")
endif()

# Makefile.common: FCEU_SRC_DIRS := boards input（wildcard）
file(GLOB FCEU_BOARDS CONFIGURE_DEPENDS ${CORE_DIR}/boards/*.c)
file(GLOB FCEU_INPUT  CONFIGURE_DEPENDS ${CORE_DIR}/input/*.c)

# Makefile.common: SOURCES_C 显式列表
set(FCEU_SOURCES
  ${FCEU_BOARDS}
  ${FCEU_INPUT}
  ${CORE_DIR}/ntsc/nes_ntsc.c
  ${CORE_DIR}/drivers/libretro/libretro.c
  ${CORE_DIR}/drivers/libretro/libretro_dipswitch.c
  ${CORE_DIR}/cart.c
  ${CORE_DIR}/cheat.c
  ${CORE_DIR}/crc32.c
  ${CORE_DIR}/fceu-endian.c
  ${CORE_DIR}/fceu-memory.c
  ${CORE_DIR}/fceu.c
  ${CORE_DIR}/fds.c
  ${CORE_DIR}/fds_apu.c
  ${CORE_DIR}/file.c
  ${CORE_DIR}/filter.c
  ${CORE_DIR}/general.c
  ${CORE_DIR}/input.c
  ${CORE_DIR}/md5.c
  ${CORE_DIR}/nsf.c
  ${CORE_DIR}/palette.c
  ${CORE_DIR}/ppu.c
  ${CORE_DIR}/sound.c
  ${CORE_DIR}/state.c
  ${CORE_DIR}/video.c
  ${CORE_DIR}/vsuni.c
  ${CORE_DIR}/ines.c
  ${CORE_DIR}/unif.c
  ${CORE_DIR}/x6502.c
  # memory_stream：core 的 savestate 代码直接依赖（Makefile.common 注释要求
  # 放在 STATIC_LINKING 守卫之外）
  ${LC}/streams/memory_stream.c
  # 动态链接时补的 libretro-common 兼容层
  ${LC}/compat/compat_posix_string.c
  ${LC}/compat/compat_snprintf.c
  ${LC}/compat/compat_strcasestr.c
  ${LC}/compat/compat_strl.c
  ${LC}/compat/fopen_utf8.c
  ${LC}/encodings/encoding_utf.c
  ${LC}/file/file_path.c
  ${LC}/file/file_path_io.c
  ${LC}/streams/file_stream.c
  ${LC}/streams/file_stream_transforms.c
  ${LC}/string/stdstring.c
  ${LC}/time/rtime.c
  ${LC}/vfs/vfs_implementation.c
)

add_library(fceumm SHARED ${FCEU_SOURCES})

target_include_directories(fceumm PRIVATE
  ${CORE_DIR}/drivers/libretro
  ${LC}/include
  ${CORE_DIR}
  ${CORE_DIR}/input
  ${CORE_DIR}/boards
  ${CORE_DIR}/ntsc
)

target_compile_definitions(fceumm PRIVATE
  __LIBRETRO__
  PATH_MAX=1024
  FCEU_VERSION_NUMERIC=9900
  HAVE_NTSC_FILTER
  FRONTEND_SUPPORTS_RGB565
  PSS_STYLE=1
  ANDROID
)

# 核心源码是 90 年代 C 方言，别名假设遍地：优化但保守。
# 注意 debug 构建也要 -O2，否则解释型 6502 调度器会慢到不可用。
target_compile_options(fceumm PRIVATE
  -O2 -fno-strict-aliasing -w
  -fomit-frame-pointer
)

# link.T：只导出 retro_*（dlopen+dlsym 消费）；16KB 页对齐（Android15+）
target_link_options(fceumm PRIVATE
  "-Wl,--version-script=${CORE_DIR}/drivers/libretro/link.T"
  "-Wl,-z,max-page-size=16384"
)

target_link_libraries(fceumm PRIVATE m log)
