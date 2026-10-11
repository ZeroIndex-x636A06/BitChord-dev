#!/usr/bin/env bash
# Usage: probe.sh "<query>" [params]
set -u
q="$1"; p="${2:-}"
body=$(jq -n --arg q "$q" --arg p "$p" '{context:{client:{clientName:"WEB_REMIX",clientVersion:"1.20251001.01.00",hl:"en",gl:"US"}},query:$q} + (if $p=="" then {} else {params:$p} end)')
curl -sS -m 30 -X POST "https://music.youtube.com/youtubei/v1/search?prettyPrint=false" \
  -H "Content-Type: application/json" -H "Origin: https://music.youtube.com" -H "Referer: https://music.youtube.com/" \
  -H "X-YouTube-Client-Name: 67" -H "X-YouTube-Client-Version: 1.20251001.01.00" \
  -H "User-Agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36" \
  -d "$body" > "resp-$(echo "$q$p" | tr -c 'a-zA-Z0-9' '_').json"
