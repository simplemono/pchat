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
2. **Chats:** free conversations, one pi session each. Since 0.2.0 every
   chat works in a folder of its own (see "The chats"); before, all of
   them shared the one workspace folder. Projects as a grouping are not
   in the MVP.
3. **Artifacts:** files the agent writes into the chat's `artifacts/`
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
6. **Automations:** a job per chat on the VM (see Jobs below): the
   agent runs JOB.md on a schedule or when a service calls the job's
   endpoint, a fresh chat each time. No scripts: a non-technical person
   never sees one, and the model adapts to the edge cases a script
   would not. The agent works
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
```

pchat reads `.env` and `.env.local` from the directory it starts in
(quotes around a value and `export` in front are fine), so `bb -m
pchat.main` or `bb pchat.jar` next to the file work the same.

```sh
bb test
bb smoke                             # one real agent run: a card, music, the video; cents
```

`pi` must be on the PATH ([pi-mono](https://github.com/earendil-works/pi-mono),
`npm i -g @earendil-works/pi-coding-agent`). The chats live under
`./workspace` (`PCHAT_WORKSPACE`), one folder each. `PCHAT_TOKEN` puts
the page behind a secret link, `PORT` picks the port, `PCHAT_MODEL` and
`PCHAT_PROVIDER` the model (default `openai/gpt-6.1-sol` on
`openrouter`).

### The chats

A chat's folder is its world. `workspace/chats/<id>/` is the working
directory of the chat's pi process, and everything of the chat is in
it, nothing of any other chat:

```
workspace/
  bin/                  the scripts (bin/new, bin/render, bin/music, ...)
  .pchat/               the templates, the lib the scripts load, the connection tests
  .pi/mcp.json          the connections, read by pi when a chat's process starts
  chats/<id>/           one per chat: pi's working directory
    artifacts/          what the agent makes, shown beside the chat
    uploads/            the pictures and clips the person attached
    tmp/                TMPDIR of the agent and its tools
    .session/           pi's session file, the history of the chat
    .chat.edn           the name the person gave the chat, its task
    .shares.edn         the share links
    bin .pchat .pi      links to the shared ones above
