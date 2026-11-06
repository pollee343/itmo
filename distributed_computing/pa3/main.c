#define _POSIX_C_SOURCE 200809L

#include "common.h"
#include "ipc.h"
#include "pa2345.h"
#include "banking.h"
#include "ipc.h"
#include "time.h"

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
    int error;
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
    if (size < 0 || size >= MAX_PAYLOAD_LEN)
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

static int close_extra_pipes(
    ProcessContext *context,
    int close_everything)
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

static int wait_for_messages(
    ProcessContext *context,
    MessageType type)
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

        if (message.s_header.s_type != (int16_t)type)
        {
            return -1;
        }
    }

    return 0;
}

static void prepare_message(
    Message *message,
    MessageType type,
    int size)
{
    message->s_header.s_magic = MESSAGE_MAGIC;
    message->s_header.s_payload_len = (uint16_t)size;
    message->s_header.s_type = type;
    message->s_header.s_local_time = lamport_tick();
}

static int fill_history(
    BalanceHistory *history,
    timestamp_t time)
{
    if (time < 0 || time >= UINT8_MAX)
    {
        return -1;
    }

    while (history->s_history_len <= time)
    {
        int i = history->s_history_len;

        if (i <= time)
    {
        history->s_history[i] = history->s_history[i - 1];
        history->s_history[i].s_time = (timestamp_t)i;
        history->s_history[i].s_balance_pending_in = 0;
        history->s_history_len++;
    }
    }

    return 0;
}

void transfer(
    void *parent_data,
    local_id src,
    local_id dst,
    balance_t amount)
{
    ProcessContext *context = parent_data;
    TransferOrder order;
    Message message;

    if (context->error != 0)
    {
        return;
    }

    order.s_src = src;
    order.s_dst = dst;
    order.s_amount = amount;

    memcpy(message.s_payload, &order, sizeof(order));
    prepare_message(&message, TRANSFER, sizeof(order));

    if (send(context, src, &message) != 0 ||
        receive(context, dst, &message) != 0)
    {
        context->error = -1;
        return;
    }

    if (message.s_header.s_type != ACK ||
        message.s_header.s_payload_len != 0)
    {
        context->error = -1;
    }
}

static int process_transfer(
    ProcessContext *context,
    Message *message,
    BalanceHistory *history,
    balance_t *balance,
    int events_file)
{
    TransferOrder order;
    timestamp_t time;
    timestamp_t send_time;
    char text[MAX_PAYLOAD_LEN];
    int text_size;

    if (message->s_header.s_payload_len != sizeof(order))
    {
        return -1;
    }

    memcpy(&order, message->s_payload, sizeof(order));

    if (context->id == order.s_src)
    {
        prepare_message(message, TRANSFER, sizeof(order));
        time = message->s_header.s_local_time;

        if (fill_history(history, time) != 0)
        {
            return -1;
        }

        *balance = *balance - order.s_amount;
        history->s_history[time].s_balance = *balance;

        text_size = snprintf(
            text,
            sizeof(text),
            log_transfer_out_fmt,
            (int)time,
            (int)context->id,
            (int)order.s_amount,
            (int)order.s_dst);

        if (print_log(events_file, text, text_size) != 0)
        {
            return -1;
        }

        return send(context, order.s_dst, message);
    }

    if (context->id == order.s_dst)
    {
        timestamp_t i;

        send_time = message->s_header.s_local_time;
        time = get_lamport_time();

        if (fill_history(history, time) != 0)
        {
            return -1;
        }

        for (i = send_time; i < time; i++)
        {
            history->s_history[i].s_balance_pending_in +=
                order.s_amount;
        }

        *balance = *balance + order.s_amount;
        history->s_history[time].s_balance = *balance;

        text_size = snprintf(
            text,
            sizeof(text),
            log_transfer_in_fmt,
            (int)time,
            (int)context->id,
            (int)order.s_amount,
            (int)order.s_src);

        if (print_log(events_file, text, text_size) != 0)
        {
            return -1;
        }

        memset(message, 0, sizeof(*message));
        prepare_message(message, ACK, 0);

        return send(context, PARENT_ID, message);
    }

    return -1;
}

