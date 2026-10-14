#!/bin/bash
# pchat on a sprite: the environment from .env (OPENROUTER_API_KEY,
# PCHAT_TOKEN, PCHAT_MODEL), the port the sprite URL routes to, the
# workspace, then the uberjar on babashka. pi, bb, node and java are on
# the PATH for the chats and the artifact projects. Pushed by
# `bb deploy <sprite>`.
#
# pchat ends with exit code 75 once it has put a newer jar in place
# (see pchat.update): then it is started again, with the new jar, in
# seconds. Any other exit ends the service as before.
set -a
[ -f /home/sprite/pchat/.env ] && . /home/sprite/pchat/.env
set +a
export PATH=/home/sprite/.local/bin:/.sprite/bin:/usr/local/bin:/usr/bin:/bin
export PORT=8080
export PCHAT_WORKSPACE=/home/sprite/pchat/workspace
export BB=/home/sprite/.local/bin/bb
export PCHAT_JAR=/home/sprite/pchat/pchat.jar
cd /home/sprite/pchat
while true; do
  /home/sprite/.local/bin/bb /home/sprite/pchat/pchat.jar
  code=$?
  [ "$code" = 75 ] || exit "$code"
done
