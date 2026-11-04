#ifndef LAMPORT_H
#define LAMPORT_H

#include "ipc.h"

timestamp_t get_lamport_time(void);
timestamp_t lamport_tick(void);
void lamport_receive(timestamp_t time);

#endif