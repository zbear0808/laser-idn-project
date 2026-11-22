#!/bin/sh
# Render every mockup HTML page in this folder (or the ones named) to a PNG
# beside it, with headless Chrome. A page's size comes from its .page box:
# 1440×900 by default, .tall 1440×1100, .small 1100×760.
#   sh render.sh                 # all pages
#   sh render.sh a1-perform.html # one page
set -e
cd "$(dirname "$0")"
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
pages="$*"
[ -z "$pages" ] && pages=$(ls *.html)
for f in $pages; do
  size="1440,900"
  grep -q 'class="page tall' "$f" && size="1440,1100"
  grep -q 'class="page small' "$f" && size="1100,760"
  out="${f%.html}.png"
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
    --window-size="$size" --screenshot="$PWD/$out" "file://$PWD/$f" >/dev/null 2>&1
  echo "rendered $out ($size)"
done
