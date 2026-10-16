#!/usr/bin/env python3
"""Bounded offline smoke; creates only a temporary Git repository/cache."""
import http.cookiejar, json, os, pathlib, re, subprocess, sys, tempfile, time, urllib.error, urllib.request
binary = str(pathlib.Path(sys.argv[1] if len(sys.argv)>1 else 'backend/target/debug/pulls-review-server').resolve())
with tempfile.TemporaryDirectory(prefix='pulls-rust-smoke-') as tmp:
    root=pathlib.Path(tmp); repo=root/'repo'; repo.mkdir()
    def git(*args): return subprocess.check_output(['git','-C',str(repo),*args],stderr=subprocess.DEVNULL)
    git('init'); (repo/'hello.txt').write_text('old\n'); git('add','.'); git('-c','user.name=Smoke','-c','user.email=smoke@example.invalid','commit','-m','fixture'); (repo/'hello.txt').write_text('new\n')
    port=0
    assets=root/'assets'; assets.mkdir(); (assets/'index.html').write_text('<!doctype html><title>Smoke UI</title>')
    log=open(root/'server.log','w+')
    # Do not inherit provider or GitHub credentials into the fixture process.
    env={k:v for k,v in os.environ.items() if not any(x in k.upper() for x in ('TOKEN','KEY','SECRET','PASSWORD','PULLS_REVIEW'))}
    env['PULLS_REVIEW_ANALYTICS']='off'
    env['GH_CONFIG_DIR']=str(repo/'empty-gh-config')
    env['HOME']=str(repo/'empty-home')
    server=subprocess.Popen([binary,'--port',str(port),'--cwd',str(repo),'--data-dir',str(repo/'runtime-cache'),'--no-warm','--no-open','--assets',str(assets)],stdout=log,stderr=log,env=env)
    opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def request(path,body=None,headers=None):
        req=urllib.request.Request(f'http://127.0.0.1:{port}'+path,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json',**(headers or {})})
        try:
            with opener.open(req,timeout=5) as r:return r.status,json.load(r)
        except urllib.error.HTTPError as e:return e.code,json.load(e)
    def rpc(name,args):
        status,body=request('/api/rpc',{'name':name,'args':args}); assert status==200,(name,status,body);return body['result']
    try:
        for _ in range(60):
            try:
                bound=re.search(r'Rust backend: http://127\.0\.0\.1:(\d+)',(root/'server.log').read_text())
                if bound:port=int(bound.group(1))
                if port and request('/health')[0]==200:break
                time.sleep(.05)
            except OSError:time.sleep(.05)
        else:raise AssertionError('Server did not become healthy')
        assert request('/health',headers={'Origin':f'http://127.0.0.1:{port}'})[0]==200
        assert request('/api/backend')[1]['backend']=='rust'
        with opener.open(urllib.request.Request(f'http://127.0.0.1:{port}/',headers={'Accept':'text/html'}),timeout=5) as response:
            assert response.status==200 and b'Smoke UI' in response.read()
        with opener.open(urllib.request.Request(f'http://127.0.0.1:{port}/worktree',headers={'Accept':'text/html'}),timeout=5) as response:
            assert response.status==200 and b'Smoke UI' in response.read()
        # The retained Vue posted-link page only explains the phone-only workflow.
        with opener.open(urllib.request.Request(f'http://127.0.0.1:{port}/-/Fixture123',headers={'Accept':'text/html'}),timeout=5) as response:
            assert response.status==200 and b'Smoke UI' in response.read()
        assert request('/api/web-rpc',{'name':'storage-get-keys','args':{}})[0]==401
        assert request('/api/rpc',{'name':'storage-get-keys','args':{}})[0]==404
        assert request('/api/rpc',{'name':'github-pr','args':[]})[0]==400
        assert request('/api/rpc',{'name':'repo-info','args':{}},{'Origin':'https://evil.invalid'})[0]==403
        assert request('/health',headers={'Host':'evil.invalid'})[0]==403
        # Declared argument schemas must fail before any Git/provider operation.
        for name in ('source-key', 'source-fetch', 'source-fingerprint'):
            for args in ({}, {'target': 42}, {'target': None}):
                status, body = request('/api/rpc', {'name': name, 'args': args})
                assert status == 400 and body['error'] == 'That method received invalid arguments.', (name, body)
        assert request('/api/rpc', {'name': 'source-load-file', 'args': {'target': '', 'path': 'hello.txt'}})[0] == 400
        assert request('/api/rpc', {'name': 'local-diff', 'args': {}})[1]['error'] == 'A local diff needs a git target.'
        fetched = rpc('source-fetch', {'target': '', 'ignored': 'discard'})
        assert len(fetched['files']) == 1 and fetched['files'][0]['path'] == 'hello.txt', fetched
        assert fetched['ref']['target'] == '' and fetched['head']['sha'].startswith('worktree:')
        assert fetched['files'][0]['hunks'][0]['newStart'] == 1
        assert rpc('source-fingerprint', {'target': ''}) == fetched['head']['sha']
        assert rpc('source-load-file', {'target': '', 'path': 'hello.txt', 'sha': fetched['head']['sha']}) == 'new\n'
        opened=rpc('local-diff',{'target':''}); assert len(opened['diff']['files'])==1,opened
        assert rpc('source-key', {'target': ''}) == opened['key']
        assert opened['diff']['files'][0]['path']=='hello.txt'; assert opened['groups']
        key=opened['key']; sha=opened['diff']['files'][0]['sha']
        assert rpc('reviewed-set',{'key':key,'shas':[sha],'reviewed':True}) is True
        assert sha in rpc('reviewed-get',{'key':key})['reviewed']
        assert 'hello.txt' in rpc('diff-patches',{'key':key,'paths':['hello.txt']})['files']
        assert rpc('file-content',{'key':key,'path':'hello.txt'})['new']=='new\n'
        assert rpc('source-load-file',{'target':'','path':'.git/config','sha':opened['diff']['head']['sha']}) is None
        blob=repo/'blob.bin'; blob.write_bytes(b'\0old')
        first=rpc('local-diff',{'target':''}); first_file=next(f for f in first['diff']['files'] if f['path']=='blob.bin')
        blob.write_bytes(b'\0new')
        second=rpc('local-diff',{'target':''}); second_file=next(f for f in second['diff']['files'] if f['path']=='blob.bin')
        assert first_file['sha']!=second_file['sha'] and first['diff']['head']['sha']!=second['diff']['head']['sha']
        assert all(not f['path'].startswith('runtime-cache/') for f in second['diff']['files'])
        log.flush(); code=re.search(r'Browser trust code: (\d+)',(root/'server.log').read_text()).group(1)
        assert request('/api/session',{'code':code})[0]==200
        def web_rpc(name, args):
            status, body = request('/api/web-rpc', {'name': name, 'args': args})
            assert status == 200, (name, status, body)
            return body['result']
        assert web_rpc('source-fetch', {'target': ''})['files']
        assert request('/api/web-rpc', {'name': 'source-fetch', 'args': {'target': False}})[0] == 400
        assert request('/api/web-rpc', {'name': 'storage-get-keys', 'args': {}})[0] == 400
        # Browser-only paths use the same parser, before any network or batch write.
        for invalid in ({'path': '/user', 'method': None}, {'path': '/user', 'body': []}):
            assert request('/api/web-rpc', {'name': 'github-request', 'args': invalid})[1]['error'] == 'That method received invalid arguments.'
        assert request('/api/web-rpc', {'name': 'storage-set-items', 'args': {'items': [
            {'key': 'review:schema-first', 'value': '{}', 'expected': None},
            {'key': 'review:schema-second', 'value': None, 'expected': None},
        ]}})[0] == 400
        assert web_rpc('storage-get-item', {'key': 'review:schema-first'}) is None
        assert web_rpc('cache-diff', {'method': 'get', 'key': key})['diff']['ref']['kind'] == 'local'
        # JSON.stringify(undefined) is null in optional tuple positions.
        fixture={**fetched,'ref':{'kind':'paste','hash':'schema-fixture'}}
        copied = web_rpc('cache-diff', {'method': 'putDiff', 'key': 'paste:schema-fixture', 'values': [fixture, fixture['head']['sha'], None, None]})
        assert copied['diff'] == fixture
        # Valid multi-file diffs may exceed the old 4 MiB transport ceiling.
        large=json.loads(json.dumps(fetched))
        large['ref']={'kind':'paste','hash':'large-fixture'}
        template=large['files'][0]
        large['files']=[{**template,'path':f'large-{i}.txt','hunks':[{**template['hunks'][0],'patch':'@@ -1 +1 @@\n-old\n+'+'x'*(480*1024)+'\n'}]} for i in range(10)]
        large_saved=web_rpc('cache-diff', {'method':'putDiff','key':'paste:large-fixture','values':[large,large['head']['sha'],None,None]})
        assert len(large_saved['diff']['files'])==10
        assert web_rpc('cache-diff', {'method':'get','key':'paste:large-fixture'})['diff']==large
        assert web_rpc('cache-diff', {'method': 'setLlmSession', 'key': 'paste:schema-fixture', 'values': [None, 'null']}) is None
        assert web_rpc('cache-diff', {'method': 'setSharedComment', 'key': 'paste:schema-fixture', 'values': [None]}) is None
        assert request('/api/web-rpc', {'name': 'cache-diff', 'args': {'method': 'setReviewData', 'key': 'paste:schema-fixture', 'values': [{'threads': [{}], 'summaries': []}]}})[0] == 400
        assert web_rpc('cache-diff', {'method': 'get', 'key': 'paste:schema-fixture'}).get('reviews') is None
        assert request('/api/web-rpc',{'name':'storage-get-keys','args':{'base':'pr-meta:'}})[0]==200
        assert request('/api/web-rpc',{'name':'storage-set-item','args':{'key':'pr-meta:bad','value':'{}','expected':None}})[0]==400
        assert request('/api/web-rpc',{'name':'storage-set-item','args':{'key':'pr-meta/bad','value':'{}','expected':None}})[0]==400
        assert git('diff','--cached')==b''
        # A Git administration directory need not be named .git. Relocate it
        # inside the checkout and expose a listed symlink to its config.
        git('init', '--separate-git-dir', str(repo/'private-admin'))
        (repo/'admin-alias').symlink_to(repo/'private-admin', target_is_directory=True)
        (repo/'admin-config').symlink_to(repo/'private-admin'/'config')
        relocated=rpc('local-diff',{'target':''})
        assert all(not f['path'].startswith('private-admin/') and f['path']!='admin-config' for f in relocated['diff']['files'])
        assert rpc('source-load-file',{'target':'','path':'admin-config','sha':relocated['diff']['head']['sha']}) is None
        assert rpc('source-load-file',{'target':'','path':'private-admin/config','sha':relocated['diff']['head']['sha']}) is None
        assert rpc('source-load-file',{'target':'','path':'admin-alias/config','sha':relocated['diff']['head']['sha']}) is None

        print('Offline HTTP/auth/local-diff/cache/review smoke passed')
    finally:
        server.terminate();server.wait(timeout=5);log.close()