```

The shared parts are read through the links, so `bin/new`, the
templates and the connections are the same in every chat, and a
connection added in the settings reaches them all; nothing of a chat is
written outside its folder. The agent is told that the folder is its
own and the rest is not its concern; a reference picture it gets is a
design to rebuild, not material to crop (`resources/pchat/prompts/system.md`).
The artifacts pane shows the chat's own folder only. Deleting a chat
deletes its folder.

The id of a chat is a UUID version 7 (`pchat.chats/new-id`, the time
first, so the folders sort by age): the name of its folder, the id of
its pi session, and the id in its URL. Every chat has a page,
`/chat/<id>`; `/` opens the newest chat and the browser puts the chat's
address in the bar. A click in the sidebar changes the address without a
reload and tells the server which chat this tab shows now (`:chat/show`
with the tab's id, see `pchat.client`); the back button works; a second
tab shows whatever its own URL names, and two tabs can show two chats.
The address of a chat that is gone falls back to the newest one with a
note. The files of a chat are served under its page,
`/chat/<id>/artifacts/...` and `/chat/<id>/uploads/...`; the agent names
them as it sees them (`/artifacts/card/music.mp3` in a hiccup block)
and the page rewrites them (`pchat.genui`).

Chats from before 0.2.0, when all of them shared the workspace folder,
are offered back by a banner at the top of the page: "Chats from before
the update found. Bring them back?" runs `bin/migrate-chats`, a
babashka script in the workspace that is idempotent and never deletes:
every old session file becomes a chat with the file moved into its
`.session/` (pi resumes it), the pictures of the session go into its
`uploads/`, the artifacts stamped for the chat in
`.pchat/artifact-owners.edn` move into its `artifacts/` and every
unstamped artifact into one chat named "Earlier artifacts"; names,
tasks and share links follow. The old folders stay, empty,
and `.pchat/migrated-at` marks the run; the banner says how many chats
came back. By hand: `bb bin/migrate-chats` in the workspace.

The page is in English or German: the language of the browser picks
(`Accept-Language`), `?lang=de` or `?lang=en` on the page URL overrides
it for that tab. The texts are [m1p](https://github.com/cjohansen/m1p)
dictionaries, `resources/pchat/i18n/<lang>.edn`, one file per language:
the page is hiccup with `[:i18n :chat/new]` where a text goes, and
`pchat.i18n/translate` folds the dictionary of the connection in before
a frame leaves the server. `bb test` checks that the dictionaries have
the same keys and that the source uses only keys they have. A third
language is one more file.

### Videos

`bin/render <name>` in the chat's folder turns an artifact project into
`artifacts/<name>.mp4`, which shows up beside the chat like any video.
A motion design renders through `fnmotion.render`: every frame as HTML,
a headless Chrome screenshots them one after the other at the `size` of
the design (1080 by 1920 for a story), ffmpeg encodes them and mixes the
`:audio` tracks of `design.edn` in, `{:src "music.mp3" :volume 0.6
:fade-out 1}`, with `:start`, `:duration`, `:media-start`, `:fade-in`
and `:loop?` as needed. The same tracks play in the preview, in step
with the scrubber. A second template, `bin/new <name> hyperframes`, is a
[HyperFrames](https://github.com/heygen-com/hyperframes) composition:
HTML with GSAP in `composition/index.html`, shown in the HyperFrames
player, rendered by `hyperframes render`, audio declared on `<audio>`
elements with `data-start`, `data-duration`, `data-volume`.

Needed on the machine: a Chrome (`google-chrome` or `chromium` on the
PATH, or `CHROME`), ffmpeg, and for the HyperFrames template `npm
install -g hyperframes @hyperframes/player`. pchat says at start what it
found; `bb deploy` installs all three on a sprite. "Make the video" in
the artifacts bar renders the design on screen; a few Chromes work side
by side (`fnmotion.render`, `:workers`).

### Tasks: skills from agentitos

`PCHAT_AGENTITOS` points at an [agentitos](https://github.com/storrito/agentitos)
checkout, a folder of skills ([Agent Skills](https://agentskills.io/specification)
format, a `SKILL.md` each). A new chat starts with the person picking
what it is for: a card per skill, titled and described by the `metadata`
of its `SKILL.md`, and a free chat. The pick stays with the chat
(`.pchat/tasks.edn`) and shapes it:

- **Onboarding:** the chat opens with the skill's `ask` (what to bring)
  and a button for the file when the skill takes `inputs` (`video`,
  `audio`, `image`); the composer shows the skill's `placeholder`. Each
  text has a `-de` variant for German.
- **The agent:** pi gets every skill with `--skill` (names and
  descriptions in its prompt, read on demand), and the chat's own skill
  in full in the system prompt as its task. It uses the others only when
  they serve the task and points to a new chat for anything else. A free
  chat follows whichever skill matches the wish.
- **The contract** (`pchat.skills/prompt`): a skill's HyperFrames work
  goes into an artifact from `bin/new <name> hyperframes`, files into
  `artifacts/`, `bin/render <name>` makes the video, attached clips are
  under `uploads/`, and skill folders are never changed.

A link can make the pick, for a button elsewhere (on Storrito, "a short
from a clip"): `/task/<skill>` opens a chat for that skill without the
picker, `/task/free` a free one. With the token it is
`/task/<skill>?token=…`; the path survives the redirect that sets the
cookie, a query would not. The newest chat is taken while nothing has
happened in it, so the same link twice gives one chat; an unknown skill
shows the picker.

Without `PCHAT_AGENTITOS` there is no picker: every chat is free.

Kinetic shorts from podcast clips are the `kinetic-lyrics` skill: the
person attaches a clip (video or sound, up to 1 GB), the agent makes the
artifact with `bin/new <name> hyperframes`, runs the skill's
`tools/short.mjs` step by step (words, who speaks when, its own staging
as the director, build, QA) and renders with `bin/render`. The skill's
checkout needs its `npm install`; the words and who speaks them come
through OpenRouter with the `OPENROUTER_API_KEY` pchat has, so nothing
else is installed for them (without a key the skill falls back to
Parakeet and sherpa-onnx, see its README).
The skill's world is drawn on the CPU, so a render needs no graphics card
(a 30-second short takes a few minutes on 8 cores).

### Music and the agent's own UI

`bin/music <name> "<what it should sound like>" [file]` makes a
30-second piece with Google's Lyria through OpenRouter (about 4 cents,
`--long` a whole song for 8) into the artifact's folder; the agent
declares it as a track of the design. The agent shows choices as a part
of the page: a ```hiccup block in an answer holds EDN hiccup, rendered
between the markdown (`pchat.genui`), the way json-render does with
JSON. Only the tags and attributes on the allow-lists get through, a
file or a link points at this host or at https, `:send "text"` on a
button sends that text as the person's next message, `:open` shows an
artifact. A piece of music with a player and a button each is the
typical block.

### Pictures and a voice

