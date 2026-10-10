"""显式双树白名单装配；不读取.env、Git内容、账本、备份或缓存。"""
import argparse
import json
import os
from pathlib import Path
import shutil

BACKEND = Path('C:/Users/Omelet/.codex/worktrees/5b09/Cooker')
FRONTEND = Path('C:/Users/Omelet/.codex/worktrees/0f47/Cooker')
FORBIDDEN = {'.git', '.data', '.env', 'node_modules', 'target', 'maven-repository', '__pycache__'}


def safe_file(root, path):
    rel = path.relative_to(root)
    if any(p in FORBIDDEN or p.startswith('.env') for p in rel.parts):
        raise ValueError('白名单内发现禁止文件')
    for part in [path, *path.parents]:
        if part == root.parent:
            break
        if part.is_symlink() or getattr(part, 'is_junction', lambda: False)():
            raise ValueError('拒绝链接或目录重定向')
    if not path.is_file():
        raise ValueError('缺少关键发布文件')
    return path


def walk(root, prefix, suffixes):
    base = root / prefix
    if not base.is_dir():
        raise ValueError('缺少发布源码目录')
    found = []
    for current, dirs, files in os.walk(base, followlinks=False):
        for d in dirs:
            p = Path(current) / d
            if p.is_symlink() or getattr(p, 'is_junction', lambda: False)():
                raise ValueError('拒绝源码链接')
        for name in files:
            p = Path(current) / name
            if p.suffix in suffixes:
                found.append(safe_file(root, p))
    return found


def assemble(backend, frontend, output, expected_backend=BACKEND, expected_frontend=FRONTEND):
    for candidate in [output, *output.parents]:
        if candidate.is_symlink() or getattr(candidate, 'is_junction', lambda: False)():
            raise ValueError('拒绝输出目录重定向')
    if backend.resolve() != expected_backend.resolve() or frontend.resolve() != expected_frontend.resolve():
        raise ValueError('发布来源必须为批准的5b09后端及0f47前端')
    if backend.resolve() == frontend.resolve():
        raise ValueError('禁止把后端树当作前端来源')
    if any(output.resolve().is_relative_to(x.resolve()) for x in [backend / 'backend', frontend]):
        raise ValueError('发布目录不得覆盖来源源码')
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        raise ValueError('拒绝已有非空输出目录')
    pairs = []
    required = ['backend/pom.xml', 'backend/.mvn/maven.config', 'backend/.mvn/settings.xml',
                'backend/src/main/java/com/cooker/CookerApplication.java',
                'backend/src/main/resources/application.yml',
                'backend/src/main/resources/recipe/meal-role-v1.properties',
                'backend/src/main/resources/recipe/meal-role-v1-exceptions.tsv',
                'backend/src/main/resources/db/migration/V15__create_recipe_repair_audit.sql']
    for rel in required:
        safe_file(backend, backend / rel)
    main = walk(backend, 'backend/src/main/java', {'.java'})
    resources = walk(backend, 'backend/src/main/resources', {'.xml', '.sql', '.tsv'})
    configs = [safe_file(backend, backend / ('backend/src/main/resources/' + n))
               for n in ['application.yml', 'application-docker.yml']]
    for p in main + resources + configs + [backend / x for x in required[:3]] + [backend / 'backend/src/main/resources/recipe/meal-role-v1.properties']:
        pairs.append((safe_file(backend, p), p.relative_to(backend)))
    for n in ['package.json', 'package-lock.json', 'index.html', 'tsconfig.json', 'tsconfig.app.json', 'tsconfig.node.json', 'vite.config.ts']:
        p = safe_file(frontend, frontend / 'frontend' / n)
        pairs.append((p, Path('frontend') / n))
    for p in walk(frontend, 'frontend/src', {'.vue', '.ts', '.css', '.svg', '.png', '.jpg', '.webp', '.json'}):
        if not p.name.endswith(('.test.ts', '.spec.ts')):
            pairs.append((p, p.relative_to(frontend)))
    if (frontend / 'frontend/public').exists():
        for p in walk(frontend, 'frontend/public', {'.svg', '.png', '.jpg', '.webp', '.ico', '.txt'}):
            pairs.append((p, p.relative_to(frontend)))
    deploy = backend / 'deploy/d2'
    for sub, suffixes in [('backend', {'.Dockerfile'}), ('frontend', {'.Dockerfile'}), ('postgres', {'.Dockerfile'})]:
        p = safe_file(backend, deploy / sub / 'Dockerfile')
        pairs.append((p, Path('deploy') / sub / 'Dockerfile'))
    for p in walk(backend, 'deploy/d2/nginx', {'.template'}):
        pairs.append((p, Path('deploy/nginx') / p.name))
    for p in walk(backend, 'deploy/d2/scripts', {'.py'}):
        pairs.append((p, Path('deploy/scripts') / p.name))
    for rel in ['compose.yaml', 'compose.bootstrap.yaml', '.env.example', 'images.lock.example.json', '部署手册.md', 'IP部署与续期.md', 'config/mock-catalog.yml', 'config/cert-flow.example.json']:
        # 仅这个无秘密示例允许作为.env形状文件；不读取真实.env。
        p = deploy / rel
        if rel != '.env.example':
            safe_file(backend, p)
        elif not p.is_file() or p.is_symlink():
            raise ValueError('环境示例缺失或链接')
        pairs.append((p, Path('deploy') / rel))
    for p in walk(backend, 'deploy/d2/systemd', {'.service', '.timer'}):
        pairs.append((p, Path('deploy/systemd') / p.name))
    pairs.append((safe_file(backend, deploy / '.dockerignore'), Path('.dockerignore')))
    output.mkdir(parents=True, exist_ok=True)
    entries = []
    for source, rel in pairs:
        dest = output / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, dest)
        entries.append(str(rel).replace('\\', '/'))
    manifest = {'schemaVersion': 1, 'backendSource': str(backend.resolve()),
                'frontendSource': str(frontend.resolve()), 'files': sorted(set(entries)),
                'secretsIncluded': False, 'dataExported': False}
    (output / 'release-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='双树白名单发布装配，不构建或部署')
    parser.add_argument('--backend', type=Path, required=True)
    parser.add_argument('--frontend', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    a = parser.parse_args()
    try:
        result = assemble(a.backend, a.frontend, a.output)
        print('发布白名单装配完成，文件数：', len(result['files']))
    except (ValueError, OSError):
        parser.exit(1, '发布装配失败：来源、白名单或输出目录不满足要求\n')
