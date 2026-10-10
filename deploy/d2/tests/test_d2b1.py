"""厨房启用配置专项：只用假Key和静态Compose，不启动业务或访问供应商。"""
from contextlib import redirect_stdout, redirect_stderr
import io
import json
import os
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import test_tools

render = test_tools.render
ROOT = Path(__file__).resolve().parents[1]
FAKE_KEY = 'sk-' + '0123456789abcdef' * 2


class D2B1Test(unittest.TestCase):
    def values(self):
        return test_tools.ToolsTest().values()

    def test_默认关闭且关闭允许空Key(self):
        self.assertEqual(render.kitchen({}), {'COOKER_AGENT_EXECUTION_ENABLED': 'false', 'DASHSCOPE_API_KEY': ''})
        render.validate(self.values())
        values = self.values(); values.update(COOKER_AGENT_EXECUTION_ENABLED='false', DASHSCOPE_API_KEY='')
        render.validate(values)

    def test_true和有效形状假Key允许(self):
        values = self.values(); values.update(COOKER_AGENT_EXECUTION_ENABLED='true', DASHSCOPE_API_KEY=FAKE_KEY)
        render.validate(values)
        self.assertEqual(render.kitchen(values)['DASHSCOPE_API_KEY'], FAKE_KEY)

    def test_非法布尔拒绝(self):
        for value in ['', 'TRUE', 'False', '1', 'yes', ' true', 'true ', 'true;echo']:
            with self.subTest(value=value), self.assertRaises(ValueError):
                render.kitchen({'COOKER_AGENT_EXECUTION_ENABLED': value, 'DASHSCOPE_API_KEY': FAKE_KEY})

    def test_开启缺Key非法形状及明显占位拒绝(self):
        for key in [None, '', 'change-me', 'sk-short', 'sk-'+'x'*32, 'sk-'+'x'*32+' example', 'sk-'+'placeholder'*3, 'sk-'+'0'*20+'\n', 'sk-'+'a'*201]:
            values = {'COOKER_AGENT_EXECUTION_ENABLED': 'true'}
            if key is not None: values['DASHSCOPE_API_KEY'] = key
            with self.subTest(keyCase=key is None), self.assertRaises(ValueError): render.kitchen(values)

    def test_显式文件与默认值覆盖继承shell(self):
        with patch.dict(os.environ, {'COOKER_AGENT_EXECUTION_ENABLED':'true', 'DASHSCOPE_API_KEY':FAKE_KEY, 'MODEL_RATE':'off'}):
            env = render.compose_environment(self.values())
            self.assertEqual(env['COOKER_AGENT_EXECUTION_ENABLED'], 'false')
            self.assertEqual(env['DASHSCOPE_API_KEY'], '')
            self.assertEqual(env['MODEL_RATE'], '6r/m')
            values = self.values(); values.update(COOKER_AGENT_EXECUTION_ENABLED='true', DASHSCOPE_API_KEY=FAKE_KEY)
            self.assertEqual(render.compose_environment(values)['DASHSCOPE_API_KEY'], FAKE_KEY)

    def test_静默CLI拒绝且不泄露参数或Docker正文(self):
        with tempfile.TemporaryDirectory() as tmp:
            envfile=Path(tmp)/'fake.env'
            for enabled,key,composeFailure in [('true','',False), ('bad',FAKE_KEY,False), ('true',FAKE_KEY,True), ('false','',False)]:
                values=self.values(); values.update(COOKER_AGENT_EXECUTION_ENABLED=enabled,DASHSCOPE_API_KEY=key)
                envfile.write_text('\n'.join(k+'='+v for k,v in values.items()),encoding='utf-8')
                out=io.StringIO();err=io.StringIO()
                fakeResult=subprocess.CompletedProcess([],1 if composeFailure else 0,'不应打印的Docker正文'.encode(),FAKE_KEY.encode())
                with patch.object(sys,'argv',['render.py','--env-file',str(envfile),'--compose',str(ROOT/'compose.yaml')]), patch.object(subprocess,'run',return_value=fakeResult) as runner, redirect_stdout(out), redirect_stderr(err):
                    if enabled != 'false':
                        with self.assertRaises(SystemExit) as exit: runpy.run_path(str(ROOT/'scripts/render.py'),run_name='__main__')
                        self.assertEqual(exit.exception.code,1)
                    else: runpy.run_path(str(ROOT/'scripts/render.py'),run_name='__main__')
                self.assertNotIn(FAKE_KEY,out.getvalue()+err.getvalue())
                self.assertNotIn('不应打印的Docker正文',out.getvalue()+err.getvalue())
                if not composeFailure and enabled != 'false': runner.assert_not_called()
                else: self.assertIn('--quiet',runner.call_args.args[0])

    def test_真实Compose静态注入与固定保护(self):
        # JSON只在测试进程内核对假值，不打印完整渲染，不执行up/run。
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'fake.env'
            for enabled,key in [('false',''),('true',FAKE_KEY)]:
                values=self.values(); values.update(COOKER_AGENT_EXECUTION_ENABLED=enabled,DASHSCOPE_API_KEY=key)
                path.write_text('\n'.join(k+'='+v for k,v in values.items()),encoding='utf-8')
                result=subprocess.run(['docker','compose','--project-directory',str(ROOT),'--env-file',str(path),'-f',str(ROOT/'compose.yaml'),'config','--format','json'],env=render.compose_environment(values),capture_output=True)
                self.assertEqual(result.returncode,0,'Compose静态检查失败，正文未输出')
                doc=json.loads(result.stdout);e=doc['services']['backend']['environment']
                self.assertEqual(e['COOKER_AGENT_EXECUTION_ENABLED'],enabled)
                self.assertEqual(e['DASHSCOPE_API_KEY'],key)
                for name in ['COOKER_EVALUATION_HTTP_ENABLED','COOKER_RECIPE_IMPORT_ENABLED','COOKER_RETRIEVAL_INDEX_ENABLED']: self.assertEqual(e[name],'false')
                self.assertEqual(e['SPRING_PROFILES_ACTIVE'],'docker');self.assertEqual(e['COOKER_SECURE_COOKIE'],'true')
                self.assertFalse(any('DEEPSEEK' in name for name in e))
                self.assertIn('disableRetryConnect=true',e['JAVA_TOOL_OPTIONS'])
                self.assertEqual(e['SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE'],'8')
                self.assertEqual(int(doc['services']['backend']['mem_limit']),1536*1024*1024)
                for name in ['backend','postgres']: self.assertFalse(doc['services'][name].get('ports'))


if __name__ == '__main__': unittest.main()
