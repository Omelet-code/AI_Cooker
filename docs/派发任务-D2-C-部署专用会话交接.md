# D2-C：部署专用会话交接卡

日期：2026-10-10。用户明确要求新建专门负责部署的会话。当前授权为新会话接手部署指导、只读排查和阶段记录，不等于无人值守安装或生产上线授权。

创建回执：新会话 `01a124c1-db72-77b0-a55b-9047c7f8ba4f` / local，标题“Cooker 部署：Ubuntu 24.04 与 Docker 上线”，用户本次未指定模型/思考强度，使用其配置默认值。独立projectless输出目录 `C:\Users\Omelet\Documents\Codex\2026-10-10\cookerdeploy\outputs`，不新建Git工作树或附加他人工作树。任务及卡路径已随初始消息交付；创建成功不是Docker安装或部署成功证据。

## 目标与协作方式

先解决Docker APT仓库TLS握手阻塞，再按已有教程带用户完成部署。简体中文、零基础解释；每次只给少量步骤，每个命令独立代码框，等待实际输出再继续，不将多条命令拼成一行。已有验收证据复用，不重复全量测试或数据库哈希审计。

主架构维护业务与整体计划；新会话负责部署指导、运行排错、记录和部署验收，不实现业务功能、不继续T18评测。初次回复先复述已知状态并给出下一项诊断，不重新询问已确认的系统规格。

## 输入与真实来源

- 架构与前端：`C:\Users\Omelet\.codex\worktrees\0f47\Cooker`；主树本次只读确认codex/agent、HEAD572084b5e067bc519029bf5e56132de4ede71f9c，既有dirty保留。
- 后端及部署配置：`C:\Users\Omelet\.codex\worktrees\5b09\Cooker\deploy\d2`。历史已验收后端codex/kitchen-assistant-u3、HEAD26c33ace，操作前只读重新核验；不得把注册目录E:\Cooker当最新源码。
- 必读：主树AGENTS.md；`docs/部署教程-Ubuntu24.04-零基础公网IP版.md`；`docs/阶段D2-单机Linux部署与交付计划.md`；`docs/审查回执-D2-A-部署准备轻量验收.md`；`docs/审查回执-D2-B准备-轻量验收与部署教程.md`；`docs/审查回执-D2-B1-厨房启用配置.md`。
- D2-A/B/B1已签收的是隔离准备与配置，不是服务器部署。已核对的三个镜像为linux/amd64，具体标签/ID以教程及现场只读核验为准，不因旧镜像名猜最新内容。

## 用户提供的服务器证据（非本会话直接核验）

Ubuntu24.04.4 LTS/noble；x86_64/amd64；nproc=4；总内存3.6Gi、available3.2Gi；Swap1.9Gi未用；根磁盘40G、剩余33G；inode约250万可用；仅SSH22与本地DNS53监听，80/443未监听。实际安全组、公网IP、地域和外部连通性尚未核验。无域名先公网IPv4、允许公开注册。先维持已有4GB资源限制，不自动扩大模型并发。

Docker命令及指定冲突包查询未输出，尚无Docker安装成功证据。

用户误把仓库写入、update、madison命令合成一行，tee将后续词当文件名。在用户当时的root家目录确认出现6个152字节文件：sudo、apt-get、update、apt-cache、madison、docker-ce。未删除；需要清理时先确认绝对路径与内容、再获得用户明确确认，绝不能删除/usr/bin等真实命令。

`/etc/apt/sources.list.d/docker.sources`内容已由用户贴出：官方HTTPS Ubuntu仓库、Suites noble、Components stable、Architectures amd64、Signed-By /etc/apt/keyrings/docker.asc，配置正确。

首次update报NO_PUBKEY 7EA0A9C3F273FCD8。随后用户逐条执行创建keyrings、curl官方公钥到docker.asc、chmod a+r，无显示错误；后续update未能下载索引，报：

```text
Could not handshake: Error in the pull function. [IP: 143.204.181.22 443]
Some index files failed to download. They have been ignored, or old ones used instead.
```

Ubuntu腾讯云源成功，Docker源失败，madison为空。不能声称公钥验证已成功，不能把update末尾Reading package lists当所有仓库通过。当前阻塞为Docker HTTPS/TLS连通问题，具体网络或客户端原因未知。

## 首项行动

用户尚未回报以下两个探针结果，先请其分别执行，保留证书验证、不修改系统配置：

```bash
curl --connect-timeout 10 --max-time 30 -fsS -o /dev/null -w 'HTTP %{http_code}\n' https://download.docker.com/linux/ubuntu/dists/noble/InRelease
```

```bash
curl --tlsv1.2 --tls-max 1.2 --connect-timeout 10 --max-time 30 -fsS -o /dev/null -w 'HTTP %{http_code}\n' https://download.docker.com/linux/ubuntu/dists/noble/InRelease
```

根据实际结果选择下一步。可能临时CDN/链路波动、TLS协商差异或出口限制，均是假设，不当既定根因。必要时核对可靠镜像源官方资料，不自行使用陌生脚本、HTTP源、trusted=yes或跳过签名/证书。推荐按可用的diagnose技能做精简证据排查；本问题不需新增仓库议题系统或架构改造。

## 分阶段与允许边界

1. TLS诊断→APT索引及签名正常→列出明确Docker版本→用户执行安装与版本验收。
2. 固定双树来源→装配/镜像归档与白名单菜谱导出→传输及加载。
3. 安全组/环境秘密→IP HTTPS及续期→空库V15与系统三层/已有向量恢复。
4. 禁模型基础验收→显式启用厨房与真实一餐验收→Mock采购、刷新回看→备份恢复与上线收尾。

实际导出/归档/上传、SSH连接与远程执行、安装、数据库恢复、证书签发、启停等由用户逐阶段明确确认后执行，不能把创建会话视为所有操作许可。当前没有SSH接入或公网IP，默认用户在自己的服务器终端执行，新会话给说明并检查输出。不要请求用户把密码、SSH私钥或API Key发聊天；秘密由用户本机/服务器配置。

厨房LLM/query收费风险用户已明确接受，运行配置默认关闭、显式true＋有效百炼Key可启用；用户DeepSeek官方deepseek-flash Key在账户加密保存。此费用选择不自动授权部署会话批量调用或自动重试；真实测试先说明请求范围。document索引/导入/评测仍关，迁移已有向量不重新收费生成，不恢复旧GLOBAL_STOP账本。采购仅Mock，不真实购物或支付。

## 允许修改、输出与验收

- 本会话自己的工作目录可写部署记录、教程补充和报告；0f47/5b09及E:\Cooker源码默认只读。发现配置需改，先明确目标文件和影响，与主架构/用户确认，不静默跨树写入、覆盖或整合。
- 不新建Git工作树、不修改业务源码/前端/API/Flyway，不更改ACL，不Git提交合并推送，不删除卷/旧库、不自动重启本地业务。
- 交付一份简洁部署日志与最终验收清单，必要时一份技术说明：修改文件、执行命令用途、实际结果、失败/待办和回滚风险；不重复已有教程全文。
- 分清用户提供输出、独立只读核验、隔离测试、真实服务器/供应商和人工意见；每个阶段实际验证通过才签收，不把准备状态当线上可用。Docker安装验收为Client/Server/Compose可用；上线验收按教程第17节，T18指标仍独立待执行。
- 向其他会话发送汇报需人类授权；当前未获给主架构发送消息的许可。先把报告交付在本会话，用户可带回主架构复审。
