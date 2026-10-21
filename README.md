# DiscMount (Fase 1)

Monta unidades DVD/Blu-ray USB en Android / Google TV **sin drivers del kernel**: habla SCSI/MMC directamente por USB
(Bulk-Only Transport) y lee UDF / ISO9660 en espacio de usuario, como MLUSB Mounter.

## Qué hace la Fase 1
- Detecta la unidad (clase Mass Storage, subclase 2/6, protocolo BOT), pide permiso USB y la abre.
- Espera a que el disco esté listo, lee capacidad y tipo de disco (CD/DVD/BD).
- Sistemas de ficheros: ISO9660 (+Joliet), UDF 1.02–2.60 incluida la **partición de metadatos** de Blu-ray.
- Reproductor integrado (libvlc) para vídeo, audio e imágenes del disco; explorador con D-pad, copia de ficheros a otro almacenamiento y un **DocumentsProvider**: el disco aparece
  en el selector de archivos de Android, de modo que VLC y otras apps pueden abrir los ficheros (p. ej. `.m2ts`, `.vob`).

## DVD-Video con CSS (Fase 2)
Los VOB de un DVD comercial se leen a través de **libdvdcss** (autenticación con la unidad, clave de disco y de título),
igual que hace VLC en PC. libdvdcss no está en este repositorio: `tools/fetch_libdvdcss.sh` lo descarga (fork de Kodi,
commit fijado) y le aplica un parche de una línea; el código propio en `app/src/main/cpp` sustituye `ioctl.c` por
comandos SCSI/MMC enviados por tu unidad USB y conecta todo por JNI. El botón **Reproducir película DVD** elige el
conjunto de títulos más largo de `VIDEO_TS` y lo reproduce sin menús. Úsalo solo con discos que poseas; en algunos países el
descifrado de CSS está restringido. Al enlazar libdvdcss (GPL) el APK resultante es GPL: no lo redistribuyas sin cumplir su licencia.
Pruebas en PC: el puente JNI lee discos sin cifrar bit a bit y emite la secuencia correcta de comandos de autenticación;
**el descifrado real no se ha podido probar** (sin disco cifrado ni unidad aquí).

## Blu-ray con AACS (KEYDB.cfg)
Botón **Importar KEYDB.cfg** (selector de archivos) o copia el fichero a `/sdcard/Android/data/dev.discmount/files/KEYDB.cfg`.
Al montar un Blu-ray se calcula el hash del disco (SHA-1 de `AACS/Unit_Key_RO.inf`), se busca en tu KEYDB.cfg y, con la VUK
(`V`) o las claves de unidad (`U`) de la entrada, se descifran los `.m2ts` al vuelo (`core/.../bd/Aacs.kt`). **Reproducir película
Blu-ray** elige la lista de reproducción más larga. Solo funciona con discos cuya entrada tenga VUK o claves de unidad; no hay
autenticación con la unidad (entradas solo con MEK no sirven), ni BD+, ni Ultra HD (AACS 2.0). Úsalo solo con discos que poseas.
Pruebas: `AacsTest.kt` (cifrado/descifrado sintético; no se ha probado con un disco real).

## Estructura
- `core/`: Kotlin puro (sin Android). Bloques, UDF/ISO, BOT, comandos SCSI. Aquí se podrá enganchar CSS/AACS (`ScsiTransport.execute`).
- `app/`: app Android (Activity, transporte USB, DocumentsProvider).
- `tools/`: generador de imágenes de prueba y script de tests.

## Compilar sin Android Studio (GitHub Actions)
Sube la carpeta a un repositorio tuyo de GitHub; el workflow `.github/workflows/build-apk.yml` ejecuta los tests y
genera `app-debug.apk` (pestaña *Actions* > última ejecución > *Artifacts* > `DiscMount-debug-apk`).

## Compilar con Android Studio
Abre la carpeta en Android Studio (Ladybug o superior; genera el wrapper de Gradle) y ejecuta `app`.
minSdk 26, JDK 17. Instálalo en la tele por ADB: `adb install app-debug.apk`.

## Tests del núcleo (sin Android)
`tools/run_core_tests.sh` (necesita Java 17+, Python 3 y `pip install pycdlib`). Compila el núcleo, genera imágenes
ISO/Joliet/UDF y las lee a través de un dispositivo USB falso, comprobando el SHA-256 de cada fichero.

## Estado honesto
- Verificado: el núcleo (UDF/ISO/BOT/SCSI) contra imágenes reales generadas con pycdlib y contra una imagen UDF 2.50 con
  metadatos fragmentados construida a mano.
- **No verificado**: la app Android no se ha podido compilar ni ejecutar en el entorno de desarrollo (sin SDK de Android)
  y no se ha probado con una unidad real. Espera pequeños errores de compilación y ajustes con tu hardware.
- Límites conocidos: sin UAS (solo BOT), sin particiones virtuales UDF (VAT), sin Rock Ridge, sin remapeo de sectores
  defectuosos (sparable). `AndroidUsbTransport.CHUNK` (16 KiB) puede subirse para más velocidad.

## Próximas fases
2. DVD-Video: CSS (autenticación MMC `REPORT KEY`/`SEND KEY` sobre `ScsiTransport`) + libdvdread/libdvdnav + libvlc.
3. Blu-ray: libbluray + libaacs con importación de tu `KEYDB.cfg`, reproducción de la playlist principal.

## v0.4 – Música y detección de disco

- Detecta al insertar el disco si es Compact Disc (audio/datos), DVD (vídeo/datos) o Blu-ray (vídeo/datos) y lo muestra con un disco dibujado
  y su distintivo (diseños propios, no los logotipos oficiales).
- CD de audio original: lectura de TOC y CD-TEXT, pistas leídas con READ CD y reproducidas como WAV al vuelo.
- Ficheros de audio (mp3, flac, wav, ogg, m4a, aac, opus, ape, wv, dsf/dff…) con lista de reproducción de la carpeta y siguiente/anterior.
- Los SACD (discos) no se pueden reproducir (formato protegido); sí los ficheros .dsf/.dff que libvlc sepa decodificar.
