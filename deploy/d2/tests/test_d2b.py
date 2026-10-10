"""D2-B定向工具专项：参数拒绝、证书信任/失败、资源与安全配置。"""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch, MagicMock
import yaml
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import certificates
import render
import test_tools


class D2BTest(unittest.TestCase):
    def test_IP与域名的规范HTTPS源(self):
        for host, origin in [('1.1.1.1','https://1.1.1.1'),('2606:4700:4700::1111','https://[2606:4700:4700::1111]'),('demo.example','https://demo.example')]:
            self.assertEqual(render.address(host),(host,origin))
            values=test_tools.ToolsTest().values();values.update(DOMAIN=host,PUBLIC_ORIGIN=origin);render.validate(values)

    def test_非法IP与模板注入拒绝(self):
        for host in ['999.1.1.1','127.0.0.1','10.0.0.1','169.254.1.1','0.0.0.0','224.0.0.1','::1','::','fe80::1%eth0','[2606:4700:4700::1111]','demo.invalid;return 200','demo.invalid\nfoo','https://1.1.1.1','-bad.example','bad..example','1.1.1.1:443']:
            with self.subTest(host=host),self.assertRaises(ValueError):render.address(host)

    def test_入口源不一致和缺秘密拒绝(self):
        values=test_tools.ToolsTest().values();values['PUBLIC_ORIGIN']='http://demo.invalid'
        with self.assertRaises(ValueError):render.validate(values)
        for key in render.REQUIRED:
            values=test_tools.ToolsTest().values();values.pop(key)
            with self.assertRaises(ValueError):render.validate(values)

    def test_限流值范围和注入拒绝(self):
        for key in render.LIMITS:
            for value in ['0','off','0r/m','1000r/s','1r/s;return 200','1\nfoo']:
                with self.subTest(key=key,value=value),self.assertRaises(ValueError):render.limits({key:value})
        render.limits({'MODEL_IP_CONCURRENCY':'4','MODEL_GLOBAL_CONCURRENCY':'2'})

    def test_离线镜像ID与仓库摘要区分(self):
        values=test_tools.ToolsTest().values();values['WEB_IMAGE']='sha256:'+'a'*64;render.validate(values)
        for value in ['cooker-web:latest','cooker-web:release','sha256:abc','docker.io/unknown:release']:
            values['WEB_IMAGE']=value
            with self.assertRaises(ValueError):render.validate(values)

    def test_4GB预算内部端口和收费关闭(self):
        doc=yaml.safe_load((Path(__file__).resolve().parents[1]/'compose.yaml').read_text(encoding='utf-8'))
        self.assertEqual(sum(int(v['mem_limit'][:-1]) for v in doc['services'].values()),2688)
        for key in ['postgres','backend']:self.assertNotIn('ports',doc['services'][key])
        e=doc['services']['backend']['environment']
        self.assertEqual(e['DASHSCOPE_API_KEY'],'${DASHSCOPE_API_KEY:-}');self.assertEqual(e['COOKER_AGENT_EXECUTION_ENABLED'],'${COOKER_AGENT_EXECUTION_ENABLED:-false}');self.assertEqual(e['COOKER_SECURE_COOKIE'],'true')
        self.assertEqual(e['SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE'],'8')
        self.assertIn('-Xmx768m',e['JAVA_TOOL_OPTIONS'])
        text=(Path(__file__).resolve().parents[1]/'nginx/default.conf.template').read_text(encoding='utf-8')
        self.assertNotIn('real_ip_header',text);self.assertNotIn('$proxy_add_x_forwarded_for',text)
        self.assertIn('proxy_next_upstream off;',text);self.assertIn('proxy_buffering off;',text)

    def test_证书检查必须核对有效期私钥信任及IP_SAN(self):
        calls=[]
        def fake(cmd):
            calls.append(cmd)
            return b'fake-public-key' if '-pubkey' in cmd or '-pubout' in cmd else b'OK'
        with patch.object(certificates,'command',side_effect=fake):certificates.check(Path('/fake'),'1.1.1.1',86400)
        self.assertTrue(any('-checkend' in cmd and '86400' in cmd for cmd in calls))
        self.assertTrue(any('-verify_ip' in cmd and '-CApath' in cmd for cmd in calls))
        self.assertFalse(any('-noverify' in cmd for cmd in calls))
        with patch.object(certificates,'command',side_effect=[b'OK',b'key-A',b'key-B']):
            with self.assertRaises(ValueError):certificates.check(Path('/fake'),'1.1.1.1')

    def test_过期才停止指定Web预警不停止(self):
        config={'tlsDirectory':'/fake','host':'1.1.1.1','release':'/fake/release','envFile':'/fake/env','project':'fake-project'}
        with patch.object(certificates,'check',side_effect=ValueError('假过期')),patch.object(certificates,'alert'),patch.object(certificates,'web',return_value='a'*64),patch.object(certificates,'command') as command:
            with self.assertRaises(ValueError):certificates.guard(config)
            self.assertEqual(command.call_args.args[0][-2:],['stop','web'])
        with patch.object(certificates,'check',side_effect=ValueError('假过期')),patch.object(certificates,'alert',side_effect=OSError('假告警失败')),patch.object(certificates,'web',return_value='a'*64),patch.object(certificates,'command') as command:
            with self.assertRaises(OSError):certificates.guard(config)
            self.assertEqual(command.call_args.args[0][-2:],['stop','web'])
        with patch.object(certificates,'check',side_effect=[None,ValueError('假预警')]),patch.object(certificates,'alert'),patch.object(certificates,'web') as web:
            with self.assertRaises(ValueError):certificates.guard(config)
            web.assert_not_called()

    def test_续期配置拒绝路径和项目不一致(self):
        with tempfile.TemporaryDirectory() as d:
            data={'project':'fake-project','release':str(Path(d).resolve()),'envFile':str(Path(d).resolve()/'fake.env'),
                  'host':'1.1.1.1','certName':'fake-cert','lineage':str(Path(d).resolve()/'fake-cert'),
                  'tlsDirectory':str(Path(d).resolve()/'tls'),'alertFile':str(Path(d).resolve()/'fake-alert.jsonl')}
            p=Path(d)/'config.json';p.write_text(json.dumps(data),encoding='utf-8');certificates.settings(p)
            data['lineage']='relative';p.write_text(json.dumps(data),encoding='utf-8')
            with self.assertRaises(ValueError):certificates.settings(p)
            with patch.object(certificates,'command',side_effect=[b'a'*64,b'wrong-project']):
                with self.assertRaises(ValueError):certificates.web({'release':'/fake','envFile':'/fake/env','project':'fake-project'})

    def test_Nginx语法失败回滚且不reload(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);tls=root/'tls';tls.mkdir();(tls/'.cooker-managed-tls').write_text('fake-project')
            lineage=root/'lineage';lineage.mkdir()
            for name in ['fullchain.pem','privkey.pem']:(lineage/name).write_text('假证书文件')
            current=tls/'current';old='bundles/'+'a'*32
            config={'lineage':str(lineage),'host':'1.1.1.1','tlsDirectory':str(tls),'project':'fake-project','release':'/fake','envFile':'/fake/env'}
            with patch.object(certificates,'check'),patch.object(certificates,'web',return_value='a'*64),patch.object(Path,'is_symlink',lambda p:p==current),patch.object(certificates.os,'readlink',return_value=old),patch.object(certificates.os,'symlink') as link,patch.object(certificates.os,'replace') as replace,patch.object(certificates,'command',side_effect=ValueError('假语法失败')) as command,patch.object(certificates,'alert'):
                with self.assertRaises(ValueError):certificates.publish(config)
                self.assertEqual(link.call_args.args[0],old);self.assertEqual(replace.call_count,2)
                self.assertEqual(command.call_count,1);self.assertEqual(command.call_args.args[0][-2:],['nginx','-t'])


if __name__=='__main__':unittest.main()
