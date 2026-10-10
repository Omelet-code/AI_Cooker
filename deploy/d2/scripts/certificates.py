"""证书检查、原子发布及过期保护。服务器操作须另批，本轮只用假证书/Mock。"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import uuid
from render import address

KEYS = {'project', 'release', 'envFile', 'host', 'certName', 'lineage', 'tlsDirectory', 'alertFile'}


def settings(path):
    data = json.loads(path.read_text(encoding='utf-8'))
    if set(data) != KEYS or not re.fullmatch(r'[a-z0-9][a-z0-9-]{2,50}', data['project']):
        raise ValueError('证书流程配置无效')
    address(data['host'])
    if not re.fullmatch(r'[a-z0-9-]{3,60}', data['certName']):
        raise ValueError('证书名称无效')
    for key in ['release', 'envFile', 'lineage', 'tlsDirectory', 'alertFile']:
        if not Path(data[key]).is_absolute():
            raise ValueError('证书流程路径必须为绝对路径')
    if Path(data['lineage']).name != data['certName']:
        raise ValueError('证书lineage名称不一致')
    return data


def command(argv):
    result = subprocess.run(argv, capture_output=True, timeout=30)
    if result.returncode:
        raise ValueError('证书或Nginx操作失败，未输出原错误正文')
    return result.stdout


def check(directory, host, seconds=0, ca_file=None):
    address(host)
    if type(seconds) is not int or not 0 <= seconds <= 172800:
        raise ValueError('证书检查时间无效')
    cert, key = directory / 'fullchain.pem', directory / 'privkey.pem'
    command(['openssl', 'x509', '-in', str(cert), '-noout', '-checkend', str(seconds)])
    cert_public = command(['openssl', 'x509', '-in', str(cert), '-pubkey', '-noout']).strip()
    key_public = command(['openssl', 'pkey', '-in', str(key), '-pubout']).strip()
    if not cert_public or cert_public != key_public:
        raise ValueError('证书与私钥不匹配')
    is_ip = ':' in host or re.fullmatch(r'[0-9.]+', host)
    trust = ['-CAfile', str(ca_file)] if ca_file else ['-CApath', '/etc/ssl/certs']
    command(['openssl', 'verify', '-purpose', 'sslserver', *trust, '-untrusted', str(cert),
             '-verify_ip' if is_ip else '-verify_hostname', host, str(cert)])


def compose(config):
    return ['docker', 'compose', '--project-directory', str(Path(config['release']) / 'deploy'),
            '--env-file', config['envFile'], '-p', config['project'],
            '-f', str(Path(config['release']) / 'deploy/compose.yaml')]


def web(config):
    ident = command(compose(config) + ['ps', '-aq', 'web']).decode().strip()
    if not ident:
        return None
    if not re.fullmatch(r'[a-f0-9]{12,64}', ident):
        raise ValueError('Web组件标识异常')
    project = command(['docker', 'inspect', '-f', '{{index .Config.Labels "com.docker.compose.project"}}', ident]).decode().strip()
    if project != config['project']:
        raise ValueError('Web组件项目标记不一致')
    return ident


def alert(config, event):
    # 只记录受限状态和入口，不记录秘密或OpenSSL正文；外部通知渠道部署时另接。
    record = {'at': datetime.now(timezone.utc).isoformat(), 'event': event, 'project': config['project'], 'host': config['host']}
    with Path(config['alertFile']).open('a', encoding='utf-8') as out:
        out.write(json.dumps(record, ensure_ascii=False) + '\n')


def publish(config, initial=False):
    check(Path(config['lineage']), config['host'], seconds=3600)
    if initial:
        ident = web(config)
        if ident and command(['docker', 'inspect', '-f', '{{.State.Running}}', ident]).decode().strip() != 'false':
            raise ValueError('离线发布要求Web尚未创建或已停止')
    if not initial and not web(config):
        raise ValueError('续期reload要求已存在批准项目Web')
    root = Path(config['tlsDirectory'])
    if root.is_symlink():
        raise ValueError('TLS根目录不得重定向')
    root.mkdir(parents=True, exist_ok=True)
    marker = root / '.cooker-managed-tls'
    if not marker.exists():
        if any(root.iterdir()):
            raise ValueError('拒绝接管非空TLS目录')
        marker.write_text(config['project'], encoding='utf-8')
    if marker.read_text(encoding='utf-8') != config['project']:
        raise ValueError('TLS目录项目标记不一致')
    current = root / 'current'
    if current.exists() and not current.is_symlink():
        raise ValueError('current必须为工具管理的链接')
    old = os.readlink(current) if current.is_symlink() else None
    if old is not None and not re.fullmatch(r'bundles/[a-f0-9]{32}', old.replace('\\', '/')):
        raise ValueError('拒绝非受控证书链接')
    revision = uuid.uuid4().hex
    bundle = root / 'bundles' / revision; bundle.mkdir(parents=True)
    for name, mode in [('fullchain.pem', 0o644), ('privkey.pem', 0o600)]:
        shutil.copyfile(Path(config['lineage']) / name, bundle / name)
        os.chmod(bundle / name, mode)
    pending = root / ('pending-' + revision)
    os.symlink('bundles/' + revision, pending)
    os.replace(pending, current)
    try:
        if not initial:
            command(compose(config) + ['exec', '-T', 'web', 'nginx', '-t'])
            command(compose(config) + ['exec', '-T', 'web', 'nginx', '-s', 'reload'])
    except Exception:
        if old is None:
            current.unlink()
        else:
            os.symlink(old, pending); os.replace(pending, current)
        alert(config, 'CERT_RELOAD_FAILED')
        raise


def guard(config):
    directory = Path(config['tlsDirectory']) / 'current'
    try:
        check(directory, config['host'], seconds=0)
    except Exception:
        try:
            alert(config, 'CERT_INVALID_OR_EXPIRED')
        finally:
            if web(config):
                # 即使本地告警写入失败也执行过期保护；只停止指定Web。
                command(compose(config) + ['stop', 'web'])
        raise
    try:
        check(directory, config['host'], seconds=86400)
    except Exception:
        alert(config, 'CERT_EXPIRES_WITHIN_24H')
        raise


if __name__ == '__main__':
    p = argparse.ArgumentParser(description='批准后在Ubuntu执行的证书流程；不申请证书')
    p.add_argument('operation', choices=['check', 'publish', 'guard'])
    p.add_argument('--config', required=True, type=Path)
    p.add_argument('--initial', action='store_true')
    a = p.parse_args()
    try:
        config = settings(a.config)
        if os.name != 'posix':
            raise ValueError('部署证书操作仅适用于已批准的Linux环境')
        import fcntl
        lock = a.config.with_suffix('.lock').open('a')
        fcntl.flock(lock, fcntl.LOCK_EX)
        if a.operation == 'check':
            check(Path(config['lineage']), config['host'], seconds=3600)
        elif a.operation == 'publish':
            publish(config, a.initial)
        else:
            guard(config)
        print('证书流程步骤通过')
    except Exception:
        p.exit(1, '证书流程失败：保持安全边界，检查受限告警和服务状态\n')
