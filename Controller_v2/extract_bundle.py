import re, json, base64, gzip, os, sys

SRC = r"D:\Pilot\Controller_v2\Controller_v2\app\src\main\assets\index.html"
OUT = r"D:\Pilot\Controller_v2\Controller_v2\_unbundled"
os.makedirs(OUT, exist_ok=True)

html = open(SRC, "r", encoding="utf-8").read()
print("index.html size:", len(html))

def grab(tag):
    m = re.search(r'<script type="__bundler/%s">(.*?)</script>' % re.escape(tag), html, re.S)
    return m.group(1) if m else None

manifest_raw = grab("manifest")
template_raw = grab("template")
print("manifest found:", manifest_raw is not None, "len", len(manifest_raw or ""))
print("template found:", template_raw is not None, "len", len(template_raw or ""))

if template_raw:
    try:
        template = json.loads(template_raw)
        if isinstance(template, str):
            open(os.path.join(OUT, "template.html"), "w", encoding="utf-8").write(template)
            print("template is a string -> wrote template.html, len", len(template))
        else:
            open(os.path.join(OUT, "template.json"), "w", encoding="utf-8").write(json.dumps(template, indent=2)[:2000])
            print("template is", type(template))
    except Exception as e:
        print("template parse err:", e)
        open(os.path.join(OUT, "template.raw"), "w", encoding="utf-8").write(template_raw)

if manifest_raw:
    manifest = json.loads(manifest_raw)
    print("manifest entries:", len(manifest))
    for uuid, entry in manifest.items():
        data = entry.get("data", "")
        comp = entry.get("compressed")
        name = entry.get("name") or entry.get("path") or uuid
        mime = entry.get("mime") or entry.get("type") or ""
        try:
            raw = base64.b64decode(data)
            if comp:
                raw = gzip.decompress(raw)
        except Exception as e:
            print("  decode err", uuid, e); continue
        safe = re.sub(r'[^A-Za-z0-9._-]', '_', str(name))[:80]
        path = os.path.join(OUT, "%s__%s" % (uuid[:8], safe))
        open(path, "wb").write(raw)
        print("  asset", uuid[:8], "mime=", mime, "name=", name, "bytes=", len(raw))
