# FBNeo (FinalBurn Neo libretro core) NDK build
# Source lists and switches follow vendor/fbneo/src/burner/libretro/{Makefile,
# Makefile.common,Makefile.all,jni/Android.mk} with:
#   INCLUDE_7Z_SUPPORT=1 INCLUDE_CHD_SUPPORT=1 CHD_LIBRETRO=0 EXTERNAL_ZLIB=0
#   USE_CYCLONE=0 USE_X64_DRC=0 SUPPORT_LARGE_FILES=1 WANT_NEOGEOCD=0
# Output: libfbneo.so, exporting retro_* only (link.T version script).
# NOTE: TMS34010_CPU_DIR upstream is src/cpu/tms34 (NOT src/cpu/tms34010, which is
# a stale legacy tree that does not even build - its dasm units need memhandler.h).
cmake_minimum_required(VERSION 3.22.1)
project(fbneo C CXX)

set(FB ${CMAKE_CURRENT_SOURCE_DIR}/../vendor/fbneo/src)
set(LR ${FB}/burner/libretro)
set(LC ${LR}/libretro-common)
set(BURN ${FB}/burn)
set(DRV ${BURN}/drv)
set(CPU ${FB}/cpu)
set(BURNER ${FB}/burner)
set(INTF ${FB}/intf)
set(LIB ${FB}/dep/libs)

if(NOT EXISTS ${LR}/libretro.cpp)
  message(FATAL_ERROR "vendor/fbneo sources missing, git clone first")
endif()

# Wildcard source dirs: Makefile.common FBNEO_BURN_DIRS + FBNEO_CPU_DIRS
set(GLOB_DIRS
  ${BURN}/devices ${BURN}/snd ${DRV}
  ${DRV}/atari ${DRV}/capcom ${DRV}/cave ${DRV}/channelf ${DRV}/coleco
  ${DRV}/cps3 ${DRV}/dataeast ${DRV}/galaxian ${DRV}/gba ${DRV}/irem
  ${DRV}/konami ${DRV}/megadrive ${DRV}/midway ${DRV}/msx ${DRV}/neogeo
  ${DRV}/nes ${DRV}/pce ${DRV}/pgm ${DRV}/pgm2 ${DRV}/pre90s ${DRV}/psikyo
  ${DRV}/pst90s ${DRV}/sega ${DRV}/sg1000 ${DRV}/sms ${DRV}/snes
  ${DRV}/spectrum ${DRV}/taito ${DRV}/toaplan
  ${CPU} ${CPU}/adsp2100 ${CPU}/arm ${CPU}/arm7 ${CPU}/arm9 ${CPU}/e132xs
  ${CPU}/f8 ${CPU}/h6280 ${CPU}/hd6309 ${CPU}/hmcs40 ${CPU}/i386
  ${CPU}/m377 ${CPU}/m6502 ${CPU}/m68k ${CPU}/mb88xx ${CPU}/mips3
  ${CPU}/nec ${CPU}/pic16c5x ${CPU}/sh2 ${CPU}/sh4 ${CPU}/tms34
  ${CPU}/upd7725 ${CPU}/v60 ${CPU}/i8x41 ${CPU}/i8039 ${CPU}/i8051
  ${CPU}/i8085 ${CPU}/konami ${CPU}/m6800 ${CPU}/m6805 ${CPU}/m6809
  ${CPU}/s2650 ${CPU}/t11 ${CPU}/tlcs90 ${CPU}/tlcs900 ${CPU}/tms32010
  ${CPU}/upd7810 ${CPU}/xa ${CPU}/z80 ${CPU}/z180)

