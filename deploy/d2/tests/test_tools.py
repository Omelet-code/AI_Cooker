"""独立工具专项，只使用临时目录、假秘密；不连接业务PG。"""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import assemble
import build
import recipes
import render
import base64
import json
import tempfile
import unittest
from unittest.mock import patch, MagicMock
import os
import subprocess
import yaml


class ToolsTest(unittest.TestCase):
    def values(self):
        return {'RELEASE_PROJECT': 'd2a-test', 'POSTGRES_DB': 'd2a_target', 'POSTGRES_USER': 'root',
                'POSTGRES_PASSWORD': 'fake-only', 'COOKER_JWT_SECRET': 'fake-' + 'x'*40,
                'LLM_CREDENTIAL_MASTER_KEY': base64.b64encode(b'x'*32).decode(), 'DOMAIN': 'demo.invalid', 'PUBLIC_ORIGIN': 'https://demo.invalid', 'ACME_WEBROOT': str(Path(tempfile.gettempdir()).resolve()),
                'TLS_DIRECTORY': str(Path(tempfile.gettempdir()).resolve()),
                **{k: 'fake@sha256:'+'a'*64 for k in ['POSTGRES_IMAGE', 'BACKEND_IMAGE', 'WEB_IMAGE']}}

    def test_必需值空缺与漂移镜像拒绝(self):
        render.validate(self.values())
        for key in render.REQUIRED:
            values=self.values();values[key]=''
            with self.assertRaises(ValueError):render.validate(values)
        values=self.values();values['WEB_IMAGE']='nginx:latest'
        with self.assertRaises(ValueError):render.validate(values)

    def test_域名注入与无效密钥拒绝(self):
        for key,value in [('DOMAIN','demo.invalid; rm'),('COOKER_JWT_SECRET','change-me'),('LLM_CREDENTIAL_MASTER_KEY','bad'),('POSTGRES_DB','bad;drop')]:
            values=self.values();values[key]=value
            with self.assertRaises(Exception):render.validate(values)

    def test_env重复键插值和带引号值拒绝(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'fake.env'
            base='\n'.join(k+'='+v for k,v in self.values().items())
            p.write_text(base,encoding='utf-8');render.read_env(p)
            for suffix in ['\nDOMAIN=duplicate.invalid','\nEXTRA=$SECRET', '\nEXTRA="fake"']:
                p.write_text(base+suffix,encoding='utf-8')
                with self.assertRaises(ValueError):render.read_env(p)

    def test_错误装配来源及非空输出拒绝(self):
        with tempfile.TemporaryDirectory() as d:
            b=Path(d)/'backend';f=Path(d)/'frontend';o=Path(d)/'output';b.mkdir();f.mkdir();o.mkdir();(o/'keep').write_text('保留')
            with self.assertRaises(ValueError):assemble.assemble(b,f,o)
            with self.assertRaises(ValueError):assemble.assemble(b,f,o,b,f)
            self.assertEqual((o/'keep').read_text(),'保留')

    def test_禁止文件和链接拒绝(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'.env').write_text('fake-secret')
            with self.assertRaises(ValueError):assemble.safe_file(root,root/'.env')
            with self.assertRaises(ValueError):assemble.safe_file(root,root/'missing.java')

    def test_Compose无后端数据库宿主端口且模型默认关闭(self):
        doc=yaml.safe_load((Path(__file__).resolve().parents[1]/'compose.yaml').read_text(encoding='utf-8'))
        for name in ['postgres','backend']:self.assertNotIn('ports',doc['services'][name])
        e=doc['services']['backend']['environment'];self.assertEqual(e['COOKER_AGENT_EXECUTION_ENABLED'],'${COOKER_AGENT_EXECUTION_ENABLED:-false}');self.assertEqual(e['DASHSCOPE_API_KEY'],'${DASHSCOPE_API_KEY:-}')
        self.assertTrue(doc['networks']['data']['internal']);self.assertIn('ingress',doc['services']['web']['networks']);self.assertFalse(doc['networks']['ingress'].get('internal',False))

    def test_构建来源必须是官方批准仓库摘要(self):
        build.locked({'NODE_IMAGE':'node:24.10.0-alpine@sha256:'+'a'*64},['NODE_IMAGE'])
        for value in ['node:latest','unknown/node@sha256:'+'a'*64]:
            with self.assertRaises(ValueError):build.locked({'NODE_IMAGE':value},['NODE_IMAGE'])

    def test_构建超时只结束所创建的进程树(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'release-manifest.json').write_text(json.dumps({'schemaVersion':1,'secretsIncluded':False}))
            process=MagicMock();process.pid=13579
            process.wait.side_effect=[subprocess.TimeoutExpired('fake-build',900),0]
            values={'NODE_IMAGE':'node@sha256:'+'a'*64,'NGINX_IMAGE':'nginx@sha256:'+'b'*64}
            with patch.object(build.subprocess,'Popen',return_value=process),patch.object(build.subprocess,'run') as kill,patch.object(build.os,'killpg',create=True) as killpg:
                with self.assertRaises(ValueError):build.build(root,values,'frontend','cooker-d2a-web:test')
                self.assertEqual(process.wait.call_args_list[0].kwargs,{'timeout':900})
                if os.name=='nt':self.assertEqual(kill.call_args.args[0],['taskkill','/PID','13579','/T','/F'])
                else:self.assertEqual(killpg.call_args.args[0],13579)

    def test_导出SQL只白名单并消除诊断及生成列(self):
        sql=recipes.export_sql()
        for name in ['app_user','user_llm_credential','meal_guide','recipe_repair_batch']:self.assertNotIn('FROM public.'+name,sql)
        self.assertNotIn('flyway_schema_history',recipes.TABLES);self.assertNotIn("'flyway_schema_history', COALESCE",sql);self.assertIn('READ ONLY',sql);self.assertIn('NULL AS failure_message',sql);self.assertNotIn(' AS tsv',sql);self.assertIn('amount_min::text',sql)

    def test_JSON重复字段拒绝(self):
        with self.assertRaises(ValueError): recipes.strict_json('{"tables":{},"tables":{}}')

    def test_输出链接祖先拒绝(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); b=root/'b';f=root/'f';o=root/'redirect'/'release'
            with patch.object(Path,'is_symlink',lambda p:p==root/'redirect'):
                with self.assertRaises(ValueError):assemble.assemble(b,f,o,b,f)
            self.assertFalse(o.exists())

    def test_固定角色资源缺失即拒绝(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);b=root/'b';f=root/'f';o=root/'output'
            for rel in ['backend/pom.xml','backend/.mvn/maven.config','backend/.mvn/settings.xml',
                        'backend/src/main/java/com/cooker/CookerApplication.java','backend/src/main/resources/application.yml']:
                p=b/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text('假内容')
            f.mkdir()
            with self.assertRaises(ValueError):assemble.assemble(b,f,o,b,f)
            self.assertFalse(o.exists())

    def test_私有投影和非有限向量拒绝(self):
        data={'schemaVersion':1,'migrationVersion':15,'bindings':{str(k):v for k,v in recipes.BINDINGS.items()},'tables':{name:[] for name in recipes.TABLES}}
        for key,name in recipes.BINDINGS.items():data['tables']['ingredient'].append({'id':key,'canonical_name':name,'normalization_status':'REVIEWED','created_at':'2026-10-09T00:00:00Z'})
        row={c:None for c in recipes.TABLES['recipe_search_document']}
        row.update(id=1,source_type='USER_CUSTOM',visibility='PRIVATE',owner_user_id=5)
        data['tables']['recipe_search_document']=[row]
        with self.assertRaises(ValueError):recipes.validate(data)
        row.update(source_type='SYSTEM_HOWTOCOOK',visibility='PUBLIC',owner_user_id=None,embedding_dimension=1024,embedding_model='text-embedding-v4',document_template_version=1,embedding_status='READY')
        with self.assertRaises(ValueError):recipes.validate(data)
        row['embedding']=json.dumps([float('nan')]+[0.1]*1023)
        with self.assertRaises(ValueError):recipes.validate(data)
        row['embedding']=json.dumps([0.1]*1024)
        recipes.validate(data)

    def test_额外表列与私有数据包拒绝(self):
        data={'schemaVersion':1,'migrationVersion':15,'bindings':{str(k):v for k,v in recipes.BINDINGS.items()},'tables':{name:[] for name in recipes.TABLES}}
        for key,name in recipes.BINDINGS.items():data['tables']['ingredient'].append({'id':key,'canonical_name':name,'normalization_status':'REVIEWED','created_at':'2026-10-09T00:00:00Z'})
        recipes.validate(data)
        wrong=json.loads(json.dumps(data));wrong['tables']['app_user']=[]
        with self.assertRaises(ValueError):recipes.validate(wrong)
        wrong=json.loads(json.dumps(data));wrong['tables']['ingredient'][0]['password']='fake'
        with self.assertRaises(ValueError):recipes.validate(wrong)
        self.assertIn('ALTER SEQUENCE',recipes.restore_sql(data));self.assertNotIn('setval(',recipes.restore_sql(data))


if __name__=='__main__':unittest.main()
