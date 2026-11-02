#!/usr/bin/env python3
"""Real-model contract smoke test; server must already be running locally."""
import json,sys,urllib.request,time
base=sys.argv[1] if len(sys.argv)>1 else 'http://127.0.0.1:18089'
def get(path):
 with urllib.request.urlopen(base+path,timeout=30) as r: return json.load(r)
def post(path,data):
 return urllib.request.urlopen(urllib.request.Request(base+path,json.dumps(data).encode(),{'Content-Type':'application/json'}),timeout=300)
assert get('/health')['status']=='ok'
p=get('/props'); print('capacity',p['default_generation_settings']['n_ctx'],'slots',p['total_slots'])
assert p['default_generation_settings']['n_ctx']>0
print('models',len(get('/v1/models')['data']))
with post('/tokenize',{'content':'Hello world','add_special':False}) as r: assert len(json.load(r)['tokens'])>0
body={'messages':[{'role':'user','content':'Reply with a short greeting.'}],'max_tokens':24,'temperature':0,'stream':True,'stream_options':{'include_usage':True},'cache_prompt':True}
for repeat in range(2):
 chunks=[]; done=False;start=time.monotonic()
 with post('/v1/chat/completions',body) as r:
  for line in r:
   if not line.startswith(b'data: '): continue
   data=line[6:].strip()
   if data==b'[DONE]': done=True;break
   chunks.append(json.loads(data))
 assert done,'SSE missing DONE'
 final=next(c for c in reversed(chunks) if c.get('usage'))
 u=final['usage']; assert u['prompt_tokens']>0 and u['completion_tokens']>0
 assert 0<=u['prompt_tokens_details']['cached_tokens']<=u['prompt_tokens']
 assert 'timings' in final
 print(json.dumps({'repeat':repeat,'elapsed':time.monotonic()-start,'usage':u,'timings':final['timings']}),flush=True)
# Disconnect while generating; a subsequent ordinary request must still complete.
body['max_tokens']=1000
r=post('/v1/chat/completions',body)
for line in r:
 if line.startswith(b'data: '):break
r.close();time.sleep(1)
body.update(stream=False,max_tokens=4)
with post('/v1/chat/completions',body) as r: assert json.load(r)['usage']['completion_tokens']>0
print('disconnect and subsequent request passed',flush=True)
