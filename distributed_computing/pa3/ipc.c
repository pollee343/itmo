#define _POSIX_C_SOURCE 200809L

#include "ipc.h"
#include "time.h"

#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

typedef struct
{
    local_id id;
    local_id process_count;
    int pipes[MAX_PROCESS_ID + 1][MAX_PROCESS_ID + 1][2];
    Message incoming[MAX_PROCESS_ID + 1];
    size_t received[MAX_PROCESS_ID + 1];
    int error;
} ProcessContext;

static int valid_context(const ProcessContext *ctx)
{
    return ctx != NULL && ctx->process_count > 1 &&
           ctx->process_count <= MAX_PROCESS_ID + 1 &&
           ctx->id >= 0 && ctx->id < ctx->process_count;
}

static void idle(void)
{
    struct timespec delay = {0, 1000000};
    nanosleep(&delay, NULL);
}

int send(void *self, local_id dst, const Message *msg)
{
    ProcessContext *ctx = self;
    int fd;
    int flags;
    size_t length;
    size_t offset;

    if (!valid_context(ctx) || msg == NULL || dst < 0 ||
        dst >= ctx->process_count || dst == ctx->id ||
        msg->s_header.s_magic != MESSAGE_MAGIC ||
        msg->s_header.s_payload_len > MAX_PAYLOAD_LEN)
    {
        errno = EINVAL;
        return -1;
    }

    fd = ctx->pipes[ctx->id][dst][1];
    flags = fcntl(fd, F_GETFL);

    if (flags == -1 ||
        fcntl(fd, F_SETFL, flags | O_NONBLOCK) == -1)
    {
        return -1;
    }

    length = sizeof(MessageHeader) + msg->s_header.s_payload_len;
    offset = 0;

    while (offset < length)
    {
        ssize_t n;

        n = write(fd, (const char *)msg + offset, length - offset);

        if (n > 0)
        {
            offset += (size_t)n;
        }
        else if (n < 0 && errno == EINTR)
        {
            continue;
        }
        else if (n < 0 &&
                 (errno == EAGAIN || errno == EWOULDBLOCK))
        {
            idle();
        }
        else
        {
            if (n == 0)
            {
                errno = EIO;
            }
            return -1;
        }
    }

    return 0;
}

int send_multicast(void *self, const Message *msg)
{
    ProcessContext *ctx = self;
    local_id dst;

    if (!valid_context(ctx))
    {
        errno = EINVAL;
        return -1;
    }

    for (dst = 0; dst < ctx->process_count; dst++)
    {
        if (dst != ctx->id)
        {
            if (send(ctx, dst, msg) != 0)
            {
                return -1;
            }
        }
    }

    return 0;
}

static int try_receive(
    ProcessContext *ctx,
    local_id from,
    Message *msg)
{
    int fd;
    int flags;
    Message *pending;
    size_t *used;

    fd = ctx->pipes[from][ctx->id][0];
    flags = fcntl(fd, F_GETFL);

    if (flags == -1 ||
        fcntl(fd, F_SETFL, flags | O_NONBLOCK) == -1)
    {
        return -1;
    }

    pending = &ctx->incoming[from];
    used = &ctx->received[from];

    for (;;)
    {
        size_t target = sizeof(MessageHeader);
        ssize_t n;

        if (*used >= target)
        {
            if (pending->s_header.s_magic != MESSAGE_MAGIC ||
                pending->s_header.s_payload_len > MAX_PAYLOAD_LEN)
            {
                errno = EPROTO;
                return -1;
            }

            target += pending->s_header.s_payload_len;

            if (*used == target)
            {
                memset(msg, 0, sizeof(*msg));
                memcpy(msg, pending, target);
                *used = 0;
                lamport_receive(msg->s_header.s_local_time);
                return 0;
            }
        }

        n = read(fd, (char *)pending + *used, target - *used);

        if (n > 0)
        {
            *used += (size_t)n;
            continue;
        }

        if (n == 0)
        {
            if (*used == 0)
            {
                return 2;
            }

            errno = EPROTO;
            return -1;
        }

        if (errno == EINTR)
        {
            continue;
        }

        if (errno == EAGAIN || errno == EWOULDBLOCK)
        {
            return 1;
        }

        return -1;
    }
}

int receive(void *self, local_id from, Message *msg)
{
    ProcessContext *ctx = self;

    if (!valid_context(ctx) || msg == NULL || from < 0 ||
        from >= ctx->process_count || from == ctx->id)
    {
        errno = EINVAL;
        return -1;
    }

    for (;;)
    {
        int result = try_receive(ctx, from, msg);

        if (result == 0 || result == -1)
        {
            return result;
        }

        if (result == 2)
        {
            errno = EPIPE;
            return -1;
        }

        idle();
    }
}

int receive_any(void *self, Message *msg)
{
    ProcessContext *ctx = self;

    if (!valid_context(ctx) || msg == NULL)
    {
        errno = EINVAL;
        return -1;
    }

    for (;;)
    {
        int active = 0;
        local_id from;

        for (from = 0; from < ctx->process_count; from++)
        {
            int result;

            if (from == ctx->id)
            {
                continue;
            }

            result = try_receive(ctx, from, msg);

            if (result == 0 || result == -1)
            {
                return result;
            }

            if (result == 1)
            {
                active = 1;
            }
        }

        if (!active)
        {
            errno = EPIPE;
            return -1;
        }

        idle();
    }
}
