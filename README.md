# pchat

A chat for people who are not developers, with an agent that builds
things they can see. [rframes](https://github.com/simplemono/rframes) and
babashka are the foundation, [pi](https://github.com/earendil-works/pi-mono)
is the agent underneath, one pi process per chat. A sidebar holds many
chats, like the chat products people know; beside the chat, artifacts:
pages, images, videos and motion designs the agent makes, live, while it
works on them.

MIT. Storrito-specific parts are fine here, customer-specific ones are not.

## Decisions (2026-10-10)

1. **Harness:** `pi --mode rpc` as a subprocess per chat, JSONL over stdio.
   pi owns the agent loop, the tools (bash, read, edit, write), the session
   files and the model. Model `openrouter/openai/gpt-6.1-sol` on the
   operator's OpenRouter key, `--approve`: the VM the chat runs on is the
   sandbox.
2. **Chats:** free conversations, one pi session each, all in one shared
   workspace folder. Projects as a grouping are not in the MVP.
3. **Artifacts:** files the agent writes into the workspace's `artifacts/`
   folder, shown beside the chat by type with live reload: HTML in an
   iframe (controls live inside the page), images, videos, and
   [fnmotion](https://github.com/simplemono/fnmotion) motion designs with a
   player and scrubber.
4. **Templates:** the artifact is the template. The agent develops a story
   template as an artifact with sample data; an automation renders that
   same artifact with real data.
5. **Order:** pchat core first (pi, sidebar, chat with markdown and images,
   artifacts pane, token auth, sprite deploy), the first customer
   automation as its first chat afterwards.
6. **Automations:** a webhook URL per chat on the VM. The webhook runs a
   babashka script the agent developed in the chat: deterministic, no
   model in the loop except small classification calls. The agent works
   only while developing or fixing.
7. **Rendering and posting:** the video or image is rendered on the VM
   (headless Chrome and ffmpeg from the fnmotion artifact). The Storrito
   API only overlays the Instagram stickers (music and such) and posts;
   its Instagram web components give the preview in pchat. Drafts first; an
   auto-post switch per property later.
8. **Data people maintain** (a property's theme, music, template) is told
   to the chat and kept by the agent in a plain data file the scripts
   read.
9. **Reuse:** the generic parts of [rchat](https://github.com/simplemono/rchat)
   (markdown, image attachments, upload, token auth, canvas live reload)
   come in as the `rchat$server` dependency.
10. **Never on screen:** tool calls collapse to one row, no paths, no
    terminal, artifacts have names. One download per artifact.

## Run it

```bash
echo 'OPENROUTER_API_KEY=...' > .env   # once
bb start                             # http://localhost:8080, compiles the bundle first (JVM)
bb test
```

`pi` must be on the PATH ([pi-mono](https://github.com/earendil-works/pi-mono),
`npm i -g @earendil-works/pi-coding-agent`). The agent works in
`./workspace` (`PCHAT_WORKSPACE`), its sessions under `workspace/.pchat/`.
`PCHAT_TOKEN` puts the page behind a secret link, `PORT` picks the port,
`PCHAT_MODEL` and `PCHAT_PROVIDER` the model (default
`openai/gpt-6.1-sol` on `openrouter`).

### On a sprite

```bash
sprite create <name>      # once, a sprites.dev sprite (Node and a JDK come with it)
bb deploy <name>          # the jar as the service `pchat` on the sprite, prints the link
sprite config update --url-auth public -s <name>   # the page behind the token link only
```

The first deploy installs babashka and pi under `~/.local/bin` on the
sprite, pushes `deploy/start.sh` and the local `.env` with a generated
`PCHAT_TOKEN` (an `.env` already on the sprite is kept), and warms the
dependency cache of the artifact projects. Every deploy after that
pushes the jar and restarts the service, about twenty seconds. The
workspace lives in `/home/sprite/pchat/workspace`, the log in
`/.sprite/logs/services/pchat.log`.

## Status

Milestone 1, 2026-10-10: one chat on one pi process. `pchat.pi` spawns
`pi --mode rpc` and reads its events, `pchat.chat` folds them into the
chat as a value, `pchat.view` renders it with rchat's composer and
stylesheet: streamed answers as markdown, tool calls as one collapsed row
each ("Wrote a file"), the last answer of a run as the handover, Stop
aborts.

Milestone 2, 2026-10-10: many chats. The sidebar lists pi's session files
under `workspace/.pchat/sessions/`, newest first, named after their first
message; opening one reads its history from the file (`pchat.session`),
a new chat gets a fresh id, and the first message spawns its pi, which
resumes the session file by that id after a restart. Each chat has its own
pi process; the sidebar shows which ones are working.

Milestone 3a, 2026-10-10: the artifacts pane. The files under
`workspace/artifacts/` appear beside the chat (`pchat.artifacts`): a page
in an iframe that reloads when its file changes, an image, a video or an
audio file as themselves, anything else as a download, a chooser over all
of them. The newest thing the agent wrote takes the pane, a pick of the
user holds until the agent writes something newer. The folder is read
after every tool call and twice a second.

Images, 2026-10-10: paste, drop or attach images to a message. The
browser uploads them to `/upload` (rchat's content-addressed store under
`workspace/.pchat/looks/`), the message names them, the server reads them
back as pi's image blocks, and the thumbnails sit under the message, also
after a restart: the base64 pi echoes and keeps in the session file is
interned into the same file names (`pchat.looks`).

Milestone 3b, 2026-10-10, redone the same day: motion designs as
artifact projects. A directory under `artifacts/` with a `bb.edn` is an
artifact with a process of its own: pchat starts it (`pchat.projects`),
proxies its page under `/artifacts/<name>/` into the live iframe, reloads
a source file into it over nREPL when the file changes, restarts it when
it dies, and writes its output to `.log`. The template
(`bin/new <name>` in the workspace) is a small rframes app with
[fnmotion](https://github.com/simplemono/fnmotion) and its server-side
player `fnmotion.rframes`: `design-view` as the frame at a time,
`controls` over the design data, `design.edn` across restarts. The agent
works on it like at a REPL, `bin/eval <name> '(defn design-view …)'`,
while the person watches; pchat's own process never evaluates the
agent's code. A HyperFrames project can take the same slot: a directory,
a port, a page.

**Milestone 4, a sprite.** `bb jar` bundles the app for babashka,
`bb deploy <sprite>` runs it as a service on a sprite behind the token
link. The artifact projects run there as well: the JDK and git of the
sprite resolve their dependencies once, then they start in seconds.

Next: automations (a webhook per chat runs a script the agent wrote).
