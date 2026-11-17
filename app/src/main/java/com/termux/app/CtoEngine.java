package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;

/**
 * The engine behind the CTO chat interface: it talks to the bundled antigravity CLI ("agy")
 * so that the user chats with AI models while Termux stays in the background.
 *
 * <p>Every chat message runs the CLI once in its headless print mode, equivalent to what the
 * user would type in the terminal:</p>
 * <pre>  $PREFIX/bin/agy --print [--continue] [--model=&lt;model&gt;] '&lt;message&gt;'</pre>
 *
 * <p>{@code --continue} resumes the most recent agy conversation for the dedicated working
 * directory used by this engine ("$HOME/CTO"), which gives the chat real multi-turn context
 * without keeping an interactive TUI session alive. The reply is streamed from the process
 * stdout to the UI, ANSI escape sequences are stripped, and stderr is only surfaced when the
 * command fails. Conversation continuity and the chat history are persisted in shared
 * preferences so that restarting the app keeps the same agy conversation.</p>
 */
public final class CtoEngine {

    private static final String LOG_TAG = "CtoEngine";

    /** Preferences file and keys used to persist the chat history and conversation state. */
    private static final String PREFS_NAME = "cto";
    private static final String PREF_HISTORY = "history";
    private static final String PREF_HAS_CONVERSATION = "has_conversation";
    private static final String PREF_MODEL = "model";

    /** Model ids passed with "--model"; null/empty means the CLI default. */
    public static final String MODEL_DEFAULT = "";
    public static final String MODEL_GEMINI_38_FLASH_HIGH = "gemini-3.8-flash-high";
    public static final String MODEL_GEMINI_4_HYDROGEN = "gemini-4-hydrogen";

    /** Tail length of stderr kept for error messages. */
    private static final int STDERR_TAIL_CHARS = 4000;
    /** Interval at which streamed output chunks are flushed to the UI. */
    private static final int CHUNK_FLUSH_INTERVAL_MS = 200;
    /** Maximum chat history entries kept in the persisted history. */
    private static final int MAX_HISTORY_ENTRIES = 200;

    /** Regexes stripping ANSI CSI sequences, OSC sequences and carriage returns from CLI output. */
    private static final String ANSI_CSI_REGEX = "\u001B\\[[0-9;?]*[A-Za-z]";
    private static final String ANSI_OSC_REGEX = "\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)";

    public interface Listener {
        /** A new message was appended to {@link #getMessages()}. */
        void onMessageInserted(int position);

        /** The content of the message with the given position changed (streamed chunk). */
        void onMessageChanged(int position);

        /** The engine started or stopped processing a request. */
        void onRunningStateChanged();
    }

    /** Application-scoped singleton so the engine survives activity recreation. */
    private static volatile CtoEngine sInstance;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<Listener> mListeners = new ArrayList<>();

    private final List<CtoChatMessage> mMessages = new ArrayList<>();
    private Process mCurrentProcess;
    private boolean mRunning;
    private boolean mHasConversation;
    private String mModel = MODEL_DEFAULT;
    private int mNextId = 1;
    private boolean mHistoryLoaded;

    private CtoEngine() {
    }

    public static CtoEngine getInstance() {
        if (sInstance == null)
            sInstance = new CtoEngine();
        return sInstance;
    }

    public synchronized void addListener(Listener listener) {
        mListeners.add(listener);
    }

    public synchronized void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    public List<CtoChatMessage> getMessages() {
        return mMessages;
    }

    public synchronized boolean isRunning() {
        return mRunning;
    }

    public synchronized String getModel() {
        return mModel;
    }

    public synchronized void setModel(Context context, String model) {
        mModel = model == null ? MODEL_DEFAULT : model;
        getPrefs(context).edit().putString(PREF_MODEL, mModel).apply();
    }

    /** Sends the given user message to agy and starts streaming the reply into a new message. */
    public synchronized void send(final Context context, String text) {
        if (mRunning || text == null || text.trim().isEmpty())
            return;
        ensureHistoryLoaded(context);

        long time = System.currentTimeMillis();
        mMessages.add(new CtoChatMessage(CtoChatMessage.Role.USER, mNextId++, time, text.trim(), false));
        notifyMessageInserted(mMessages.size() - 1);
        final CtoChatMessage reply = new CtoChatMessage(CtoChatMessage.Role.ASSISTANT, mNextId++, time, "", true);
        mMessages.add(reply);
        notifyMessageInserted(mMessages.size() - 1);

        mRunning = true;
        notifyRunningStateChanged();

        final String prompt = text.trim();
        final Context appContext = context.getApplicationContext();
        new Thread() {
            @Override
            public void run() {
                runAgy(appContext, prompt, reply);
            }
        }.start();
    }

