# Harbor 1.19.4（app 2.15.4）

- 来源：`https://helm.goharbor.io`，`helm pull harbor --version 1.19.4`
- chart 版本 1.19.4 / 应用版本 2.15.4（镜像 tag 均为 `v2.15.4`）
- 本版本注意：
  - `expose.type=route` 走 Gateway API HTTPRoute（`gateway.networking.k8s.io/v1`），
    不部署 nginx；backend 是同命名空间内的 `harbor-core` / `harbor-portal` Service。
  - `externalURL` 决定 token 服务返回给 docker 客户端的地址，必须与 route host 一致。
  - `existingSecretSecretKey` 期望的键名是 **`secretKey`**（不是 HARBOR_SECRET_KEY）；
    长度必须 16 字符。
  - chart 内的 database / redis 是普通模板（不是子 chart）。