static int run_child(
    ProcessContext *context,
    int events_file,
    balance_t balance)
{
    BalanceHistory history;
    Message message;
    int text_size;
    int stopped = 0;
    int done_count = 0;
    int history_size;
    timestamp_t time;

    memset(&history, 0, sizeof(history));

    history.s_id = context->id;
    history.s_history_len = 1;
    history.s_history[0].s_balance = balance;

    memset(&message, 0, sizeof(message));
    prepare_message(&message, STARTED, 0);
    time = message.s_header.s_local_time;

    text_size = snprintf(
        message.s_payload,
        sizeof(message.s_payload),
        log_started_fmt,
        (int)time,
        (int)context->id,
        (int)getpid(),
        (int)getppid(),
        (int)balance);

    if (print_log(events_file, message.s_payload, text_size) != 0)
    {
        return -1;
    }

    message.s_header.s_payload_len = (uint16_t)text_size;

    if (send_multicast(context, &message) != 0 ||
        wait_for_messages(context, STARTED) != 0)
    {
        return -1;
    }

    text_size = snprintf(
        message.s_payload,
        sizeof(message.s_payload),
        log_received_all_started_fmt,
        (int)get_lamport_time(),
        (int)context->id);

    if (print_log(events_file, message.s_payload, text_size) != 0)
    {
        return -1;
    }

    while (!stopped || done_count < context->process_count - 2)
    {
        if (receive_any(context, &message) != 0)
        {
            return -1;
        }

        if (message.s_header.s_type == TRANSFER)
        {
            if (process_transfer(
                    context,
                    &message,
                    &history,
                    &balance,
                    events_file) != 0)
            {
                return -1;
            }
        }
        else if (message.s_header.s_type == DONE)
        {
            done_count++;
        }
        else if (message.s_header.s_type == STOP && !stopped)
        {
            stopped = 1;

            memset(&message, 0, sizeof(message));
            prepare_message(&message, DONE, 0);
            time = message.s_header.s_local_time;

            if (fill_history(&history, time) != 0)
            {
                return -1;
            }

            text_size = snprintf(
                message.s_payload,
                sizeof(message.s_payload),
                log_done_fmt,
                (int)time,
                (int)context->id,
                (int)balance);

            if (print_log(
                    events_file,
                    message.s_payload,
                    text_size) != 0)
            {
                return -1;
            }

            message.s_header.s_payload_len = (uint16_t)text_size;

            if (send_multicast(context, &message) != 0)
            {
                return -1;
            }
        }
        else
        {
            return -1;
        }
    }

    text_size = snprintf(
        message.s_payload,
        sizeof(message.s_payload),
        log_received_all_done_fmt,
        (int)get_lamport_time(),
        (int)context->id);

    if (print_log(events_file, message.s_payload, text_size) != 0)
    {
        return -1;
    }

    prepare_message(&message, BALANCE_HISTORY, 0);
    time = message.s_header.s_local_time;

    if (fill_history(&history, time) != 0)
    {
        return -1;
    }

    history_size = offsetof(BalanceHistory, s_history) +
                   history.s_history_len * sizeof(BalanceState);

    memcpy(message.s_payload, &history, (size_t)history_size);
    message.s_header.s_payload_len = (uint16_t)history_size;

    return send(context, PARENT_ID, &message);
}

