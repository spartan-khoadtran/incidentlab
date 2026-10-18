#!/usr/bin/env bash
# Per-destination network faults on this container's interface, driven by the lab controller.
#
#   netem.sh apply <host> <delay_ms> <loss_pct> [<host> <delay_ms> <loss_pct>]...
#   netem.sh clear
#
# apply resolves each host and builds a prio root qdisc. Bands 1:1 to 1:3 keep the default priomap,
# so other traffic is untouched. Each target gets its own band from 1:4 up, a netem leaf on it, and
# a u32 filter that steers packets to the target's IP into it. The applied spec is kept in the state
# file, so a repeated apply with the same spec returns at once. clear removes the root qdisc and the
# state file. Needs CAP_NET_ADMIN.
set -euo pipefail

DEV=${NETEM_DEV:-eth0}
STATE=${NETEM_STATE:-/run/netem.state}
FIRST_BAND=4

usage() {
  echo "usage: $0 apply <host> <delay_ms> <loss_pct> [<host> <delay_ms> <loss_pct>]... | clear" >&2
  exit 2
}

resolve() {
  getent ahostsv4 "$1" | awk 'NR == 1 { print $1 }'
}

has_root() {
  tc qdisc show dev "$DEV" | grep -q "^qdisc prio 1: root"
}

clear_all() {
  if has_root; then
    tc qdisc del dev "$DEV" root
  fi
  rm -f "$STATE"
}

apply() {
  [ $# -ge 3 ] && [ $(( $# % 3 )) -eq 0 ] || usage
  local spec="" ips=() delays=() losses=()
  while [ $# -gt 0 ]; do
    local host=$1 delay=$2 loss=$3
    shift 3
    local ip
    ip=$(resolve "$host" || true)
    if [ -z "$ip" ]; then
      echo "netem: cannot resolve $host" >&2
      exit 1
    fi
    ips+=("$ip")
    delays+=("$delay")
    losses+=("$loss")
    spec+="$host=$ip delay=${delay}ms loss=${loss}%;"
  done
  if has_root && [ -f "$STATE" ] && [ "$(cat "$STATE")" = "$spec" ]; then
    exit 0
  fi
  clear_all
  local count=${#ips[@]}
  tc qdisc add dev "$DEV" root handle 1: prio bands $(( FIRST_BAND - 1 + count ))
  local i
  for (( i = 0; i < count; i++ )); do
    local band=$(( FIRST_BAND + i ))
    local options=()
    if [ "${delays[$i]}" != "0" ]; then options+=(delay "${delays[$i]}ms"); fi
    if [ "${losses[$i]}" != "0" ]; then options+=(loss "${losses[$i]}%"); fi
    tc qdisc add dev "$DEV" parent "1:$band" handle "${band}0:" netem "${options[@]}"
    tc filter add dev "$DEV" protocol ip parent 1:0 prio "$band" u32 match ip dst "${ips[$i]}/32" flowid "1:$band"
  done
  printf '%s' "$spec" > "$STATE"
  echo "netem: $spec"
}

case "${1:-}" in
  apply) shift; apply "$@" ;;
  clear) clear_all ;;
  *) usage ;;
esac
