# ─────────────────────────────────────────────────────────────────────
# Genesis Plus GX（MD/SMS libretro 核心）NDK 构建。
# 源清单忠实翻译 vendor/Genesis-Plus-GX/libretro/Makefile.common（unix 分支，
# HAVE_CHD=0 不编 CHD/lzma/zstd；SHARED_LIBVORBIS 关闭 → 静态 tremor）。
# 产物：libgenesis_plus_gx.so，仅导出 retro_*（自带 link.T）。
# ─────────────────────────────────────────────────────────────────────
cmake_minimum_required(VERSION 3.22.1)
project(genesis_plus_gx C)

set(CORE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../vendor/Genesis-Plus-GX)
set(LC       ${CORE_DIR}/libretro/libretro-common)

if(NOT EXISTS ${CORE_DIR}/libretro/libretro.c)
  message(FATAL_ERROR "vendor/Genesis-Plus-GX 源码缺失，请先 git clone")
endif()

# GENPLUS_SRC_DIR wildcard（Makefile.common:5-14） + 静态 tremor（:100-102）
set(GX_DIRS
  ${CORE_DIR}/core ${CORE_DIR}/core/z80 ${CORE_DIR}/core/m68k ${CORE_DIR}/core/ntsc
  ${CORE_DIR}/core/sound ${CORE_DIR}/core/sound/minimp3 ${CORE_DIR}/core/input_hw
  ${CORE_DIR}/core/cd_hw ${CORE_DIR}/core/cart_hw ${CORE_DIR}/core/cart_hw/svp
  ${CORE_DIR}/core/sound/tremor)
set(GX_SOURCES "")
foreach(d IN LISTS GX_DIRS)
  file(GLOB _s CONFIGURE_DEPENDS ${d}/*.c)
  list(APPEND GX_SOURCES ${_s})
endforeach()

# libretro-common 精选（Makefile.common:63-83；HAVE_CDROM=0 不含 cdrom.c）
set(LC_SOURCES
  ${LC}/streams/file_stream.c ${LC}/streams/file_stream_transforms.c
  ${LC}/compat/fopen_utf8.c ${LC}/compat/compat_snprintf.c
  ${LC}/compat/compat_strl.c ${LC}/compat/compat_strcasestr.c
  ${LC}/compat/compat_posix_string.c ${LC}/encodings/encoding_utf.c
  ${LC}/file/file_path.c ${LC}/file/retro_dirent.c
  ${LC}/lists/string_list.c ${LC}/lists/dir_list.c
  ${LC}/memmap/memalign.c ${LC}/string/stdstring.c
  ${LC}/vfs/vfs_implementation.c)

# 自带 zlib 最小子集（Makefile.common:92-98）
set(ZDIR ${CORE_DIR}/libretro/deps/zlib-1.2.11)
set(Z_SOURCES
  ${ZDIR}/adler32.c ${ZDIR}/crc32.c ${ZDIR}/inffast.c
  ${ZDIR}/inflate.c ${ZDIR}/inftrees.c ${ZDIR}/zutil.c)

add_library(genesis_plus_gx SHARED
  ${GX_SOURCES} ${LC_SOURCES} ${Z_SOURCES} ${CORE_DIR}/libretro/libretro.c)

target_include_directories(genesis_plus_gx PRIVATE
  ${GX_DIRS}
  ${CORE_DIR}/libretro ${LC}/include ${ZDIR})

# Makefile.libretro unix 分支 + LIBRETRO_CFLAGS（HAVE_CDROM/CHD 关闭）
target_compile_definitions(genesis_plus_gx PRIVATE
  __LIBRETRO__ USE_LIBRETRO_VFS
  USE_16BPP_RENDERING FRONTEND_SUPPORTS_RGB565
  LSB_FIRST BYTE_ORDER=LITTLE_ENDIAN
  HAVE_ZLIB USE_LIBTREMOR USE_PER_SOUND_CHANNELS_CONFIG
  M68K_OVERCLOCK_SHIFT=20 Z80_OVERCLOCK_SHIFT=20
  HAVE_YM3438_CORE HAVE_OPLL_CORE MAXROMSIZE=10485760
  "INLINE=static inline" GIT_VERSION="xbw")

target_compile_options(genesis_plus_gx PRIVATE -O2 -w -fvisibility=hidden)
target_link_options(genesis_plus_gx PRIVATE
  "-Wl,--version-script=${CORE_DIR}/libretro/link.T"
  "-Wl,--gc-sections")
