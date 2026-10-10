"""仅系统菜谱三层白名单。通过显式容器中的psql执行，不隐式连接宿主PG。"""
import argparse
import json
import math
from pathlib import Path
import re
import subprocess

# 这些ID/名称来自已签收D1-R2审定，不按名称匹配或重新审定。
BINDINGS = {4: '鸡蛋', 10: '生抽', 11: '蚝油', 12: '老抽', 35: '盐', 56: '糖', 63: '食用油',
            105: '黄油', 138: '鲜虾', 432: '芹菜', 486: '香油', 614: '西红柿'}
TABLES = {
    'recipe_import_batch': 'id source_type source_version status candidate_count succeeded_count skipped_count warning_count failed_count started_at finished_at',
    'recipe_source': 'id source_type source_key source_uri owner_user_id visibility created_at updated_at',
    'recipe_source_document': 'id source_id source_version content_hash raw_markdown parse_status parse_warnings failure_message import_batch_id imported_at',
    'ingredient': 'id canonical_name normalization_status created_at',
    'ingredient_alias': 'id ingredient_id alias_name',
    'cooking_tool': 'id canonical_name',
    'recipe': 'id source_id source_document_id name category_code summary difficulty estimated_minutes servings calories_kcal additional_content created_at updated_at',
    'recipe_ingredient': 'id recipe_id ingredient_id raw_name amount_min amount_max unit_code unit_label optional raw_line sort_order',
    'recipe_step': 'id recipe_id step_no content',
    'recipe_tool': 'recipe_id tool_id optional',
    'recipe_search_document': 'id recipe_id source_document_id source_type owner_user_id visibility chunk_type chunk_no content content_hash document_template_version embedding_model embedding_dimension embedding embedding_status failure_message indexed_at created_at updated_at',
}
TABLES = {name: cols.split() for name, cols in TABLES.items()}


def strict_json(value):
    def pairs(items):
        result = {}
        for key, item in items:
            if key in result:
                raise ValueError('JSON存在重复字段')
            result[key] = item
        return result
    return json.loads(value, object_pairs_hook=pairs)


def literal(value):
    # 使用普通字符串字面量，显式standard_conforming_strings；不执行输入SQL。
    return "'" + value.replace("'", "''") + "'"


def connection(args):
    for value in [args.container, args.database, args.role]:
        if not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}', value):
            raise ValueError('容器或数据库标识不合法')
    return ['docker', 'exec', '-i', args.container, 'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1',
            '-U', args.role, '-d', args.database]


def psql(args, sql):
    p = subprocess.run(connection(args), input=sql.encode('utf-8'), capture_output=True)
    if p.returncode:
        # PostgreSQL错误可能打印输入记录，禁止直接转发stderr。
        raise ValueError('白名单数据库操作失败，原错误正文未输出')
    return p.stdout.decode('utf-8').strip()


