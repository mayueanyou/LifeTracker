import json, math, random, urllib.request, datetime as dt
from zoneinfo import ZoneInfo
random.seed(7)
TZ = ZoneInfo("America/New_York")     # the emulator's own zone: clock times read as they will on screen
DAY = dt.date(2026, 11, 23)
def ms(d, hh, mm, ss=0): return int(dt.datetime(d.year, d.month, d.day, hh, mm, ss, tzinfo=TZ).timestamp() * 1000)
P = {
    "Home": (47.6253, -122.3141), "Office": (47.6226, -122.3368), "Sweetgreen": (47.6189, -122.3383),
    "QFC": (47.6232, -122.3209), "Volunteer Park": (47.6301, -122.3149), "Pike Street Coffee": (47.6140, -122.3268),
}
def metres(a, b):
    R = 6371000; p1, p2 = math.radians(a[0]), math.radians(b[0]); dl = math.radians(b[1] - a[1])
    h = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * R * math.asin(math.sqrt(h))
def route(a, b):
    url = f"https://router.project-osrm.org/route/v1/driving/{P[a][1]},{P[a][0]};{P[b][1]},{P[b][0]}?overview=full&geometries=geojson"
    g = json.load(urllib.request.urlopen(url, timeout=20))["routes"][0]["geometry"]["coordinates"]
    return [(lat, lon) for lon, lat in g]
fixes = []
def fix(t, ll, acc=6.0, speed=None):
    fixes.append((t, ll[0], ll[1], acc, speed))
def jitter(ll, m):
    return (ll[0] + random.uniform(-m, m) / 111000, ll[1] + random.uniform(-m, m) / 75000)
def stay(name, t0, t1, every_min=3):
    t = t0
    while t < t1:
        fix(t, jitter(P[name], 4), acc=random.choice([5.0, 6.0, 8.0])); t += every_min * 60000
def travel(a, b, t0, t1, step_s):
    pts = route(a, b); pts = [P[a]] + pts + [P[b]]
    segs = [metres(pts[i], pts[i + 1]) for i in range(len(pts) - 1)]; total = sum(segs)
    n = max(2, int((t1 - t0) / 1000 / step_s))
    for k in range(1, n):
        d = total * k / n; i = 0
        while i < len(segs) - 1 and d > segs[i]: d -= segs[i]; i += 1
        f = d / segs[i] if segs[i] else 0
        ll = (pts[i][0] + (pts[i + 1][0] - pts[i][0]) * f, pts[i][1] + (pts[i + 1][1] - pts[i][1]) * f)
        fix(t0 + int((t1 - t0) * k / n), jitter(ll, 2), acc=5.0, speed=total / ((t1 - t0) / 1000))
    return total
prev = DAY - dt.timedelta(days=1)
def go(a, b, start, kmh, step_s):
    """Travel from a to b leaving at start, at a realistic pace for the route's length; returns arrival."""
    pts = route(a, b); dist = sum(metres(pts[i], pts[i + 1]) for i in range(len(pts) - 1)) + metres(P[a], pts[0]) + metres(pts[-1], P[b])
    end = start + int(dist / (kmh / 3.6) * 1000)
    travel(a, b, start, end, step_s)
    print(f"  {a} -> {b}: {dist:.0f} m, {(end - start) / 60000:.1f} min")
    return end

NOW = int(dt.datetime.now(TZ).timestamp() * 1000)
t = ms(DAY, 8, 35); stay("Home", ms(prev, 23, 0), t, every_min=20)
t2 = go("Home", "Office", t, 28, 2); stay("Office", t2, ms(DAY, 12, 10))
t3 = go("Office", "Sweetgreen", ms(DAY, 12, 10), 5, 7); stay("Sweetgreen", t3, ms(DAY, 12, 55), every_min=2)
t4 = go("Sweetgreen", "Office", ms(DAY, 12, 55), 5, 7); stay("Office", t4, ms(DAY, 14, 35))
t5 = go("Office", "Pike Street Coffee", ms(DAY, 14, 35), 5, 7); stay("Pike Street Coffee", t5, NOW - 60000, every_min=2)
sql = ["BEGIN;"]
q = lambda s: "'" + str(s).replace("'", "''") + "'"
for t, la, lo, acc, sp in fixes:
    sql.append(f"INSERT INTO fix(time,latitude,longitude,accuracy,altitude,speed,bearing,provider) VALUES ({t},{la:.6f},{lo:.6f},{acc},NULL,{'NULL' if sp is None else round(sp,1)},NULL,'recorder');")
sql.append("INSERT INTO place(name,latitude,longitude,radius) VALUES ('Pike Street Coffee',47.6140,-122.3268,60.0);")
BANK, APP = "com.infonow.bofa", "BofA"
def pay(t, amount, merchant):
    text = f"${amount:,.2f} Credit card 1234 {merchant.upper()}"
    sql.append(f"INSERT INTO payment(time,pkg,app,title,text,amount,currency,merchant,kind,verdict) VALUES ({t},{q(BANK)},{q(APP)},'Credit card charge',{q(text)},{amount},'USD',{q(merchant)},'purchase',0);")
pay(t2 + 2 * 60000, 6.00, "PayByPhone Parking")
pay(ms(DAY, 12, 41), 14.60, "Sweetgreen")
sql.append("COMMIT;")
open("today.sql", "w").write("\n".join(sql))
print(len(fixes), "positions today until", dt.datetime.fromtimestamp(NOW/1000, TZ))
