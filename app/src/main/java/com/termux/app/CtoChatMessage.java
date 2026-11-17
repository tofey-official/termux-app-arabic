package com.termux.app;

import org.json.JSONException;
import org.json.JSONObject;

import androidx.annotation.NonNull;

/** A single message in the CTO chat interface (user question or AI reply). */
public final class CtoChatMessage {

    public enum Role { USER, ASSISTANT, ERROR }

    /** Roles serialized into the persisted history JSON. */
    private static final String ROLE_USER_JSON = "user";
    private static final String ROLE_ASSISTANT_JSON = "assistant";
    private static final String ROLE_ERROR_JSON = "error";

    public final Role role;
    /** Unique id used by the adapter/engine to stream updates into this message. */
    public final int id;
    /** Epoch millis when the message was created. */
    public final long time;
    /** Plain text for user/error messages, markdown for assistant replies. */
    public volatile String content;
    /** True while the AI engine is still streaming this message's content. */
    public volatile boolean streaming;

    public CtoChatMessage(Role role, int id, long time, String content, boolean streaming) {
        this.role = role;
        this.id = id;
        this.time = time;
        this.content = content;
        this.streaming = streaming;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("role", roleToJson(role));
        json.put("id", id);
        json.put("time", time);
        json.put("content", content);
        json.put("streaming", streaming);
        return json;
    }

    public static CtoChatMessage fromJson(JSONObject json) throws JSONException {
        return new CtoChatMessage(roleFromJson(json.getString("role")), json.getInt("id"),
            json.getLong("time"), json.getString("content"), json.optBoolean("streaming", false));
    }

    private static String roleToJson(Role role) {
        switch (role) {
            case USER:
                return ROLE_USER_JSON;
            case ERROR:
                return ROLE_ERROR_JSON;
            default:
                return ROLE_ASSISTANT_JSON;
        }
    }

    private static Role roleFromJson(@NonNull String value) {
        switch (value) {
            case ROLE_USER_JSON:
                return Role.USER;
            case ROLE_ERROR_JSON:
                return Role.ERROR;
            default:
                return Role.ASSISTANT;
        }
    }
}
