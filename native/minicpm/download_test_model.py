#!/usr/bin/env python3
"""Download pinned public test weights. Never ships weights in the APK/repository."""
import concurrent.futures, hashlib, json, pathlib, sys, urllib.request, time
catalog=json.load(open(sys.argv[1])); dest=pathlib.Path(sys.argv[2]);dest.mkdir(parents=True,exist_ok=True)
def fetch(f):
 p=dest/f['path'];p.parent.mkdir(parents=True,exist_ok=True)
 def valid():
  if not p.exists() or p.stat().st_size!=f['sizeBytes']:return False
  h=hashlib.sha256()
  with p.open('rb') as r:
   for b in iter(lambda:r.read(4*1024*1024),b''):h.update(b)
  return h.hexdigest()==f['sha256']
 if valid():return f['path']+' existing verified'
 url=f"https://huggingface.co/{catalog['repoId']}/resolve/{catalog['revision']}/{catalog['remoteDirectory']}/{f['path']}?download=true"
 tmp=p.with_name(p.name+'.part')
 for attempt in range(3):
  try:
   h=hashlib.sha256();count=0
   with urllib.request.urlopen(url,timeout=180) as r,tmp.open('wb') as w:
    while True:
     b=r.read(1024*1024)
     if not b:break
     w.write(b);h.update(b);count+=len(b)
   if count!=f['sizeBytes'] or h.hexdigest()!=f['sha256']:raise RuntimeError('integrity mismatch '+f['path'])
   tmp.replace(p);return f['path']+' verified'
  except Exception:
   if attempt==2:raise
   time.sleep(2)
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
 for r in pool.map(fetch,catalog['files']):print(r,flush=True)
print('ALL VERIFIED',flush=True)
