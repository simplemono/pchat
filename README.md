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

## Status

Nothing runs yet. Milestone 1: a babashka process that drives pi over
rpc and shows one chat with rframes.
