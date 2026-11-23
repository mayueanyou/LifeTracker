import subprocess, imageio_ffmpeg
F = imageio_ffmpeg.get_ffmpeg_exe()
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
W, H, BAR = 720, 1600, 150
# (source, start, end, speed, line1, line2) -- a .png source is a still shown for (end - start) seconds
SEG = [
 ("title.png", 0, 2.6, 1, None, None),
 ("s1.mp4", 0, 4, 1.3, "Your day as a map and a timeline", "Every place you stayed, every trip between"),
 ("s1.mp4", 5, 10, 1.5, "Tap a stay or a trip", "to see it on the map"),
 ("s1.mp4", 10, 20, 4, "Tap it again", "for its details and costs"),
 ("s1.mp4", 20, 24, 1.5, "Tap it again", "for its details and costs"),
 ("s1.mp4", 25, 30, 1.2, "Purchases come from your bank's", "notifications — placed where you paid"),
 ("s2.mp4", 1, 10, 1.6, "A month of spending at a glance", "Tap a day to open it"),
 ("s3.mp4", 0, 5, 1.6, "Turning money on", "is one step of the setup"),
 ("s3.mp4", 6, 11, 1.4, "First, what Life Tracker reads", "— and that it ignores everything else"),
 ("s3.mp4", 12, 23, 2.2, "Then Android's own switch:", "turn on Life Tracker, then come back"),
 ("s3.mp4", 23, 28, 2, "Done. Last, back up to your own", "Google Drive — or keep it on the phone"),
 ("s4.mp4", 8, 12, 1.2, "A card purchase notification arrives", ""),
 ("s4.mp4", 12, 20, 3, "Your bank's app counts by itself;", "any other app is asked about once"),
 ("s4.mp4", 20, 25, 1.2, "Your bank's app counts by itself;", "any other app is asked about once"),
 ("s4.mp4", 25, 53, 20, "Your bank's app counts by itself;", "any other app is asked about once"),
 ("s4.mp4", 53, 58, 1.2, "It lands on the place where you paid", "$5.75 at Pike Street Coffee"),
 ("s4.mp4", 58, 64, 3, "With the bank's own words,", "so you always know what it was"),
 ("s4.mp4", 64, 70, 1.3, "With the bank's own words,", "so you always know what it was"),
 ("s5.mp4", 0, 6, 1.4, "Summary: your time, added up", "every visit to each place, every trip"),
 ("s6.png", 0, 3.6, 1, "Screen time per app —", "and during each place you stayed"),
 ("s7.mp4", 0, 4.2, 1.2, "Everything else is in Settings —", "including this tour, any time"),
 ("end.png", 0, 3.2, 1, None, None),
]
def esc(t): return t.replace("\\", "\\\\").replace(":", "\\:").replace("'", "’").replace("%", "\\%")
inputs, parts, labels = [], [], []
for i, (src, a, b, sp, l1, l2) in enumerate(SEG):
    if src.endswith(".png"):
        inputs += ["-loop", "1", "-t", str(b - a), "-i", src]
        f = f"[{i}:v]fps=30,"
    else:
        inputs += ["-i", src]
        f = f"[{i}:v]trim={a}:{b},setpts=(PTS-STARTPTS)/{sp},fps=30,"
    if l1 is None:          # a full-screen card
        f += f"scale={W}:{H + BAR}"
    else:
        f += f"scale={W}:{H},pad={W}:{H + BAR}:0:{BAR}:color=0x312E81"
        for j, t in enumerate([l1, l2]):
            if t:
                y = 30 + j * 48 if l2 else 56
                f += f",drawtext=fontfile={FONT}:text='{esc(t)}':fontsize=29:fontcolor=white:x=(w-text_w)/2:y={y}"
    f += f",setsar=1,format=yuv420p[v{i}]"
    parts.append(f); labels.append(f"[v{i}]")
fc = ";".join(parts) + ";" + "".join(labels) + f"concat=n={len(SEG)}:v=1:a=0[out]"
out = "intro.mp4"
subprocess.run([F, "-y", "-loglevel", "error", *inputs, "-filter_complex", fc, "-map", "[out]",
  "-c:v", "libx264", "-profile:v", "main", "-pix_fmt", "yuv420p", "-crf", "27", "-preset", "slow", "-movflags", "+faststart", out], check=True)
print(round(sum((b - a) / sp for _, a, b, sp, *_ in SEG), 1), "s")
