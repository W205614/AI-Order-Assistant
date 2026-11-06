# 试运营服务接入指南

本项目保留默认本地模拟模式，并提供微信支付服务商合单、阿里云短信与 OSS、高德地图，以及外部 MySQL/Redis 的接入入口。本文约定的是这些具体服务及协议；更换服务商、增加平台抽佣或改变资金模式属于新的开发范围。

当前适配代码与本地验证已完成，默认使用本地模式。实现、验证与日常部署结果见[部署收尾](verification/2026-11-06-production-closeout.md)；各项服务的配置和实联步骤列在下文。

## 1. 配置和启动约定

先按 README 准备根目录的私有 `.env`，再把 [接入配置模板](../deploy/integrations.env.example) 中需要的条目合并进去。不要直接用模板空值启动生产服务。根 Compose 仍会解析默认本地服务使用的变量，云部署也须保留原模板中的非空本地占位配置；这些本地服务不会在云模式启动。

常见模式：

| 模式 | Compose 文件顺序 | 作用 |
|---|---|---|
| 本地 | `docker-compose.yml` | 本地数据库、缓存和默认模拟支付 |
| 公网本地数据 | 根文件、`deploy/compose/public.yml` | Caddy HTTPS，关闭网关直接端口 |
| 公网云数据 | 根文件、`deploy/compose/cloud.yml`、`deploy/compose/public.yml` | 外部 MySQL/Redis，保留一次性迁移 |
| 加告警投递 | 在对应组合末尾追加 `deploy/compose/operations.yml` | Alertmanager 与 SMTP/Webhook |

公网配置关闭演示种子。`PAYMENT_MODE=WECHAT_PARTNER` 时须完整配置微信身份和签名材料；外部调用失败不能回退为模拟支付成功。默认关闭短信和地图时，对应请求明确返回不可用；菜单、购物车和允许的本地业务仍按自身契约运行。

支付私钥、公钥放在本机忽略目录 `.local/integration-secrets/`，通过只读目录挂载到 `/run/integration-secrets/`。保证 Java 容器用户能读取指定文件，不要将目录或密钥提交 Git。短信和 OSS 还支持同名 `*_FILE` 配置，从挂载文件读取对应值；文件路径必须是容器可读路径。宿主机检查时须使用宿主机可读的路径。

先执行不发送第三方请求的检查：

```powershell
python scripts/check-integrations.py --env-file .env
python scripts/check-cloud-compose.py
# 正式公网/云配置再检查生产要求
python scripts/check-integrations.py --env-file .env --production --cloud
```

预检查核对配置和文件；`--live --base-url https://实际域名`还读取应用健康及能力接口。支付、短信、存储等业务操作使用后文对应的验收命令。

## 2. 微信支付：各商户独立收款

资金模式固定为服务商合单：平台是合单发起方，每家店对应已授权的子商户，资金按渠道规则进入对应商户。本轮不抽佣，不实现平台代收后手工打款。平台券属于参与商户承担折扣的联合活动，商户须明确参与；报表展示的是商户承担金额，不是平台真实资金补贴。

### 2.1 申请与控制台设置

1. 准备符合微信要求的服务商身份和商户，申请 Native/H5/JSAPI 及相应合单能力；让所有参与店铺完成对应授权。在微信商户平台完成资质、签约和产品审核。
2. 准备服务商绑定的 AppID；微信内 JSAPI 使用公众号身份获取同一 AppID 下的 OpenID。完成公众号网页授权域名和支付产品要求的域名配置。
3. 提供可从公网访问的 HTTPS 域名。回调地址为 `https://实际域名/callbacks/wechatpay/payment`、`https://实际域名/callbacks/wechatpay/refund`；不能加网页登录拦截，也不要手动绕过接口自身的验签。
4. OAuth 回调为 `https://实际域名/integrations/wechat/oauth/callback`。用户从 `/integrations/wechat/oauth/start?returnTo=/campus/` 发起授权，服务端验证一次性状态后关联当前登录身份。

