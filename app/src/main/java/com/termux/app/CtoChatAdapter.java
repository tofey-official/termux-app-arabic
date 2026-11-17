package com.termux.app;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.noties.markwon.Markwon;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.linkify.LinkifyPlugin;

/**
 * RecyclerView adapter rendering the CTO chat: gradient user bubbles on the trailing side,
 * markdown AI replies with an avatar on the leading side, and distinct error messages.
 */
public final class CtoChatAdapter extends RecyclerView.Adapter<CtoChatAdapter.ViewHolder> {

    private static final int TYPE_USER = 0;
    private static final int TYPE_ASSISTANT = 1;
    private static final int TYPE_ERROR = 2;

    private static Typeface sVazirmatn;

    private final List<CtoChatMessage> mMessages;
    private final Markwon mMarkwon;
    private final SimpleDateFormat mTimeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

    public CtoChatAdapter(List<CtoChatMessage> messages, Markwon markwon) {
        mMessages = messages;
        mMarkwon = markwon;
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        return mMessages.get(position).id;
    }

    @Override
    public int getItemViewType(int position) {
        switch (mMessages.get(position).role) {
            case USER:
                return TYPE_USER;
            case ERROR:
                return TYPE_ERROR;
            default:
                return TYPE_ASSISTANT;
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case TYPE_USER:
                return new ViewHolder(inflater.inflate(R.layout.item_cto_user, parent, false), viewType);
            case TYPE_ERROR:
                return new ViewHolder(inflater.inflate(R.layout.item_cto_error, parent, false), viewType);
            default:
                return new ViewHolder(inflater.inflate(R.layout.item_cto_assistant, parent, false), viewType);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position);
        } else {
            // Chunked streaming update: only re-render the markdown content.
            CtoChatMessage message = mMessages.get(position);
            if (holder.text != null && !message.content.trim().isEmpty())
                renderContent(holder, message);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CtoChatMessage message = mMessages.get(position);

        boolean showTyping = message.streaming && message.content.trim().isEmpty();
        if (holder.typing != null)
            holder.typing.setVisibility(showTyping ? View.VISIBLE : View.GONE);
        if (holder.text != null)
            holder.text.setVisibility(showTyping ? View.GONE : View.VISIBLE);

        if (!showTyping && holder.text != null)
            renderContent(holder, message);

        if (holder.time != null)
            holder.time.setText(mTimeFormat.format(new Date(message.time)));
    }

    private void renderContent(ViewHolder holder, CtoChatMessage message) {
        holder.text.setTypeface(getVazirmatn(holder.text.getTypeface()));
        if (message.role == CtoChatMessage.Role.USER)
            holder.text.setText(message.content);
        else
            mMarkwon.setMarkdown(holder.text, message.content);
    }

    private static Typeface getVazirmatn(Typeface fallback) {
        if (sVazirmatn == null)
            return fallback;
        return sVazirmatn;
    }

    public static void setVazirmatn(Typeface typeface) {
        sVazirmatn = typeface;
    }

    @Override
    public int getItemCount() {
        return mMessages.size();
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final TextView text;
        final TextView time;
        final CtoTypingView typing;

        ViewHolder(@NonNull View itemView, int viewType) {
            super(itemView);
            text = itemView.findViewById(R.id.cto_message_text);
            time = itemView.findViewById(R.id.cto_message_time);
            typing = itemView.findViewById(R.id.cto_message_typing);
        }
    }
}
