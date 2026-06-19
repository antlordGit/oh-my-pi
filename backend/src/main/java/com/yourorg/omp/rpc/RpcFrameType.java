package com.yourorg.omp.rpc;

/**
 * Outbound frame `type` strings (frames streamed on omp's stdout).
 */
public final class RpcFrameType {
    private RpcFrameType() {}

    public static final String READY = "ready";
    public static final String RESPONSE = "response";

    // Agent lifecycle
    public static final String AGENT_START = "agent_start";
    public static final String AGENT_END = "agent_end";
    public static final String TURN_START = "turn_start";
    public static final String TURN_END = "turn_end";
    public static final String MESSAGE_START = "message_start";
    public static final String MESSAGE_UPDATE = "message_update";
    public static final String MESSAGE_END = "message_end";
    public static final String TOOL_EXECUTION_START = "tool_execution_start";
    public static final String TOOL_EXECUTION_UPDATE = "tool_execution_update";
    public static final String TOOL_EXECUTION_END = "tool_execution_end";

    // Host callbacks (require handler in Java side)
    public static final String HOST_TOOL_CALL = "host_tool_call";
    public static final String HOST_TOOL_CANCEL = "host_tool_cancel";
    public static final String HOST_URI_REQUEST = "host_uri_request";
    public static final String HOST_URI_CANCEL = "host_uri_cancel";

    // Extension UI / error
    public static final String EXTENSION_UI_REQUEST = "extension_ui_request";
    public static final String EXTENSION_ERROR = "extension_error";

    // Misc side channels
    public static final String AVAILABLE_COMMANDS_UPDATE = "available_commands_update";
    public static final String SUBAGENT_LIFECYCLE = "subagent_lifecycle";
    public static final String SUBAGENT_PROGRESS = "subagent_progress";
    public static final String SUBAGENT_EVENT = "subagent_event";
    public static final String COMMAND_OUTPUT = "command_output";
    public static final String SESSION_INFO_UPDATE = "session_info_update";
    public static final String CONFIG_UPDATE = "config_update";

    public static boolean isResponse(String type) {
        return RESPONSE.equals(type);
    }
}