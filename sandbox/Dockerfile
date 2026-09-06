# Sandbox the agent runs shell commands in.
#
# Deliberately minimal: the model gets a POSIX shell and the handful of tools the
# skills actually need, and nothing else. Anything missing here is a deliberate
# decision, not an oversight — a smaller image is a smaller blast radius.
FROM alpine:3.22

RUN apk add --no-cache \
        bash \
        coreutils \
        curl \
        git \
        jq \
        tzdata

# Only this directory is writable by the agent. The project's .git is mounted
# read-only at /project/.git by the bot, so history can be read but not rewritten.
WORKDIR /work

# The container is a long-lived shell host; commands arrive via `docker exec`.
CMD ["sleep", "infinity"]
