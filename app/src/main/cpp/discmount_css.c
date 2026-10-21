/*
 * discmount_css.c: puente JNI entre Kotlin y libdvdcss.
 *  - libdvdcss lee sectores y envía comandos SCSI de autenticación a través de callbacks hacia Kotlin
 *    (interfaz dev.discmount.app.CssBridge), que a su vez usa la unidad USB.
 *  - Kotlin pide sectores ya descifrados con nativeRead().
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <pthread.h>

#include "dvdcss/dvdcss.h"
#include "discmount_scsi.h"

static JavaVM   *g_vm;
static jobject   g_bridge;           /* referencia global al CssBridge */
static jmethodID g_mid_read;         /* int readBlocks(long lba, int count, byte[] dst) */
static jmethodID g_mid_scsi;         /* int scsi(byte[] cdb, int dir, byte[] buf, int len) */

typedef struct { uint64_t i_pos; } dm_stream;

static JNIEnv *get_env( void )
{
    JNIEnv *env = NULL;
    if( !g_vm || (*g_vm)->GetEnv( g_vm, (void **)&env, JNI_VERSION_1_6 ) != JNI_OK )
        return NULL;
    return env;
}

int discmount_scsi_exec( const uint8_t *p_cdb, int i_dir, uint8_t *p_buf, int i_len )
{
    JNIEnv *env = get_env();
    if( !env || !g_bridge ) return -1;

    jbyteArray cdb = (*env)->NewByteArray( env, 12 );
    jbyteArray buf = (*env)->NewByteArray( env, i_len > 0 ? i_len : 1 );
    if( !cdb || !buf ) return -1;
    (*env)->SetByteArrayRegion( env, cdb, 0, 12, (const jbyte *)p_cdb );
    if( i_dir == DM_DIR_OUT && i_len > 0 )
        (*env)->SetByteArrayRegion( env, buf, 0, i_len, (const jbyte *)p_buf );

    jint r = (*env)->CallIntMethod( env, g_bridge, g_mid_scsi, cdb, (jint)i_dir, buf, (jint)i_len );
    if( (*env)->ExceptionCheck( env ) )
    {
        (*env)->ExceptionClear( env );
        r = -1;
    }
    if( r >= 0 && i_dir == DM_DIR_IN && i_len > 0 )
        (*env)->GetByteArrayRegion( env, buf, 0, i_len, (jbyte *)p_buf );

    (*env)->DeleteLocalRef( env, cdb );
    (*env)->DeleteLocalRef( env, buf );
    return r;
}

/* ---- callbacks de "stream" de libdvdcss ---- */

static int cb_seek( void *p_stream, uint64_t i_pos )
{
    ((dm_stream *)p_stream)->i_pos = i_pos;
    return 0;
}

static int cb_read( void *p_stream, void *p_buffer, int i_bytes )
{
    dm_stream *s = (dm_stream *)p_stream;
    JNIEnv *env = get_env();
    if( !env || !g_bridge ) return -1;

    int blocks = i_bytes / DVDCSS_BLOCK_SIZE;
    if( blocks <= 0 ) return 0;

    jbyteArray arr = (*env)->NewByteArray( env, blocks * DVDCSS_BLOCK_SIZE );
    if( !arr ) return -1;
    jint n = (*env)->CallIntMethod( env, g_bridge, g_mid_read, (jlong)( s->i_pos / DVDCSS_BLOCK_SIZE ), (jint)blocks, arr );
    if( (*env)->ExceptionCheck( env ) )
    {
        (*env)->ExceptionClear( env );
        n = -1;
    }
    if( n > 0 )
    {
        (*env)->GetByteArrayRegion( env, arr, 0, n * DVDCSS_BLOCK_SIZE, (jbyte *)p_buffer );
        s->i_pos += (uint64_t)n * DVDCSS_BLOCK_SIZE;
    }
    (*env)->DeleteLocalRef( env, arr );
    return n < 0 ? -1 : n * DVDCSS_BLOCK_SIZE;
}

static dvdcss_stream_cb g_cb = { cb_seek, cb_read, NULL };

typedef struct { dvdcss_t dvdcss; dm_stream stream; } dm_handle;

JNIEXPORT jint JNICALL JNI_OnLoad( JavaVM *vm, void *reserved )
{
    (void)reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_dev_discmount_app_CssNative_nativeOpen( JNIEnv *env, jclass clazz, jobject bridge )
{
    (void)clazz;
    setenv( "DVDCSS_CACHE", "off", 1 );   /* sin caché de claves en disco */

    if( g_bridge ) (*env)->DeleteGlobalRef( env, g_bridge );
    g_bridge = (*env)->NewGlobalRef( env, bridge );
    jclass bc = (*env)->GetObjectClass( env, bridge );
    g_mid_read = (*env)->GetMethodID( env, bc, "readBlocks", "(JI[B)I" );
    g_mid_scsi = (*env)->GetMethodID( env, bc, "scsi", "([BI[BI)I" );
    if( !g_mid_read || !g_mid_scsi ) return 0;

    dm_handle *h = calloc( 1, sizeof(*h) );
    if( !h ) return 0;
    h->dvdcss = dvdcss_open_stream( &h->stream, &g_cb );
    if( !h->dvdcss ) { free( h ); return 0; }
    return (jlong)(intptr_t)h;
}

JNIEXPORT jint JNICALL
Java_dev_discmount_app_CssNative_nativeSeek( JNIEnv *env, jclass clazz, jlong handle, jint block, jint flags )
{
    (void)env; (void)clazz;
    dm_handle *h = (dm_handle *)(intptr_t)handle;
    return dvdcss_seek( h->dvdcss, block, flags );
}

/* Lee [blocks] bloques desde la posición actual; devuelve bloques leídos o <0. flags: 1 = descifrar. */
JNIEXPORT jint JNICALL
Java_dev_discmount_app_CssNative_nativeRead( JNIEnv *env, jclass clazz, jlong handle, jbyteArray dst, jint blocks, jint flags )
{
    (void)clazz;
    dm_handle *h = (dm_handle *)(intptr_t)handle;
    void *buf = malloc( (size_t)blocks * DVDCSS_BLOCK_SIZE );
    if( !buf ) return -1;
    int n = dvdcss_read( h->dvdcss, buf, blocks, flags );
    if( n > 0 )
        (*env)->SetByteArrayRegion( env, dst, 0, n * DVDCSS_BLOCK_SIZE, (const jbyte *)buf );
    free( buf );
    return n;
}

JNIEXPORT jstring JNICALL
Java_dev_discmount_app_CssNative_nativeError( JNIEnv *env, jclass clazz, jlong handle )
{
    (void)clazz;
    dm_handle *h = (dm_handle *)(intptr_t)handle;
    return (*env)->NewStringUTF( env, h && h->dvdcss ? dvdcss_error( h->dvdcss ) : "sin handle" );
}

JNIEXPORT jboolean JNICALL
Java_dev_discmount_app_CssNative_nativeIsScrambled( JNIEnv *env, jclass clazz, jlong handle )
{
    (void)env; (void)clazz;
    dm_handle *h = (dm_handle *)(intptr_t)handle;
    return dvdcss_is_scrambled( h->dvdcss ) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_discmount_app_CssNative_nativeClose( JNIEnv *env, jclass clazz, jlong handle )
{
    (void)clazz;
    dm_handle *h = (dm_handle *)(intptr_t)handle;
    if( h )
    {
        dvdcss_close( h->dvdcss );
        free( h );
    }
    if( g_bridge ) { (*env)->DeleteGlobalRef( env, g_bridge ); g_bridge = NULL; }
}
