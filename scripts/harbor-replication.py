#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把组件/中间件用到的镜像同步进平台 Harbor（项目 / 上游 endpoint / 复制规则一条龙）。

背景（细节见 docs/development/image-registry.md）：
  * 平台只改写镜像地址，镜像本身靠 Harbor 的复制规则搬进来；
  * 落点约定是 ``<harbor>/<上游主机>/<仓库>``，也就是「一个上游一个项目」，
    所以规则的 ``dest_namespace`` = 上游主机名，``dest_namespace_replace_count`` = 0
    （默认的 -1 会把源路径第一段替换掉）；
  * Harbor 的资源过滤器是 **AND** 语义：一条规则里放两个 name 过滤器 = 什么都匹配不到，
    因此本脚本**一个仓库一条规则**。

用法（在能访问 Harbor 的机器上跑，Python 3.8+，只用标准库）：
    export HARBOR_URL=http://192.168.31.80:30002
    export HARBOR_ADMIN_PASSWORD='...'          # 平台生成的，存在 Secret harbor/platform-harbor-secrets
    python3 scripts/harbor-replication.py --all
    python3 scripts/harbor-replication.py --dry-run            # 只看会做什么
    python3 scripts/harbor-replication.py --only quay.io --trigger
    python3 scripts/harbor-replication.py --status             # 看最近的执行结果

