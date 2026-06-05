import re, json, os, shutil, sys

SRC = r"D:\Pilot\Controller_v2\Controller_v2\app\src\main\assets\index.html"
BAK = SRC + ".bak"

html = open(SRC, "r", encoding="utf-8").read()
print("original index.html size:", len(html))

# Backup once.
if not os.path.exists(BAK):
    shutil.copyfile(SRC, BAK)
    print("backup written:", BAK)
else:
    print("backup already exists:", BAK)

# --- pull out the template script tag content (JSON-encoded HTML string) ---
m = re.search(r'(<script type="__bundler/template">)(.*?)(</script>)', html, re.S)
if not m:
    print("ERROR: template script tag not found"); sys.exit(1)

template_str = json.loads(m.group(2))   # decoded HTML string

OLD = """/* equalizer bars */
@keyframes eqb { 0%,100% { transform: scaleY(0.25); } 50% { transform: scaleY(1); } }
.eqbar { height: 18px !important; }"""

NEW = """/* equalizer bars — infinite scaleY animation DISABLED for kiosk WebView GPU
   stability. The continuous per-frame compositing of the music card was
   corrupting GPU tiles ("red dots") on the tablet. Static varied-height bars
   keep the visual with zero per-frame GPU churn. */
@keyframes eqb { 0%,100% { transform: scaleY(0.25); } 50% { transform: scaleY(1); } }
.eqbar { height: 18px !important; animation: none !important; transform: none !important; }
.eqbar:nth-child(1) { height: 9px  !important; }
.eqbar:nth-child(2) { height: 16px !important; }
.eqbar:nth-child(3) { height: 6px  !important; }
.eqbar:nth-child(4) { height: 13px !important; }
.eqbar:nth-child(5) { height: 11px !important; }"""

if OLD not in template_str:
    print("ERROR: equalizer CSS block not found in template (already patched?)")
    sys.exit(2)

template_str = template_str.replace(OLD, NEW)

# Re-encode as JSON, escaping </ so inner </script> can't break the outer tag
# (matches how the bundler originally encoded it).
encoded = json.dumps(template_str, ensure_ascii=False).replace("</", "<\\/")

new_html = html[:m.start(2)] + encoded + html[m.end(2):]
open(SRC, "w", encoding="utf-8").write(new_html)
print("patched index.html size:", len(new_html))

# sanity: re-parse to confirm the new template round-trips
chk = re.search(r'(<script type="__bundler/template">)(.*?)(</script>)', new_html, re.S)
parsed = json.loads(chk.group(2))
assert "animation: none !important" in parsed, "patch missing after round-trip"
assert parsed.count("</script>") == 0, "literal </script> leaked into JSON"
print("OK: round-trip verified, eqbar animation disabled")
