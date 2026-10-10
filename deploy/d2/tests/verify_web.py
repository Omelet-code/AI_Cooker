"""固定本轮Web镜像＋临时自签名证书；内部网络/随机端口，清理精确标记组件。"""
import argparse
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import ssl
import time
import urllib.request
import uuid
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from verify_pg import run


def verify(output):
    output.mkdir(parents=True,exist_ok=False)
    key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    subject=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'demo.invalid')])
    cert=x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(datetime.now(timezone.utc)-timedelta(minutes=1)).not_valid_after(datetime.now(timezone.utc)+timedelta(hours=1)).add_extension(x509.SubjectAlternativeName([x509.DNSName('demo.invalid')]),False).sign(key,hashes.SHA256())
    cert_dir=output/'fake-tls';cert_dir.mkdir()
    (cert_dir/'fullchain.pem').write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    (cert_dir/'privkey.pem').write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
    name='cooker-d2a-web-'+uuid.uuid4().hex[:10];network=name+'-ingress';label='cooker.d2a.verification'
    net_created=False;created=False
    try:
        run(['docker','network','create','--label',label+'='+name,network]);net_created=True
        run(['docker','run','-d','--name',name,'--label',label+'='+name,'--network',network,
             '--add-host','backend:127.0.0.1','-p','127.0.0.1::443','-e','DOMAIN=demo.invalid',
             '-e','NGINX_ENVSUBST_FILTER=^DOMAIN$','--mount','type=bind,source='+str(cert_dir)+',target=/etc/nginx/tls,readonly',
             'cooker-d2a-frontend:verify-20261009']);created=True
        port=int(run(['docker','port',name,'443/tcp']).rsplit(':',1)[1]);assert port not in {8080,5173,5433}
        # 仅对本轮自签证书使用测试信任设置；不改变宿主信任库。
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPSHandler(context=ssl._create_unverified_context()))
        for _ in range(40):
            try:
                with opener.open(urllib.request.Request(f'https://127.0.0.1:{port}/',headers={'Host':'demo.invalid'}),timeout=2) as response:
                    assert response.status==200 and '<html' in response.read().decode('utf-8')
                break
            except OSError:time.sleep(.5)
        else:raise RuntimeError('隔离Web HTTPS不可访问')
        run(['docker','exec',name,'nginx','-t'])
        config=run(['docker','exec',name,'cat','/etc/nginx/conf.d/default.conf'])
        assert 'server_name demo.invalid;' in config and 'proxy_set_header Host $host;' in config and 'proxy_buffering off;' in config
        (output/'result.json').write_text(json.dumps({'https':200,'nginxSyntax':True,'domainRendered':True,'proxyVariablesPreserved':True,'ingressPublishedPort':True,'fakeCertificateOnly':True,'container':name,'port':port},indent=2),encoding='utf-8')
        print('隔离Web通过：HTTPS静态页/Nginx语法/域名替换/代理变量/SSE模板/入口网络端口')
    finally:
        if created:
            assert run(['docker','inspect','-f','{{index .Config.Labels "'+label+'"}}',name])==name
            run(['docker','rm','-f','-v',name])
        if net_created:
            assert run(['docker','network','inspect','-f','{{index .Labels "'+label+'"}}',network])==name
            run(['docker','network','rm',network])
        print('已清理本轮Web容器及独立网络')


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True)
    verify(p.parse_args().output.resolve())
