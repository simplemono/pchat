# This artifact

A HyperFrames composition: HTML with GSAP, the way the HyperFrames skills
describe it. pchat shows it in the HyperFrames player beside the chat and
loads it again whenever a file of it changes.

- `composition/index.html`: the design. The root carries
  `data-composition-id`, `data-duration` (seconds), `data-width` and
  `data-height` (1080 by 1920 for a story); timed elements are
  `class="clip"` with `data-start` and `data-duration`; one paused GSAP
  timeline registered as `window.__timelines["main"]`. What changes per
  use is a variable: declared on `<html data-composition-variables>`, read
  with `window.__hyperframes.getVariables()`.
- Music or a voice: put the file into `composition/` and declare it,
  `<audio id="music" src="music.mp3" data-start="0" data-duration="6"
  data-volume="0.6" data-fade-out="1">`, also `data-media-start` and
  `data-fade-in`. It plays in the preview and is mixed into the video.
  Images go into `composition/` as well.
- The video: `bin/render <name>` from the workspace, `artifacts/<name>.mp4`
  by `hyperframes render`. A `variables.json` in this directory gives the
  variables their values for that render.
- `hyperframes lint` in `composition/` finds mistakes in seconds;
  `hyperframes check` plays the whole composition and takes minutes, so
  leave it to the render.
- `src/artifact/main.clj` is the small server that shows the composition;
  it needs no change.
- `.log` has the output of the process, errors included.