官方资料：[合单接入准备](https://pay.wechatpay.cn/doc/v3/partner/4013461849)、[Native 合单](https://pay.wechatpay.cn/doc/v3/partner/4012079333)、[H5 合单](https://pay.wechatpay.cn/doc/v3/partner/4013462080)、[JSAPI 合单](https://pay.wechatpay.cn/doc/v3/partner/4012757938)、[官方 Java SDK](https://github.com/wechatpay-apiv3/wechatpay-java)。具体产品权限以开通时的官方规则为准。

### 2.2 配置

| 配置 | 内容 |
|---|---|
| `PAYMENT_MODE` | 正式渠道为 `WECHAT_PARTNER`；默认 `SIMULATED` |
| `WECHAT_MCH_ID` | 合单服务商商户号 |
| `WECHAT_APP_ID`、`WECHAT_APP_SECRET` | 同一公众号的 AppID 和 OAuth Secret |
| `WECHAT_PRIVATE_KEY_PATH`、`WECHAT_CERTIFICATE_SERIAL` | 商户 API 私钥文件和商户证书序列号 |
| `WECHAT_PUBLIC_KEY_ID`、`WECHAT_PUBLIC_KEY_PATH` | 微信支付公钥 ID 和公钥文件，用于渠道验签 |
| `WECHAT_API_V3_KEY` | 32 字节 API v3 密钥，用于回调解密 |
| `WECHAT_NOTIFY_BASE_URL` | 真实公网 HTTPS 源站地址，无回调路径后缀 |

`WECHAT_APP_SECRET`、`WECHAT_API_V3_KEY` 也可由对应 `*_FILE` 指向只读挂载文件提供。

平台管理员通过 `PUT /platform/merchant-payments/{merchantId}` 配置 `{ "subMchId": "渠道子商户号", "enabled": true }`。配置必须对应本店真实收款身份；把普通商户号填进去不会自动获得合单权限。商户通过 `PUT /admin/platform-coupons/{campaignId}/participation` 确认是否参与平台联合优惠。

### 2.3 真实验收

- 用最小业务金额分别完成电脑扫码、微信外 H5、微信内 JSAPI。支付结果取自服务端验签回调或渠道查询，浏览器返回后重新查询支付状态。
- 逐店核对渠道子单、实付、优惠分摊、库存、取餐码和商家待办。重复刷新、重试和重复回调不能增加收款记录。
- 验证未付款关闭、支付与关闭竞争、渠道响应超时及服务重启；结果未知时查看支付后台并查单，不能手工改为已付。
- 覆盖制作前取消、部分售后、重复退款请求和退款失败。批准售后后先显示退款处理中，确认渠道结果后再显示成功。
- 对已出账日期运行渠道账单对账，核对差异和处理记录。真实账号未完成这些步骤前，保持“生产实联待验证”。

零元结算不发起渠道扣款。历史 `SIMULATED_*` 记录保留其演示性质，不能迁移成真实资金记录。

## 3. 短信、绑定和找回密码

使用阿里云短信 `SendSms`，需要对应账号、已审核签名和模板，以及有权限的 RAM 身份。验证码和业务通知模板分别配置。接入说明见[阿里云官方 Java SDK](https://help.aliyun.com/zh/sms/developer-reference/using-java-openapi-example)。

| 配置 | 内容 |
|---|---|
| `SMS_PROVIDER` | `aliyun` 启用；`disabled` 关闭 |
| `SMS_REGION_ID` | 短信服务区域，默认 `cn-hangzhou` |
| `SMS_ACCESS_KEY_ID`、`SMS_ACCESS_KEY_SECRET` | 短信发送身份；可用对应 `*_FILE` |
| `SMS_SIGN_NAME` | 已审核签名 |
| `SMS_OTP_TEMPLATE_CODE` | 已审核验证码模板；兼容 `SMS_TEMPLATE_CODE` |

业务通知按事件配置 `SMS_ORDER_TEMPLATE_CODE`、`SMS_READY_TEMPLATE_CODE`、`SMS_AFTERSALE_TEMPLATE_CODE`、`SMS_EXCEPTION_TEMPLATE_CODE`，模板变量为 `${resource}`（业务单号）。未配置模板时不创建该类短信任务；未绑定验证手机号则记为跳过。平台可查看 `/platform/notification-tasks`，结果未知时先查询渠道状态再决定重试；BizId记录受理请求，终端送达另查渠道结果。站内通知入口为 `/notifications`，支持已读和 SSE。

手机号绑定必须同时验证当前账号身份和验证码，收货地址电话不会自动成为可找回身份。顾客和管理账号使用各自账号类别；找回密码后旧会话失效。验证码五分钟有效、最多五次核验，并限制发送间隔和频率。生产接口不返回明文验证码，也不写入日志。

接入后用自己控制的手机号验证绑定、错误码、过期码、重放、限流、密码找回及旧会话失效。供应商受理后继续核对短信发送状态和终端收码结果。`disabled` 或渠道异常时明确报错，不发送假验证码。

## 4. 图片与 OSS

使用私有 Bucket。媒体由应用验证业务身份及资源归属后提供，不把 AccessKey 或永久签名链接返回给浏览器。菜品/店铺媒体与售后凭证按各自授权访问，商户只能关联本店资源。

| 配置 | 内容 |
|---|---|
| `STORAGE_PROVIDER` | `local` 或 `oss` |
| `STORAGE_LOCAL_DIRECTORY` | 本地模式的持久化媒体目录，容器默认 `/app/media` |
| `OSS_ENDPOINT`、`OSS_REGION`、`OSS_BUCKET` | 同一区域的 HTTPS Endpoint、Region 与私有 Bucket |
| `OSS_ACCESS_KEY_ID`、`OSS_ACCESS_KEY_SECRET` | 仅限媒体 Bucket/前缀的 RAM 身份，可用对应 `*_FILE` |

使用[官方 OSS Java SDK](https://help.aliyun.com/zh/oss/developer-reference/oss-sdk-for-java-2-0)。配置最小的对象读写权限，不向媒体身份授予账号级管理权限。媒体和备份建议使用不同 Bucket 及不同 RAM 身份。

上传入口 `POST /media` 使用 multipart，字段为图片、用途和资源标识；用途为 `DISH`、`MERCHANT` 或 `AFTERSALE`。读取为 `GET /media/{id}`。单图上限 5 MiB，真实格式校验不依赖扩展名。验收需覆盖普通图片、伪造扩展名、超限文件、跨店上传、他人售后凭证，以及容器重建后的媒体读取。

切换默认存储实现只影响新上传对象，不自动搬迁旧文件。历史本地媒体仍按记录从原媒体卷读取，必须保留该卷；正式迁移旧对象时另行复制、核对并更新记录，不能直接移除原卷。

## 5. 高德地址与路线

申请高德 Web 服务 Key，启用所需地理编码及骑行路线权限，根据服务配置限制来源和配额。仅在后端配置 `AMAP_API_KEY`（或 `AMAP_API_KEY_FILE`），不要发给页面。后端使用官方 HTTPS API，测试环境的受控 HTTP 服务不属于生产 Endpoint。

- `/maps/geocode` 解析地址并返回候选位置，由用户确认后保存。
- `/maps/route` 查询起终点的骑行路线；骑手页面可进一步打开地图导航。
- 配送资格按校区及商户服务规则判断，地址坐标和路线用于定位、导航及时间估算。

验收模糊地址、多候选、无结果、限额、Key 无权限及网络失败。渠道不可用时页面提示原因，不编造坐标或路线。[官方路线接口](https://developer.amap.com/api/webservice/guide/api/newroute)。

## 6. 外部 MySQL 和 Redis

先在云平台创建 MySQL 兼容实例、Redis 兼容实例、数据库及账号，并把应用运行主机加入允许访问的网络；不要把数据库或缓存端口开放给任意公网来源。

MySQL 使用独立 DML 应用账号与 DDL 迁移账号，分别配置 `CLOUD_DB_USERNAME/PASSWORD` 和 `CLOUD_DB_MIGRATION_USERNAME/PASSWORD`。`CLOUD_DB_URL` 包含数据库名、`sslMode=VERIFY_IDENTITY` 和一致的时区。必要时为 Java 配置信任库；不要通过 `useSSL=false`、`trustServerCertificate=true` 或跳过证书校验绕过云证书错误。

私有 CA 文件放到 `CLOUD_CERTS_DIRECTORY`（默认 `.local/cloud-certs`），云覆盖会将其只读挂载到 Java、Agent 和迁移容器的 `/run/cloud-certs`。Agent 使用 `REDIS_SSL_CA_CERTS=/run/cloud-certs/redis-ca.pem` 指定 Redis 的 PEM CA；云模式强制 `rediss://`。Java 的公有 CA 信任库可通过 `CLOUD_JAVA_TOOL_OPTIONS` 指向挂载的 truststore，例如 `-Djavax.net.ssl.trustStore=/run/cloud-certs/truststore.p12`；该变量同时作用于网关和一次性迁移。JVM 会打印启动选项，勿在此变量中放业务密码或私钥。

Redis 为 Java 与 Agent 配置独立 ACL 身份：`CLOUD_REDIS_GATEWAY_USERNAME/PASSWORD`、`CLOUD_REDIS_AGENT_USERNAME/PASSWORD`。Java 使用 `CLOUD_REDIS_HOST/PORT`，Agent 使用无内嵌密码的 `CLOUD_REDIS_URL=rediss://主机:端口/库号`，两端均校验证书。ACL 键前缀和命令权限参照 [本地 Redis 入口](../deploy/redis/entrypoint.sh)，并覆盖新增功能实际需要的权限；不要把本地 ACL 用户名当成云平台已创建的账号。

```powershell
docker compose -p ai-order-assistant -f docker-compose.yml -f deploy/compose/cloud.yml -f deploy/compose/public.yml up --build -d --wait
```

云覆盖默认排除本地 MySQL、Redis 和本地备份容器，并解除 Agent/迁移对它们的启动依赖。网关仍等待迁移成功。不要在云组合中启用 `local-data` 或 `local-data-backup` profile。对已有数据库升级前先取得可核验恢复点；不要随意对未知结构开启 Flyway baseline。

## 7. 告警必须实际送达

`operations.yml` 增加 Alertmanager，Prometheus 将告警发送给它，再由 SMTP 或通用 HTTPS Webhook 投递。两种渠道可同时配置。Webhook 使用 Alertmanager 通用消息格式；企业微信/钉钉机器人若要求不同协议，应提供能接受该格式的接收服务，不能直接填写不兼容机器人 URL。

配置 `ALERT_SMTP_HOST`（含端口）、`ALERT_SMTP_FROM`、`ALERT_SMTP_TO`；需要认证时提供 `ALERT_SMTP_USERNAME/PASSWORD`，默认要求 TLS。或设置 `ALERT_WEBHOOK_URL`。生成私有配置后再启动：

```powershell
python scripts/render-alertmanager.py --env-file .env
docker compose -p ai-order-assistant -f docker-compose.yml -f deploy/compose/operations.yml up -d --wait
```

云和公网场景在上述命令中保留各自覆盖文件，再把 operations 覆盖放在末尾。`OPERATIONS_UID/GID` 必须是生成配置和数据目录的用户 UID/GID；Linux 可用 `id -u`、`id -g` 获取，Windows 可使用模板的 1000。配置文件权限为仅所有者可读写，服务按该身份运行；配置位于 `.local/operations/`，禁止提交 Git。

运维规则包括支付未知、退款积压、对账差异、通知失败、告警渠道失败和 OSS 备份过期。服务没有暴露宿主机管理端口。实际验收须触发可控测试告警，并在接收端确认 firing/resolved；规则校验成功、Alertmanager 健康或日志“发送中”都不能替代送达证据。

本地可运行 `python scripts/test-alert-delivery.py`，使用已有 Agent 镜像中的 Python 标准库建立独立 HTTP/SMTP 收件器，真实运行固定摘要的 Alertmanager 并校验 FIRING/RESOLVED 两种实际收件。脚本只创建唯一测试项目和内部网络，结束移除该项目容器及网络，原始 JSON、邮件和回执保留在 `.local/trial-20261106/alertmanager-test/`。默认测试子网为 `10.77.244.0/28`，运行前确认无占用，必要时传入 `--subnet`；它不更改 Docker 全局地址池。该测试覆盖隔离网络内的明文收件器；接入时另验证实际SMTP TLS、Webhook与收件地址。

## 8. 备份、异地副本与独立恢复

本机 `.backup-disabled` 继续生效。脚本遇到所选目录中的停用标记会拒绝导出或上传，不删除标记、不静默覆盖策略。生产可单独使用 `.local/cloud-backups/`，并用 `BACKUP_HEALTH_HOST_DIRECTORY=./.local/cloud-backups/health` 让 Agent 只读健康元数据；Agent 不挂载 SQL 内容。

安装与数据库匹配的 MySQL 8.4 客户端和[官方 ossutil 2.x](https://help.aliyun.com/zh/oss/developer-reference/ossutil-overview/)，凭据通过环境或受保护文件传递，不放进命令行。配置 `BACKUP_DB_HOST/PORT/NAME/USERNAME/PASSWORD`，按需指定 CA；备份账号权限须覆盖实际表、视图、触发器、事件及存储过程读取。OSS 使用单独的备份 Bucket、前缀和凭据。

```powershell
# 云数据库一致性导出并上传；下载已上传内容，重新计算SHA-256
python scripts/backup-oss.py backup --env-file .env --directory .local/cloud-backups
# 上传已有且带同名.sha256的备份
python scripts/backup-oss.py upload --env-file .env --file .local/cloud-backups/实际备份.sql
# 从OSS下载并复用现有独立数据库恢复验证；目录须尚无同名文件
python scripts/backup-oss.py restore-check --env-file .env --object-name 实际备份.sql --directory .local/restore-20261106
```

OSS 配置为 `OSS_BACKUP_BUCKET/PREFIX` 和对应区域、Endpoint、身份。可用 `OSS_BACKUP_ACCESS_KEY_ID/SECRET` 配置独立备份身份；未提供时使用媒体的 `OSS_ACCESS_KEY_ID/SECRET`，因此生产应显式配置独立身份。可选的 `OSS_BACKUP_REGION/ENDPOINT` 覆盖媒体区域设置。回读文件与校验回执保留在 `.local/oss-verification/`。上传命令退出成功后仍会下载原对象和校验文件，只有重新计算的 SHA-256 一致才标记异地副本已核验。它不会删除原备份或远端对象；保留策略由专门 Bucket 生命周期管理。

SQL、`.sha256`及`.metadata.json`一同上传、回读和下载。元数据绑定SQL摘要与原始创建时间；已有SQL首次上传使用原mtime记录，此后不刷新。默认integration恢复不接受缺失元数据；旧备份可显式`--schema-mode campus`或`legacy`恢复，但不能作为新版发布恢复证据。

数据库恢复通过独立的`restore-check`执行。`restore-check`需要Python 3.13、PowerShell 7、Docker和MySQL运行镜像，默认在独立无外部网络的数据库检查V1–V9业务表全列SHA-256、四个新增域的16项不变量及旧业务约束。报告同时生成`restore-report.media-manifest.json`。云平台自动备份/PITR仍需单独开通并实测。

数据库SQL不包含本地图片或OSS媒体对象。先取得与数据库恢复报告匹配的媒体清单，再备份和独立恢复：

```powershell
# 本地媒体卷示例；实际卷名用 docker volume ls 核对
python scripts/backup-media.py backup --manifest .local/restore-20261106/restore-report.media-manifest.json --backup-directory .local/media-copy-20261106 --local-volume ai-order-assistant_media-data --env-file .env --report .local/media-backup.json
# 向独立空目录恢复并逐对象回读；若使用命名卷，改为--local-volume 独立空卷名
python scripts/backup-media.py restore --backup-directory .local/media-copy-20261106 --local-directory .local/media-restore-20261106 --env-file .env --report .local/media-restore.json
```

清单中的本地对象从`--local-directory`或`--local-volume`读取；OSS对象使用已配置媒体身份，通过官方ossutil逐个下载并校验长度与SHA-256。OSS恢复另加`--target-bucket 独立恢复Bucket`，不能指定原媒体Bucket；身份需要目标Bucket的`oss:GetBucketVersioning`以及对象的`oss:PutObject`、`oss:GetObject`权限。目标必须尚未启用版本控制，脚本以`--forbid-overwrite true`拒绝覆盖；版本控制启用或暂停时该保护不适用，因此会明确拒绝。参数与权限见官方 [get-bucket-versioning](https://help.aliyun.com/en/oss/developer-reference/get-bucket-versioning) 和 [put-object](https://help.aliyun.com/en/oss/developer-reference/put-object)。

目录／卷必须为空，云恢复使用独立Bucket，避免演练覆盖在用对象。卷操作使用已有Agent镜像中的Python标准库，不安装主机Docker SDK；仅临时无网络的归属修复容器使用CHOWN／FOWNER，日常Agent权限不增加。`verify`仅检查归档，结果`restored=false`，不能替代真正恢复。

媒体副本目录包含私有凭证图片，按备份敏感级别保护，并纳入自己的异地副本和保留计划。OSS接入后对实际Bucket执行备份、恢复和回读验收。

在任务计划或 cron 中每天执行 `backup-oss.py backup ...`，使用固定私有环境文件和绝对脚本路径，保存退出码并监控健康元数据。周期备份由任务计划或cron显式启用，本地默认启动流程保留当前备份设置。

## 9. 固定镜像发布与回滚

`scripts/release.py` 在 Windows/Linux 使用相同参数，应用和 Caddy 镜像必须带不可变 `@sha256:` 摘要。构建、镜像仓库权限和镜像上传按实际环境准备；脚本不自动推送源码或镜像。

```powershell
# 先准备并验证发布组合，不更改运行服务
python scripts/release.py prepare --project ai-order-assistant --env-file .env --gateway-image 仓库/gateway@sha256:实际64位摘要 --agent-image 仓库/agent@sha256:实际64位摘要
# 正式发布需当前有效备份和摘要匹配的独立恢复报告
python scripts/release.py deploy --project ai-order-assistant --env-file .env --gateway-image 仓库/gateway@sha256:实际64位摘要 --agent-image 仓库/agent@sha256:实际64位摘要 --backup-file 实际备份.sql --restore-report 实际恢复报告.json
```

云部署加`--cloud`，公网加`--public --proxy-image 仓库/caddy@sha256:实际摘要`，已准备告警配置则加`--operations`。公网/云发布运行生产配置检查。发布要求三十小时内的原始快照及独立V9恢复报告，而不是看下载时间；存在媒体记录时还要加`--media-restore-report .local/media-restore.json`，数量和清单摘要必须一致。先迁移再启动应用，镜像不重新构建、不删除业务卷。发布回执位于`.local/releases/项目/时间/receipt.json`，`last-successful.json`记录最近成功发布，当前服务状态通过巡检获取。

回滚使用先前成功回执，同时重新提供有效备份和恢复报告，并显式确认旧应用兼容当前数据库结构：

```powershell
python scripts/release.py rollback --project ai-order-assistant --env-file .env --receipt .local/releases/ai-order-assistant/先前时间/receipt.json --schema-compatible --backup-file 实际备份.sql --restore-report 实际恢复报告.json
```

回滚不执行旧迁移或数据库降级。`--schema-compatible` 还要求旧应用理解当前支付、退款和后台任务状态；只支持模拟支付的历史版本不能作为启用真实渠道后的安全回滚版本。迁移不可兼容时先向前修复或制定恢复方案；发布失败时，依据报告中的迁移及服务状态选择向前修复或回滚。上线后仍需执行实际域名健康、角色登录和业务验收。

## 10. 接入验收记录

每次接入保存提交号、配置模式、脱敏资源标识、测试时间、执行命令和结果。不要把密钥、验证码、完整手机号、收货地址、带签名下载 URL 或数据库 SQL 放进报告。

| 能力 | 实现状态 | 本地验证状态（2026-11-06） | 生产实联及完成条件 |
|---|---|---|---|
| 微信支付/OAuth | 完成 | 真实SDK签名、回调解密、受控HTTP及MySQL通过；对账贯通签名HTTP与实际MySQL | `NOT_RUN`；三终端付款、回调、退款、关闭和已出账对账均有渠道证据 |
| 短信找回 | 完成 | 真实SDK受控HTTP、验证码安全、失败及限流通过 | `NOT_RUN`；自有手机号实际收到，绑定找回及旧会话失效通过 |
| 本地/OSS图片 | 完成 | 本地存取、内容授权及OSS SDK受控HTTP通过 | `NOT_RUN`；生产Bucket实际上传读取、隔离及重启保留通过 |
| 高德 | 完成 | 官方协议受控HTTP及错误契约通过 | `NOT_RUN`；真实Key地理编码、候选确认和骑行路线通过 |
| 云MySQL/Redis | 完成 | Compose与TLS参数通过；本地真实MySQL/Redis通过 | `NOT_RUN`；真实云TLS连接、权限及迁移通过 |
| 告警 | 完成 | 实际Alertmanager向隔离SMTP/Webhook发送触发和恢复通过 | `NOT_RUN`；生产接收端实际收件且TLS通过 |
| 异地备份 | 完成 | 上传回读契约、失败拒绝及停用规则通过 | `NOT_RUN`；真实OSS回读与独立数据库恢复报告匹配 |
| AI能力一致性 | 完成 | 运行时规则、工具约束及受控评测通过 | `NOT_RUN`；在明确额度内完成指定模型真实评测 |

接入记录按上表填写实现、本地验证和生产实联三栏，附对应命令及结果。

本轮新增短信 SDK 引入的旧 Bouncy Castle 传递依赖已在 Maven 中统一锁定 `bcprov-jdk18on:1.86`，避免继续使用已被扫描识别为存在可修复高危问题的 `1.78.1`。[官方稳定版发布说明](https://www.bouncycastle.org/resources/new-release-bouncy-castle-java-1-86/)；具体扫描结论及镜像标识见本轮验收报告。

新增依赖固定版本：微信 `wechatpay-java-core:0.2.17`、短信 `dysmsapi20170525:4.6.0`、OSS `alibabacloud-oss-v2:0.6.1`，均采用官方维护的 Java SDK 和 Apache-2.0 许可。选择微信核心模块以复用官方签名、验签和解密，不引入本轮未使用的产品模块；高德复用 JDK HTTP 客户端。Java 21 编译和 JRE 25 容器运行均已验证；依赖纳入 Maven、运行镜像 SBOM 和现有安全扫描。页面二维码复用 Nayuki MIT 实现，原始许可、固定来源和摘要见 [vendor说明](../java-gateway/src/main/resources/static/assets/vendor/README.md)，不额外引入前端框架。

## 11. 执行生产服务验收

`scripts/verify-live-integrations.py` 通过应用真实接口完成验收，使用 Cookie、CSRF 和角色认证，不直接读取生产数据库。默认仅获取应用能力配置，不登录、不发送短信、不上传、不创建支付会话，也不调用模型；此时报告总状态为`NOT_RUN`，等待选择具体业务操作。

```powershell
python scripts/verify-live-integrations.py --base-url https://实际应用域名 --report .local/live-capabilities.json
# 只对显式选择的操作登录；使用单独准备、有对应资源权限的验收账号
$env:LIVE_VERIFY_USERNAME = '实际验收账号'
$env:LIVE_VERIFY_ROLE = 'CUSTOMER' # OWNER / STAFF / PLATFORM_ADMIN / RIDER 走管理会话
$env:LIVE_VERIFY_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host '验收账号密码' -AsSecureString)).Password
```

远端地址必须使用 HTTPS，可通过 `--ca-file` 指定自己的可信 CA；脚本不会关闭证书验证。登录凭据仅从上述环境变量读取，不进入命令行、报告或标准输出。以下命令会调用对应真实服务并可能消耗服务配额，只在准备验收时显式运行：

```powershell
# 真实地址解析；报告仅保留候选数量，不保存地址或坐标
python scripts/verify-live-integrations.py --base-url https://实际应用域名 --map-address '本人控制的完整测试地址' --report .local/live-geocode.json
# 两点均使用高德GCJ02坐标，不将浏览器原始GPS坐标直接传入
python scripts/verify-live-integrations.py --base-url https://实际应用域名 --route-origin '实际经度,实际纬度' --route-destination '实际经度,实际纬度' --report .local/live-route.json
# 上传会创建真实媒体记录；用已有店铺/菜品或本人售后资源，随后回读并比较SHA-256
python scripts/verify-live-integrations.py --base-url https://实际应用域名 --upload-image .local/验收图片.png --media-purpose AFTERSALE --resource-id 实际售后申请UUID --report .local/live-media.json
# 发送绑定验证码；受理后标为PENDING，实际收码后继续验证
python scripts/verify-live-integrations.py --base-url https://实际应用域名 --sms-phone 本人手机号码 --account-type CUSTOMER --report .local/live-sms.json
# 使用上一步challengeId；--code是交互开关，验证码由getpass读取且不回显，不把码值写在命令中
python scripts/verify-live-integrations.py --base-url https://实际应用域名 --challenge-id 上一步challengeId --code --report .local/live-phone-binding.json
```

只有实际输入手机收到的正确验证码、完成绑定并验证旧会话失效，才将绑定及收码项记为通过。图片回读报告包含 `provider`；OSS实联项以`provider=oss`的实际上传与回读结果验收，本地存储项单独记录。

```powershell
# 用同一顾客账号已有的未支付结算；创建渠道会话后，本人到校园页核对并付款
python scripts/verify-live-integrations.py --base-url https://实际应用域名 --checkout-id 实际结算UUID --scene NATIVE --poll-seconds 120 --report .local/live-payment.json
# H5使用手机浏览器，JSAPI先在微信内完成公众号授权；脚本不代为确认付款
# 已有支付单只查询，结果未知/未付不会标为通过
python scripts/verify-live-integrations.py --base-url https://实际应用域名 --payment-id 实际支付单ID --poll-seconds 120 --report .local/live-payment-query.json
# 平台账号只核对已有退款结果，不发起退款；退款须先由正常售后流程产生
$env:LIVE_VERIFY_ROLE = 'PLATFORM_ADMIN'
python scripts/verify-live-integrations.py --base-url https://实际应用域名 --refund-id 实际退款单ID --report .local/live-refund.json
# 账单已生成后，拉取并对账；日期采用实际业务日
python scripts/verify-live-integrations.py --base-url https://实际应用域名 --bill-date YYYY-MM-DD --report .local/live-reconciliation.json
```

切换角色时同时提供对应角色的验收账号及密码。支付会话创建后等待付款，服务端记录渠道确认的`PAID`后通过付款项。退款查询仅检查最近 200 笔平台渠道退款，未找到或仍在处理记为 `PENDING`；只有 `SUCCESS` 通过。对账须该日期存在已核验摘要的 `MATCHED` 批次，否则保留待处理状态。报告不包含验证码、Cookie、密码、完整手机号、地址、支付唤起参数或签名 URL。

每项状态为 `PASS`、`FAIL`、`PENDING` 或 `NOT_RUN`。退出码：存在失败为 `1`，存在待确认项且无失败为 `2`，通过或仅默认配置读取为 `0`；须同时看报告，不能将默认命令的退出码 `0` 当成生产验收通过。验收结束后清除临时凭据环境变量：

```powershell
Remove-Item Env:LIVE_VERIFY_USERNAME,Env:LIVE_VERIFY_PASSWORD,Env:LIVE_VERIFY_ROLE -ErrorAction SilentlyContinue
```
