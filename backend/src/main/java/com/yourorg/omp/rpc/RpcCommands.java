package com.yourorg.omp.rpc;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Fluent builder helpers for RpcCommand JSON. Keeps call sites tidy and type-safe.
 */
public final class RpcCommands {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RpcCommands() {}

    private static ObjectNode cmd(String type) {
        return MAPPER.createObjectNode().put("type", type);
    }

    public static ObjectNode prompt(String message) {
        return cmd(RpcCommandType.PROMPT).put("message", message);
    }

    public static ObjectNode prompt(String message, String streamingBehavior) {
        return prompt(message).put("streamingBehavior", streamingBehavior);
    }

    public static ObjectNode steer(String message) {
        return cmd(RpcCommandType.STEER).put("message", message);
    }

    public static ObjectNode followUp(String message) {
        return cmd(RpcCommandType.FOLLOW_UP).put("message", message);
    }

    public static ObjectNode abort() {
        return cmd(RpcCommandType.ABORT);
    }

    public static ObjectNode abortAndPrompt(String message) {
        return cmd(RpcCommandType.ABORT_AND_PROMPT).put("message", message);
    }

    public static ObjectNode newSession() {
        return cmd(RpcCommandType.NEW_SESSION);
    }

    public static ObjectNode newSessionFromParent(String parentSessionFile) {
        return newSession().put("parentSession", parentSessionFile);
    }

    public static ObjectNode getState() {
        return cmd(RpcCommandType.GET_STATE);
    }

    public static ObjectNode getMessages() {
        return cmd(RpcCommandType.GET_MESSAGES);
    }

    public static ObjectNode getAvailableCommands() {
        return cmd(RpcCommandType.GET_AVAILABLE_COMMANDS);
    }

    public static ObjectNode getAvailableModels() {
        return cmd(RpcCommandType.GET_AVAILABLE_MODELS);
    }

    public static ObjectNode setModel(String provider, String modelId) {
        return cmd(RpcCommandType.SET_MODEL).put("provider", provider).put("modelId", modelId);
    }

    public static ObjectNode setThinkingLevel(String level) {
        return cmd(RpcCommandType.SET_THINKING_LEVEL).put("level", level);
    }

    public static ObjectNode setSessionName(String name) {
        return cmd(RpcCommandType.SET_SESSION_NAME).put("name", name);
    }

    public static ObjectNode switchSession(String sessionPath) {
        return cmd(RpcCommandType.SWITCH_SESSION).put("sessionPath", sessionPath);
    }

    public static ObjectNode branch(String entryId) {
        return cmd(RpcCommandType.BRANCH).put("entryId", entryId);
    }

    public static ObjectNode getBranchMessages() {
        return cmd(RpcCommandType.GET_BRANCH_MESSAGES);
    }

    public static ObjectNode getLastAssistantText() {
        return cmd(RpcCommandType.GET_LAST_ASSISTANT_TEXT);
    }

    public static ObjectNode compact() {
        return cmd(RpcCommandType.COMPACT);
    }

    public static ObjectNode compact(String customInstructions) {
        return cmd(RpcCommandType.COMPACT).put("customInstructions", customInstructions);
    }

    public static ObjectNode setSubagentSubscription(String level) {
        return cmd(RpcCommandType.SET_SUBAGENT_SUBSCRIPTION).put("level", level);
    }

    public static ObjectNode getSubagents() {
        return cmd(RpcCommandType.GET_SUBAGENTS);
    }

    public static ObjectNode getSubagentMessages(String subagentId, String sessionFile, Long fromByte) {
        ObjectNode n = cmd(RpcCommandType.GET_SUBAGENT_MESSAGES);
        if (subagentId != null) n.put("subagentId", subagentId);
        if (sessionFile != null) n.put("sessionFile", sessionFile);
        if (fromByte != null) n.put("fromByte", fromByte);
        return n;
    }

    public static ObjectNode bash(String command) {
        return cmd(RpcCommandType.BASH).put("command", command);
    }

    public static ObjectNode abortBash() {
        return cmd(RpcCommandType.ABORT_BASH);
    }

    public static ObjectNode handoff() {
        return cmd(RpcCommandType.HANDOFF);
    }

    public static ObjectNode exportHtml(String outputPath) {
        ObjectNode n = cmd(RpcCommandType.EXPORT_HTML);
        if (outputPath != null) n.put("outputPath", outputPath);
        return n;
    }
}