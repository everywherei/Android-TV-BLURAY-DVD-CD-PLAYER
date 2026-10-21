/*
 * ioctl_scsi.c: implementación de las funciones ioctl_*() de libdvdcss sobre comandos SCSI/MMC
 * enviados por un puente (USB desde Android). Sustituye a src/ioctl.c de libdvdcss.
 * Los formatos de los CDB y de las respuestas siguen la rama USCSI de ioctl.c y las especificaciones MMC.
 */
#include <string.h>
#include <stdint.h>

#include "dvdcss/dvdcss.h"
#include "css.h"
#include "ioctl.h"
#include "discmount_scsi.h"

#define DM_CHALLENGE_SIZE 10
#define MAXBUF (DVD_DISCKEY_SIZE + 8)

/* Envía un CDB de 12 bytes. [i_len] = longitud de datos (bytes 8-9 del CDB). */
static int send_cdb( uint8_t i_opcode, int i_dir, uint8_t *p_buf, int i_len,
                     int i_pos, int i_b6, int i_b7, int i_b10 )
{
    uint8_t cdb[12];
    memset( cdb, 0, sizeof(cdb) );
    cdb[0]  = i_opcode;
    cdb[2]  = ( i_pos >> 24 ) & 0xff;
    cdb[3]  = ( i_pos >> 16 ) & 0xff;
    cdb[4]  = ( i_pos >>  8 ) & 0xff;
    cdb[5]  =   i_pos         & 0xff;
    cdb[6]  = i_b6;
    cdb[7]  = i_b7;
    cdb[8]  = ( i_len >> 8 ) & 0xff;
    cdb[9]  =   i_len        & 0xff;
    cdb[10] = i_b10;
    return discmount_scsi_exec( cdb, i_dir, p_buf, i_len ) < 0 ? -1 : 0;
}

int ioctl_ReadCopyright( int i_fd, int i_layer, int *pi_copyright )
{
    uint8_t b[8 + 1];
    (void)i_fd;
    memset( b, 0, sizeof(b) );
    if( send_cdb( GPCMD_READ_DVD_STRUCTURE, DM_DIR_IN, b, 8, 0, i_layer, DVD_STRUCT_COPYRIGHT, 0 ) < 0 )
        return -1;
    *pi_copyright = b[4];
    return 0;
}

int ioctl_ReadDiscKey( int i_fd, const int *pi_agid, uint8_t *p_key )
{
    static uint8_t b[MAXBUF];
    (void)i_fd;
    memset( b, 0, sizeof(b) );
    if( send_cdb( GPCMD_READ_DVD_STRUCTURE, DM_DIR_IN, b, DVD_DISCKEY_SIZE + 4, 0, 0, DVD_STRUCT_DISCKEY,
                  *pi_agid << 6 ) < 0 )
        return -1;
    memcpy( p_key, b + 4, DVD_DISCKEY_SIZE );
    return 0;
}

int ioctl_ReadTitleKey( int i_fd, const int *pi_agid, int i_pos, uint8_t *p_key )
{
    uint8_t b[12 + 1];
    (void)i_fd;
    memset( b, 0, sizeof(b) );
    if( send_cdb( GPCMD_REPORT_KEY, DM_DIR_IN, b, 12, i_pos, 0, 0, DVD_REPORT_TITLE_KEY | ( *pi_agid << 6 ) ) < 0 )
        return -1;
    memcpy( p_key, b + 5, DVD_KEY_SIZE );
    return 0;
}

int ioctl_ReportAgid( int i_fd, int *pi_agid )
{
    uint8_t b[8 + 1];
    (void)i_fd;
    memset( b, 0, sizeof(b) );
    if( send_cdb( GPCMD_REPORT_KEY, DM_DIR_IN, b, 8, 0, 0, 0, DVD_REPORT_AGID | ( *pi_agid << 6 ) ) < 0 )
        return -1;
    *pi_agid = b[7] >> 6;
    return 0;
}

int ioctl_ReportChallenge( int i_fd, const int *pi_agid, uint8_t *p_challenge )
{
    uint8_t b[16 + 1];
    (void)i_fd;
    memset( b, 0, sizeof(b) );
    if( send_cdb( GPCMD_REPORT_KEY, DM_DIR_IN, b, 16, 0, 0, 0, DVD_REPORT_CHALLENGE | ( *pi_agid << 6 ) ) < 0 )
        return -1;
    memcpy( p_challenge, b + 4, DM_CHALLENGE_SIZE );
    return 0;
}

int ioctl_ReportASF( int i_fd, int *pi_asf )
{
    uint8_t b[8 + 1];
    (void)i_fd;
    memset( b, 0, sizeof(b) );
    if( send_cdb( GPCMD_REPORT_KEY, DM_DIR_IN, b, 8, 0, 0, 0, DVD_REPORT_ASF ) < 0 )
        return -1;
    *pi_asf = b[7] & 1;
    return 0;
}

int ioctl_ReportKey1( int i_fd, const int *pi_agid, uint8_t *p_key )
{
    uint8_t b[12 + 1];
    (void)i_fd;
    memset( b, 0, sizeof(b) );
    if( send_cdb( GPCMD_REPORT_KEY, DM_DIR_IN, b, 12, 0, 0, 0, DVD_REPORT_KEY1 | ( *pi_agid << 6 ) ) < 0 )
        return -1;
    memcpy( p_key, b + 4, DVD_KEY_SIZE );
    return 0;
}

int ioctl_InvalidateAgid( int i_fd, int *pi_agid )
{
    (void)i_fd;
    return send_cdb( GPCMD_REPORT_KEY, DM_DIR_NONE, NULL, 0, 0, 0, 0, DVDCSS_INVALIDATE_AGID | ( *pi_agid << 6 ) );
}

int ioctl_SendChallenge( int i_fd, const int *pi_agid, const uint8_t *p_challenge )
{
    uint8_t b[16 + 1];
    (void)i_fd;
    memset( b, 0, sizeof(b) );
    b[1] = 0x0e;
    memcpy( b + 4, p_challenge, DM_CHALLENGE_SIZE );
    return send_cdb( GPCMD_SEND_KEY, DM_DIR_OUT, b, 16, 0, 0, 0, DVD_SEND_CHALLENGE | ( *pi_agid << 6 ) );
}

int ioctl_SendKey2( int i_fd, const int *pi_agid, const uint8_t *p_key )
{
    uint8_t b[12 + 1];
    (void)i_fd;
    memset( b, 0, sizeof(b) );
    b[1] = 0x0a;
    memcpy( b + 4, p_key, DVD_KEY_SIZE );
    return send_cdb( GPCMD_SEND_KEY, DM_DIR_OUT, b, 12, 0, 0, 0, DVD_SEND_KEY2 | ( *pi_agid << 6 ) );
}

int ioctl_ReportRPC( int i_fd, int *p_type, int *p_mask, int *p_scheme )
{
    uint8_t b[8 + 1];
    (void)i_fd;
    memset( b, 0, sizeof(b) );
    if( send_cdb( GPCMD_REPORT_KEY, DM_DIR_IN, b, 8, 0, 0, 0, DVD_REPORT_RPC ) < 0 )
        return -1;
    *p_type   = b[4] >> 6;
    *p_mask   = b[5];
    *p_scheme = b[6];
    return 0;
}
