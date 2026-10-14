#!/bin/bash
# pchat on a sprite: the environment from .env (OPENROUTER_API_KEY,
# PCHAT_TOKEN, PCHAT_MODEL), the port the sprite URL routes to, the
# workspace, then the uberjar on babashka. pi, bb, node and java are on
# the PATH for the chats and the artifact projects. Pushed by
# `bb deploy <sprite>`. Kept identical, by hand, with the start script
# Storrito writes onto an agent's sprite: a change here is a change there.
set -a
[ -f /home/sprite/pchat/.env ] && . /home/sprite/pchat/.env
set +a
export PATH=/home/sprite/.local/bin:/.sprite/bin:/usr/local/bin:/usr/bin:/bin
export PORT=8080
export PCHAT_WORKSPACE=/home/sprite/pchat/workspace
export BB=/home/sprite/.local/bin/bb
export PCHAT_JAR=/home/sprite/pchat/pchat.jar
cd /home/sprite/pchat
# pchat ends with exit code 75 once it has put a newer jar in place (the
# person took an update, see pchat.update): then it is started again,
# with the new jar, after a second. Any other exit ends the service.
# bb runs as a child of this shell, so a stop or restart of the service
# (a TERM to this shell) is passed on to bb, and the shell waits for
# bb's own exit: no orphan keeps the port.
pid=
trap 'kill -TERM "$pid" 2>/dev/null' TERM INT
while true; do
  "$BB" "$PCHAT_JAR" &
  pid=$!
  wait "$pid"; code=$?
  # wait returns early (>128) when the trap fires; collect the real exit
  kill -0 "$pid" 2>/dev/null && { wait "$pid"; code=$?; }
  [ "$code" = 75 ] || exit "$code"
  sleep 1
done
