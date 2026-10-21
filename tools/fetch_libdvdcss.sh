#!/bin/sh
# Descarga libdvdcss (fork de Kodi, basado en 1.4.3) en la versión exacta probada y aplica el parche de DiscMount.
# libdvdcss es GPL-2.0+; este script no lo incluye en el repositorio, lo descarga en tu máquina/CI.
set -e
cd "$(dirname "$0")/../app/src/main/cpp"
COMMIT=84a7ba82a31f4ac73d93de108ee8eaa2d250cf5e
rm -rf libdvdcss
git clone -q https://github.com/xbmc/libdvdcss.git libdvdcss
cd libdvdcss
git checkout -q "$COMMIT"
git apply ../discmount-libdvdcss.patch
echo "libdvdcss $COMMIT preparado."