static int run_parent(ProcessContext *context)
{
    AllHistory history;
    Message message;
    int i;
    int max_length = 0;

    if (wait_for_messages(context, STARTED) != 0)
    {
        return -1;
    }

    bank_robbery(context, (local_id)(context->process_count - 1));

    if (context->error != 0)
    {
        return -1;
    }

    memset(&message, 0, sizeof(message));
    prepare_message(&message, STOP, 0);

    if (send_multicast(context, &message) != 0 ||
        wait_for_messages(context, DONE) != 0)
    {
        return -1;
    }

    memset(&history, 0, sizeof(history));

    history.s_history_len = (uint8_t)(context->process_count - 1);

    for (i = 1; i < context->process_count; i++)
    {
        BalanceHistory *child_history = &history.s_history[i - 1];
        size_t size;

        if (receive(context, (local_id)i, &message) != 0)
        {
            return -1;
        }

        size = message.s_header.s_payload_len;

        if (message.s_header.s_type != BALANCE_HISTORY ||
            size < offsetof(BalanceHistory, s_history) ||
            size > sizeof(BalanceHistory))
        {
            return -1;
        }

        memcpy(child_history, message.s_payload, size);

        if (child_history->s_id != i ||
            child_history->s_history_len == 0 ||
            size != offsetof(BalanceHistory, s_history) +
                    child_history->s_history_len * sizeof(BalanceState))
        {
            return -1;
        }

        if (child_history->s_history_len > max_length)
        {
            max_length = child_history->s_history_len;
        }
    }

    for (i = 0; i < history.s_history_len; i++)
    {
        if (fill_history(
                &history.s_history[i],
                (timestamp_t)(max_length - 1)) != 0)
        {
            return -1;
        }
    }

    print_history(&history);

    return 0;
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

static int parse_number(const char *text, int maximum)
{
    int value = 0;
    int i;

    if (text[0] == '\0')
    {
        return -1;
    }

    for (i = 0; text[i] != '\0'; i++)
    {
        if (text[i] < '0' || text[i] > '9')
        {
            return -1;
        }

        value = value * 10 + text[i] - '0';

        if (value > maximum)
        {
            return -1;
        }
    }

    return value;
}

int main(int argc, char *argv[])
{
    ProcessContext context;
    pid_t children_pids[MAX_PROCESS_ID];
    balance_t balances[MAX_PROCESS_ID + 1];
    int children_count;
    int created_children;
    int events_file;
    int pipes_file;
    int result;
    int i;
    int from;
    int to;

    if (argc < 4 || strcmp(argv[1], "-p") != 0)
    {
        return EXIT_FAILURE;
    }

    children_count = parse_number(argv[2], 10);

    if (children_count < 1 || argc != children_count + 3)
    {
        return EXIT_FAILURE;
    }

    for (i = 1; i <= children_count; i++)
    {
        int balance;

        balance = parse_number(argv[i + 2], 99);

        if (balance < 1)
        {
            return EXIT_FAILURE;
        }

        balances[i] = (balance_t)balance;
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

            int end;

            for (end = 0; end < 2; end++)
			{
    			int fd;
    			int flags;

    			fd = context.pipes[from][to][end];
    			flags = fcntl(fd, F_GETFL);

    			if (flags == -1)
    			{
        			return clean_program(
            		&context,
            		events_file,
            		pipes_file,
            		children_pids,
            		created_children,
            		-1);
    			}

                if (fcntl(fd, F_SETFL, flags | O_NONBLOCK) == -1)
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

            text_size = snprintf(
                text,
                sizeof(text),
                "pipe %d -> %d: read=%d write=%d\n",
                from,
                to,
                context.pipes[from][to][0],
                context.pipes[from][to][1]);

            if (text_size < 0 || (size_t)text_size >= sizeof(text))
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
        return clean_program(
            &context,
            events_file,
            pipes_file,
            children_pids,
            created_children,
            -1);
    }

    if (context.id == PARENT_ID)
    {
        if (run_parent(&context) != 0)
        {
            return clean_program(
                &context,
                events_file,
                pipes_file,
                children_pids,
                created_children,
                -1);
        }

        for (i = 0; i < created_children; i++)
        {
            int status;
            pid_t waited;

            do
            {
                waited = waitpid(children_pids[i], &status, 0);
            }
            while (waited < 0 && errno == EINTR);

            if (waited < 0 ||
                !WIFEXITED(status) ||
                WEXITSTATUS(status) != 0)
            {
                result = -1;
            }
        }
    }
    else
    {
        if (run_child(
                &context,
                events_file,
                balances[context.id]) != 0)
        {
            result = -1;
        }
    }

    return clean_program(
        &context,
        events_file,
        pipes_file,
        children_pids,
        created_children,
        result);
}
