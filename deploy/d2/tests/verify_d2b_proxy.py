"""只复用已核对Web镜像，隔离Nginx＋假后端；验证信任、真实路由限流和长请求。"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import http.client
import ipaddress
import json
import os
from pathlib import Path
import socket
import ssl
import subprocess
import sys
import threading
import time
import uuid
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import render

ROOT=Path(__file__).resolve().parents[3]
IMAGE='sha256:506b5c5fe6d2d0417d9c892402b82248b9e10232020aec1cae18a9bab3653bfd'


def run(cmd):
    p=subprocess.run(cmd,capture_output=True,timeout=90)
    if p.returncode:raise RuntimeError('隔离组件命令失败：'+cmd[0])
    return p.stdout.decode('utf-8',errors='replace').strip()


def request(port,ca,method,path,headers=None,ready=None,verify_host='1.1.1.1'):
    # TCP只连本机随机端口；TLS严格验证测试信任根和公网IP SAN，不访问这个公网IP。
    context=ssl.create_default_context(cafile=str(ca))
    conn=http.client.HTTPSConnection(verify_host,timeout=20,context=context)
    conn.sock=context.wrap_socket(socket.create_connection(('127.0.0.1',port),timeout=20),server_hostname=verify_host)
    try:
        conn.request(method,path,body=b'{}' if method=='POST' else None,headers={'Host':('['+verify_host+']' if ':' in verify_host else verify_host),**(headers or {})})
        response=conn.getresponse()
        if ready:ready.set()
        return response.status,dict(response.headers),response.read()
    finally:conn.close()


def verify(output):
    output.mkdir(parents=True,exist_ok=False)
    key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    subject=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'D2-B隔离测试证书')])
    cert=x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(datetime.now(timezone.utc)-timedelta(minutes=1)).not_valid_after(datetime.now(timezone.utc)+timedelta(hours=1)).add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address('1.1.1.1')),x509.IPAddress(ipaddress.ip_address('2606:4700:4700::1111')),x509.DNSName('demo.invalid')]),False).sign(key,hashes.SHA256())
    tls=output/'tls';(tls/'current').mkdir(parents=True)
    (tls/'current/fullchain.pem').write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    (tls/'current/privkey.pem').write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
    ca=tls/'current/fullchain.pem'
    webroot=output/'acme';(webroot/'.well-known/acme-challenge').mkdir(parents=True)
    (webroot/'.well-known/acme-challenge/fake-token').write_text('fake-http01-token',encoding='utf-8')
    (output/'slow.json').write_text(json.dumps({'fake':True,'payload':'x'*12000}),encoding='utf-8')
    (output/'backend.conf').write_text('''events {}
http {
 server {
  listen 8080;
  location /api/ {
   error_page 418 =200 /slow;
   if ($http_x_d2b_slow = yes) { return 418; }
   default_type application/json;
   return 200 '{"fake":true,"forwarded":"$http_x_forwarded_for","real":"$http_x_real_ip"}';
  }
  location = /slow { internal; alias /fixture/slow.json; limit_rate 2048; }
 }
}
''',encoding='utf-8')
    project='d2b-proxy-'+uuid.uuid4().hex[:10]
    label='cooker.d2b.verification'
    composefile=output/'compose.json'
    spec={'name':project,'services':{'backend':{'image':IMAGE,'entrypoint':['nginx'],'command':['-g','daemon off;'],
          'volumes':[str(output/'backend.conf')+':/etc/nginx/nginx.conf:ro',str(output)+':/fixture:ro'],
          'networks':['isolated'],'labels':{label:project}}},'networks':{'isolated':{'internal':True,'labels':{label:project}},'ingress':{'labels':{label:project}}}}
    started=False;checks=[]
    cmd=['docker','compose','-p',project,'-f',str(composefile)]
    def phase(name,values):
        template=(ROOT/'deploy/d2/nginx/default.conf.template').read_text(encoding='utf-8')
        settings={'DOMAIN':'1.1.1.1','PUBLIC_ORIGIN':'https://1.1.1.1',**render.LIMITS,**values}
        render.address(settings['DOMAIN']);render.limits(settings)
        # 仅替换批准变量，保留Nginx自身变量；避免改宿主环境。
        for k,v in settings.items():template=template.replace('${'+k+'}',v)
        conf=output/(name+'.template');conf.write_text(template,encoding='utf-8')
        spec['services']['web']={'image':IMAGE,'environment':{'NGINX_ENVSUBST_FILTER':'^DO_NOT_SUBSTITUTE$'},
             'volumes':[str(conf)+':/etc/nginx/templates/default.conf.template:ro',str(tls)+':/etc/nginx/tls:ro',str(webroot)+':/var/www/acme:ro'],
             'ports':['127.0.0.1::443','127.0.0.1::80'],'networks':['isolated','ingress'],'labels':{label:project},'depends_on':['backend']}
        composefile.write_text(json.dumps(spec),encoding='utf-8');run(cmd+['up','-d','--force-recreate','web'])
        ident=run(cmd+['ps','-q','web']);port=int(run(['docker','port',ident,'443/tcp']).rsplit(':',1)[1])
        assert port not in {8080,5173,5433}
        for _ in range(40):
            try:
                if request(port,ca,'GET','/api/actuator/health')[0]==200:break
            except OSError:time.sleep(.2)
        else:raise RuntimeError('隔离Nginx未就绪')
        run(['docker','exec',ident,'nginx','-t'])
        return ident,port
    try:
        composefile.write_text(json.dumps(spec),encoding='utf-8');started=True
        run(cmd+['up','-d','backend'])
        ident,port=phase('rate',{'REGISTER_RATE':'2r/m','LOGIN_RATE':'2r/m','MODEL_RATE':'2r/m',
                              'REGISTER_BURST':'1','LOGIN_BURST':'1','MODEL_BURST':'1'})
        checks.append('严格信任测试证书与IP SAN：HTTPS成功')
        try:request(port,ca,'GET','/',verify_host='8.8.8.8')
        except ssl.SSLCertVerificationError:checks.append('IP SAN不匹配拒绝，未关闭证书验证')
        else:raise AssertionError('未拒绝SAN不匹配')
        for path in ['/api/auth/register','/api/auth/login','/api/conversations/1/turns']:
            statuses=[request(port,ca,'POST',path,{'X-Forwarded-For':'8.8.8.'+str(n)})[0] for n in range(1,5)]
            assert statuses[:2]==[200,200] and statuses[2:]==[429,429],statuses
        checks.append('注册/登录/旧推荐速率429；伪造XFF不能绕过')
        for path in ['/api/conversations/1/meals','/api/conversations/1/meals/2/revisions','/api/conversations/1/meals/2/guides','/api/auth/%72egister','/api//auth/register']:
            status,headers,body=request(port,ca,'POST',path);assert status==429,(path,status)
            assert json.loads(body)['code']=='ENTRY_RATE_LIMITED' and 'Retry-After' in headers
        checks.append('当前餐单/修订/指导与规范化URI均受保护，429结构明确')
        for path in ['/api/auth/csrf','/api/conversations/1/meals/2','/api/conversations/1/meals/2/guides/3','/api/recipes/71','/api/conversations/1/meals/2/actions/4']:
            assert request(port,ca,'GET',path)[0]==200
        assert request(port,ca,'POST','/api/conversations/1/meals/2/selection')[0]==200
        assert request(port,ca,'POST','/api/auth/refresh')[0]==200
        value=json.loads(request(port,ca,'GET','/api/recipes/71',{'X-Forwarded-For':'8.8.8.8','X-Real-IP':'8.8.8.8'})[2])
        assert value['forwarded']==value['real'] and value['real']!='8.8.8.8'
        checks.append('历史/CSRF/续期/零模型选择不被泛化限流，代理覆盖伪造地址')
        http_port=int(run(['docker','port',ident,'80/tcp']).rsplit(':',1)[1]);plain=http.client.HTTPConnection('127.0.0.1',http_port,timeout=3)
        plain.request('GET','/.well-known/acme-challenge/fake-token');r=plain.getresponse();assert r.status==200 and r.read()==b'fake-http01-token';plain.close()
        plain=http.client.HTTPConnection('127.0.0.1',http_port,timeout=3);plain.request('GET','/login',headers={'Host':'attacker.invalid'});r=plain.getresponse();assert r.status==308 and r.getheader('Location')=='https://1.1.1.1/login';r.read();plain.close()
        checks.append('HTTP-01不中断且固定HTTPS源不受Host注入')
        ident,port=phase('concurrency',{'MODEL_RATE':'60r/s','MODEL_BURST':'30','MODEL_IP_CONCURRENCY':'1','MODEL_GLOBAL_CONCURRENCY':'2'})
        ready=threading.Event()
        with ThreadPoolExecutor(max_workers=2) as pool:
            active=pool.submit(request,port,ca,'POST','/api/conversations/1/meals/2/guides',{'X-D2B-Slow':'yes'},ready)
            assert ready.wait(5)
            assert request(port,ca,'POST','/api/conversations/2/meals/3/guides',{'X-Forwarded-For':'different.invalid'})[0]==429
            assert request(port,ca,'GET','/api/conversations/1/meals/2/guides/3')[0]==200
            run(['docker','exec',ident,'nginx','-t']);run(['docker','exec',ident,'nginx','-s','reload'])
            response=active.result(timeout=15);assert response[0]==200 and len(json.loads(response[2])['payload'])==12000
        checks.append('单IP并发拒绝新请求，历史可读，已建立长请求经过安全reload仍完整完成')
        ident,port=phase('global',{'MODEL_RATE':'60r/s','MODEL_BURST':'30','MODEL_IP_CONCURRENCY':'4','MODEL_GLOBAL_CONCURRENCY':'2'})
        with ThreadPoolExecutor(max_workers=2) as pool:
            events=[threading.Event(),threading.Event()]
            active=[pool.submit(request,port,ca,'POST',f'/api/conversations/{n+1}/meals/2/guides',{'X-D2B-Slow':'yes'},events[n]) for n in range(2)]
            assert all(event.wait(5) for event in events)
            assert request(port,ca,'POST','/api/conversations/3/meals/2/guides')[0]==429
            assert all(f.result(timeout=15)[0]==200 for f in active)
        checks.append('全局HTTP并发2：第三请求429，前两长请求完整完成')
        ident,port=phase('ipv6',{'DOMAIN':'2606:4700:4700::1111','PUBLIC_ORIGIN':'https://[2606:4700:4700::1111]'})
        assert request(port,ca,'GET','/api/recipes/71',verify_host='2606:4700:4700::1111')[0]==200
        plain=http.client.HTTPConnection('127.0.0.1',int(run(['docker','port',ident,'80/tcp']).rsplit(':',1)[1]),timeout=3)
        plain.request('GET','/login');r=plain.getresponse();assert r.status==308 and r.getheader('Location')=='https://[2606:4700:4700::1111]/login';r.read();plain.close()
        checks.append('IPv6 IP SAN严格验证与带方括号HTTPS重定向通过')
        ident,port=phase('domain',{'DOMAIN':'demo.invalid','PUBLIC_ORIGIN':'https://demo.invalid'})
        assert request(port,ca,'GET','/api/recipes/71',verify_host='demo.invalid')[0]==200
        checks.append('未来域名切换模板与DNS SAN严格验证通过')
        boot=output/'bootstrap.template';boot.write_text((ROOT/'deploy/d2/nginx/bootstrap.conf.template').read_text(encoding='utf-8').replace('${DOMAIN}','1.1.1.1'),encoding='utf-8')
        spec['services']['web']['volumes']=[str(boot)+':/etc/nginx/templates/default.conf.template:ro',str(webroot)+':/var/www/acme:ro']
        spec['services']['web']['ports']=['127.0.0.1::80'];composefile.write_text(json.dumps(spec),encoding='utf-8');run(cmd+['up','-d','--force-recreate','web'])
        ident=run(cmd+['ps','-q','web']);http_port=int(run(['docker','port',ident,'80/tcp']).rsplit(':',1)[1])
        for _ in range(40):
            try:
                plain=http.client.HTTPConnection('127.0.0.1',http_port,timeout=2);plain.request('GET','/.well-known/acme-challenge/fake-token');r=plain.getresponse();assert r.status==200 and r.read()==b'fake-http01-token';plain.close();break
            except OSError:time.sleep(.2)
        else:raise RuntimeError('首次引导HTTP未就绪')
        plain=http.client.HTTPConnection('127.0.0.1',http_port,timeout=2);plain.request('POST','/api/auth/register',body=b'{}');r=plain.getresponse();assert r.status==503;r.read();plain.close()
        checks.append('无证书首次引导只开放HTTP-01，注册HTTP入口503')
        (output/'result.json').write_text(json.dumps({'checks':checks,'project':project,'image':IMAGE,'fakeBackendOnly':True,'certificateVerificationEnabled':True,'realModelRequests':0},ensure_ascii=False,indent=2),encoding='utf-8')
        print('D2-B隔离代理专项通过：',len(checks),'组')
    finally:
        if started:
            for ident in run(cmd+['ps','-aq']).splitlines():
                assert run(['docker','inspect','-f','{{index .Config.Labels "'+label+'"}}',ident])==project
                (output/('component-'+ident[:12]+'.log')).write_text(run(['docker','logs',ident]),encoding='utf-8')
            run(cmd+['down','--volumes','--remove-orphans'])
            print('已清理本轮精确项目的Nginx/假后端/网络：',project)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True)
    verify(p.parse_args().output.resolve())
