# Agent 基础镜像 HIGH 告警核查

核查日期：2026-10-21。基线为 `0bd2675e7ceb2bc9a574c419b7cd373ab353245c` 的镜像扫描：Java 网关及其应用依赖无 HIGH/CRITICAL，Python Agent 的 Debian 13（trixie）系统包有 **44 条包匹配、8 个不同 CVE、0 个 CRITICAL**。同一源码包生成多个二进制包，因此 44 条不代表 44 个独立漏洞；范围不包含所有运维服务或宿主机。

“没有可修复版本”仅指当前 trixie 稳定源。上游及 Debian sid/forky 已有修复，不能解释为漏洞不存在或全球没有补丁。核查时在隔离临时容器刷新 APT 索引，以下包候选版本与在用版本一致。

## 逐项触发条件与影响

| CVE / 来源 | 受影响组件与版本 | 漏洞及潜在影响 | 本项目核查与处置 |
|---|---|---|---|
| [CVE-2025-69720](https://security-tracker.debian.org/tracker/CVE-2025-69720) | ncurses `6.5+20250216-2`，4 个包匹配 | `infocmp` 的 `analyze_string` 存在栈溢出；恶意终端描述可使命令崩溃，属于内存破坏风险 | 应用没有调用该命令；镜像中将 `/usr/bin/infocmp` 权限设为 `000`，以服务用户执行确认为拒绝。保留 ncurses 库和 Essential 包；这是关闭执行入口，包本身未打补丁 |
| [CVE-2026-16742](https://security-tracker.debian.org/tracker/CVE-2026-16742) | systemd 源码的 `libsystemd0`、`libudev1` `257.13-1~deb13u1` | `systemd-homed` 会话的组权限处理可导致本地提权 | 镜像没有 `systemd-homed`，PID 1 为 uvicorn，没有 homed 管理的交互登录；扫描按同源库匹配，当前缺少公告所述漏洞功能 |
| [CVE-2026-54369](https://security-tracker.debian.org/tracker/CVE-2026-54369) | `libacl1` `2.3.2-2+b1` | 特权程序在攻击者可改写的路径上使用 ACL API 时，符号链接竞争可能导致提权或改写权限 | 业务没有 ACL 调用；Agent 非 root、无 capabilities，禁止通过 setuid 提权。保留基础库，未声明原生库漏洞已修复 |
| [CVE-2026-76642](https://security-tracker.debian.org/tracker/CVE-2026-76642) | util-linux `2.41.5-0+deb13u1`，9 个包匹配 | 外部 mount helper 失败后仍执行特权挂载钩子，可造成权限变化或提权 | 业务不执行 mount；fstab 没有授权条目，无特权挂载需求。新增 capability 全部移除和禁止提权，限制触发前提 |
| [CVE-2026-78408](https://security-tracker.debian.org/tracker/CVE-2026-78408) | 同上，9 个包匹配 | root 使用 `nsenter --join-cgroup` 进入不可信目标时泄漏特权文件描述符，目标可迁移或终止其他进程 | 业务不执行 nsenter；没有上述 root 运维流程，不共享宿主机 PID 命名空间，不挂载 Docker socket；新增禁止提权。管理员另行用 root 调试不在此结论范围内 |
| [CVE-2026-78409](https://security-tracker.debian.org/tracker/CVE-2026-78409) | 同上，9 个包匹配 | Linux ≥6.15 的 `X-mount.subdir` 路径可经中间符号链接逃逸，挂入非预期路径 | 没有对应 fstab 授权或业务挂载调用；新增权限限制，原包仍等待稳定源补丁 |
| [CVE-2026-78410](https://security-tracker.debian.org/tracker/CVE-2026-78410) | 同上，9 个包匹配 | 授权 bind mount 源可被替换，特权 owner/group/mode 钩子可能修改错误目标 | 没有对应 fstab 授权或业务挂载调用；新增权限限制，原包仍等待稳定源补丁 |
| [CVE-2026-9538](https://security-tracker.debian.org/tracker/CVE-2026-9538) | `perl-base` `5.40.1-6+deb13u1` | Perl `Archive::Tar` 处理恶意声明长度时可能无限分配内存，导致拒绝服务 | `perl -MArchive::Tar` 实测找不到模块，业务也不使用 Perl 解包；当前镜像缺少公告所述漏洞模块。保留原扫描记录，不将其笼统列为已打补丁 |

代码检索未发现 Agent 业务调用 shell/subprocess、ACL、curses 或 Perl 解包；这只是当前实现的静态证据，不能证明任意未来代码、依赖利用链或外部 root 操作均不可触发。

## 安全修复与暂缓原因

- Compose 中 Agent 增加 `cap_drop: [ALL]` 和 `security_opt: [no-new-privileges:true]`，继承到隔离、演示、模型评测及公网配置。保留非 root、只读根文件系统、默认 seccomp。
- 镜像禁用不用的 `infocmp` 命令。APT 模拟卸载确认 `ncurses-bin` 为 Essential，因此没有强制卸载，也没有删掉库或包管理记录来减少扫描数字。
- `scripts/check-agent-security.py` 检查实际 PID 1 的 UID、五组 capability 位、NoNewPrivs、seccomp、不可执行的 infocmp、只读应用目录，以及 Agent readiness、Redis 依赖、监控和 Java HTTP 可达性。CI 在隔离部署后执行相同检查；不调用付费模型。
- 稳定源没有这些 CVE 的更新候选。libacl 修复涉及新 ABI，Debian 明确说明不单独回移；Perl 修复仍有上游回归问题需要处理。直接混用 sid/forky 的底层包或自行替换动态库会扩大兼容性及维护风险，本轮不采用。ncurses、systemd、util-linux 的修复也等待稳定发行版更新后正常升级、扫描及定向验证。

## 结论边界

本轮隔离环境验证已通过：六种 Compose 合并配置保留权限限制；运行进程 UID 为 10001，五组 capability 位均为零，NoNewPrivs 为 1、seccomp 生效，infocmp 执行被拒；Agent/Redis 就绪、指标与网关 HTTP 可达，镜像内 9 项运行安全测试通过。新 Agent 镜像扫描仍为 44 条 HIGH 包匹配、8 个 CVE、0 个 CRITICAL、0 个有稳定源修复版本的发现；未忽略 CVE，也未放宽安全门禁。

当前没有发现这 8 个 CVE 会经正常点餐 HTTP 流程触发；权限限制通过后，现有演示和日常功能预期不受影响。仍保留系统包漏洞，尤其不能把“Security 工作流通过”解释为零漏洞：该门禁阻止的是存在可用修复版本的 HIGH/CRITICAL，原始扫描记录仍保留全部发现。

本结论适用于非 root、只读、无额外 capabilities、无宿主机敏感挂载的当前部署。改成特权容器、添加挂载或 shell/解包工具、安装 homed/Archive::Tar，均需要重新评估。没有进行攻击性漏洞利用或扩大到宿主机的渗透验证。

本轮原始扫描、APT 核查及运行证明保存在忽略目录 `.local/high-review-20261021/`；之前的 `.local/recruit-20261021/security-final/` 原样保留。