`bin/image "<what to show>" <file>` makes a picture with an image model
through OpenRouter (about 7 cents), `--ratio 9:16` for a story, `--from
<picture>` to start from a photo; `bin/voice <name> "<the words>"`
speaks them with an ElevenLabs voice (a tenth of a cent a sentence),
as a track of the design like music. `bin/ask "<question>" [text]` is
one answer of the model for a small judgment inside a script.

### Jobs

A job is a chat that runs by itself. The person assembles the template,
the material and the instructions in a chat, then picks "Turn into a
job" from the ⋮ menu of the chat's row (or asks the agent, which runs
`bin/job create <name>`). That makes the job without the agent: the
chat's folder is copied as the job's template (artifacts, uploads,
notes; not the history), and a copy of the chat becomes the job's
conversation, the chat on the job's page `/job/<id>`, beside the job.
The agent there, with the whole history, writes the job's prompt
(`bin/job prompt <id>`: what to do each time, which template, what to
publish where, what to leave alone), says what it wrote, and asks when
the job should run; the person comes back to that conversation to
change the job later. A job lives under `jobs/<id>/` in the workspace:
`template/`, `JOB.md`, `job.edn` (name, state, schedule and its words,
the time zone, the token of the endpoint, the caps, the failures, the
conversation's chat id). The conversation's folder under `chats/` links
`template/` to the job's template and is kept out of the sidebar.

Every run is a chat of its own, made from the template, with the
prompt and a section "What triggered this run" (the schedule's time, or
the body of the call as text, marked as information) as its first
message; it ends when the agent hands over, and the person reads or
continues it like any chat. One run at a time per job: a trigger during
a run waits (one at most, the newest), a schedule tick during a run is
skipped. Each run may spend $1 and take 30 minutes (`:caps` in
`job.edn`); past that it is stopped. Three failed runs in a row pause
the job; Resume on its page starts it again. A run whose agent is not
there two minutes after its start (pchat restarted) is failed.

Three triggers. "Run now" on the page. A schedule: the person writes
when, in their words ("every weekday at 9", "montags um 8"), the model
turns it into a cron line (`pchat.schedule`, one completion), the page
shows the line back as words with the next three runs, and Save keeps
it; a cron line typed in is taken as it is; what cron cannot say (every
other week) comes back as a sentence. Every job has a time zone, the
browser's when it is made, chosen on the page or with `bin/job tz`;
the line is read on that zone's wall clock like cron reads it, and the
agent sets it with `bin/job schedule <id> "0 9 * * 1-5" Europe/Berlin`.
The endpoint, `POST
/jobs/<id>/run?token=<token>` (or the token in `X-Job-Token` or as a
bearer), outside the page's token, to paste into any service that can
call an address, for example Composio's trigger setup for a new file
in a folder or a message in a channel; the body of the call reaches the
agent as text (a megabyte read, twenty thousand characters kept); the
answer is `{"run": "<chat-id>"}` or `{"queued": true}`.

pchat keeps no clock: the machines it runs on sleep between requests.
The owner of the machine fires the schedule by calling the endpoint
at the time; `bin/job schedule` tells the agent to register the
schedule, with its time zone, with the owner's service (it searches
the connected services for a tool that registers a schedule for a
URL). A machine that stays awake can do the same with its own cron
and `curl`. `bin/job
show|list|run|pause|resume|rename|delete` are the rest (`pchat.jobs`,
`pchat.jobs-view`).

### Sharing

Share in the artifacts bar makes a link anyone can open,
`/share/<id>`, for one file as it is at the time: the person pastes it
into a message, the other side sees the video. Stop sharing ends it.
Projects are not shared, only files (`pchat.share`).

### Connections

