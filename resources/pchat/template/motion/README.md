# This artifact

A motion design as a small babashka app of its own. pchat starts it and
shows its page beside the chat; the person sees every change at once.

- `src/artifact/main.clj`: `size` (width and height of the video in
  pixels, 1080 by 1920 for a story), `duration` in seconds, `design-view`
  (the frame at time `t`, a pure function of the design and the time,
  hiccup with inline styles that fills the stage, a fragment is a seq,
  never a vector of vectors, no functions in it), `controls` (sliders,
  color pickers, text fields over the design with `(player/setter
  [:key])`), the page, `render!`.
- `design.edn`: the data of the design, what changes per use. It is
  written on every change and read at start, so it survives restarts.
- Music or a voice: put the audio file into this directory and add it
  to `:audio` in `design.edn`: `{:src "music.mp3" :volume 0.6 :fade-out
  1}`, with `:start` (seconds into the video, 0), `:duration` (to the
  end), `:media-start` (where in the file it begins), `:fade-in`, `:loop?
  true` as needed. It plays in the preview and is mixed into the video.
  An image a frame shows by its file name lives here as well.
- The video: `bin/render <name>` from the workspace, `artifacts/<name>.mp4`,
  thirty frames a second at `size`. Every frame is screenshot by a
  headless Chrome, ffmpeg encodes them. The preview plays the design
  live, so render only when the file itself is needed (the person asks
  for it, or it is posted somewhere), not after every change.
- fnmotion is at hand as `fm` (interpolate, progress, lerp, spring,
  easings such as cubic-out, px, pct), `tl` (scene, still, sequence,
  parallel, at) and `captions` (captions, at, word-at over words with
  :start :end :text in seconds).
- Save a file and the running process loads it. Or evaluate into it:
  `bin/eval <name> '(defn design-view [design t] ...)'` from the
  workspace. What you evaluate is gone after a restart; write it into
  the file when it is right, the render reads the file.
- `.log` has the output of the process, errors included.
