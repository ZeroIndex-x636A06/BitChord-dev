import json, sys, os, time, urllib.parse, urllib.request
songs = [l.rstrip("\n").split("|") for l in open(sys.argv[1]) if l.strip()]
out = sys.argv[2]; os.makedirs(out, exist_ok=True)
def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "BitChord lyric-check probe (github.com/ZeroIndex-x636A06/BitChord-dev)"})
    with urllib.request.urlopen(req, timeout=20) as r: return json.load(r)
for i, (_, title, artist) in enumerate(songs):
    text = None
    try:
        q = urllib.parse.urlencode({"track_name": title, "artist_name": artist})
        try:
            d = get("https://lrclib.net/api/get?" + q); text = d.get("plainLyrics")
        except Exception: pass
        if not text:
            for d in get("https://lrclib.net/api/search?" + q):
                if d.get("plainLyrics"): text = d["plainLyrics"]; break
    except Exception as e:
        print("fail", title, e)
    if text: open(f"{out}/{i}.txt", "w").write(text)
    time.sleep(0.3)