    /** Destroys the currently running agy process, keeping any streamed partial reply. */
    public synchronized void cancel() {
        if (mCurrentProcess != null)
            mCurrentProcess.destroy();
    }

    /** Clears the chat history and starts a fresh agy conversation with the next message. */
    public synchronized void newChat(Context context) {
        cancel();
        mMessages.clear();
        mHasConversation = false;
        ensureHistoryLoaded(context);
        saveHistory(context);
        notifyRunningStateChanged();
        notifyMessageInserted(-1);
    }

    private void runAgy(final Context context, final String prompt, final CtoChatMessage reply) {
        List<String> command = new ArrayList<>();
        command.add(TERMUX_PREFIX_DIR_PATH + "/bin/agy");
        command.add("--print");
        synchronized (this) {
            if (mHasConversation)
                command.add("--continue");
            if (mModel != null && !mModel.isEmpty())
                command.add("--model=" + mModel);
        }
        command.add(prompt);

        File workspaceDir = new File(getTermuxHome(), "CTO");
        if (!workspaceDir.isDirectory())
            workspaceDir.mkdirs();

        String exitDetails = null;
        StringBuilder stdout = new StringBuilder();
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(workspaceDir);
            Map<String, String> environment = processBuilder.environment();
            environment.put("HOME", getTermuxHome().getAbsolutePath());
            environment.put("PREFIX", TERMUX_PREFIX_DIR_PATH);
            environment.put("PATH", TERMUX_PREFIX_DIR_PATH + "/bin");
            environment.put("TMPDIR", TERMUX_PREFIX_DIR_PATH + "/tmp");
            environment.put("TERM", "dumb");
            environment.put("LANG", "en_US.UTF-8");
            environment.put("NO_COLOR", "1");

            Logger.logInfo(LOG_TAG, "Running agy: " + String.join(" ", command));
            Process process = processBuilder.start();
            synchronized (this) {
                mCurrentProcess = process;
            }

            final StringBuilder stderrTail = new StringBuilder();
            Thread stderrThread = new Thread() {
                @Override
                public void run() {
                    try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                        int c;
                        StringBuilder buffer = new StringBuilder();
                        while ((c = reader.read()) != -1) {
                            buffer.append((char) c);
                            if (buffer.length() > STDERR_TAIL_CHARS)
                                buffer.delete(0, buffer.length() - STDERR_TAIL_CHARS);
                        }
                        synchronized (stderrTail) {
                            stderrTail.setLength(0);
                            stderrTail.append(buffer);
                        }
                    } catch (IOException e) {
                        // The process was likely cancelled; stderr is only advisory.
                    }
                }
            };
            stderrThread.start();

