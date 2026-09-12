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

static int write_text(int fd, const char *text, size_t size)
{
    size_t written = 0;
    while (written < size)
    {
        ssize_t result;
        result = write(fd, text + written, size - written);

        if (result > 0)
        {
            written = written + (size_t)result;
        }
        else if (result < 0 && errno == EINTR)
        {
            continue;
        }
        else
        {
            return -1;
        }
    }

    return 0;
}

static int print_log(int file, const char *text, int size)
{
    if (size < 0)
    {
        return -1;
    }
    if (size >= MAX_PAYLOAD_LEN)
    {
        return -1;
    }
    if (write_text(STDOUT_FILENO, text, (size_t)size) != 0)
    {
        return -1;
    }

    if (write_text(file, text, (size_t)size) != 0)
    {
        return -1;
    }
    return 0;
}

static int close_extra_pipes(ProcessContext *context, int close_everything)
{
    int result = 0;
    int from;
    int to;

    for (from = 0; from < context->process_count; from++)
    {
        for (to = 0; to < context->process_count; to++)
        {
            int read_fd;
            int write_fd;
            read_fd = context->pipes[from][to][0];
            write_fd = context->pipes[from][to][1];

            if (read_fd >= 0)
            {
                if (close_everything || to != context->id)
                {
                    if (close(read_fd) != 0)
                    {
                        result = -1;
                    }
                    context->pipes[from][to][0] = -1;
                }
            }

            if (write_fd >= 0)
            {
                if (close_everything || from != context->id)
                {
                    if (close(write_fd) != 0)
                    {
                        result = -1;
                    }

                    context->pipes[from][to][1] = -1;
                }
            }
        }
    }

    return result;
}
static int clean_program(
    ProcessContext *context,
    int events_file,
    int pipes_file,
    pid_t children_pids[],
    int created_children,
    int result)
{
    int i;
    if (close_extra_pipes(context, 1) != 0)
    {
        result = -1;
    }
    if (close(events_file) != 0)
    {
        result = -1;
    }
    if (close(pipes_file) != 0)
    {
        result = -1;
    }
    if (context->id == PARENT_ID)
    {
        if (result != 0)
        {
            for (i = 0; i < created_children; i++)
            {
                kill(children_pids[i], SIGTERM);
            }
        }
    }
    if (result == 0)
    {
        return EXIT_SUCCESS;
    }
    return EXIT_FAILURE;
}

static int wait_for_messages(ProcessContext *context, MessageType type)
{
    local_id i;
    for (i = 1; i < context->process_count; i++)
    {
        Message message;
        if (i == context->id)
        {
            continue;
        }
        if (receive(context, i, &message) != 0)
        {
            return -1;
        }
        if (message.s_header.s_type != type)
        {
            return -1;
        }
    }
    return 0;
}

static int run_child(ProcessContext *context, int events_file)
{
    Message message;
    int text_size;
    memset(&message, 0, sizeof(message));
    text_size = snprintf(
        message.s_payload,
        sizeof(message.s_payload),
        log_started_fmt,
        (int)context->id,
        (int)getpid(),
        (int)getppid());

    if (print_log(events_file, message.s_payload, text_size) != 0)
    {
        return -1;
    }

    message.s_header.s_magic = MESSAGE_MAGIC;
    message.s_header.s_payload_len = (uint16_t)text_size;
    message.s_header.s_type = STARTED;
    message.s_header.s_local_time = 0;

    if (send_multicast(context, &message) != 0)
    {
        return -1;
    }

    if (wait_for_messages(context, STARTED) != 0)
    {
        return -1;
    }

    text_size = snprintf(
        message.s_payload,
        sizeof(message.s_payload),
        log_received_all_started_fmt,
        (int)context->id);

    if (print_log(events_file, message.s_payload, text_size) != 0)
    {
        return -1;
    }

    memset(&message, 0, sizeof(message));
    text_size = snprintf(
        message.s_payload,
        sizeof(message.s_payload),
        log_done_fmt,
        (int)context->id);
    if (print_log(events_file, message.s_payload, text_size) != 0)
    {
        return -1;
    }
    message.s_header.s_magic = MESSAGE_MAGIC;
    message.s_header.s_payload_len = (uint16_t)text_size;
    message.s_header.s_type = DONE;
    message.s_header.s_local_time = 0;
    if (send_multicast(context, &message) != 0)
    {
        return -1;
    }
    if (wait_for_messages(context, DONE) != 0)
    {
        return -1;
    }
    text_size = snprintf(
        message.s_payload,
        sizeof(message.s_payload),
        log_received_all_done_fmt,
        (int)context->id);

    if (print_log(events_file, message.s_payload, text_size) != 0)
    {
        return -1;
    }
    return 0;
}

