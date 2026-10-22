#include "PullyFileLock.h"
#include <sys/file.h>

int pully_file_lock(int descriptor, int operation) {
    return flock(descriptor, operation);
}
