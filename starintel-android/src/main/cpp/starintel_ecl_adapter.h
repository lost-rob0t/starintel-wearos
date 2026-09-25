#pragma once

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// Implemented by the ABI-specific ECL/Tek9 artifact. Returned strings remain owned by
// the adapter until starintel_ecl_free is called.
int starintel_ecl_start(const char* runtime_directory, char** error);
char* starintel_ecl_request(const char* request_json);
void starintel_ecl_free(char* value);
void starintel_ecl_stop(void);

#ifdef __cplusplus
}
#endif
