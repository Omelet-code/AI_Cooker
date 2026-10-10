"""装配后JAR的假秘密HTTP启动验收；新PG/随机端口，关闭模型，无业务服务操作。"""
import argparse
import base64
import json
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.request
import uuid
from verify_pg import IMAGE, JAVA, ROOT, run


def verify(output, release):
    output.mkdir(parents=True, exist_ok=False)
    name = 'cooker-d2a-http-' + uuid.uuid4().hex[:10]
    label = 'cooker.d2a.verification'
    proc = None; created = False
    try:
        run(['docker','run','-d','--name',name,'--label',label+'='+name,'-p','127.0.0.1::5432',
             '-e','POSTGRES_USER=root','-e','POSTGRES_PASSWORD=fake-d2a-password','-e','POSTGRES_DB=d2a_http',IMAGE])
        created = True
        for _ in range(60):
            if subprocess.run(['docker','exec',name,'pg_isready','-U','root','-d','d2a_http'],capture_output=True).returncode == 0:
                break
            time.sleep(.5)
        db_port = int(run(['docker','port',name,'5432/tcp']).rsplit(':',1)[1])
        with socket.socket() as sock:
            sock.bind(('127.0.0.1',0)); http_port=sock.getsockname()[1]
        assert not {db_port,http_port} & {8080,5173,5433}
        env={k:v for k,v in os.environ.items() if not any(t in k.upper() for t in ['COOKER','DASHSCOPE','DEEPSEEK','LLM_','SPRING_','DB_','BACKEND_PORT','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS'])}
        env.update({'DB_URL':f'jdbc:postgresql://127.0.0.1:{db_port}/d2a_http','DB_USERNAME':'root',
                    'DB_PASSWORD':'fake-d2a-password','BACKEND_PORT':str(http_port),'SPRING_PROFILES_ACTIVE':'docker',
                    'COOKER_JWT_SECRET':'fake-'+ 'x'*40,'LLM_CREDENTIAL_MASTER_KEY':base64.b64encode(b'x'*32).decode(),
                    'DASHSCOPE_API_KEY':'','COOKER_AGENT_EXECUTION_ENABLED':'false',
                    'COOKER_EVALUATION_HTTP_ENABLED':'false','COOKER_RECIPE_IMPORT_ENABLED':'false',
                    'SPRING_CONFIG_ADDITIONAL_LOCATION':(ROOT/'deploy/d2/config/mock-catalog.yml').as_uri()})
        with (output/'startup.log').open('w',encoding='utf-8') as log:
            proc=subprocess.Popen([str(JAVA/'java.exe'),'-jar',str(release/'backend/target/cooker-backend-0.0.1-SNAPSHOT.jar'),
                                   '--server.address=127.0.0.1'],env=env,stdout=log,stderr=subprocess.STDOUT,cwd=output)
            base=f'http://127.0.0.1:{http_port}/api'
            opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
            for _ in range(120):
                if proc.poll() is not None: raise RuntimeError('隔离应用启动失败，见假秘密日志')
                try:
                    with opener.open(base+'/actuator/health',timeout=1) as response:
                        health=json.load(response)
                    if health['status']=='UP':break
                except (OSError, ValueError):pass
                time.sleep(.5)
            else: raise RuntimeError('隔离health未通过')
            with opener.open(base+'/auth/csrf',timeout=3) as response:
                assert response.status==200
                assert 'Secure' in response.headers.get('Set-Cookie','')
            value=run(['docker','exec',name,'psql','-X','-qAt','-U','root','-d','d2a_http','-c',
                       'SELECT (SELECT count(*) FROM flyway_schema_history WHERE success)=15 AND (SELECT count(*) FROM recipe)=0 AND (SELECT count(*) FROM app_user)=0'])
            assert value=='t'
            (output/'result.json').write_text(json.dumps({'health':'UP','csrf':200,'secureCookie':True,'flyway':15,
                'recipeCount':0,'userCount':0,'modelEnabled':False,'realKeysRead':False,'container':name,'httpPort':http_port,'dbPort':db_port},indent=2),encoding='utf-8')
            print('独立JAR HTTP通过：health/CSRF/Secure Cookie/V15空库，模型配置关闭')
    finally:
        if proc is not None and proc.poll() is None:
            proc.terminate(); proc.wait(timeout=20)
            print('已停止本轮独立Java进程')
        if created:
            assert run(['docker','inspect','-f','{{index .Config.Labels "'+label+'"}}',name])==name
            run(['docker','rm','-f','-v',name]);print('已清理本轮独立PG及匿名卷：',name)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);p.add_argument('--release',type=Path,required=True)
    a=p.parse_args();verify(a.output.resolve(),a.release.resolve())
