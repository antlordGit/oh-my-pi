package com.yourorg.omp.rpc;

/**
 * All RpcCommand types accepted by `omp --mode rpc`.
 * Source of truth: docs/rpc.md in the upstream omp repo.
 */
public final class RpcCommandType {
    private RpcCommandType() {}

    // Prompting
    public static final String PROMPT = "prompt";
    public static final String STEER = "steer";
    public static final String FOLLOW_UP = "follow_up";
    public static final String ABORT = "abort";
    public static final String ABORT_AND_PROMPT = "abort_and_prompt";
    public static final String NEW_SESSION = "new_session";

    // State
    public static final String GET_STATE = "get_state";
    public static final String GET_AVAILABLE_COMMANDS = "get_available_commands";
    public static final String SET_TODOS = "set_todos";
    public static final String SET_HOST_TOOLS = "set_host_tools";
    public static final String SET_HOST_URI_SCHEMES = "set_host_uri_schemes";
    public static final String SET_SUBAGENT_SUBSCRIPTION = "set_subagent_subscription";
    public static final String GET_SUBAGENTS = "get_subagents";
    public static final String GET_SUBAGENT_MESSAGES = "get_subagent_messages";

    // Model
    public static final String SET_MODEL = "set_model";
    public static final String CYCLE_MODEL = "cycle_model";
    public static final String GET_AVAILABLE_MODELS = "get_available_models";

    // Thinking
    public static final String SET_THINKING_LEVEL = "set_thinking_level";
    public static final String CYCLE_THINKING_LEVEL = "cycle_thinking_level";

    // Queue modes
    public static final String SET_STEERING_MODE = "set_steering_mode";
    public static final String SET_FOLLOW_UP_MODE = "set_follow_up_mode";
    public static final String SET_INTERRUPT_MODE = "set_interrupt_mode";

    // Compaction
    public static final String COMPACT = "compact";
    public static final String SET_AUTO_COMPACTION = "set_auto_compaction";

    // Retry
    public static final String SET_AUTO_RETRY = "set_auto_retry";
    public static final String ABORT_RETRY = "abort_retry";

    // Bash
    public static final String BASH = "bash";
    public static final String ABORT_BASH = "abort_bash";

    // Session
    public static final String GET_SESSION_STATS = "get_session_stats";
    public static final String EXPORT_HTML = "export_html";
    public static final String SWITCH_SESSION = "switch_session";
    public static final String BRANCH = "branch";
    public static final String GET_BRANCH_MESSAGES = "get_branch_messages";
    public static final String GET_LAST_ASSISTANT_TEXT = "get_last_assistant_text";
    public static final String SET_SESSION_NAME = "set_session_name";
    public static final String HANDOFF = "handoff";

    // Messages
    public static final String GET_MESSAGES = "get_messages";

    // Login
    public static final String GET_LOGIN_PROVIDERS = "get_login_providers";
    public static final String LOGIN = "login";
}