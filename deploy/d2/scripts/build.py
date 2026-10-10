"""仅在装配目录构建独立镜像；依赖摘要必须事先由可信来源核对。"""
import argparse
import json
import os
import signal
from pathlib import Path
import re
import subprocess

ALLOWED = {'MAVEN_IMAGE': 'maven', 'JRE_IMAGE': 'eclipse-temurin', 'NODE_IMAGE': 'node',
           'NGINX_IMAGE': 'nginx', 'PGVECTOR_IMAGE': 'pgvector/pgvector'}


def locked(values, keys):
    for key in keys:
        if not re.fullmatch(re.escape(ALLOWED[key]) + r'(?::[a-zA-Z0-9._-]+)?@sha256:[a-f0-9]{64}', values.get(key, '')):
            raise ValueError('构建基础镜像必须为批准仓库的固定摘要')


def build(release, lock, component, tag):
    manifest = json.loads((release / 'release-manifest.json').read_text(encoding='utf-8'))
    if manifest.get('schemaVersion') != 1 or manifest.get('secretsIncluded') is not False:
        raise ValueError('缺少批准装配清单')
    if not re.fullmatch(r'cooker-d2[a-z0-9-]*:[a-z0-9._-]+', tag):
        raise ValueError('仅允许独立D2镜像标签')
    keys = {'backend': ['MAVEN_IMAGE', 'JRE_IMAGE'], 'frontend': ['NODE_IMAGE', 'NGINX_IMAGE'],
            'postgres': ['PGVECTOR_IMAGE']}[component]
    locked(lock, keys)
    if component == 'postgres':
        import hashlib
        for filename, key in [('scws-1.2.3.tar.bz2', 'SCWS_SHA256'), ('zhparser-2.3.tar.gz', 'ZHPARSER_SHA256')]:
            artifact = release / 'deploy/vendor' / filename
            digest = lock.get(key, '')
            if not re.fullmatch('[a-f0-9]{64}', digest) or not artifact.is_file() or artifact.is_symlink() or hashlib.sha256(artifact.read_bytes()).hexdigest() != digest:
                raise ValueError('扩展归档缺失或与核验摘要不一致')
            keys.append(key)
    cmd = ['docker', 'build', '--pull=false', '-f', str(release / 'deploy' / component / 'Dockerfile'), '-t', tag]
    for key in keys:
        cmd.extend(['--build-arg', key + '=' + lock[key]])
    options = {'creationflags': subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == 'nt' else {'start_new_session': True}
    process = subprocess.Popen(cmd + [str(release)], **options)
    try:
        code = process.wait(timeout=900)
    except subprocess.TimeoutExpired:
        # 精确结束本工具创建的进程树，避免客户端退出后buildx继续。
        if os.name == 'nt':
            subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'], capture_output=True)
        else:
            os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=15)
        raise ValueError('独立构建超过有界时间，已结束本轮进程树')
    if code:
        raise ValueError('隔离镜像构建失败')


if __name__ == '__main__':
    p = argparse.ArgumentParser(description='构建独立D2镜像，不启动服务')
    p.add_argument('--release', type=Path, required=True)
    p.add_argument('--lock', type=Path, required=True)
    p.add_argument('--component', choices=['backend', 'frontend', 'postgres'], required=True)
    p.add_argument('--tag', required=True)
    a = p.parse_args()
    try:
        build(a.release.resolve(), json.loads(a.lock.read_text(encoding='utf-8')), a.component, a.tag)
    except Exception:
        p.exit(1, '独立构建被拒绝或失败；未部署\n')
