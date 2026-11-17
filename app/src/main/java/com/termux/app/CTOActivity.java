package com.termux.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.shared.logger.Logger;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import io.noties.markwon.Markwon;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.linkify.LinkifyPlugin;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;

/**
 * The CTO chat interface: an AI-first launcher screen for this app. The user chats with the
 * bundled antigravity CLI (see {@link CtoEngine}) while Termux itself stays available in the
 * background through the terminal button in the top bar.
 */
public class CTOActivity extends AppCompatActivity implements CtoEngine.Listener {

    private static final String LOG_TAG = "CTOActivity";

    /** Max time (ms) to wait for the bundled agy binaries to appear after triggering install. */
    private static final long AGY_WAIT_TIMEOUT_MS = 120000;
    private static final long AGY_WAIT_INTERVAL_MS = 500;

    private static final String[] MODEL_LABELS = {
        "افتراضي (آخر نموذج مختار في agy)",
        "Gemini 3.8 Flash (High)",
        "Gemini 4 Hydrogen"
    };
    private static final String[] MODEL_VALUES = {
        CtoEngine.MODEL_DEFAULT,
        CtoEngine.MODEL_GEMINI_38_FLASH_HIGH,
        CtoEngine.MODEL_GEMINI_4_HYDROGEN
    };