int main(int argc, char *argv[])
{
    ProcessContext context;
    pid_t children_pids[MAX_PROCESS_ID];
    int children_count;
    int created_children;
    int events_file;
    int pipes_file;
    int result;
    int i;
    int from;
    int to;

    if (argc != 3)
    {
        return EXIT_FAILURE;
    }

    if (strcmp(argv[1], "-p") != 0)
    {
        return EXIT_FAILURE;
    }

    if (argv[2][0] == '\0')
    {
        return EXIT_FAILURE;
    }

    for (i = 0; argv[2][i] != '\0'; i++)
    {
        if (argv[2][i] < '0' || argv[2][i] > '9')
        {
            return EXIT_FAILURE;
        }
    }

    children_count = atoi(argv[2]);

    if (children_count < 1 || children_count > 10)
    {
        return EXIT_FAILURE;
    }

    if (signal(SIGPIPE, SIG_IGN) == SIG_ERR)
    {
        return EXIT_FAILURE;
    }

    memset(&context, 0, sizeof(context));
    memset(children_pids, 0, sizeof(children_pids));

    context.id = PARENT_ID;
    context.process_count = (local_id)(children_count + 1);

    for (from = 0; from <= MAX_PROCESS_ID; from++)
    {
        for (to = 0; to <= MAX_PROCESS_ID; to++)
        {
            context.pipes[from][to][0] = -1;
            context.pipes[from][to][1] = -1;
        }
    }

    events_file = open(
        events_log,
        O_WRONLY | O_CREAT | O_TRUNC | O_APPEND,
        0644);

    if (events_file < 0)
    {
        return EXIT_FAILURE;
    }

    pipes_file = open(
        pipes_log,
        O_WRONLY | O_CREAT | O_TRUNC,
        0644);

    if (pipes_file < 0)
    {
        close(events_file);
        return EXIT_FAILURE;
    }
    result = 0;
    created_children = 0;
    for (from = 0; from < context.process_count; from++)
    {
        for (to = 0; to < context.process_count; to++)
        {
            char text[128];
            int text_size;
            if (from == to)
            {
                continue;
            }
            if (pipe(context.pipes[from][to]) != 0)
            {
                return clean_program(
                    &context,
                    events_file,
                    pipes_file,
                    children_pids,
                    created_children,
                    -1);
            }
            text_size = snprintf(
                text,
                sizeof(text),
                "pipe %d -> %d: read=%d write=%d\n",
                from,
                to,
                context.pipes[from][to][0],
                context.pipes[from][to][1]);

            if (text_size < 0)
            {
                result = -1;
                return clean_program(
                    &context,
                    events_file,
                    pipes_file,
                    children_pids,
                    created_children,
                    -1);
            }
            if ((size_t)text_size >= sizeof(text))
            {
                return clean_program(
                    &context,
                    events_file,
                    pipes_file,
                    children_pids,
                    created_children,
                    -1);
            }
            if (write_text(pipes_file, text, (size_t)text_size) != 0)
            {
                return clean_program(
                    &context,
                    events_file,
                    pipes_file,
                    children_pids,
                    created_children,
                    -1);
            }
        }
    }
    for (i = 1; i < context.process_count; i++)
    {
        pid_t pid;
        pid = fork();
        if (pid < 0)
        {
            return clean_program(
                &context,
                events_file,
                pipes_file,
                children_pids,
                created_children,
                -1);
        }
        if (pid == 0)
        {
            context.id = (local_id)i;
            break;
        }
        children_pids[created_children] = pid;
        created_children++;
    }

    if (close_extra_pipes(&context, 0) != 0)
    {
        result = -1;
    }
    if (result == 0)
    {
        if (context.id == PARENT_ID)
        {
            if (wait_for_messages(&context, STARTED) != 0)
            {
                result = -1;
            }
            if (result == 0)
            {
                if (wait_for_messages(&context, DONE) != 0)
                {
                    result = -1;
                }
            }
        }
        else
        {
            if (run_child(&context, events_file) != 0)
            {
                result = -1;
            }
        }
    }
}
