# The host toolbox for the CLI-only exercises (`make toolbox`, `make shell`). It reads the whole Docker VM:
# compose runs it with the host PID namespace, privileged, with the cgroup tree read-only and the docker socket.
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bpftrace \
        ca-certificates \
        curl \
        dnsutils \
        iproute2 \
        iputils-ping \
        jq \
        less \
        linux-tools-common \
        linux-tools-generic \
        lsof \
        postgresql-client \
        procps \
        psmisc \
        strace \
        sysstat \
        tcpdump \
        tzdata \
        util-linux \
    && rm -rf /var/lib/apt/lists/*

COPY bin/ /usr/local/bin/
COPY profile.sh /etc/profile.d/toolbox.sh

ENV PGHOST=postgres PGUSER=lab PGPASSWORD=lab PGDATABASE=lab

CMD ["/bin/sh", "-c", "mount-tracefs >/dev/null 2>&1; exec sleep infinity"]
