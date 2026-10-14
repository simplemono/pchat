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
# pchat swaps this file for a newer release when the person takes an
# update (pchat.update) and then exits with code 75. The sprite's runtime
# starts a service whose process exited by itself again (verified
# 2026-10-14: restart_count grows, the first restart comes within a
# second, repeated exits back off), so pchat comes back with the new jar.
# The variable tells pchat where the jar is and that an exit is followed
# by a restart; without it pchat offers no update.
export PCHAT_JAR=/home/sprite/pchat/pchat.jar
cd /home/sprite/pchat
exec "$BB" "$PCHAT_JAR"