    private static final String[] SUGGESTIONS = {
        "اكتب سكربت Python يحل معادلة من الدرجة الثانية",
        "اشرح لي أوامر Linux الأساسية بأمثلة",
        "راجع كودي واقترح تحسينات عليه",
        "اكتب دالة bash تنسخ احتياطياً مجلداً"
    };

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private CtoEngine mEngine;
    private CtoChatAdapter mAdapter;
    private RecyclerView mRecyclerView;
    private LinearLayoutManager mLayoutManager;
    private TextView mModelChip;
    private ImageButton mSendButton;
    private EditText mInput;
    private View mEmptyState;
    private boolean mFollowBottom = true;
    private boolean mEngineReady;
    private final AtomicBoolean mWaitingForAgy = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_cto);

        mEngine = CtoEngine.getInstance();

        Markwon markwon = Markwon.builder(this)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .build();
        CtoChatAdapter.setVazirmatn(loadVazirmatn());

        mRecyclerView = findViewById(R.id.cto_recycler);
        mLayoutManager = new LinearLayoutManager(this);
        mLayoutManager.setStackFromEnd(true);
        mRecyclerView.setLayoutManager(mLayoutManager);
        mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                mFollowBottom = isNearBottom();
            }
        });

        mAdapter = new CtoChatAdapter(mEngine.getMessages(), markwon);
        mRecyclerView.setAdapter(mAdapter);

        mModelChip = findViewById(R.id.cto_model_chip);
        mModelChip.setOnClickListener(v -> showModelDialog());

        ImageButton newChatButton = findViewById(R.id.cto_new_chat);
        newChatButton.setOnClickListener(v -> confirmNewChat());

        ImageButton terminalButton = findViewById(R.id.cto_terminal);
        terminalButton.setOnClickListener(v -> startActivity(new Intent(this, TermuxActivity.class)));

        mInput = findViewById(R.id.cto_input);
        mInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                sendInput();
                return true;
            }
            return false;
        });

        mSendButton = findViewById(R.id.cto_send);
        mSendButton.setOnClickListener(v -> {
            if (mEngine.isRunning())
                mEngine.cancel();
            else
                sendInput();
        });

        mEmptyState = findViewById(R.id.cto_empty_state);
        android.view.ViewGroup suggestionsGroup = findViewById(R.id.cto_suggestions);
        for (int i = 0; i < suggestionsGroup.getChildCount(); i++) {
            TextView suggestion = (TextView) suggestionsGroup.getChildAt(i);
            suggestion.setText(SUGGESTIONS[i]);
            suggestion.setOnClickListener(v -> {
                mInput.setText(suggestion.getText());
                mInput.setSelection(suggestion.getText().length());
                mInput.requestFocus();
            });
        }

        updateSendButtonState();
        updateEmptyState();
        refreshModelChip();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mEngine.addListener(this);
        mAdapter.notifyDataSetChanged();
        ensureEngineReady();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mEngine.removeListener(this);
    }

    private android.graphics.Typeface loadVazirmatn() {
        try {
            return android.graphics.Typeface.createFromAsset(getAssets(), "fonts/Vazirmatn-Regular.ttf");
        } catch (final Exception e) {
            return null;
        }
    }

    /** Make sure bootstrap, glibc and the bundled agy CLI are installed before enabling chat. */
    private void ensureEngineReady() {
        if (mEngineReady || mWaitingForAgy.get())
            return;

        File agyBinary = new File(TERMUX_PREFIX_DIR_PATH + "/bin/agy");
        File agyPayload = new File(TERMUX_PREFIX_DIR_PATH + "/bin/agy.va39");
        File resolvConf = new File(TERMUX_PREFIX_DIR_PATH + "/etc/resolv.conf");

        if (agyBinary.isFile() && agyPayload.isFile() && resolvConf.isFile()) {
            mEngineReady = true;
            return;
        }

        mWaitingForAgy.set(true);
        Toast.makeText(this, R.string.cto_preparing_engine, Toast.LENGTH_LONG).show();
        // This shows the bootstrap progress dialog on first runs and installs glibc/agy assets.
        TermuxInstaller.setupBootstrapIfNeeded(this, () -> {
            if (!agyBinary.isFile())
                AntigravityInstaller.setupIfNeeded(this);
            waitForAgy(new File(TERMUX_PREFIX_DIR_PATH + "/bin/agy"));
        });
    }

    private void waitForAgy(final File agyBinary) {
        if (isFinishing() || isDestroyed())
            return;
        final long start = System.currentTimeMillis();
        final Handler handler = new Handler(Looper.getMainLooper());
        Runnable check = new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed())
                    return;
                if (agyBinary.isFile() && agyBinary.length() > 0
                    && new File(TERMUX_PREFIX_DIR_PATH + "/bin/agy.va39").isFile()) {
                    mWaitingForAgy.set(false);
                    mEngineReady = true;
                    mInput.setEnabled(true);
                    Toast.makeText(CTOActivity.this, R.string.cto_engine_ready, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (System.currentTimeMillis() - start > AGY_WAIT_TIMEOUT_MS) {
                    mWaitingForAgy.set(false);
                    Toast.makeText(CTOActivity.this, R.string.cto_engine_wait_timeout, Toast.LENGTH_LONG).show();
                    return;
                }
                handler.postDelayed(this, AGY_WAIT_INTERVAL_MS);
            }
        };
        handler.postDelayed(check, AGY_WAIT_INTERVAL_MS);
    }

    private void sendInput() {
        String text = mInput.getText().toString();
        if (text.trim().isEmpty())
            return;
        if (!mEngineReady) {
            Toast.makeText(this, R.string.cto_engine_not_ready, Toast.LENGTH_SHORT).show();
            return;
        }
        mInput.setText("");
        mFollowBottom = true;
        mEngine.send(this, text);
    }

    private void showModelDialog() {
        int checked = 0;
        String current = mEngine.getModel();
        for (int i = 0; i < MODEL_VALUES.length; i++) {
            if (MODEL_VALUES[i].equals(current)) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(this, R.style.Theme_CTO_Dialog)
            .setTitle(R.string.cto_model_dialog_title)
            .setSingleChoiceItems(MODEL_LABELS, checked, (dialog, which) -> {
                mEngine.setModel(this, MODEL_VALUES[which]);
                refreshModelChip();
                dialog.dismiss();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void refreshModelChip() {
        String current = mEngine.getModel();
        String label = "النموذج: افتراضي";
        for (int i = 1; i < MODEL_VALUES.length; i++) {
            if (MODEL_VALUES[i].equals(current))
                label = "النموذج: " + MODEL_LABELS[i];
        }
        mModelChip.setText(label);
    }

    private void confirmNewChat() {
        new AlertDialog.Builder(this, R.style.Theme_CTO_Dialog)
            .setTitle(R.string.cto_new_chat_title)
            .setMessage(R.string.cto_new_chat_body)
            .setPositiveButton(R.string.cto_new_chat_confirm, (dialog, which) -> {
                mEngine.newChat(this);
                mAdapter.notifyDataSetChanged();
                updateEmptyState();
                mFollowBottom = true;
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private boolean isNearBottom() {
        int last = mLayoutManager.findLastCompletelyVisibleItemPosition();
        return last < 0 || last >= mAdapter.getItemCount() - 2;
    }

    private void scrollToBottom() {
        mRecyclerView.smoothScrollToPosition(Math.max(0, mAdapter.getItemCount() - 1));
    }

    private void updateSendButtonState() {
        boolean running = mEngine.isRunning();
        mSendButton.setImageResource(running ? R.drawable.cto_ic_stop : R.drawable.cto_ic_send);
        mSendButton.setBackgroundResource(running ? R.drawable.cto_bg_send_stop : R.drawable.cto_bg_send);
        mSendButton.setContentDescription(getString(running
            ? R.string.cto_stop_desc : R.string.cto_send_desc));
    }

    private void updateEmptyState() {
        mEmptyState.setVisibility(mEngine.getMessages().isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ---- CtoEngine.Listener ----

    @Override
    public void onMessageInserted(int position) {
        if (position < 0) {
            mAdapter.notifyDataSetChanged();
        } else {
            mAdapter.notifyItemInserted(position);
        }
        updateEmptyState();
        if (mFollowBottom)
            scrollToBottom();
    }

    @Override
    public void onMessageChanged(int position) {
        if (position < 0)
            return;
        mAdapter.notifyItemChanged(position, "chunk");
        if (mFollowBottom)
            scrollToBottom();
    }

    @Override
    public void onRunningStateChanged() {
        updateSendButtonState();
    }
}