            // Stream stdout, stripping ANSI sequences, flushing to the UI at a fixed interval.
            StringBuilder pending = new StringBuilder();
            long lastFlush = 0;
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8), 8192)) {
                int c;
                while ((c = reader.read()) != -1) {
                    stdout.append((char) c);
                    pending.append((char) c);
                    long now = System.currentTimeMillis();
                    if (now - lastFlush >= CHUNK_FLUSH_INTERVAL_MS) {
                        flushPending(reply, pending);
                        lastFlush = now;
                    }
                }
            }
            flushPending(reply, pending);
            process.waitFor();
            synchronized (this) {
                mCurrentProcess = null;
            }

            String replyText = cleanCliOutput(stdout.toString());
            boolean success = process.exitValue() == 0 && !replyText.trim().isEmpty();
            if (success) {
                synchronized (this) {
                    reply.content = replyText;
                    reply.streaming = false;
                    mHasConversation = true;
                }
            } else {
                exitDetails = buildErrorDetails(replyText, stderrTail, process.exitValue());
            }
        } catch (final IOException | InterruptedException e) {
            exitDetails = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }

        final String failure = exitDetails;
        mHandler.post(() -> {
            if (failure != null) {
                // Replace the empty streaming reply with an explicit error message; keep any
                // partial streamed content visible above the error details.
                if (reply.content.trim().isEmpty()) {
                    mMessages.remove(reply);
                    mMessages.add(new CtoChatMessage(CtoChatMessage.Role.ERROR, reply.id,
                        reply.time, failure, false));
                } else {
                    synchronized (CtoEngine.this) {
                        reply.streaming = false;
                    }
                    mMessages.add(new CtoChatMessage(CtoChatMessage.Role.ERROR, mNextId++,
                        System.currentTimeMillis(), failure, false));
                }
                // The reply item may have been removed and replaced: refresh the whole list.
                notifyMessageInserted(-1);
            } else {
                notifyMessageChanged(mMessages.indexOf(reply));
            }
            synchronized (CtoEngine.this) {
                mRunning = false;
            }
            saveHistory(context);
            notifyRunningStateChanged();
        });
    }

    /** Move pending streamed output into the reply message and notify the UI. */
    private void flushPending(final CtoChatMessage reply, final StringBuilder pending) {
        if (pending.length() == 0)
            return;
        final String chunk = cleanCliOutput(pending.toString());
        pending.setLength(0);
        if (chunk.isEmpty())
            return;
        synchronized (this) {
            reply.content += chunk;
        }
        mHandler.post(() -> notifyMessageChanged(mMessages.indexOf(reply)));
    }

    /** Strip ANSI escape sequences, OSC sequences and carriage returns from CLI output. */
    private static String cleanCliOutput(String text) {
        if (text == null)
            return "";
        return text
            .replaceAll(ANSI_OSC_REGEX, "")
            .replaceAll(ANSI_CSI_REGEX, "")
            .replace("\r", "");
    }

    private static String buildErrorDetails(String stdout, StringBuilder stderrTail, int exitCode) {
        StringBuilder details = new StringBuilder();
        String stderr = stderrTail == null ? "" : stderrTail.toString();
        stderr = cleanCliOutput(stderr).trim();
        stdout = stdout == null ? "" : stdout.trim();

        if (!stdout.isEmpty())
            details.append("```\n").append(abbreviate(stdout, 1500)).append("\n```\n");
        details.append("agy exited with code ").append(exitCode);
        if (!stderr.isEmpty())
            details.append(":\n```\n").append(abbreviate(stderr, 1500)).append("\n```");
        if (stderr.contains("authentication required"))
            details.insert(0, "**مطلوب تسجيل الدخول**: افتح الطرفية (زر ↑) وشغّل `agy` ثم سجّل الدخول، وارجع هنا.\n\n");
        return details.toString().trim();
    }

    private static String abbreviate(String text, int max) {
        if (text.length() <= max)
            return text;
        return "…" + text.substring(text.length() - max);
    }

    private static File getTermuxHome() {
        return new File("/data/data/com.termux/files/home");
    }

    private void notifyMessageInserted(final int position) {
        mHandler.post(() -> {
            for (Listener listener : mListeners)
                listener.onMessageInserted(position);
        });
    }

    private void notifyMessageChanged(final int position) {
        mHandler.post(() -> {
            for (Listener listener : mListeners)
                listener.onMessageChanged(position);
        });
    }

    private void notifyRunningStateChanged() {
        mHandler.post(() -> {
            for (Listener listener : mListeners)
                listener.onRunningStateChanged();
        });
    }

    private SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void ensureHistoryLoaded(Context context) {
        if (mHistoryLoaded)
            return;
        mHistoryLoaded = true;
        SharedPreferences prefs = getPrefs(context);
        mHasConversation = prefs.getBoolean(PREF_HAS_CONVERSATION, false);
        mModel = prefs.getString(PREF_MODEL, MODEL_DEFAULT);
        try {
            JSONArray array = new JSONArray(prefs.getString(PREF_HISTORY, "[]"));
            for (int i = 0; i < array.length(); i++)
                mMessages.add(CtoChatMessage.fromJson(array.getJSONObject(i)));
            for (CtoChatMessage message : mMessages) {
                if (message.streaming) {
                    message.streaming = false;
                    if (message.content.trim().isEmpty())
                        message.content = "تمت مقاطعة الرد بسبب إغلاق التطبيق.";
                }
                if (message.id >= mNextId)
                    mNextId = message.id + 1;
            }
        } catch (JSONException e) {
            Logger.logErrorExtended(LOG_TAG, "Failed to load CTO chat history: " + e.getMessage());
        }
    }

    private void saveHistory(Context context) {
        SharedPreferences.Editor editor = getPrefs(context).edit();
        editor.putBoolean(PREF_HAS_CONVERSATION, mHasConversation);
        editor.putString(PREF_MODEL, mModel);
        try {
            JSONArray array = new JSONArray();
            int start = Math.max(0, mMessages.size() - MAX_HISTORY_ENTRIES);
            for (int i = start; i < mMessages.size(); i++)
                array.put(mMessages.get(i).toJson());
            editor.putString(PREF_HISTORY, array.toString());
        } catch (JSONException e) {
            Logger.logErrorExtended(LOG_TAG, "Failed to save CTO chat history: " + e.getMessage());
        }
        editor.apply();
    }
}
