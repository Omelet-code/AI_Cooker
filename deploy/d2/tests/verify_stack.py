"""三服务Compose隔离冒烟；假秘密/自签证书，精确独立项目名，最终清理卷。"""
import argparse
import base64
import json
import os
from pathlib import Path
import subprocess
import ssl
import time
import urllib.request
import uuid
from verify_pg import run


def verify(release, output, cert_dir):
    output.mkdir(parents=True,exist_ok=False)
    project='d2a-stack-'+uuid.uuid4().hex[:10]
    values={'RELEASE_PROJECT':project,'POSTGRES_DB':'d2a_stack','POSTGRES_USER':'root',
            'POSTGRES_PASSWORD':'fake-only-stack','COOKER_JWT_SECRET':'fake-'+ 'x'*40,
            'LLM_CREDENTIAL_MASTER_KEY':base64.b64encode(b'x'*32).decode(),'DOMAIN':'demo.invalid',
            'TLS_DIRECTORY':str(cert_dir),'HTTP_BIND':'127.0.0.1','HTTPS_BIND':'127.0.0.1'}
    images={k:run(['docker','image','inspect',tag,'--format','{{.Id}}']) for k,tag in {
        'postgres':'cooker-d2a-postgres:verify-20261009','backend':'cooker-d2a-backend:final-20261009',
        'web':'cooker-d2a-frontend:verify-20261009'}.items()}
    # 本地镜像没有发布仓库RepoDigest；测试覆盖为已核对本轮image ID，不伪造生产摘要。
    values.update({k:'fake@sha256:'+'a'*64 for k in ['POSTGRES_IMAGE','BACKEND_IMAGE','WEB_IMAGE']})
    envfile=output/'fake-stack.env';envfile.write_text('\n'.join(k+'='+v for k,v in values.items()),encoding='utf-8')
    override={'services':{k:{'image':v,'labels':{'cooker.d2a.verification':project}} for k,v in images.items()}}
    # !override替换80/443端口列表，绝不占用宿主业务端口。
    overlay=output/'overlay.yaml'
    import yaml
    text=yaml.safe_dump(override)
    overlay.write_text(text,encoding='utf-8')
    ports=output/'ports.yaml';ports.write_text('services:\n  web:\n    ports: !override\n      - "127.0.0.1::443"\n      - "127.0.0.1::80"\n',encoding='utf-8')
    cmd=['docker','compose','--project-directory',str(release/'deploy'),'--env-file',str(envfile),'-p',project,
         '-f',str(release/'deploy/compose.yaml'),'-f',str(overlay),'-f',str(ports)]
    env=dict(os.environ);env.update(values)
    started=False
    try:
        # 渲染检查只捕获到内存；不打印含假秘密的配置正文。
        run(cmd+['config','--quiet'],env=env)
        started=True
        run(cmd+['up','-d','--wait','--wait-timeout','60'],env=env)
        web=run(cmd+['ps','-q','web'],env=env)
        port=int(run(['docker','port',web,'443/tcp']).rsplit(':',1)[1]);assert port not in {8080,5173,5433,80,443}
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPSHandler(context=ssl._create_unverified_context()))
        base=f'https://127.0.0.1:{port}'
        for _ in range(90):
            try:
                with opener.open(urllib.request.Request(base+'/api/actuator/health',headers={'Host':'demo.invalid'}),timeout=2) as r:
                    if json.load(r)['status']=='UP':break
            except (OSError,ValueError):pass
            time.sleep(.5)
        else:raise RuntimeError('三服务health未通过')
        with opener.open(urllib.request.Request(base+'/api/auth/csrf',headers={'Host':'demo.invalid'}),timeout=3) as r:
            assert r.status==200 and 'Secure' in r.headers.get('Set-Cookie','')
        for component in ['backend','postgres']:
            container=run(cmd+['ps','-q',component],env=env)
            mappings=json.loads(run(['docker','inspect','-f','{{json .NetworkSettings.Ports}}',container]))
            assert all(v is None for v in mappings.values())
        (output/'result.json').write_text(json.dumps({'project':project,'images':images,'httpsHealth':'UP',
            'csrf':200,'secureCookie':True,'backendAndPgHostPortsAbsent':True,'fakeSecretsOnly':True,
            'modelsDisabled':True,'webPort':port},indent=2),encoding='utf-8')
        print('三服务Compose通过：HTTPS代理health/CSRF/Secure Cookie/后端PG无宿主端口')
    finally:
        if started:
            ids=run(cmd+['ps','-aq'],env=env).splitlines()
            for ident in ids:
                assert run(['docker','inspect','-f','{{index .Config.Labels "cooker.d2a.verification"}}',ident])==project
            volumes=run(['docker','volume','ls','--filter','label=com.docker.compose.project='+project,'-q']).splitlines()
            for volume in volumes:
                assert run(['docker','volume','inspect','-f','{{index .Labels "com.docker.compose.project"}}',volume])==project
            run(cmd+['down','--volumes','--remove-orphans'],env=env)
            print('已清理本轮独立Compose项目容器/网络/卷：',project)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--release',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--cert-dir',type=Path,required=True)
    a=p.parse_args();verify(a.release.resolve(),a.output.resolve(),a.cert_dir.resolve())
