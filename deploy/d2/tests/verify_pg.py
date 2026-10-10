"""D2-A独立假数据PG验收；只创建精确标记的新容器，finally清理。"""
from pathlib import Path
import argparse
import copy
import json
import subprocess
import sys
import time
import uuid
import zipfile
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'deploy/d2/scripts'))
import recipes

IMAGE = 'sha256:5626ad875419ccad0ff84a05d1b31097fc5fa14dfd63d76123b2866c2c16c718'
JAVA = Path('C:/Program Files/Java/jdk-21/bin')


def run(cmd, **kwargs):
    result = subprocess.run(cmd, capture_output=True, **kwargs)
    if result.returncode:
        raise RuntimeError('隔离命令失败：' + cmd[0])
    return result.stdout.decode('utf-8', errors='replace').strip()


def verify(output, image=IMAGE):
    output.mkdir(parents=True, exist_ok=False)
    libs = output / 'libs'; libs.mkdir()
    # 只提取既有构建中的第三方库；迁移读取当前源码，绝不启动旧应用。
    with zipfile.ZipFile(ROOT / 'backend/target/cooker-backend-0.0.1-SNAPSHOT.jar') as jar:
        for name in jar.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                (libs / Path(name).name).write_bytes(jar.read(name))
    run([str(JAVA / 'javac.exe'), '-encoding', 'UTF-8', '-cp', str(libs / '*'),
         '-d', str(output), str(ROOT / 'deploy/d2/tests/FlywayPrepare.java')])
    name = 'cooker-d2a-verify-' + uuid.uuid4().hex[:10]
    label = 'cooker.d2a.verification'
    created = False
    checks = []
    try:
        run(['docker', 'run', '-d', '--name', name, '--label', label + '=' + name,
             '-p', '127.0.0.1::5432', '-e', 'POSTGRES_USER=root',
             '-e', 'POSTGRES_PASSWORD=fake-d2a-password', '-e', 'POSTGRES_DB=d2a_source', image])
        created = True
        for attempt in range(60):
            result = subprocess.run(['docker', 'exec', name, 'pg_isready', '-U', 'root', '-d', 'd2a_source'], capture_output=True)
            if result.returncode == 0:
                break
            time.sleep(0.5)
        else:
            raise RuntimeError('临时PG未就绪')
        port = run(['docker', 'port', name, '5432/tcp']).rsplit(':', 1)[1]
        assert port not in {'8080', '5173', '5433'}
        cp = str(output) + ';' + str(libs / '*')
        for database in ['source', 'target', 'broken']:
            if database != 'source':
                run(['docker', 'exec', name, 'createdb', '-U', 'root', 'd2a_' + database])
            run([str(JAVA / 'java.exe'), '-cp', cp, 'FlywayPrepare',
                 'jdbc:postgresql://127.0.0.1:' + port + '/d2a_' + database,
                 str(ROOT / 'backend/src/main/resources/db/migration')])
        checks.append('三个临时库实际执行当前V1～V15')
        args = SimpleNamespace(container=name, database='d2a_source', role='root', file=output / 'fake-recipes.json')
        bindings = ','.join('(' + str(k) + ',' + recipes.literal(v) + ",'REVIEWED')" for k, v in recipes.BINDINGS.items())
        vector = '[' + ','.join(['0.01'] * 1024) + ']'
        seed = """
INSERT INTO recipe_import_batch(id,source_type,source_version,status,error_summary) VALUES(41,'SYSTEM_HOWTOCOOK','fake-v1','COMPLETED','{"path":"C:/fake/private"}');
INSERT INTO recipe_source(id,source_type,source_key,source_uri,visibility) VALUES(51,'SYSTEM_HOWTOCOOK','test/鸡蛋.md','C:/fake/private','PUBLIC');
INSERT INTO recipe_source_document(id,source_id,source_version,content_hash,raw_markdown,parse_status,parse_warnings,failure_message,import_batch_id) VALUES(61,51,'fake-v1',repeat('a',64),'# 鸡蛋\n鸡蛋适量：UNKNOWN','SUCCESS','["fake private path"]','fake diagnostic',41);
INSERT INTO recipe(id,source_id,source_document_id,name,category_code) VALUES(71,51,61,'隔离鸡蛋','test');
INSERT INTO ingredient(id,canonical_name,normalization_status) VALUES """ + bindings + ";" + """
INSERT INTO ingredient_alias(id,ingredient_id,alias_name) VALUES(81,4,'隔离鸡蛋别名');
INSERT INTO cooking_tool(id,canonical_name) VALUES(91,'隔离锅');
INSERT INTO recipe_ingredient(id,recipe_id,ingredient_id,raw_name,raw_line,sort_order) VALUES(101,71,4,'鸡蛋','鸡蛋适量：UNKNOWN',1);
INSERT INTO recipe_ingredient(id,recipe_id,raw_name,raw_line,sort_order,amount_min,amount_max) VALUES(102,71,'假数量','假数量0.123456789012345678901',2,0.123456789012345678901,0.123456789012345678901);
INSERT INTO recipe_step(id,recipe_id,step_no,content) VALUES(111,71,1,'煮鸡蛋');
INSERT INTO recipe_tool(recipe_id,tool_id) VALUES(71,91);
INSERT INTO recipe_search_document(id,recipe_id,source_document_id,source_type,visibility,chunk_type,chunk_no,content,content_hash,document_template_version,embedding_model,embedding_dimension,embedding,embedding_status) VALUES(121,71,61,'SYSTEM_HOWTOCOOK','PUBLIC','overview',0,'鸡蛋烹饪',repeat('b',64),1,'text-embedding-v4',1024,""" + recipes.literal(vector) + """,'READY');
INSERT INTO app_user(id,username,password_hash) VALUES(501,'d2a_private','fake-hash');
INSERT INTO recipe_source(id,source_type,source_key,owner_user_id,visibility) VALUES(502,'USER_CUSTOM','private.md',501,'PRIVATE');
INSERT INTO recipe_source_document(id,source_id,source_version,content_hash,raw_markdown,parse_status) VALUES(503,502,'fake-private',repeat('c',64),'私有原文','SUCCESS');
INSERT INTO recipe(id,source_id,source_document_id,name,category_code) VALUES(504,502,503,'私有菜谱','test');
"""
        recipes.psql(args, seed)
        recipes.export(args)
        data = recipes.strict_json(args.file.read_text(encoding='utf-8'))
        assert [r['id'] for r in data['tables']['recipe']] == [71]
        assert data['tables']['recipe_source'][0]['source_uri'] is None
        assert data['tables']['recipe_source_document'][0]['parse_warnings'] == []
        assert 'app_user' not in data['tables'] and 'flyway_schema_history' not in data['tables']
        checks.append('导出仅系统三层，排除私有菜谱/用户/诊断')
        args.database = 'd2a_target'
        recipes.psql(args, recipes.restore_sql(data))
        assert recipes.psql(args, "SELECT raw_markdown='# 鸡蛋\n鸡蛋适量：UNKNOWN' AND import_batch_id=41 FROM recipe_source_document WHERE id=61") == 't'
        assert recipes.psql(args, 'SELECT amount_min IS NULL AND amount_max IS NULL AND ingredient_id=4 FROM recipe_ingredient WHERE id=101') == 't'
        assert recipes.psql(args, "SELECT amount_min::text='0.123456789012345678901' FROM recipe_ingredient WHERE id=102") == 't'
        assert recipes.psql(args, "SELECT vector_dims(embedding)=1024 AND tsv=to_tsvector('cooker_zh',content) AND tsv<>''::tsvector FROM recipe_search_document WHERE id=121") == 't'
        assert recipes.psql(args, "SELECT nextval(pg_get_serial_sequence('recipe','id'))=72 AND nextval(pg_get_serial_sequence('ingredient','id'))=615") == 't'
        private = recipes.psql(args, "SELECT jsonb_object_agg(tablename,n) FROM (SELECT tablename, (xpath('/row/n/text()',query_to_xml(format('SELECT count(*) AS n FROM public.%I',tablename),false,true,'')))[1]::text::bigint n FROM pg_tables WHERE schemaname='public' AND tablename NOT IN (" + ','.join(recipes.literal(t) for t in [*recipes.TABLES, 'flyway_schema_history']) + ')) x')
        assert all(n == 0 for n in json.loads(private).values())
        checks.append('ID/FK/原文/UNKNOWN/精确numeric/生成tsv/1024维向量/序列/全部私有表空')
        try:
            recipes.psql(args, recipes.restore_sql(data))
        except ValueError:
            checks.append('非空目标拒绝且既有数据保留')
        else:
            raise AssertionError('非空目标未拒绝')
        assert recipes.psql(args, 'SELECT count(*) FROM recipe') == '1'
        args.database = 'd2a_broken'
        broken = copy.deepcopy(data); broken['tables']['recipe'][0]['source_document_id'] = 999999
        try:
            recipes.psql(args, recipes.restore_sql(broken))
        except ValueError:
            assert recipes.psql(args, 'SELECT count(*) FROM recipe_source') == '0'
            assert recipes.psql(args, "SELECT nextval(pg_get_serial_sequence('recipe_source','id'))=1") == 't'
            checks.append('外键错误整体回滚，事务序列回滚')
        else:
            raise AssertionError('错误外键未拒绝')
        (output / 'result.json').write_text(json.dumps({'checks': checks, 'container': name, 'hostPort': int(port), 'fakeDataOnly': True}, ensure_ascii=False, indent=2), encoding='utf-8')
        print('隔离PG专项通过：', len(checks))
    finally:
        if created:
            marker = run(['docker', 'inspect', '-f', '{{index .Config.Labels "' + label + '"}}', name])
            if marker != name:
                raise RuntimeError('标记不符，拒绝清理')
            run(['docker', 'rm', '-f', '-v', name])
            print('已清理本次精确标记临时容器及匿名卷：', name)


if __name__ == '__main__':
    p = argparse.ArgumentParser(); p.add_argument('--output', type=Path, required=True);p.add_argument('--image',default=IMAGE)
    a=p.parse_args()
    import re
    if not re.fullmatch(r'sha256:[a-f0-9]{64}',a.image):p.error('必须指定已核对本轮镜像ID')
    verify(a.output.resolve(),a.image)