维护：新版本上线时改下面的 ``IMAGES`` 表（加 tag 或加一整行），重跑 ``--all`` 即可。
已存在的项目/endpoint/规则会被跳过或更新，不会重复创建。
"""

import argparse
import base64
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

# ---------------------------------------------------------------------------
# 需要维护的表：上游 → 仓库:tag。
# 不带 tag 会复制该仓库的**全部** tag（几百个、几十 GB），除非确实要全量，否则请写死 tag；
# 中间件允许创建新版本时（ES / Milvus），把新 tag 加一行再重跑。
# 镜像来自内置 chart 的 values + 中间件适配器里写死的常量；chart 升级后记得跟着改。
# ---------------------------------------------------------------------------
IMAGES = {
    'docker.io': [
        'bats/bats:1.14.0',
        'bitnamilegacy/rabbitmq-cluster-operator:2.16.1-debian-12-r0',
        'bitnamilegacy/rmq-default-credential-updater:1.0.8-debian-12-r0',
        'chrislusf/seaweedfs:4.48',
        'grafana/grafana:13.2.4-distroless',
        'grafana/tempo:3.1.0',
        'istio/install-cni:1.31.3',
        'istio/pilot:1.31.3',
        # proxyv2：istio-gateway 的 Deployment 写的是 `image: auto`，由 istiod 注入时
        # 按 global.hub 拼成 <hub>/proxyv2:<tag> —— 不搬这个，网关 Pod 拉不到镜像
        'istio/proxyv2:1.31.3',
        'istio/ztunnel:1.31.3',
        'library/busybox:latest',
        'library/rabbitmq:4.1.3',
        'milvusdb/milvus:v2.6.25',   # 中间件默认版本；建别的版本时把 tag 加到这里
        'milvusdb/milvus-operator:v1.3.12',
        'nacos/nacos-server:v3.2.4',
        'percona/percona-server:8.4',
        'percona/percona-server-mysql-operator:1.2.0',
        'percona/percona-xtradb-cluster-operator:1.14.1-haproxy',
        'prom/mysqld-exporter:v0.15.1',
    ],
    'quay.io': [
        # calico **不纳管**：它必须能先于镜像仓库可用（换 CNI 镜像得先有 CNI），固定走上游 quay.io。
        # 原因见 ChartImagePins 里那段注释；`quay.io` 项目下残留的 calico/* 仓库可以随手清掉。
        'jetstack/cert-manager-cainjector:v1.21.2',
        'jetstack/cert-manager-controller:v1.21.2',
        'jetstack/cert-manager-startupapicheck:v1.21.2',
        'jetstack/cert-manager-webhook:v1.21.2',
        'kiwigrid/k8s-sidecar:2.14.1',
        'opstree/redis:v7.0.15',
        'opstree/redis-exporter:v1.44.0',
        'opstree/redis-operator:v0.27.0',
        'opstree/redis-sentinel:v7.0.15',
        'prometheus-operator/prometheus-operator:v0.94.1',
        'prometheus/alertmanager:v0.34.1',
        'prometheus/node-exporter:v1.12.1-distroless',
        'prometheus/prometheus:v3.15.0-distroless',
        'prometheuscommunity/elasticsearch-exporter:v1.7.0',
        'strimzi/kafka:1.3.0-kafka-4.2.1',
        'strimzi/operator:1.3.0',
        'thanos/thanos:v0.42.4',
        'tigera/operator:v1.44.0',
    ],
    'ghcr.io': [
        'cloudnative-pg/cloudnative-pg:1.30.1',
        'jkroepke/kube-webhook-certgen:1.11.0',
        'open-telemetry/opentelemetry-operator/opentelemetry-operator:0.161.0',
        'tektoncd/operator/operator-303303c315a48490ba6517859ef65b77:v0.81.2',
        'tektoncd/operator/webhook-f2bb711aa8f0c0892856a4cbf6d9ddd8:v0.81.2',
    ],
    'registry.k8s.io': [
        'kube-state-metrics/kube-state-metrics:v2.20.0',
        'metrics-server/metrics-server:v0.9.0',
    ],
    'docker.elastic.co': [
        'eck/eck-operator:3.5.0',
        'elasticsearch/elasticsearch:9.5.0',   # 中间件默认版本；建别的版本时把 tag 加到这里
    ],
    # registry.istio.io 已移除：Istio 1.31.3 起 chart 的 hub 改成 docker.io/istio
    # （registry.istio.io 只有 1.30.x），内置 chart 里的 1.30.6 目录已无 recipe 引用。
    # 真要支持 1.30.6 再把这行加回来，并先给上游建 endpoint（Harbor 得能连上 registry.istio.io）。
}

# 上游 endpoint 的类型（Harbor 对 docker.io / quay / ghcr 有专门适配器）
REGISTRY_TYPES = {
    'docker.io': ('docker-hub', 'https://registry-1.docker.io'),
    'quay.io': ('quay', 'https://quay.io'),
    'ghcr.io': ('ghcr', 'https://ghcr.io'),
    'registry.k8s.io': ('docker-registry', 'https://registry.k8s.io'),
    'docker.elastic.co': ('docker-registry', 'https://docker.elastic.co'),
    'registry.istio.io': ('docker-registry', 'https://registry.istio.io'),
}

# Harbor 单条策略的过滤器上限（实测 20；一个 name + 19 个 tag 是安全的）
MAX_FILTERS = 20


class Harbor:
    def __init__(self, url, user, password, dry_run=False):
        self.url = url.rstrip('/')
        token = base64.b64encode(f'{user}:{password}'.encode()).decode()
        self.auth = 'Basic ' + token
        self.dry_run = dry_run

    def call(self, method, path, body=None, allow=(200, 201, 204)):
        if self.dry_run and method != 'GET':
            print(f'    [dry-run] {method} {path} {json.dumps(body, ensure_ascii=False) if body else ""}')
            return None
        data = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
        req = urllib.request.Request(self.url + path, data=data, method=method)
        req.add_header('Authorization', self.auth)
        if data is not None:
            req.add_header('Content-Type', 'application/json')
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                raw = resp.read().decode() or 'null'
                return json.loads(raw) if raw.strip() else None
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors='replace')
            if e.code in allow:
                return None
            raise RuntimeError(f'{method} {path} -> {e.code} {detail}') from None

    def paged(self, path):
        """翻页取全量。⚠️ Harbor 默认 page_size=10：列表接口不带分页就会只拿到前 10 条，
        于是「规则已存在」被判成「不存在」→ POST 409 CONFLICT（2026-10-29 撞到）。"""
        out, page = [], 1
        while True:
            sep = '&' if '?' in path else '?'
            chunk = self.call('GET', f'{path}{sep}page={page}&page_size=100') or []
            out.extend(chunk)
            if len(chunk) < 100:
                return out
            page += 1

    def policies(self):
        return self.paged('/api/v2.0/replication/policies')

    # -- 项目 ---------------------------------------------------------------
    def ensure_project(self, name):
        cur = self.call('GET', f'/api/v2.0/projects/{urllib.parse.quote(name)}', allow=(200, 404))
        if isinstance(cur, dict) and cur.get('project_id'):
            if (cur.get('metadata') or {}).get('public') != 'true':
                self.call('PUT', f'/api/v2.0/projects/{urllib.parse.quote(name)}',
                          {'metadata': {'public': 'true'}})
                return 'public'
            return 'ok'
        self.call('POST', '/api/v2.0/projects', {'project_name': name, 'public': True})
        return 'created'

    # -- 上游 endpoint ------------------------------------------------------
    def ensure_registry(self, upstream):
        kind, url = REGISTRY_TYPES[upstream]
        credential = credential_of(upstream)
        registries = self.call('GET', '/api/v2.0/registries') or []
        for r in registries:
            # 同名，或同一地址但早先手工起过别的名字（如 mirror-elastic）都算已有，免得出现两个
            if r.get('name') == endpoint_name(upstream)                     or (r.get('url') or '').rstrip('/') == url.rstrip('/'):
                if credential and not (r.get('credential') or {}).get('access_key'):
                    # 已存在的匿名 endpoint：补上凭据（Docker Hub 匿名限额按出口 IP，一打就满）
                    self.call('PUT', f'/api/v2.0/registries/{r["id"]}', {
                        'name': r['name'], 'type': r.get('type') or kind,
                        'url': r.get('url') or url, 'insecure': False,
                        'credential': credential,
                    })
                    return r['id'], 'credential-set'
                return r['id'], 'ok'

        body = {'name': endpoint_name(upstream), 'type': kind, 'url': url, 'insecure': False}
        if credential:
            body['credential'] = credential
        self.call('POST', '/api/v2.0/registries', body)
        if self.dry_run:
            return -1, 'created'
        registries = self.call('GET', '/api/v2.0/registries') or []
        for r in registries:
            if r.get('name') == endpoint_name(upstream):
                return r['id'], 'created'
        raise RuntimeError(f'endpoint 建完却查不到：{endpoint_name(upstream)}')

    # -- 复制规则 ------------------------------------------------------------
    def ensure_policies(self, registry_id, policies):
        existing = {p['name']: p for p in self.policies()}
        out = []
        for policy in policies:
            cur = existing.get(policy['name'])
            if cur is None:
                self.call('POST', '/api/v2.0/replication/policies', policy)
                out.append((policy['name'], 'created'))
                continue
            if same_policy(cur, policy):
                out.append((policy['name'], 'ok'))
                continue
            self.call('PUT', f'/api/v2.0/replication/policies/{cur["id"]}', policy)
            out.append((policy['name'], 'updated'))
        return out

    def trigger(self, name):
        for p in self.policies():
            if p['name'] == name:
                self.call('POST', '/api/v2.0/replication/executions', {'policy_id': p['id']})
                return True
        return False

    def trigger_one(self, name, wait=True, timeout=1800, interval=5):
        """触发一条规则；wait=True 时等它到终态再返回（配额紧张时分批跑用）。"""
        if not self.trigger(name):
            print(f'  {name}: 找不到规则，跳过')
            return
        if not wait:
            print(f'  {name}: 已触发')
            return
        executions = self.paged('/api/v2.0/replication/executions')
        mine = [e for e in executions if e['policy_id'] == self.policy_id(name)]
        if not mine:
            print(f'  {name}: 触发后查不到执行记录')
            return
        execution = max(mine, key=lambda e: e['id'])
        import time
        waited = 0
        while waited < timeout:
            current = self.call('GET', f'/api/v2.0/replication/executions/{execution["id"]}')
            status = (current or {}).get('status', '?')
            if status in ('Succeed', 'Failed', 'Stopped'):
                print(f'  {name}: {status} ok={current.get("succeed")} failed={current.get("failed")}')
                return
            time.sleep(interval)
            waited += interval
        print(f'  {name}: 等待超时（仍在 {status}）')

    def policy_id(self, name):
        for p in self.policies():
            if p['name'] == name:
                return p['id']
        return -1

    def recent_executions(self, limit=15):
        executions = self.paged('/api/v2.0/replication/executions')
        executions.sort(key=lambda e: e['id'], reverse=True)
        return executions[:limit]


def credential_of(upstream):
    """Docker Hub 的凭据来自环境变量（PAT 不进脚本、不进仓库）。"""
    user = os.environ.get('DOCKERHUB_USER', '').strip()
    token = os.environ.get('DOCKERHUB_PAT', '').strip()
    if upstream == 'docker.io' and user and token:
        return {'type': 'basic', 'access_key': user, 'access_secret': token}
    return None


def endpoint_name(upstream):
    return 'mirror-' + upstream.replace('.', '-').replace(':', '-')


def policy_name(upstream, repo, index):
    base = 'mirror-{}-{}'.format(upstream.replace('.', '-'), repo.replace('/', '-').replace('.', '-'))
    if index:
        base += f'-t{index}'
    return base[:60]


def build_policies(upstream):
    """一个仓库一条规则：把每个仓库的 tag 拆成不超过上限的几片。"""
    pinned = {}
    dynamic = []
    for item in IMAGES[upstream]:
        if ':' in item.rsplit('/', 1)[-1]:
            repo, tag = item.rsplit(':', 1)
            pinned.setdefault(repo, []).append(tag)
        else:
            dynamic.append(item)

    policies = []
    for repo, tags in sorted(pinned.items()):
        tags = sorted(set(tags))
        room = MAX_FILTERS - 1
        for i in range(0, len(tags), room):
            chunk = tags[i:i + room]
            policies.append(make_policy(upstream, repo,
                                        [{'type': 'tag', 'value': t} for t in chunk],
                                        index=0 if i == 0 else i // room + 1))
    for repo in sorted(set(dynamic)):
        # 版本在创建时才定：复制全部 tag
        policies.append(make_policy(upstream, repo, [{'type': 'tag', 'value': '*'}], index=0))
    return policies


def make_policy(upstream, repo, tag_filters, index):
    return {
        'name': policy_name(upstream, repo, index),
        'description': f'{upstream}/{repo} -> {upstream} 项目',
        'dest_namespace': upstream,
        'dest_namespace_replace_count': 0,   # 0 = 保留完整源路径；-1 会把第一段替换掉
        'trigger': {'type': 'manual', 'trigger_settings': {'cron': ''}},
        'filters': [{'type': 'name', 'value': repo}] + tag_filters,
        'enabled': True,
        'override': True,
        'speed': -1,
        # 分块复制：大镜像经代理/跨境链路传输时整块拉容易断，分块能重试续传
        # （2026-10-28 实测：percona-server / nacos / milvus 这类整块复制反复失败，开分块就过了）
        'copy_by_chunk': True,
    }


def same_policy(current, desired):
    def filters(p):
        return sorted((f['type'], str(f['value'])) for f in (p.get('filters') or []))
    return (current.get('dest_namespace') == desired['dest_namespace']
            and current.get('dest_namespace_replace_count') == 0
            and filters(current) == filters(desired)
            and bool(current.get('enabled')) == desired['enabled']
            and bool(current.get('override')) == desired['override']
            # copy_by_chunk 也要比：早先建的规则是 false，不比对就永远更新不到，
            # 大镜像（elasticsearch）重触发时会照样整块失败
            and bool(current.get('copy_by_chunk')) == desired['copy_by_chunk'])


def main():
    parser = argparse.ArgumentParser(description='把上游镜像同步进平台 Harbor')
    parser.add_argument('--all', action='store_true', help='项目 + endpoint + 规则')
    parser.add_argument('--ensure-projects', action='store_true')
    parser.add_argument('--ensure-registries', action='store_true')
    parser.add_argument('--ensure-policies', action='store_true')
    parser.add_argument('--trigger', action='store_true', help='建完规则后立刻触发一次复制')
    parser.add_argument('--sequential', action='store_true',
                        help='配合 --trigger：一条跑完再触发下一条（Docker Hub 限额紧张时用）')
    parser.add_argument('--status', action='store_true', help='只看最近的执行结果')
    parser.add_argument('--only', metavar='UPSTREAM', help='只处理某个上游，如 quay.io')
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()

    url = os.environ.get('HARBOR_URL', 'http://127.0.0.1:30002')
    user = os.environ.get('HARBOR_ADMIN_USER', 'admin')
    password = os.environ.get('HARBOR_ADMIN_PASSWORD')
    if not password and not args.status:
        sys.exit('缺少 HARBOR_ADMIN_PASSWORD（平台生成，见 Secret harbor/platform-harbor-secrets）')

    harbor = Harbor(url, user, password or '', args.dry_run)
    upstreams = [args.only] if args.only else sorted(IMAGES)
    for up in upstreams:
        if up not in IMAGES:
            sys.exit(f'未知上游：{up}（可选：{", ".join(sorted(IMAGES))}）')

    if args.status:
        print(f'{url} 最近的复制执行：')
        for e in harbor.recent_executions():
            print(f'  exec {e["id"]} policy {e["policy_id"]} {e["status"]} '
                  f'ok={e.get("succeed")} failed={e.get("failed")}')
        return

    do_all = args.all or not (args.ensure_projects or args.ensure_registries or args.ensure_policies)
    if do_all or args.ensure_projects:
        print('== 项目（公开读）==')
        for up in upstreams:
            print(f'  {up}: {harbor.ensure_project(up)}')

    for up in upstreams:
        registry_id = None
        if do_all or args.ensure_registries:
            print(f'== 上游 endpoint：{up} ==')
            try:
                registry_id, state = harbor.ensure_registry(up)
            except RuntimeError as e:
                # 最常见：Harbor 够不到上游（内网没代理）→ 建 endpoint 直接被拒。
                # 一个上游失败不该中断其余的，跳过并说清楚。
                print(f'  跳过 {up}：{e}')
                continue
            print(f'  {endpoint_name(up)}: {state} (id={registry_id})')
        if not (do_all or args.ensure_policies):
            continue
        if registry_id is None:
            registries = harbor.call('GET', '/api/v2.0/registries') or []
            kind, url = REGISTRY_TYPES[up]
            registry_id = next((r['id'] for r in registries
                                if (r.get('url') or '').rstrip('/') == url.rstrip('/')), None)
        if registry_id is None:
            print(f'  跳过 {up}：没有可用的 endpoint（多半是 Harbor 够不到上游，先配出网代理）')
            continue
        policies = build_policies(up)
        for p in policies:
            p['src_registry'] = {'id': registry_id}
        print(f'== 复制规则：{up}（{len(policies)} 条）==')
        for name, state in harbor.ensure_policies(registry_id, policies):
            print(f'  {name}: {state}')
        if args.trigger:
            if args.sequential:
                for p in policies:
                    harbor.trigger_one(p['name'], wait=True)
                print(f'  已串行跑完 {len(policies)} 条复制')
            else:
                for p in policies:
                    harbor.trigger(p['name'])
                print(f'  已触发 {len(policies)} 条复制')

    print('\n完成。核对：Harbor 的 Projects 里看各上游项目的仓库数，或用 --status 看执行结果。')


if __name__ == '__main__':
    main()
