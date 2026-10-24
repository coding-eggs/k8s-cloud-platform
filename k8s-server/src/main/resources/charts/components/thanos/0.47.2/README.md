# thanos chart 0.47.2（app v0.42.4）

- 来源：`https://github.com/thanos-community/helm-charts`（chart `thanos` 0.47.2 / app v0.42.4）
- 拉取日期：2026-10-17；原样入库
  - 上游 Thanos 项目**不发布官方 Helm chart**，使用社区 chart（2026-10-17 用户裁定）
- 镜像：`quay.io/thanos/thanos:v0.42.4`
- 本版本注意：
  - receive 的 remote-write 端口是 **10908**（`--remote-write.address=0.0.0.0:10908`）；10902 是 HTTP/metrics，
    19291 是旧默认值，两个都不对；
  - `global.thanosRules.enabled` 默认 true，会下发 `thanos-component-absent` 规则；
    不开 ServiceMonitor 时四条规则恒 firing —— 平台把 `global.serviceMonitor.enabled` 打开来消除；
  - compactor 保留期有下限（raw ≥ 40h、5m ≥ 10d），违反时 helm 成功但 compactor CrashLoopBackOff ——
    平台在下发前校验（见组件级 README）；
  - 对象存储走 `global.objstore.createSecret=false` + 平台生成的 Secret `platform-thanos-objstore`。

组件级约定、坑与加版本流程见上一级 `../README.md`。
