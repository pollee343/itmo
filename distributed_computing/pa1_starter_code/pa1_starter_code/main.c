#define _POSIX_C_SOURCE 200809L
#include "common.h"
#include "ipc.h"
#include "pa1.h"
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

typedef struct
{
    local_id id;
    local_id process_count;
    int pipes[MAX_PROCESS_ID + 1][MAX_PROCESS_ID + 1][2];
    Message incoming[MAX_PROCESS_ID + 1];
    size_t received[MAX_PROCESS_ID + 1];
} ProcessContext;

static int write_all(int fd, const char *text, size_t length)
{
    while (length > 0)
    {
        ssize_t n = write(fd, text, length);
        if (n < 0 && errno == EINTR)
            continue;
        if (n <= 0)
            return -1;
        text += n;
        length -= (size_t)n;
    }
    return 0;
}

static int log_event(int fd, const char *text, int length)
{
    if (length < 0 || length >= MAX_PAYLOAD_LEN)
        return -1;
    if (write_all(STDOUT_FILENO, text, (size_t)length) != 0)
        return -1;
    return write_all(fd, text, (size_t)length);
}

/* Keep only this process's incoming reads and outgoing writes. */
static int close_pipes(ProcessContext *ctx, int all)
{
    int result = 0;
    for (int from = 0; from < ctx->process_count; ++from)
        for (int to = 0; to < ctx->process_count; ++to)
            for (int end = 0; end < 2; ++end)
            {
                int *fd = &ctx->pipes[from][to][end];
                int keep = end == 0 ? to == ctx->id : from == ctx->id;
                if (*fd >= 0 && (all || !keep))
                {
                    if (close(*fd) != 0)
                        result = -1;
                    *fd = -1;
                }
            }
    return result;
}

static int await_children(ProcessContext *ctx, MessageType type)
{
    for (local_id from = 1; from < ctx->process_count; ++from)
    {
        if (from == ctx->id)
            continue;
        Message msg;
        if (receive(ctx, from, &msg) != 0 || msg.s_header.s_type != type)
            return -1;
    }
    return 0;
}

static int child_run(ProcessContext *ctx, int events_fd)
{
    for (int phase = 0; phase < 2; ++phase)
    {
        Message msg;
        memset(&msg, 0, sizeof(msg));
        int length = phase == 0
                         ? snprintf(msg.s_payload, sizeof(msg.s_payload), log_started_fmt,
                                    (int)ctx->id, (int)getpid(), (int)getppid())
                         : snprintf(msg.s_payload, sizeof(msg.s_payload), log_done_fmt,
                                    (int)ctx->id);
        if (log_event(events_fd, msg.s_payload, length) != 0)
            return -1;
        msg.s_header.s_magic = MESSAGE_MAGIC;
        msg.s_header.s_payload_len = (uint16_t)length;
        msg.s_header.s_type = phase == 0 ? STARTED : DONE;
        msg.s_header.s_local_time = 0;
        if (send_multicast(ctx, &msg) != 0 ||
            await_children(ctx, phase == 0 ? STARTED : DONE) != 0)
            return -1;
        length = snprintf(msg.s_payload, sizeof(msg.s_payload),
                          phase == 0 ? log_received_all_started_fmt
                                     : log_received_all_done_fmt,
                          (int)ctx->id);
        if (log_event(events_fd, msg.s_payload, length) != 0)
            return -1;
    }
    return 0;
}

int main(int argc, char *argv[])
{
    if (argc != 3 || strcmp(argv[1], "-p") != 0 || argv[2][0] == '\0')
        return EXIT_FAILURE;
    for (const char *p = argv[2]; *p; ++p)
        if (*p < '0' || *p > '9')
            return EXIT_FAILURE;
    char *end;
    errno = 0;
    long children = strtol(argv[2], &end, 10);
    if (errno != 0 || *end || children < 1 || children > 10)
        return EXIT_FAILURE;
    if (signal(SIGPIPE, SIG_IGN) == SIG_ERR)
        return EXIT_FAILURE;

    ProcessContext ctx;
    memset(&ctx, 0, sizeof(ctx));
    ctx.id = PARENT_ID;
    ctx.process_count = (local_id)(children + 1);
    for (int from = 0; from <= MAX_PROCESS_ID; ++from)
        for (int to = 0; to <= MAX_PROCESS_ID; ++to)
            ctx.pipes[from][to][0] = ctx.pipes[from][to][1] = -1;
    int events_fd = open(events_log, O_WRONLY | O_CREAT | O_TRUNC | O_APPEND, 0644);
    if (events_fd < 0)
        return EXIT_FAILURE;
    int pipes_fd = open(pipes_log, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (pipes_fd < 0)
    {
        close(events_fd);
        return EXIT_FAILURE;
    }
    int result = 0;
    pid_t pids[MAX_PROCESS_ID] = {0};
    int created = 0;
    for (int from = 0; from < ctx.process_count; ++from)
    {
        for (int to = 0; to < ctx.process_count; ++to)
        {
            if (from == to)
                continue;
            if (pipe(ctx.pipes[from][to]) != 0)
            {
                result = -1;
                goto cleanup;
            }
            char line[128];
            int n = snprintf(line, sizeof(line), "pipe %d -> %d: read=%d write=%d\n",
                             from, to, ctx.pipes[from][to][0], ctx.pipes[from][to][1]);
            if (n < 0 || (size_t)n >= sizeof(line) ||
                write_all(pipes_fd, line, (size_t)n) != 0)
            {
                result = -1;
                goto cleanup;
            }
        }
    }
    for (local_id id = 1; id < ctx.process_count; ++id)
    {
        pid_t pid = fork();
        if (pid < 0)
        {
            result = -1;
            goto cleanup;
        }
        if (pid == 0)
        {
            ctx.id = id;
            break;
        }
        pids[created++] = pid;
    }
    if (close_pipes(&ctx, 0) != 0)
        result = -1;
    if (result == 0)
    {
        if (ctx.id == PARENT_ID)
        {
            if (await_children(&ctx, STARTED) != 0 ||
                await_children(&ctx, DONE) != 0)
                result = -1;
        }
        else
            result = child_run(&ctx, events_fd);
    }

cleanup:
    if (close_pipes(&ctx, 1) != 0)
        result = -1;
    if (close(events_fd) != 0)
        result = -1;
    if (close(pipes_fd) != 0)
        result = -1;
    if (ctx.id == PARENT_ID)
    {
        if (result != 0)
            for (int i = 0; i < created; ++i)
                kill(pids[i], SIGTERM);
        for (int i = 0; i < created; ++i)
        {
            int status;
            pid_t waited;
            do
            {
                waited = waitpid(pids[i], &status, 0);
            } while (waited < 0 && errno == EINTR);
            if (waited < 0 || !WIFEXITED(status) || WEXITSTATUS(status) != 0)
                result = -1;
        }
    }
    return result == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}
