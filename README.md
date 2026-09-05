# plugin-goo-hub 咕咕星系（联邦中心）

Halo 联邦中心插件：站点注册表 → 全网内容聚合 → 统一页面 → 用户订阅。仅装在咕咕总站。

消费 plugin-goo 的公开地基（`federation/capabilities`、`timelines/local?since=`、`feed.atom`），不改 plugin-goo 公开层。

## API

- `POST /apis/hub.api.goo.themenets.com/v1alpha1/federation/register` — 站点自助登记（服务端拉对端 capabilities 自动验证）
- `GET /apis/hub.api.goo.themenets.com/v1alpha1/federation/sites` — 公开站点目录

## 阶段

- 阶段一（本版）：插件骨架 + FedSite 注册表 + 登记/目录端点
- 阶段二：聚合器（逐站 since 增量入库 FedItem + 墓碑失效）
- 阶段三：统一页面（站点目录 Tab + 全网时间线 Tab）
- 阶段四：用户订阅（关注清单 → 时间线过滤）

License: GPL-3.0
