# Qoder2API

Qoder2API 是一个本地反向代理服务，用来把 Qoder 的对话接口包装成 OpenAI Chat Completions 兼容接口，方便在 Claude Code、cc-switch 或其他支持 OpenAI API 协议的客户端里调用 Qoder。


## 功能

- 提供 `POST /v1/chat/completions` 接口。
- 支持 OpenAI 风格的流式响应。
- 支持从上层请求体读取 `model`，并转发到 Qoder 的 `model_config.key` 和 `x-model-key`。
- 支持 PAT 登录，也支持读取本机 Qoder 登录凭据作为 fallback。
- 默认过滤 `baseprompt.json` 中的基础 system prompt，避免额外身份提示影响 Claude Code 等上层工具。
- 保留上层客户端传来的 `system`、`developer`、`user`、`assistant`、`tool` 等消息，并拼接后发送给 Qoder。
- 对常见工具名做兼容映射，例如 `Bash` -> `RunCommand`。
- 提供调试日志开关，方便排查请求体、工具列表和 Qoder SSE 返回。

## 环境要求

- Windows、macOS 或 Linux
- JDK 17+
- Maven 3.8+

Windows 本地如果有多个 Java 版本，建议显式指定 JDK 17：

```powershell
cmd /c "set JAVA_HOME=D:\Work\Java\jdk-17.0.8&& set PATH=D:\Work\Java\jdk-17.0.8\bin;%PATH%&& mvn compile"
```

## 构建

```powershell
mvn clean package
```

构建完成后，产物通常位于：

```text
target/qoder-client-0.1.0.jar
```

## 启动

### 使用 QODER_PAT

```powershell
java -DQODER_PAT="你的 PAT" -jar target/qoder-client-0.1.0.jar
```

### 使用本机 Qoder 登录凭据

如果不传 `QODER_PAT`，程序会尝试读取本机 Qoder 登录信息：

```powershell
java -jar target/qoder-client-0.1.0.jar
```

启动成功后会监听：

```text
http://127.0.0.1:8963/v1/chat/completions
```

## 请求示例

```powershell
$body = @{
  model = "lite"
  stream = $true
  messages = @(
    @{
      role = "user"
      content = "你好，简单介绍一下你自己"
    }
  )
} | ConvertTo-Json -Depth 20

Invoke-WebRequest `
  -Method Post `
  -Uri "http://127.0.0.1:8963/v1/chat/completions" `
  -ContentType "application/json" `
  -Body $body
```

## 模型选择

上层请求可以通过 OpenAI 请求体里的 `model` 字段选择 Qoder 模型：

```json
{
  "model": "lite",
  "messages": [
    {
      "role": "user",
      "content": "hello"
    }
  ],
  "stream": true
}
```

当前本地模板默认值是 `lite`。根据 Qoder 官方文档，tier 模型常用 value 为：

| value | Qoder 显示名 | 说明 |
| --- | --- | --- |
| `lite` | Lite | 免费，适合简单问答和轻量任务 |
| `efficient` | Efficient | 低消耗，适合日常编码 |
| `auto` | Auto | 标准消耗，自动路由，适合复杂任务 |
| `performance` | Performance | 高消耗，适合更困难的工程问题 |
| `ultimate` | Ultimate | 最高消耗，适合高强度推理任务 |

Specific Model 的显示名可能包括 `Qwen3.6-Plus`、`GLM-5.1`、`GLM-5`、`Kimi-K2.5`、`MiniMax-M2.7` 等，但它们在接口里的精确 value 可能会随 Qoder 更新变化。需要精确值时，建议在 Qoder CLI 中使用 `/model` 选择后查看本地配置或抓取请求中的 `model_config.key`。

参考：

- [Qoder CLI Model](https://docs.qoder.com/cli/model)
- [Qoder Model Selector](https://docs.qoder.com/user-guide/chat/model-tier-selector)

## Claude Code 接入

将 Claude Code 或 cc-switch 的 OpenAI 兼容 provider 指向本地代理：

```text
base_url = http://127.0.0.1:8963/v1
model = lite
api_key = 任意非空字符串
```

如果客户端直接请求 `/v1/chat/completions`，则完整地址是：

```text
http://127.0.0.1:8963/v1/chat/completions
```

## 配置开关

这些开关通过 JVM system property 设置：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `QODER_PAT` | 空 | Qoder PAT。为空时尝试读取本机 Qoder 登录凭据 |
| `QODER_DEBUG` | `false` | 是否输出调试日志 |
| `QODER_DEBUG_MAX_CHARS` | `12000` | 单段调试 JSON 最大输出长度 |
| `QODER_DEBUG_SSE_LINES` | `20` | 最多打印多少行 Qoder SSE 调试数据 |
| `QODER_STRIP_BASE_SYSTEM` | `true` | 是否过滤 `baseprompt.json` 中的基础 system prompt |

调试启动示例：

```powershell
java `
  -DQODER_PAT="你的 PAT" `
  -DQODER_DEBUG=true `
  -DQODER_DEBUG_SSE_LINES=50 `
  -jar target/qoder-client-0.1.0.jar
```

如果你希望保留 `baseprompt.json` 中的 system prompt：

```powershell
java -DQODER_STRIP_BASE_SYSTEM=false -jar target/qoder-client-0.1.0.jar
```

## 工作流程

1. 客户端按 OpenAI Chat Completions 协议请求本地代理。
2. 代理读取 `messages`、`tools`、`stream` 和 `model`。
3. 代理基于 `baseprompt.json` 生成 Qoder 请求体。
4. 代理过滤本地模板里的基础 system prompt，并把上层传入的完整对话重建为 Qoder 可接收的 prompt。
5. 代理调用 Qoder SSE 接口。
6. 代理把 Qoder 返回转换为 OpenAI Chat Completions 风格的响应。

## 注意事项

- 该项目是本地兼容层，不是 Qoder 官方 API。
- Qoder 上游账号、额度、限流和模型权限仍由 Qoder 决定。
- 如果 Qoder 返回 `403` 或类似 `agentLimitResetTime` 的错误，通常是账号额度或 Qoder 侧限制，代理无法绕过。
- 不要把 PAT、`config.json`、本机登录凭据或任何 token 提交到 Git。
- `baseprompt.jsonbak`、`.idea/`、`target/` 通常不应提交。

## 项目结构

```text
.
+-- baseprompt.json
+-- images/
+-- pom.xml
+-- README.md
`-- src/main/java/us/cubk/
    +-- BearerApiClient.java
    +-- BearerBuilder.java
    +-- LocalAuth.java
    +-- OpenAiBridge.java
    +-- QoderEncoding.java
    `-- SignatureApiClient.java
```
