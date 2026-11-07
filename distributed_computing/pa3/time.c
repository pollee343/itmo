#include "time.h"

static timestamp_t lamport_time = 0;

timestamp_t get_lamport_time(void)
{
    return lamport_time;
}

timestamp_t lamport_tick(void)
{
    lamport_time++;

    return lamport_time;
}

void lamport_receive(timestamp_t time)
{
    if (lamport_time < time)
    {
        lamport_time = time;
    }

    lamport_time++;
}
