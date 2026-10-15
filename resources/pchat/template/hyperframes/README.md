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
- The variables are the controls beside the player: one per declaration,
  `{"id": "title", "type": "string", "label": "Title", "default": "Hello"}`,
  types `string`, `number` (`min`, `max`, `step`), `color` and `boolean`,
  `options` for a choice (`["calm", {"value": "loud", "label": "Loud"}]`).
  What the person sets goes into `variables.json` here; the composition
  gets it as `window.__hfVariables`, the way the render does, so read the
  variables when the composition loads. Write `variables.json` yourself
  to set values; the controls follow.
- Music or a voice: put the file into `composition/` and declare it,
  `<audio id="music" src="music.mp3" data-start="0" data-duration="6"
  data-volume="0.6" data-fade-out="1">`, also `data-media-start` and
  `data-fade-in`. It plays in the preview and is mixed into the video.
  Images go into `composition/` as well.
- The video: `bin/render <name>` from the workspace, `artifacts/<name>.mp4`
  by `hyperframes render`, with the values of `variables.json`.
- Several compositions, side by side: without `composition/index.html`,
  every folder `composition/<name>/` with its own `index.html` (a whole
  HyperFrames project: its `compositions/`, `assets/`) is shown in a
  player of its own, titled by its `<title>`, without controls. Each
  renders on its own, `hyperframes render` in its folder; `bin/render`
  takes only the single composition.
- `hyperframes lint` in `composition/` finds mistakes in seconds;
  `hyperframes check` plays the whole composition and takes minutes, so
  leave it to the render.
- `src/artifact/main.clj` is the small server that shows the composition
  and the controls, a page of rframes; it needs no change. Other controls:
  `src/artifact/controls.clj`, namespace `artifact.controls`, with
  `controls` (a function of the declarations and the values to hiccup;
  `[[:data/command {:command/kind :variables/set :command/data {:id "title"
  :value :event/target.value}}]]` sets a value) and `register`, more
  rframes entries such as commands of its own.
- `.log` has the output of the process, errors included.