set(SRCS "")
foreach(d ${GLOB_DIRS})
  file(GLOB _c CONFIGURE_DEPENDS ${d}/*.c)
  file(GLOB _cxx CONFIGURE_DEPENDS ${d}/*.cpp)
  list(APPEND SRCS ${_c} ${_cxx})
endforeach()

# BURN_BLACKLIST, transcribed from Makefile.all
set(BLACKLIST
  ${CPU}/adsp2100/2100ops.c ${CPU}/adsp2100/2100dasm.cpp
  ${CPU}/arm7/arm7exec.c ${CPU}/arm7/arm7core.c
  ${CPU}/h6280/tblh6280.c
  ${CPU}/hd6309/6309tbl.c ${CPU}/hd6309/6309ops.c
  ${CPU}/hmcs40/hmcs40op.cpp
  ${CPU}/i386/i386dasm.c ${CPU}/i386/i386op16.c ${CPU}/i386/i386op32.c
  ${CPU}/i386/i386ops.c ${CPU}/i386/i486ops.c ${CPU}/i386/pentops.c
  ${CPU}/i386/x87ops.c
  ${CPU}/m6502/t65sc02.c ${CPU}/m6502/t65c02.c ${CPU}/m6502/tdeco16.c
  ${CPU}/m6502/tn2a03.c ${CPU}/m6502/t6502.c
  ${CPU}/m68k/m68k_in.c ${CPU}/m68k/m68kmake.c ${CPU}/m68k/m68kfpu.c ${CPU}/m68k/m68kdasm.c
  ${CPU}/mips3/mips3_dasm.cpp ${CPU}/mips3/cop0.cpp ${CPU}/mips3/cop1.cpp ${CPU}/mips3/mips3.cpp
  ${CPU}/mips3_intf.cpp ${CPU}/c68000_intf.cpp
  ${CPU}/nec/v25sfr.c ${CPU}/nec/v25instr.c ${CPU}/nec/necinstr.c
  ${CPU}/tms34/34010fld.c ${CPU}/tms34/34010gfx.c ${CPU}/tms34/34010ops.c ${CPU}/tms34/34010tbl.c
  ${CPU}/tms34010_intf.cpp
  ${CPU}/v60/am.c ${CPU}/v60/am1.c ${CPU}/v60/am2.c ${CPU}/v60/am3.c
  ${CPU}/v60/op2.c ${CPU}/v60/op3.c ${CPU}/v60/op4.c ${CPU}/v60/op5.c ${CPU}/v60/op6.c
  ${CPU}/v60/op7a.c ${CPU}/v60/op12.c ${CPU}/v60/optable.c ${CPU}/v60/v60d.c ${CPU}/v60/v60mem.c
  ${CPU}/konami/konamtbl.c ${CPU}/konami/konamops.c
  ${CPU}/m6800/6800ops.c ${CPU}/m6800/6800tbl.c
  ${CPU}/m6805/6805ops.c
  ${CPU}/m6809/6809ops.c ${CPU}/m6809/6809tbl.c
  ${CPU}/i8051/mcs51ops.c
  ${CPU}/upd7810/7810ops.c ${CPU}/upd7810/7810tbl.c
  ${CPU}/i8x41/8x41dasm.c
  ${CPU}/z180/z180xy.c ${CPU}/z180/z180fd.c ${CPU}/z180/z180op.c ${CPU}/z180/z180ed.c
  ${CPU}/z180/z180dd.c ${CPU}/z180/z180cb.c
  ${CPU}/t11/t11ops.c ${CPU}/t11/t11table.c
  ${CPU}/tlcs900/900tbl.c
  ${BURNER}/wave_writer.cpp ${BURNER}/sshot.cpp ${BURNER}/cong.cpp ${BURNER}/image.cpp
  ${BURNER}/misc.cpp ${BURNER}/state.cpp ${BURNER}/gami.cpp ${BURNER}/gamc.cpp
  ${BURNER}/dynhuff.cpp ${BURNER}/luaengine.cpp ${BURNER}/luasav.cpp
  ${DRV}/capcom/ctv_make.cpp
  ${DRV}/pgm/pgm_sprite_create.cpp
  ${INTF}/audio/aud_interface.cpp
  ${DRV}/atari/WHATSNEW.txt.cpp
  ${DRV}/midway/d_kinst.cpp ${DRV}/midway/ide.cpp)

set(SRCS_RM "")
list(LENGTH BLACKLIST _bl_total)
set(_bl_hit 0)
foreach(b ${BLACKLIST})
  if(EXISTS ${b})
    list(APPEND SRCS_RM ${b})
    math(EXPR _bl_hit "${_bl_hit}+1")
  endif()
endforeach()
if(NOT _bl_hit EQUAL _bl_total)
  message(WARNING "FBNeo blacklist matched ${_bl_hit}/${_bl_total} (upstream file churn is normal)")
endif()
list(REMOVE_ITEM SRCS ${SRCS_RM})

# Explicit source lists from Makefile.common SOURCES_C / SOURCES_CXX
set(SRCS ${SRCS}
  # burn core
  ${BURN}/burn.cpp ${BURN}/burn_gun.cpp ${BURN}/burn_memory.cpp ${BURN}/burn_sound.cpp
  ${BURN}/cheat.cpp ${BURN}/debug_track.cpp ${BURN}/hiscore.cpp ${BURN}/load.cpp ${BURN}/timer.cpp
  ${BURN}/burn_bitmap.cpp ${BURN}/burn_led.cpp ${BURN}/burn_pal.cpp ${BURN}/burn_sha1.cpp
  ${BURN}/burn_shift.cpp ${BURN}/tilemap_generic.cpp ${BURN}/tiles_generic.cpp ${BURN}/vector.cpp
  # burner
  ${BURNER}/ioapi.c ${BURNER}/unzip.c ${BURNER}/conc.cpp ${BURNER}/dat.cpp
  ${BURNER}/statec.cpp ${BURNER}/zipfn.cpp ${BURNER}/nvram.cpp ${BURNER}/cdlist.cpp
  # intf/audio
  ${INTF}/audio/aud_dsp.cpp ${INTF}/audio/lowpass2.cpp
  # libretro adapter layer
  ${LR}/libretro.cpp ${LR}/retro_cdemu.cpp ${LR}/retro_common.cpp ${LR}/retro_input.cpp
  ${LR}/retro_memory.cpp ${LR}/retro_string.cpp ${LR}/ips_manager.cpp ${LR}/romdata.cpp
  ${LR}/retro_pgm2_cards.cpp ${LR}/ugui_tools.c
  # ugui
  ${LR}/deps/ugui/ugui.c ${LR}/deps/ugui/ugui_textbox.c ${LR}/deps/ugui/font_8x8.c
  ${LR}/deps/ugui/SourceHanSansCN_Medium_13X17.c
  # libretro-common
  ${LC}/file/file_path.c ${LC}/file/file_path_io.c ${LC}/file/retro_dirent.c
  ${LC}/encodings/encoding_utf.c
  ${LC}/compat/compat_posix_string.c ${LC}/compat/compat_strcasestr.c
  ${LC}/compat/compat_strl.c ${LC}/compat/compat_strldup.c ${LC}/compat/fopen_utf8.c
  ${LC}/string/stdstring.c
  ${LC}/streams/file_stream.c ${LC}/streams/file_stream_transforms.c
  ${LC}/features/features_cpu.c
  ${LC}/file/config_file.c ${LC}/file/config_file_userdata.c
  ${LC}/lists/string_list.c ${LC}/memmap/memalign.c ${LC}/time/rtime.c
  ${LC}/vfs/vfs_implementation.c
  # bundled zlib (EXTERNAL_ZLIB=0)
  ${LIB}/zlib/inflate.c ${LIB}/zlib/zutil.c ${LIB}/zlib/inffast.c ${LIB}/zlib/crc32.c
  ${LIB}/zlib/adler32.c ${LIB}/zlib/inftrees.c ${LIB}/zlib/deflate.c ${LIB}/zlib/trees.c
  # 7z (INCLUDE_7Z_SUPPORT=1)
  ${BURNER}/un7z.cpp
  ${LIB}/lib7z/7zArcIn.c ${LIB}/lib7z/7zBuf.c ${LIB}/lib7z/7zCrc.c ${LIB}/lib7z/7zCrcOpt.c
  ${LIB}/lib7z/7zDec.c ${LIB}/lib7z/7zStream.c ${LIB}/lib7z/Bcj2.c ${LIB}/lib7z/Bra.c
  ${LIB}/lib7z/Bra86.c ${LIB}/lib7z/BraIA64.c ${LIB}/lib7z/CpuArch.c ${LIB}/lib7z/Delta.c
  ${LIB}/lib7z/Lzma2Dec.c ${LIB}/lib7z/LzmaDec.c ${LIB}/lib7z/Ppmd7.c ${LIB}/lib7z/Ppmd7Dec.c
  # CHD (INCLUDE_CHD_SUPPORT=1, CHD_LIBRETRO=0)
  ${INTF}/cd/cd_chd.cpp ${LIB}/libchdr/libchdr_flac.c
  ${LIB}/libchdr/libchdr_chd.c ${LIB}/libchdr/libchdr_codec_zlib.c
  ${LIB}/libchdr/libchdr_codec_cdzl.c ${LIB}/libchdr/libchdr_bitstream.c
  ${LIB}/libchdr/libchdr_huffman.c
  ${LIB}/libchdr/libchdr_codec_huff.c
  ${LIB}/libchdr/libchdr_codec_flac.c ${LIB}/libchdr/libchdr_codec_lzma.c
  ${LIB}/libchdr/libchdr_codec_zstd.c ${LIB}/libchdr/libchdr_codec_cdlz.c
  ${LIB}/libchdr/libchdr_codec_cdfl.c ${LIB}/libchdr/libchdr_codec_cdzs.c
  ${LIB}/libchdr/libchdr_cdrom.c ${LIB}/lzma/libchdr_lzma.c ${LIB}/zstd/zstddeclib.c)

list(REMOVE_DUPLICATES SRCS)

add_library(fbneo SHARED ${SRCS})

target_include_directories(fbneo PRIVATE
  ${BURNER}/win32 ${LC}/include ${LR} ${BURN} ${CPU} ${BURN}/snd ${BURN}/devices
  ${INTF} ${INTF}/input ${INTF}/cd ${INTF}/audio ${BURNER}
  ${CPU}/f8 ${CPU}/hmcs40 ${CPU}/i8039 ${CPU}/i8051 ${CPU}/i8085 ${CPU}/i8x41
  ${CPU}/m377 ${CPU}/m6805 ${CPU}/tlcs900 ${CPU}/tms32010 ${CPU}/upd7725
  ${CPU}/upd7810 ${CPU}/v60 ${CPU}/t11 ${CPU}/xa ${CPU}/z80 ${CPU}/z180 ${CPU}/sh4
  ${CPU}/pic16c5x
  ${LIB}/zlib ${LIB}/lib7z ${LIB}/dr_libs ${LIB}/stb_vorbis ${LIB}/r8brain ${LIB}
  ${LIB}/libchdr/include ${LIB}/lzma
  ${DRV}/capcom ${DRV}/konami ${DRV}/dataeast ${DRV}/cave ${DRV}/neogeo ${DRV}/psikyo
  ${DRV}/sega ${DRV}/toaplan ${DRV}/taito ${DRV}/irem ${DRV}/pce ${DRV}/galaxian
  ${FB}/dep/generated ${LIB} ${LR}/deps/ugui)

# Android.mk COMMON_FLAGS + FBNEO_DEFINES
target_compile_definitions(fbneo PRIVATE
  __LIBRETRO__ USE_SPEEDHACKS ANDROID LSB_FIRST
  HAVE_UNISTD_H
  INCLUDE_7Z_SUPPORT Z7_PPMD_SUPPORT Z7_ST
  INCLUDE_CHD_SUPPORT USE_LIBRETRO_VFS CHDR_SYSTEM_ZLIB
  GIT_VERSION="GIT-xbw" GIT_DATE="")

target_compile_definitions(fbneo PRIVATE _FILE_OFFSET_BITS=64)

# bionic trap (NDK r27 headers): with _FILE_OFFSET_BITS=64 on a 32-bit ABI at
# API<24, stdio.h declares fseeko/ftello ONLY for API>=24 (renamed to *64).
# Our minSdk is 21, so vfs_implementation.c - which unconditionally takes the
# fseeko/ftello tier of its 64-bit ladder - fails to compile for armeabi-v7a.
# Back to 32-bit off_t declarations for THIS FILE ONLY: fine for ROM/zip-sized
# streams (<2GiB); 64-bit ABIs keep the flag untouched.
if(NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
  set_source_files_properties(${LC}/vfs/vfs_implementation.c PROPERTIES
    COMPILE_OPTIONS "-U_FILE_OFFSET_BITS")
endif()
target_compile_options(fbneo PRIVATE -O2 -w -ffast-math -fexceptions -frtti
  -std=gnu99 -Wno-write-strings)
# AGP injects -g for debug variants; FBNeo is ~1100 TUs, so debug info would blow the
# APK up to ~190MB. -g0 (appended after AGP's -g) plus section GC keeps it shippable.
target_compile_options(fbneo PRIVATE -g0 -ffunction-sections -fdata-sections)

# C++ stays on gnu++98 (upstream standard; newer clang defaults break its old-style code).
# CMake dispatches C/C++ by extension, so do not force one language standard globally.
target_compile_options(fbneo PRIVATE $<$<COMPILE_LANGUAGE:CXX>:-std=gnu++98>)

target_link_options(fbneo PRIVATE
  "-Wl,--version-script=${LR}/link.T"
  "-Wl,-z,max-page-size=16384"
  "-Wl,--gc-sections"
  "-Wl,--strip-debug"
  "-Wl,-no-undefined")
target_link_libraries(fbneo PRIVATE log m)