def export_sql():
    filters = {
        'recipe_source': "id IN (SELECT id FROM src)",
        'recipe_source_document': 'source_id IN (SELECT id FROM src)',
        'recipe': 'source_id IN (SELECT id FROM src)',
        'recipe_ingredient': 'recipe_id IN (SELECT id FROM rec)',
        'recipe_step': 'recipe_id IN (SELECT id FROM rec)',
        'recipe_tool': 'recipe_id IN (SELECT id FROM rec)',
        'recipe_search_document': "recipe_id IN (SELECT id FROM rec) AND source_type='SYSTEM_HOWTOCOOK' AND owner_user_id IS NULL AND visibility='PUBLIC'",
        'recipe_import_batch': 'id IN (SELECT import_batch_id FROM doc WHERE import_batch_id IS NOT NULL)',
        'ingredient': 'id IN (SELECT ingredient_id FROM ing WHERE ingredient_id IS NOT NULL) OR id IN (' + ','.join(map(str, BINDINGS)) + ')',
        'ingredient_alias': 'ingredient_id IN (SELECT id FROM selected_ingredient)',
        'cooking_tool': 'id IN (SELECT tool_id FROM recipe_tool WHERE recipe_id IN (SELECT id FROM rec))',
    }
    fields = []
    for table, cols in TABLES.items():
        expr = []
        for col in cols:
            if col in {'parse_warnings'}:
                value = "'[]'::jsonb"
            elif col in {'source_uri', 'failure_message'}:
                value = 'NULL'
            elif col in {'amount_min', 'amount_max', 'embedding'}:
                value = col + '::text'
            else:
                value = col
            expr.append(value + ' AS ' + col)
        fields.append(literal(table) + ", COALESCE((SELECT jsonb_agg(to_jsonb(x)) FROM (SELECT " + ','.join(expr)
                      + ' FROM public.' + table + ' WHERE ' + filters[table] + ') x),\'[]\'::jsonb)')
    return """BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL standard_conforming_strings=on;
DO $$ BEGIN
IF (SELECT count(*) FROM public.flyway_schema_history WHERE success) <> 15
 OR (SELECT max(version::integer) FROM public.flyway_schema_history WHERE success) <> 15
 THEN RAISE EXCEPTION 'source migration mismatch'; END IF; END $$;
WITH src AS (SELECT id FROM public.recipe_source WHERE source_type='SYSTEM_HOWTOCOOK' AND owner_user_id IS NULL AND visibility='PUBLIC'),
rec AS (SELECT id FROM public.recipe WHERE source_id IN (SELECT id FROM src)),
doc AS (SELECT * FROM public.recipe_source_document WHERE source_id IN (SELECT id FROM src)),
ing AS (SELECT * FROM public.recipe_ingredient WHERE recipe_id IN (SELECT id FROM rec)),
selected_ingredient AS (SELECT id FROM public.ingredient WHERE id IN (SELECT ingredient_id FROM ing) OR id IN (""" + ','.join(map(str, BINDINGS)) + "))\nSELECT jsonb_build_object(" + ','.join(fields) + ');\nCOMMIT;'


def validate(data):
    if not isinstance(data, dict) or set(data) != {'schemaVersion', 'migrationVersion', 'bindings', 'tables'}:
        raise ValueError('数据包外壳不在白名单')
    if type(data['schemaVersion']) is not int or type(data['migrationVersion']) is not int or data['schemaVersion'] != 1 or data['migrationVersion'] != 15 or data['bindings'] != {str(k): v for k, v in BINDINGS.items()}:
        raise ValueError('迁移版本或Mock绑定不一致')
    tables = data['tables']
    if not isinstance(tables, dict) or set(tables) != set(TABLES):
        raise ValueError('数据包含额外表或缺表')
    for name, columns in TABLES.items():
        if not isinstance(tables[name], list) or len(tables[name]) > 1000000:
            raise ValueError('表数据形状或数量异常')
        seen = set()
        for row in tables[name]:
            if not isinstance(row, dict) or set(row) != set(columns):
                raise ValueError('数据包含非白名单列')
            if 'id' in columns:
                if type(row['id']) is not int or not 0 < row['id'] < 2**63 or row['id'] in seen:
                    raise ValueError('ID不合法或重复')
                seen.add(row['id'])
            if name in {'recipe_source', 'recipe_search_document'} and (row['source_type'] != 'SYSTEM_HOWTOCOOK' or row['visibility'] != 'PUBLIC' or row['owner_user_id'] is not None):
                raise ValueError('拒绝用户自定义或私有数据')
            if name == 'recipe_source':
                key = row['source_key']
                if not isinstance(key, str) or key.startswith(('/', '\\')) or re.match(r'^[a-zA-Z]:', key) or '..' in key.split('/') or '\\' in key or row['source_uri'] is not None:
                    raise ValueError('来源含绝对路径或非清理元数据')
            if name == 'recipe_import_batch':
                if row['source_type'] != 'SYSTEM_HOWTOCOOK' or not re.fullmatch(r'[a-zA-Z0-9._-]{1,128}', row['source_version']):
                    raise ValueError('导入批次元数据不合法')
            if name == 'recipe_source_document' and (row['parse_warnings'] != [] or row['failure_message'] is not None):
                raise ValueError('拒绝导入失败诊断正文')
            if name == 'recipe_search_document':
                if row['failure_message'] is not None or row['embedding_dimension'] != 1024 or row['embedding_model'] != 'text-embedding-v4' or row['document_template_version'] != 1:
                    raise ValueError('向量配置或诊断不合法')
                if row['embedding'] is not None:
                    vector = json.loads(row['embedding'])
                    if not isinstance(vector, list) or len(vector) != 1024 or any(type(v) not in (float, int) or not math.isfinite(v) for v in vector):
                        raise ValueError('向量维度不合法')
                elif row['embedding_status'] == 'READY':
                    raise ValueError('READY缺少向量')
    ingredients = {row['id']: row for row in tables['ingredient']}
    for key, name in BINDINGS.items():
        row = ingredients.get(key, {})
        if row.get('canonical_name') != name or row.get('normalization_status') != 'REVIEWED':
            raise ValueError('12项Mock审定绑定缺失或漂移')
    return tables


