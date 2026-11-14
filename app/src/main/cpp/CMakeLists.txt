# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2025-2026 DiscMount contributors

cmake_minimum_required(VERSION 3.22)
project(discmountcss C)

# libdvdcss se descarga con tools/fetch_libdvdcss.sh (no se incluye en este repositorio).
set(DVDCSS ${CMAKE_CURRENT_SOURCE_DIR}/libdvdcss)
if(NOT EXISTS ${DVDCSS}/src/css.c)
    message(FATAL_ERROR "Falta app/src/main/cpp/libdvdcss. Ejecuta tools/fetch_libdvdcss.sh antes de compilar.")
endif()

# dvdcss.h incluye "version.h", que normalmente genera autoconf a partir de version.h.in.
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/gen/version.h
"#ifndef DVDCSS_VERSION_H_\n#define DVDCSS_VERSION_H_\n#define DVDCSS_VERSION_MAJOR 1\n#define DVDCSS_VERSION_MINOR 4\n#define DVDCSS_VERSION_MICRO 3\n#define DVDCSS_VERSION_STRING \"1.4.3\"\n#define DVDCSS_VERSION 10403\n#endif\n")

add_library(discmountcss SHARED
    ${DVDCSS}/src/css.c
    ${DVDCSS}/src/device.c
    ${DVDCSS}/src/error.c
    ${DVDCSS}/src/libdvdcss.c
    ioctl_scsi.c            # sustituye a libdvdcss/src/ioctl.c
    discmount_css.c)

target_include_directories(discmountcss PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}      # config.h, discmount_scsi.h
    ${CMAKE_CURRENT_BINARY_DIR}/gen  # version.h
    ${DVDCSS}/src)
target_compile_definitions(discmountcss PRIVATE DISCMOUNT_SCSI_BRIDGE=1 _FILE_OFFSET_BITS=64)
target_compile_options(discmountcss PRIVATE -O2 -fvisibility=default)
target_link_libraries(discmountcss log)
