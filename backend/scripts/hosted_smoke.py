"""Bounded offline account, agent-key and posted-diff route regression fixture."""
import json, os, pathlib, socket, subprocess, tempfile, time, urllib.request, urllib.error
binary=str(pathlib.Path(__file__).resolve().parents[1] / 'target/debug/pulls-review-server')
with tempfile.TemporaryDirectory(prefix='pulls-hosted-') as tmp:
 with socket.socket() as sock: sock.bind(('127.0.0.1',0)); port=sock.getsockname()[1]
 env={k:v for k,v in os.environ.items() if not any(x in k.upper() for x in ('TOKEN','KEY','SECRET','PASSWORD','PULLS_REVIEW','PULLY_'))}
 env.update(PULLY_PUBLIC_URL='https://posted.example.invalid',PULLY_ACCOUNT_EMAIL='smoke@example.invalid',PULLY_ACCOUNT_PASSWORD='test-fixture-password',PULLS_REVIEW_ANALYTICS='off',GH_CONFIG_DIR=tmp+'/empty-gh',HOME=tmp)
 log=open(tmp+'/log','w+')
 proc=subprocess.Popen([binary,'--server','--data-dir',tmp+'/store','--port',str(port),'--no-warm','--no-open'],env=env,stdout=log,stderr=log)
 def request(path,body=None,headers=None,method=None):
  req=urllib.request.Request(f'http://127.0.0.1:{port}'+path,data=body.encode() if isinstance(body,str) else json.dumps(body).encode() if body is not None else None,headers={'Content-Type':'application/json',**(headers or {})},method=method)
  try:r=urllib.request.urlopen(req,timeout=8)
  except urllib.error.HTTPError as e:r=e
  raw=r.read()
  try:data=json.loads(raw)
  except ValueError:data=raw.decode()
  return r.status,data
 def rpc(name,args={},headers=None):return request('/api/rpc',{'name':name,'args':args},headers)
 try:
  for _ in range(80):
   try:
    if request('/health')[0]==200:break
   except OSError:time.sleep(.05)
  else:raise AssertionError('startup')
  assert rpc('account-status')[1]['result']['hasAccount']
  assert rpc('local-diff',{'target':''})[0]==404
  status,body=rpc('account-sign-in',{'email':'smoke@example.invalid','password':'test-fixture-password'});assert status==200,body
  headers={'X-Pully-Session':body['result']['session']}
  status,body=rpc('agent-key-create',{'name':'smoke'},headers);assert status==200,body
  key=body['result']['key'];key_id=body['result']['item']['id'];bearer={'Authorization':'Bearer '+key}
  assert request('/api/v1/me',headers=bearer)[0]==200
  patch='diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-old\n+new\n'
  status,body=request('/api/v1/diffs',{'patch':patch,'title':'Smoke'},bearer);assert status==201,(status,body)
  id=body['id'];assert body['url']=='https://posted.example.invalid/-/'+id;assert rpc('posted-diff',{'id':id})[0]==401
  status,body=rpc('posted-diff',{'id':id},headers);assert status==200,(status,body)
  assert body['result']['diff']['ref']['kind']=='posted'
  sha=body['result']['diff']['files'][0]['sha']
  assert rpc('reviewed-set',{'key':'posted:'+id,'shas':[sha],'reviewed':True},headers)[1]['result']
  assert sha in rpc('reviewed-get',{'key':'posted:'+id},headers)[1]['result']['reviewed']
  assert request('/api/v1/diffs/'+id,{'patch':patch.replace('+new','+updated')},bearer,method='PUT')[0]==200
  assert request('/api/v1/diffs/'+id,headers=bearer)[0]==200
  status,raw=request('/api/v1/diffs',patch,{**bearer,'Content-Type':'text/x-diff'})
  assert status==201,(status,raw)
  assert request('/api/v1/diffs/'+raw['id'],headers=bearer,method='DELETE')[0]==204
  assert request('/-/'+id)[0]==200
  for path in ['/api/v1/me','/api%2Fv1/me','/API/V1/me','//api/v1/me']:
   assert request(path)[0]==401,path
  assert rpc('agent-key-revoke',{'id':key_id},headers)[0]==200
  assert request('/api/v1/me',headers=bearer)[0]==401
  assert rpc('posted-delete',{'id':id},headers)[1]['result']
  assert rpc('posted-list',{},headers)[1]['result']==[]
  print('Hosted offline smoke passed: seeded account, no-checkout, key lifecycle, posted CRUD, session/encoded-route gates, private phone link.')
 finally:
  proc.terminate()
  try:proc.wait(timeout=5)
  except subprocess.TimeoutExpired:proc.kill();proc.wait()
