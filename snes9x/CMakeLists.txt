# ─────────────────────────────────────────────────────────────────────
# Snes9x（SFC libretro 核心）NDK 构建。
# 源清单忠实翻译 vendor/snes9x/libretro/Makefile.common。
# 产物：libsnes9x.so，仅导出 retro_*（上游自带 libretro/link.T）。
# ─────────────────────────────────────────────────────────────────────
cmake_minimum_required(VERSION 3.22.1)
project(snes9x C CXX)

set(CORE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../vendor/snes9x)
set(LC       ${CORE_DIR}/libretro/libretro-common)

if(NOT EXISTS ${CORE_DIR}/libretro/libretro.cpp)
  message(FATAL_ERROR "vendor/snes9x 源码缺失，请先 git clone libretro/snes9x")
endif()

# Makefile.common SOURCES_C
set(S9X_SOURCES_C
  ${CORE_DIR}/c4emu.c ${CORE_DIR}/msu1.c ${CORE_DIR}/zipfile.c ${CORE_DIR}/srtc.c
  ${CORE_DIR}/obc1.c ${CORE_DIR}/bsflash.c ${CORE_DIR}/tile.c ${CORE_DIR}/bsx.c
  ${CORE_DIR}/spc7110.c ${CORE_DIR}/fxemu.c ${CORE_DIR}/sdd1.c ${CORE_DIR}/seta.c
  ${CORE_DIR}/sa1hw.c ${CORE_DIR}/dsp.c ${CORE_DIR}/filter/snes_ntsc.c)

# Makefile.common SOURCES_CXX
set(S9X_SOURCES_CXX
  ${CORE_DIR}/apu/apu.cpp ${CORE_DIR}/apu/bapu/dsp/sdsp.cpp
  ${CORE_DIR}/apu/bapu/smp/smp.cpp ${CORE_DIR}/apu/bapu/smp/smp_state.cpp
  ${CORE_DIR}/cheats2.cpp ${CORE_DIR}/clip.cpp ${CORE_DIR}/controls.cpp
  ${CORE_DIR}/cpu.cpp ${CORE_DIR}/cpuexec.cpp ${CORE_DIR}/cpuops.cpp
  ${CORE_DIR}/crosshairs.cpp ${CORE_DIR}/dma.cpp ${CORE_DIR}/gfx.cpp
  ${CORE_DIR}/globals.cpp ${CORE_DIR}/loadzip.cpp ${CORE_DIR}/memmap.cpp
  ${CORE_DIR}/ppu.cpp ${CORE_DIR}/sa1.cpp ${CORE_DIR}/sa1cpu.cpp
  ${CORE_DIR}/snapshot.cpp ${CORE_DIR}/sha256.cpp ${CORE_DIR}/bml.cpp
  ${CORE_DIR}/fscompat.cpp ${CORE_DIR}/libretro/libretro.cpp)

# libretro-common（Makefile.common 精选清单）
set(LC_SOURCES
  ${LC}/compat/compat_posix_string.c ${LC}/compat/compat_strcasestr.c
  ${LC}/compat/compat_snprintf.c ${LC}/compat/compat_strl.c
  ${LC}/compat/fopen_utf8.c ${LC}/encodings/encoding_utf.c
  ${LC}/encodings/encoding_deflate.c ${LC}/file/file_path.c
  ${LC}/file/file_path_io.c ${LC}/streams/file_stream.c
  ${LC}/streams/file_stream_transforms.c ${LC}/string/stdstring.c
  ${LC}/time/rtime.c ${LC}/vfs/vfs_implementation.c
  ${LC}/vfs/vfs_hybrid.c ${LC}/file/retro_dirent.c)

add_library(snes9x SHARED ${S9X_SOURCES_C} ${S9X_SOURCES_CXX} ${LC_SOURCES})

# Makefile.common INCFLAGS
target_include_directories(snes9x PRIVATE
  ${CORE_DIR}/libretro ${LC}/include ${CORE_DIR}
  ${CORE_DIR}/apu ${CORE_DIR}/apu/bapu)

target_compile_definitions(snes9x PRIVATE __LIBRETRO__)
target_compile_features(snes9x PRIVATE cxx_std_14)
target_compile_options(snes9x PRIVATE -O2 -w)

# encoding_deflate.c 需要 zlib（NDK sysroot 自带）
target_link_libraries(snes9x PRIVATE z)
target_link_options(snes9x PRIVATE
  "-Wl,--version-script=${CORE_DIR}/libretro/link.T"
  "-Wl,--gc-sections")
