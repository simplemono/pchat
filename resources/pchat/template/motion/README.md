# This artifact

A motion design as a small babashka app of its own. pchat starts it and
shows its page beside the chat; the person sees every change at once.

- `src/artifact/main.clj`: `duration` in seconds, `design-view` (the frame
  at time `t`, a pure function of the design and the time, hiccup with
  inline styles, a fragment is a seq, never a vector of vectors, no
  functions in it), `controls` (sliders, color pickers, text fields over
  the design with `(player/setter [:key])`), the page.
- `design.edn`: the data of the design, what changes per use. It is
  written on every change and read at start, so it survives restarts.
- fnmotion is at hand as `fm` (interpolate, progress, lerp, spring,
  easings such as cubic-out, px, pct), `tl` (scene, still, sequence,
  parallel, at) and `captions` (captions, at, word-at over words with
  :start :end :text in seconds).
- Save a file and the running process loads it. Or evaluate into it:
  `bin/eval <name> '(defn design-view [design t] ...)'` from the
  workspace. What you evaluate is gone after a restart; write it into
  the file when it is right.
- `.log` has the output of the process, errors included.
