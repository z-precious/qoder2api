package us.cubk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class OpenAiBridge {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("QODER_DEBUG", "false"));
    private static final boolean STRIP_BASE_SYSTEM = Boolean.parseBoolean(System.getProperty("QODER_STRIP_BASE_SYSTEM", "true"));
    private static final int DEBUG_MAX_CHARS = Integer.parseInt(System.getProperty("QODER_DEBUG_MAX_CHARS", "12000"));
    private static final int DEBUG_SSE_LINES = Integer.parseInt(System.getProperty("QODER_DEBUG_SSE_LINES", "20"));
    private final BearerBuilder.SessionContext sess;
    private final BearerApiClient bearerClient;
    private final JsonNode templateBase;

    private record QoderDelta(String content, JsonNode toolCalls, String finishReason) {}

    public OpenAiBridge(String pat) throws Exception {
        String mid = UUID.randomUUID().toString();
        String mtoken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString((UUID.randomUUID().toString() + UUID.randomUUID()).substring(0, 50).getBytes());
        String mtype = UUID.randomUUID().toString().replace("-", "").substring(0, 18);
        var sigClient = new SignatureApiClient(mid, mtoken, mtype);
        JsonNode jt = sigClient.exchangeJobToken(pat);
        System.out.println("[bridge] session for " + jt.path("name").asText() + " (" + jt.path("id").asText() + ")");
        var identity = new BearerBuilder.AuthIdentity(jt.path("name").asText(""), jt.path("id").asText(""), jt.path("id").asText(""), "", "", "", jt.path("userType").asText("personal_standard"), jt.path("securityOauthToken").asText(), jt.path("refreshToken").asText());
        this.sess = BearerBuilder.newSession(identity, mid, mtoken, mtype);
        this.bearerClient = new BearerApiClient(sess);
        this.templateBase = loadTemplate();
    }

    public OpenAiBridge(JsonNode localAuth) throws Exception {
        String mid = UUID.randomUUID().toString();
        String mtoken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString((UUID.randomUUID().toString() + UUID.randomUUID()).substring(0, 50).getBytes());
        String mtype = UUID.randomUUID().toString().replace("-", "").substring(0, 18);
        var identity = new BearerBuilder.AuthIdentity(
                localAuth.path("name").asText(""),
                localAuth.path("aid").asText(""),
                localAuth.path("uid").asText(""),
                localAuth.path("yx_uid").asText(""),
                localAuth.path("organization_id").asText(""),
                localAuth.path("organization_name").asText(""),
                localAuth.path("user_type").asText("personal_standard"),
                localAuth.path("security_oauth_token").asText(""),
                localAuth.path("refresh_token").asText("")
        );
        System.out.println("[bridge] session for " + identity.name() + " (" + identity.uid() + ") type=" + identity.userType());
        this.sess = BearerBuilder.newSession(identity, mid, mtoken, mtype);
        this.bearerClient = new BearerApiClient(sess);
        this.templateBase = loadTemplate();
    }

    private static JsonNode loadTemplate() throws Exception {
        String basePrompt = new String(java.nio.file.Files.readAllBytes(new File("baseprompt.json").toPath()));
        basePrompt = basePrompt.replace("{UUID1}",UUID.randomUUID().toString());
        basePrompt = basePrompt.replace("{UUID2}",UUID.randomUUID().toString());
        basePrompt = basePrompt.replace("{UUID3}",UUID.randomUUID().toString());
        basePrompt = basePrompt.replace("{UUID4}",UUID.randomUUID().toString());
        basePrompt = basePrompt.replace("{UUID5}",UUID.randomUUID().toString());
        basePrompt = basePrompt.replace("{TIME1}",String.valueOf(System.currentTimeMillis()));
        basePrompt = basePrompt.replace("D:/Projects/Qoder", System.getProperty("user.dir").replace("\\", "/"));
        return objectMapper.readTree(basePrompt);
    }

    public void start(int port) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/v1/chat/completions", this::handleChat);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("[bridge] listening http://127.0.0.1:" + port + "/v1/chat/completions");
    }

    private void handleChat(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1); return;
            }
            JsonNode req = objectMapper.readTree(ex.getRequestBody());
            boolean stream = req.path("stream").asBoolean(false);
            String model = req.path("model").asText("lite");
            JsonNode messages = req.path("messages");
            logOpenAiRequest(req, model, stream, messages);
            Set<String> availableTools = collectToolNames(req.path("tools"));

            ObjectNode body = templateBase.deepCopy();
            String nid = UUID.randomUUID().toString();
            body.put("request_id", nid);
            body.put("chat_record_id", nid);
            body.put("request_set_id", UUID.randomUUID().toString());
            body.put("session_id", UUID.randomUUID().toString());
            body.put("stream", true);
            body.put("aliyun_user_type", sess.identity().userType());
            ObjectNode mc = (ObjectNode) body.path("model_config");
            mc.put("key", model);
            ObjectNode biz = (ObjectNode) body.path("business");
            biz.put("id", UUID.randomUUID().toString());
            biz.put("begin_at", System.currentTimeMillis());

            String prompt = buildPrompt(messages);
            ObjectNode ctx = (ObjectNode) body.path("chat_context");
            ((ObjectNode) ctx.path("text")).put("text", prompt);
            ((ObjectNode) ctx.path("extra").path("originalContent")).put("text", prompt);
            biz.put("name", prompt.length() > 30 ? prompt.substring(0, 30) : prompt);
            ArrayNode msgsArr = (ArrayNode) body.path("messages");
            ArrayNode rebuilt = objectMapper.createArrayNode();
            for (JsonNode msg : msgsArr) {
                String role = msg.path("role").asText("");
                if (!"user".equals(role) && !(STRIP_BASE_SYSTEM && "system".equals(role))) {
                    rebuilt.add(msg);
                }
            }
            ObjectNode userMsg = objectMapper.createObjectNode();
            userMsg.put("role", "user"); userMsg.put("content", "");
            ArrayNode contents = objectMapper.createArrayNode();
            ObjectNode cn = objectMapper.createObjectNode();
            cn.put("type", "text"); cn.put("text", prompt);
            contents.add(cn);
            userMsg.set("contents", contents);
            ObjectNode rmu = objectMapper.createObjectNode();
            rmu.put("prompt_tokens", 0); rmu.put("completion_tokens", 0); rmu.put("total_tokens", 0);
            ObjectNode ctd = objectMapper.createObjectNode(); ctd.put("reasoning_tokens", 0); rmu.set("completion_tokens_details", ctd);
            ObjectNode ptd = objectMapper.createObjectNode(); ptd.put("cached_tokens", 0); rmu.set("prompt_tokens_details", ptd);
            ObjectNode rm = objectMapper.createObjectNode(); rm.put("id", ""); rm.set("usage", rmu);
            userMsg.set("response_meta", rm);
            userMsg.put("reasoning_content_signature", "");
            rebuilt.add(userMsg);
            body.set("messages", rebuilt);

            System.out.println("[bridge] prompt=" + (prompt.length() > 80 ? prompt.substring(0,80)+"..." : prompt));
            for (JsonNode msg : body.path("messages")) {
                String content = msg.path("content").asText();
                String contentsStr = msg.path("contents").toString();
                System.out.println("[bridge] msg role=" + msg.path("role").asText() + " content=" + (content.length()>40 ? content.substring(0,40)+"..." : content) + " contents=" + (contentsStr.length()>120 ? contentsStr.substring(0,120)+"..." : contentsStr));
            }

            String url = "https://api3.qoder.sh/algo/api/v2/service/pro/sse/agent_chat_generation" + "?FetchKeys=llm_model_result&AgentId=agent_common&Encode=1";
            Map<String,String> extraHeaders = Map.of("x-model-key", model, "x-model-source", mc.path("source").asText("system"));

            String reqId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            long created = System.currentTimeMillis() / 1000;

            if (stream) {
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                ex.getResponseHeaders().add("Cache-Control", "no-cache");
                ex.sendResponseHeaders(200, 0);
                OutputStream out = ex.getResponseBody();
                int[] sseLineNo = {0};
                boolean[] sawToolCalls = {false};
                String[] finishReason = {"stop"};
                bearerClient.openStreamLines(url, body, extraHeaders, line -> {
                    if (!line.startsWith("data:")) return;
                    String data = line.substring(5).trim();
                    logQoderSseData(data, ++sseLineNo[0]);
                    QoderDelta delta = extractDelta(data);
                    if (delta == null) return;
                    if (delta.finishReason() != null && !delta.finishReason().isEmpty()) {
                        finishReason[0] = delta.finishReason();
                    }
                    boolean hasContent = delta.content() != null && !delta.content().isEmpty();
                    boolean hasToolCalls = delta.toolCalls() != null && delta.toolCalls().isArray() && delta.toolCalls().size() > 0;
                    if (!hasContent && !hasToolCalls) return;
                    try {
                        ObjectNode chunk = makeChunk(reqId, created, model);
                        ObjectNode outDelta = (ObjectNode) chunk.path("choices").get(0).path("delta");
                        outDelta.put("role", "assistant");
                        if (hasContent) outDelta.put("content", delta.content());
                        if (hasToolCalls) {
                            sawToolCalls[0] = true;
                            outDelta.set("tool_calls", normalizeToolCalls(delta.toolCalls(), availableTools));
                        }
                        out.write(("data: " + objectMapper.writeValueAsString(chunk) + "\n\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } catch (IOException ie) { throw new RuntimeException(ie); }
                });
                ObjectNode done = makeChunk(reqId, created, model);
                ((ObjectNode) done.path("choices").get(0)).put("finish_reason", sawToolCalls[0] ? "tool_calls" : finishReason[0]);
                ((ObjectNode) done.path("choices").get(0)).set("delta", objectMapper.createObjectNode());
                out.write(("data: " + objectMapper.writeValueAsString(done) + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.close();
            } else {
                StringBuilder full = new StringBuilder();
                Map<Integer, ObjectNode> toolCalls = new LinkedHashMap<>();
                String[] finishReason = {"stop"};
                int[] sseLineNo = {0};
                bearerClient.openStreamLines(url, body, extraHeaders, line -> {
                    if (!line.startsWith("data:")) return;
                    String data = line.substring(5).trim();
                    logQoderSseData(data, ++sseLineNo[0]);
                    QoderDelta delta = extractDelta(data);
                    if (delta == null) return;
                    if (delta.content() != null) full.append(delta.content());
                    if (delta.toolCalls() != null) appendToolCalls(toolCalls, delta.toolCalls(), availableTools);
                    if (delta.finishReason() != null && !delta.finishReason().isEmpty()) {
                        finishReason[0] = delta.finishReason();
                    }
                });
                ObjectNode out = objectMapper.createObjectNode();
                out.put("id", reqId); out.put("object", "chat.completion");
                out.put("created", created); out.put("model", model);
                ArrayNode choices = objectMapper.createArrayNode();
                ObjectNode ch = objectMapper.createObjectNode();
                ch.put("index", 0);
                ObjectNode msg = objectMapper.createObjectNode();
                msg.put("role", "assistant");
                if (full.length() > 0) {
                    msg.put("content", full.toString());
                } else {
                    msg.putNull("content");
                }
                if (!toolCalls.isEmpty()) {
                    ArrayNode calls = objectMapper.createArrayNode();
                    toolCalls.values().forEach(calls::add);
                    msg.set("tool_calls", calls);
                }
                ch.set("message", msg);
                ch.put("finish_reason", !toolCalls.isEmpty() ? "tool_calls" : finishReason[0]);
                choices.add(ch);
                out.set("choices", choices);
                ObjectNode usage = objectMapper.createObjectNode();
                usage.put("prompt_tokens", 0); usage.put("completion_tokens", 0); usage.put("total_tokens", 0);
                out.set("usage", usage);
                byte[] outBytes = objectMapper.writeValueAsBytes(out);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, outBytes.length);
                ex.getResponseBody().write(outBytes);
            }
        } catch (Exception e) {
            String err = "{\"error\":{\"message\":\"" + e.getMessage().replace("\"", "\\\"") + "\",\"type\":\"qoder_error\"}}";
            byte[] errBytes = err.getBytes(StandardCharsets.UTF_8);
            try {
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(500, errBytes.length);
                ex.getResponseBody().write(errBytes);
            } catch (IOException ignore) {}
        } finally {
            ex.close();
        }
    }

    private void streamSseChunks(java.io.InputStream is, java.util.function.Consumer<String> onChunk) throws IOException {
        java.io.ByteArrayOutputStream lineBuf = new java.io.ByteArrayOutputStream();
        int b;
        while ((b = is.read()) != -1) {
            if (b == '\n') {
                String line = lineBuf.toString(StandardCharsets.UTF_8);
                lineBuf.reset();
                String trimmed = line.endsWith("\r") ? line.substring(0, line.length()-1) : line;
                if (trimmed.startsWith("data:")) {
                    String content = extractContent(trimmed.substring(5).trim());
                    if (content != null && !content.isEmpty()) onChunk.accept(content);
                }
            } else {
                lineBuf.write(b);
            }
        }
        if (lineBuf.size() > 0) {
            String line = lineBuf.toString(StandardCharsets.UTF_8);
            if (line.startsWith("data:")) {
                String content = extractContent(line.substring(5).trim());
                if (content != null && !content.isEmpty()) onChunk.accept(content);
            }
        }
    }

    private String extractContent(String dataLine) {
        QoderDelta delta = extractDelta(dataLine);
        return delta == null ? null : delta.content();
    }

    private QoderDelta extractDelta(String dataLine) {
        try {
            JsonNode wrapper = objectMapper.readTree(dataLine);
            String inner = wrapper.path("body").asText("");
            if (inner.isEmpty()) return null;
            JsonNode innerJson = objectMapper.readTree(inner);
            for (JsonNode ch : innerJson.path("choices")) {
                JsonNode delta = ch.path("delta");
                String finishReason = ch.path("finish_reason").isNull() || ch.path("finish_reason").isMissingNode()
                        ? null
                        : ch.path("finish_reason").asText();
                JsonNode toolCalls = delta.has("tool_calls") ? delta.path("tool_calls") : null;
                String content = null;
                if (delta.has("content") && !delta.path("content").asText().isEmpty()) {
                    content = delta.path("content").asText();
                }
                if (content != null || toolCalls != null || finishReason != null) {
                    return new QoderDelta(content, toolCalls, finishReason);
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    private String buildPrompt(JsonNode messages) {
        if (!messages.isArray()) return "";
        StringBuilder prompt = new StringBuilder();
        for (JsonNode msg : messages) {
            String role = msg.path("role").asText("");
            if ("assistant".equals(role)) {
                appendSection(prompt, roleLabel(role), contentAsText(msg.path("content")));
                if (msg.path("tool_calls").isArray()) {
                    for (JsonNode call : msg.path("tool_calls")) {
                        JsonNode fn = call.path("function");
                        String tool = fn.path("name").asText("");
                        String args = fn.path("arguments").asText("");
                        appendSection(prompt, "Assistant tool call", tool + "(" + args + ")");
                    }
                }
            } else if ("tool".equals(role)) {
                String label = "Tool result";
                String callId = msg.path("tool_call_id").asText("");
                if (!callId.isEmpty()) label += " " + callId;
                appendSection(prompt, label, contentAsText(msg.path("content")));
            } else {
                appendSection(prompt, roleLabel(role), contentAsText(msg.path("content")));
            }
        }
        return prompt.toString().trim();
    }

    private String roleLabel(String role) {
        if (role == null || role.isBlank()) return "Message";
        return switch (role) {
            case "system" -> "System";
            case "developer" -> "Developer";
            case "user" -> "User";
            case "assistant" -> "Assistant";
            default -> role.substring(0, 1).toUpperCase() + role.substring(1);
        };
    }

    private void appendSection(StringBuilder out, String label, String value) {
        if (value == null || value.isBlank()) return;
        if (out.length() > 0) out.append("\n\n");
        out.append(label).append(":\n").append(value);
    }

    private String contentAsText(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) return "";
        if (content.isTextual()) return content.asText();
        if (!content.isArray()) return content.toString();
        StringBuilder out = new StringBuilder();
        for (JsonNode part : content) {
            if ("text".equals(part.path("type").asText(""))) {
                if (out.length() > 0) out.append("\n");
                out.append(part.path("text").asText(""));
            }
        }
        return out.toString();
    }

    private Set<String> collectToolNames(JsonNode tools) {
        Set<String> names = new HashSet<>();
        if (!tools.isArray()) return names;
        for (JsonNode tool : tools) {
            String name = tool.path("function").path("name").asText("");
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    private ArrayNode normalizeToolCalls(JsonNode toolCalls, Set<String> availableTools) {
        ArrayNode normalized = objectMapper.createArrayNode();
        for (JsonNode toolCall : toolCalls) {
            ObjectNode copy = toolCall.deepCopy();
            if (copy.has("id") && copy.path("id").asText("").isEmpty()) {
                copy.remove("id");
            }
            JsonNode function = copy.path("function");
            if (function instanceof ObjectNode fn && fn.has("name")) {
                String name = fn.path("name").asText("");
                if (name.isEmpty()) {
                    fn.remove("name");
                } else {
                    fn.put("name", mapToolName(name, availableTools));
                }
            }
            normalized.add(copy);
        }
        return normalized;
    }

    private String mapToolName(String name, Set<String> availableTools) {
        if ("Bash".equals(name) && availableTools.contains("RunCommand")) return "RunCommand";
        if ("BashOutput".equals(name) && availableTools.contains("CheckCommandStatus")) return "CheckCommandStatus";
        if ("KillBash".equals(name) && availableTools.contains("StopCommand")) return "StopCommand";
        return name;
    }

    private void appendToolCalls(Map<Integer, ObjectNode> toolCalls, JsonNode fragments, Set<String> availableTools) {
        if (!fragments.isArray()) return;
        for (JsonNode fragment : fragments) {
            int index = fragment.path("index").asInt(toolCalls.size());
            ObjectNode target = toolCalls.computeIfAbsent(index, k -> {
                ObjectNode n = objectMapper.createObjectNode();
                n.put("index", k);
                n.put("type", "function");
                n.set("function", objectMapper.createObjectNode());
                return n;
            });
            String id = fragment.path("id").asText("");
            if (!id.isEmpty()) target.put("id", id);
            String type = fragment.path("type").asText("");
            if (!type.isEmpty()) target.put("type", type);
            JsonNode fn = fragment.path("function");
            if (fn.isObject()) {
                ObjectNode targetFn = (ObjectNode) target.path("function");
                String name = fn.path("name").asText("");
                if (!name.isEmpty()) targetFn.put("name", mapToolName(name, availableTools));
                if (fn.has("arguments")) {
                    targetFn.put("arguments", targetFn.path("arguments").asText("") + fn.path("arguments").asText(""));
                }
            }
        }
    }

    private void logOpenAiRequest(JsonNode req, String model, boolean stream, JsonNode messages) {
        int messageCount = messages.isArray() ? messages.size() : 0;
        int toolCount = req.path("tools").isArray() ? req.path("tools").size() : 0;
        String toolChoice = req.has("tool_choice") ? req.path("tool_choice").toString() : "<missing>";
        System.out.println("[debug] openai request model=" + model + " stream=" + stream
                + " messages=" + messageCount + " tools=" + toolCount + " tool_choice=" + toolChoice);
        if (messages.isArray()) {
            for (int i = 0; i < messages.size(); i++) {
                JsonNode msg = messages.get(i);
                System.out.println("[debug] openai msg[" + i + "] role=" + msg.path("role").asText("")
                        + " has_tool_calls=" + msg.has("tool_calls")
                        + " tool_call_id=" + msg.path("tool_call_id").asText(""));
            }
        }
        if (req.path("tools").isArray()) {
            for (int i = 0; i < req.path("tools").size(); i++) {
                JsonNode tool = req.path("tools").get(i);
                System.out.println("[debug] openai tool[" + i + "] type=" + tool.path("type").asText("")
                        + " name=" + tool.path("function").path("name").asText(""));
            }
        }
        if (DEBUG) {
            logJson("[debug] openai request body=", req);
        }
    }

    private void logQoderSseData(String data, int lineNo) {
        if (!DEBUG || lineNo > DEBUG_SSE_LINES) return;
        try {
            JsonNode wrapper = objectMapper.readTree(data);
            String inner = wrapper.path("body").asText("");
            System.out.println("[debug] qoder sse[" + lineNo + "] wrapper=" + truncate(objectMapper.writeValueAsString(wrapper)));
            if (!inner.isEmpty()) {
                JsonNode innerJson = objectMapper.readTree(inner);
                logJson("[debug] qoder sse[" + lineNo + "] body=", innerJson);
            }
        } catch (Exception e) {
            System.out.println("[debug] qoder sse[" + lineNo + "] raw=" + truncate(data));
        }
    }

    private void logJson(String prefix, JsonNode json) {
        try {
            System.out.println(prefix + truncate(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json)));
        } catch (Exception e) {
            System.out.println(prefix + truncate(json.toString()));
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= DEBUG_MAX_CHARS) return value;
        return value.substring(0, DEBUG_MAX_CHARS) + "...<truncated>";
    }

    private ObjectNode makeChunk(String id, long created, String model) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", id); root.put("object", "chat.completion.chunk");
        root.put("created", created); root.put("model", model);
        ArrayNode choices = objectMapper.createArrayNode();
        ObjectNode c = objectMapper.createObjectNode();
        c.put("index", 0);
        c.set("delta", objectMapper.createObjectNode());
        c.putNull("finish_reason");
        choices.add(c);
        root.set("choices", choices);
        return root;
    }

    public static void run(String pat, int port) throws Exception {
        OpenAiBridge bridge;
        if (pat == null || pat.isBlank()) {
            pat = System.getProperty("QODER_PAT");
        }
        if (pat != null && !pat.isBlank()) {
            bridge = new OpenAiBridge(pat);
        } else {
            try {
                JsonNode localAuth = LocalAuth.readUserInfo();
                System.out.println("[bridge] using local auth from ~/.qoder/.auth/");
                bridge = new OpenAiBridge(localAuth);
            } catch (Exception e) {
                throw new RuntimeException("No PAT provided and local auth not available: " + e.getMessage());
            }
        }
        bridge.start(port);
        Thread.currentThread().join();
    }

    public static void main(String[] args) throws Exception {
        run(null, 8963);
    }
}
