"""只读取显式指定的部署env，校验后Compose渲染；不搜索开发.env。"""
import argparse
import ipaddress
import os
from pathlib import Path
import re
import subprocess

REQUIRED = ['RELEASE_PROJECT', 'POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD', 'COOKER_JWT_SECRET',
            'LLM_CREDENTIAL_MASTER_KEY', 'DOMAIN', 'PUBLIC_ORIGIN', 'TLS_DIRECTORY', 'ACME_WEBROOT',
            'POSTGRES_IMAGE', 'BACKEND_IMAGE', 'WEB_IMAGE']

LIMITS = {'REGISTER_RATE': '3r/m', 'LOGIN_RATE': '10r/m', 'MODEL_RATE': '6r/m',
          'REGISTER_BURST': '2', 'LOGIN_BURST': '5', 'MODEL_BURST': '2',
          'REGISTER_CONCURRENCY': '1', 'LOGIN_CONCURRENCY': '2',
          'MODEL_IP_CONCURRENCY': '1', 'MODEL_GLOBAL_CONCURRENCY': '2'}


def address(host):
    if not isinstance(host, str) or not host or '%' in host:
        raise ValueError('入口地址无效')
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        if ':' in host or re.fullmatch(r'[0-9.]+', host):
            raise ValueError('IP地址无效')
        labels = host.split('.')
        if len(host) > 253 or len(labels) < 2 or any(not re.fullmatch(r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?', s) for s in labels):
            raise ValueError('域名无效')
        return host, 'https://' + host
    if not ip.is_global or ip.is_multicast or ip.is_unspecified or getattr(ip, 'ipv4_mapped', None):
        raise ValueError('必须为全局单播IP地址')
    normalized = str(ip)
    if normalized != host:
        raise ValueError('IP必须使用规范形式')
    return normalized, 'https://' + ('[' + normalized + ']' if ip.version == 6 else normalized)


def limits(values):
    for key, default in LIMITS.items():
        value = values.get(key, default)
        if key.endswith('_RATE'):
            match = re.fullmatch(r'([1-9][0-9]{0,2})r/([ms])', value)
            if not match or int(match[1]) > (600 if match[2] == 'm' else 60):
                raise ValueError('限流速率无效')
        elif not re.fullmatch(r'[1-9][0-9]?', value) or int(value) > (30 if key.endswith('_BURST') else 8):
            raise ValueError('限流突发量或并发无效')


def kitchen(values):
    enabled = values.get('COOKER_AGENT_EXECUTION_ENABLED', 'false')
    key = values.get('DASHSCOPE_API_KEY', '')
    if enabled not in {'true', 'false'}:
        raise ValueError('厨房开关仅接受true或false')
    if enabled == 'true':
        # 仅检查格式与明显占位，不请求供应商，也不宣称Key真实有效。
        placeholders = ('change', 'placeholder', 'replace', 'your', 'example', 'fake', 'dummy', 'test')
        if not re.fullmatch(r'sk-[A-Za-z0-9_-]{20,200}', key) or len(set(key[3:])) < 2 or any(x in key.lower() for x in placeholders):
            raise ValueError('启用厨房调用必须提供非占位的百炼Key')
    return {'COOKER_AGENT_EXECUTION_ENABLED': enabled, 'DASHSCOPE_API_KEY': key}


def compose_environment(values):
    # 不读取继承环境中的厨房Key；显式文件及安全默认值覆盖shell优先级。
    controlled = set(REQUIRED) | set(LIMITS) | {'COOKER_AGENT_EXECUTION_ENABLED', 'DASHSCOPE_API_KEY'}
    env = {key: os.environ[key] for key in os.environ if key not in controlled}
    env.update(LIMITS)
    env.update(values)
    env.update(kitchen(values))
    return env


def validate(values):
    kitchen(values)
    if any(not values.get(k) or values[k].lower() in {'change-me', 'changeme'} for k in REQUIRED):
        raise ValueError('必需部署参数缺失')
    for key in ['POSTGRES_IMAGE', 'BACKEND_IMAGE', 'WEB_IMAGE']:
        if not re.fullmatch(r'(?:[a-zA-Z0-9./:_-]+@sha256:|sha256:)[a-f0-9]{64}', values[key]):
            raise ValueError('必须使用仓库摘要或经核对的本地image ID')
    if not re.fullmatch(r'[a-z0-9][a-z0-9-]{2,50}', values['RELEASE_PROJECT']):
        raise ValueError('项目名无效')
    for key in ['POSTGRES_DB', 'POSTGRES_USER']:
        if not re.fullmatch(r'[a-z][a-z0-9_]{0,62}', values[key]):
            raise ValueError('数据库标识无效')
    _, origin = address(values['DOMAIN'])
    if values['PUBLIC_ORIGIN'] != origin:
        raise ValueError('HTTPS源与入口地址不一致')
    limits(values)
    import base64
    if len(values['COOKER_JWT_SECRET']) < 32 or len(base64.b64decode(values['LLM_CREDENTIAL_MASTER_KEY'], validate=True)) != 32:
        raise ValueError('JWT或凭证加密密钥形状错误')
    if any(not Path(values[key]).is_absolute() for key in ['TLS_DIRECTORY', 'ACME_WEBROOT']):
        raise ValueError('证书和挑战目录必须为绝对路径')


def read_env(path):
    values = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        key, sep, value = line.partition('=')
        if not sep or key in values:
            raise ValueError('env键重复或格式错误')
        if not re.fullmatch(r'[A-Z][A-Z0-9_]*', key) or '$' in value or value.startswith(('\"', "'")):
            raise ValueError('env不允许插值或带引号值')
        values[key] = value
    validate(values)
    return values


if __name__ == '__main__':
    p = argparse.ArgumentParser(description='检查并渲染显式部署参数；建议--quiet避免秘密进入终端')
    p.add_argument('--env-file', required=True, type=Path)
    p.add_argument('--compose', required=True, type=Path)
    a = p.parse_args()
    try:
        values = read_env(a.env_file)
        env = compose_environment(values)
        result = subprocess.run(['docker', 'compose', '--project-directory', str(a.compose.parent),
            '--env-file', str(a.env_file), '-f', str(a.compose), 'config', '--quiet'], env=env, capture_output=True)
        if result.returncode:
            raise ValueError('Compose配置检查失败')
        print('部署参数和Compose渲染检查通过；未启动服务')
    except Exception:
        p.exit(1, '部署参数检查失败，未打印任何参数值\n')
