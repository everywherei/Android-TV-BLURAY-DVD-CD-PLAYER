#ifndef DISCMOUNT_SCSI_H
#define DISCMOUNT_SCSI_H
#include <stdint.h>

#define DM_DIR_NONE 0
#define DM_DIR_IN   1
#define DM_DIR_OUT  2

/* Ejecuta un CDB de 12 bytes contra la unidad (vía puente JNI -> Kotlin -> USB).
 * Devuelve >= 0 si el comando terminó bien, < 0 si hubo error / CHECK CONDITION. */
int discmount_scsi_exec( const uint8_t *p_cdb, int i_dir, uint8_t *p_buf, int i_len );

#endif