def empty_check():
    # 在恢复事务内锁住所有当前业务表，拒绝非空库；不覆盖用户或现有菜谱。
    return """DO $$ DECLARE r record; n bigint; BEGIN
IF (SELECT count(*) FROM public.flyway_schema_history WHERE success) <> 15
 OR (SELECT max(version::integer) FROM public.flyway_schema_history WHERE success) <> 15
 THEN RAISE EXCEPTION 'target migration mismatch'; END IF;
FOR r IN SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename <> 'flyway_schema_history' ORDER BY tablename LOOP
 EXECUTE format('LOCK TABLE public.%I IN EXCLUSIVE MODE',r.tablename);
 EXECUTE format('SELECT count(*) FROM public.%I',r.tablename) INTO n;
 IF n <> 0 THEN RAISE EXCEPTION 'target is not empty'; END IF;
END LOOP; END $$;"""


def restore_sql(data):
    tables = validate(data)
    lines = ['BEGIN;', 'SET LOCAL standard_conforming_strings=on;', empty_check()]
    for name, columns in TABLES.items():
        value = json.dumps(tables[name], ensure_ascii=False, allow_nan=False, separators=(',', ':'))
        cols = ','.join(columns)
        lines.append('INSERT INTO public.' + name + '(' + cols + ') SELECT ' + cols
                     + ' FROM json_populate_recordset(NULL::public.' + name + ',' + literal(value) + '::json);')
        if 'id' in columns:
            # ALTER SEQUENCE事务化，不用非事务性的setval在失败回滚后留下序列副作用。
            next_id = max([r['id'] for r in tables[name]], default=0) + 1
            lines.append("DO $$ DECLARE seq text; BEGIN SELECT pg_get_serial_sequence('public." + name
                         + "','id') INTO seq; EXECUTE format('ALTER SEQUENCE %s RESTART WITH " + str(next_id) + "',seq); END $$;")
    lines += ['COMMIT;']
    return '\n'.join(lines)


def export(args):
    if args.file.exists():
        raise ValueError('拒绝覆盖已有导出文件')
    data = {'schemaVersion': 1, 'migrationVersion': 15, 'bindings': {str(k): v for k, v in BINDINGS.items()},
            'tables': json.loads(psql(args, export_sql()))}
    validate(data)
    # 目标需显式给出；不会读取本机其他文件或复制Flyway记录。
    with args.file.open('x', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, allow_nan=False)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description='系统菜谱白名单导出/恢复，操作授权须另行确认')
    p.add_argument('operation', choices=['export', 'restore', 'check'])
    p.add_argument('--container', required=True)
    p.add_argument('--database', required=True)
    p.add_argument('--role', required=True)
    p.add_argument('--file', required=True, type=Path)
    p.add_argument('--confirm-database', required=True)
    a = p.parse_args()
    try:
        if a.confirm_database != a.database:
            raise ValueError('目标确认不一致')
        if a.operation == 'export':
            export(a)
        else:
            if a.file.stat().st_size > 256 * 1024 * 1024:
                raise ValueError('数据包超过256MiB人工复核边界')
            data = strict_json(a.file.read_text(encoding='utf-8'))
            validate(data)
            if a.operation == 'restore':
                psql(a, restore_sql(data))
        print('菜谱白名单工具完成；未调用模型，未迁移用户表')
    except Exception:
        p.exit(1, '菜谱白名单操作拒绝或失败，未输出输入记录/数据库错误正文\n')
