# The in-app tour

`app/src/main/res/raw/intro.mp4` — played on the first open and from Settings › Tour.

Made on the emulator from made-up data, the same Seattle demo as the store screenshots:

1. `make_tour_day.py` writes `today.sql`: a day up to now (home, office, lunch, a coffee shop you are still at).
   Load it with `../make_demo_day.py`'s `demo.sql` into a fresh install's `life.db`.
2. Record each scene with `screenrecord`; `record_helpers.sh` has the clean status bar, tap-by-text and the
   money reset used for the live purchase (a `cmd notification post` from the shell, answered "Count").
3. `cut.py` trims, speeds up and captions the scenes into `intro.mp4` (720×1750, about 80 s),
   with `title.html` / `end.html` rendered to PNG as the first and last cards.
