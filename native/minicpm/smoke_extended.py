#!/usr/bin/env python3
"""Destructive local test: server slots2/context8192/mmproj/key=test-only-key.
Set SERVER_PID to the owned test-server PID; sends SIGTERM after vision test.
Run in same process/network namespace as that server. Never point at production.
"""
import urllib.request,json,time,os,signal,concurrent.futures,base64,zlib,struct
url='http://127.0.0.1:18089'
def post(body):
 return urllib.request.urlopen(urllib.request.Request(url+'/v1/chat/completions',json.dumps(body).encode(),{'Content-Type':'application/json','Authorization':'Bearer test-only-key'}),timeout=180)
def get(path,key=True):
 return urllib.request.urlopen(urllib.request.Request(url+path,headers={'Authorization':'Bearer test-only-key'} if key else {}),timeout=30)
p=json.load(get('/props'));assert p['total_slots']==2 and p['default_generation_settings']['n_ctx']==4096;print('props2',p['modalities'],flush=True)
try:get('/slots',False);raise AssertionError('missing key accepted')
except urllib.error.HTTPError as e:assert e.code==401
# Tiny lossless solid-red image generated without external dependencies.
def chunk(t,d):return struct.pack('>I',len(d))+t+d+struct.pack('>I',zlib.crc32(t+d)&0xffffffff)
w=h=64
png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,2,0,0,0))+chunk(b'IDAT',zlib.compress((b'\0'+b'\xff\0\0'*w)*h))+chunk(b'IEND',b'')
body={'messages':[{'role':'user','content':[{'type':'text','text':'What color is the image? Answer briefly.'},{'type':'image_url','image_url':{'url':'data:image/png;base64,'+base64.b64encode(png).decode()}}]}],'max_tokens':20,'temperature':0}
with post(body) as r:
 a=json.load(r);print('vision',json.dumps(a),flush=True);assert a['usage']['completion_tokens']>0
# Hold two active streams plus queued third request, then terminate server.
body={'messages':[{'role':'user','content':'Count integers from 1 to 500 without stopping.'}],'max_tokens':1000,'stream':True,'ignore_eos':True}
def drain():
 try:
  with post(body) as r:
   for l in r:pass
 except Exception:pass
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
 fs=[pool.submit(drain) for _ in range(3)];time.sleep(2);os.kill(int(os.environ['SERVER_PID']),signal.SIGTERM)
 for f in fs:f.result(timeout=15)
print('authenticated vision, two slots, queued+active shutdown passed',flush=True)