Settings, at the bottom of the sidebar, opens the settings in place of
the chat (the chat stays the current one, Back returns to it); its
Connections tab lists the MCP servers the agent can use: a service's
tools over the Model Context Protocol, a calendar, a file store, a
database. A row per server with its name, address or program, the
names of its secrets (never their values), how many tools the last
test found or what went wrong; Test, Turn off, Remove. "Add a
connection" takes a name, an address (`https://…/mcp`) or a program to
run, an access token (`Authorization: Bearer …` as a header,
`NAME=value` for a program's environment), and whether the agent sees
its tools when it needs them (the default, loaded through pi's
`tool_search`) or always. "Let the agent set it up" sends the current
chat (a fresh one when there is none) a message with the name of the
service and shows the chat: the agent reads the service's documentation, asks for a token
when one is needed, and sets it up with `bin/mcp add`, `bin/mcp test`,
`bin/mcp list` and `bin/mcp remove`, the script that edits the same
file through `.pchat/lib/pchat/mcp.clj`. The file is pi's
`.pi/mcp.json` in the workspace (mode 600), linked from every chat's
folder, the results of the tests `.pchat/connections.edn`. pi reads
the file when a chat's process
starts, so after a change the idle processes end and the working ones
when they settle; the next message starts each again on its session
with the new tools (`pchat.mcp`).

### Housekeeping

A chat's name is a field in the header; Delete asks once and deletes
the chat's folder. The pi process of a chat that idles for fifteen
minutes is ended and starts again on the next message, on the same
session; an artifact project runs while it is on screen in a tab
someone looks at or changed in the last ten minutes, and is stopped
after fifteen idle minutes. The artifacts folders of the chats on
screen, working or rendering are watched twice a second.

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
pushes the jar and restarts the service, about twenty seconds. Every
deploy also brings the skills: the last commit of the local
`PCHAT_AGENTITOS` checkout goes to `/home/sprite/agentitos` (`git
archive`, so the sprite needs no access to GitHub; uncommitted changes
stay behind, the deploy says so), `npm ci` runs in a skill only when its
`package-lock.json` changed, and the sprite's `.env` gets
`PCHAT_AGENTITOS=/home/sprite/agentitos`. `deploy/start.sh` stays as it
is, identical with the start script Storrito writes; sprites Storrito
sets up get their skills from Storrito. The
workspace lives in `/home/sprite/pchat/workspace`, the log in
`/.sprite/logs/services/pchat.log`.

Releases: pushing a tag `v<version>` builds the jar on GitHub Actions
and publishes it as a release (`.github/workflows/release.yml`).
Machines that set themselves up download a pinned version from
`https://github.com/storrito/pchat/releases/download/v<version>/pchat.jar`,
or the newest from `.../releases/latest/download/pchat.jar`; a
`pchat.jar.sha256` sits next to it.

Updates: a running pchat looks the newest release up once a day and
offers it as a banner; the person's click downloads the jar, checks it
with `--version`, waits until no chat works and no video renders, puts
it in place and ends the process with exit code 75 (`pchat.update`).
Whatever supervises the process starts it again with the new jar: a
sprite's runtime restarts a service whose process exited by itself, in
under a second. The banner appears only when `PCHAT_JAR` is set, which
`deploy/start.sh` does: a pchat started any other way is never offered
an update, since nothing would start it again.

## Live development

pchat starts an nREPL server by default, bound to 127.0.0.1 on a free
port written to `.nrepl-port` in the workspace (`PCHAT_NREPL_PORT` pins
it, `PCHAT_NREPL=off` leaves it out). On a sprite nothing outside
reaches it; `sprite proxy <port>` tunnels to it through the authenticated
API, and a changed namespace evaluated there shows on the page through
its frames, no restart. Stylesheets and other resources come from the
jar, so those take a `bb deploy <sprite>`.

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

Languages, 2026-10-10: English and German with m1p, chosen per
connection from the browser or `?lang=`, see "Run it".

Videos, 2026-10-11: `bin/render <name>` makes `artifacts/<name>.mp4`
from an artifact project, see "Videos". fnmotion got `fnmotion.render`
(Chrome over the DevTools protocol, ffmpeg, the audio mix) and the
server-side player a stage of the video's size and audio tracks in step
with the time; pchat got the HyperFrames template, `pchat.tools` for the
programs on the machine, and the installs in `bb deploy`.

Loose ends closed, 2026-10-11: render workers, "Make the video" in
the bar, fades in the preview, music through OpenRouter, hiccup blocks
in answers, rename and delete, idle reapers for pi and the projects,
the composer one line under the creation on a phone, `<html lang>` and
the language of a rejection follow the page, EN/DE links in the
sidebar, tests on GitHub Actions.

Automations and the rest, 2026-10-11: a webhook per chat ran the
script the agent wrote, its runs under the chat (`pchat.hooks`,
removed 2026-10-15: a job does what it did, with the model in the loop;
`bin/ask` stays); `bin/image` and `bin/voice` beside `bin/music`; Stop
while a video renders; Share for a file; `bb smoke` runs the agent
once for real and checks the video that came out.

Chats in folders of their own, 0.2.0, 2026-10-14: one folder per chat
under `workspace/chats/`, pi's working directory with the chat's
artifacts, uploads, temporary files and session; shared scripts,
templates and connections through links; URL per chat, `/chat/<id>`,
the chat a tab shows kept by the tab; UUID version 7 ids;
`bin/migrate-chats` and a banner for the chats from before, see "The
chats". The REPL experiment of 0.1.13 is gone: the isolated folder is
what it was testing for.

Next: the first customer automation as a chat.
