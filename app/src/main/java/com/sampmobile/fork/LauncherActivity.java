package com.sampmobile.fork;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.sampmobile.fork.data.ServerEntry;
import com.sampmobile.fork.data.ServerStore;

import java.util.List;

/**
 * Entry screen: nickname, favorite server list, Play button.
 * The in-game server browser (native, ImGui) takes over once connected.
 */
public class LauncherActivity extends Activity {

    private static final int ACCENT = Color.rgb(240, 180, 41);
    private static final int CARD = Color.rgb(29, 35, 52);
    private static final int BG = Color.rgb(18, 21, 31);
    private static final int WHITE = Color.rgb(245, 247, 255);
    private static final int GRAY = Color.rgb(154, 160, 180);

    private List<ServerEntry> servers;
    private ServerAdapter adapter;
    private EditText nicknameInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        servers = ServerStore.loadServers(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = dp(20);
        root.setPadding(pad, dp(28), pad, pad);

        TextView title = label("SA-MP Mobile", 30, true);
        title.setTextColor(ACCENT);
        TextView subtitle = label("San Andreas Multiplayer for Android", 14, false);
        subtitle.setTextColor(GRAY);
        root.addView(title);
        root.addView(subtitle);
        root.addView(gap(18));

        root.addView(label("Nickname", 14, true));
        root.addView(gap(6));
        nicknameInput = new EditText(this);
        nicknameInput.setHint("Nickname");
        nicknameInput.setHintTextColor(GRAY);
        nicknameInput.setTextColor(WHITE);
        nicknameInput.setSingleLine(true);
        nicknameInput.setText(ServerStore.getNickname(this));
        nicknameInput.setBackground(cardBg());
        nicknameInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(nicknameInput);
        root.addView(gap(18));

        LinearLayout serverHeader = new LinearLayout(this);
        serverHeader.setOrientation(LinearLayout.HORIZONTAL);
        serverHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView serverLabel = label("Servers", 16, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        serverHeader.addView(serverLabel, lp);
        ImageButton addBtn = new ImageButton(this);
        addBtn.setImageResource(R.drawable.ic_add);
        addBtn.setColorFilter(ACCENT);
        addBtn.setBackgroundColor(Color.TRANSPARENT);
        addBtn.setOnClickListener(v -> showServerDialog(-1));
        serverHeader.addView(addBtn);
        root.addView(serverHeader);
        root.addView(gap(6));

        ListView list = new ListView(this);
        adapter = new ServerAdapter();
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setDividerHeight(dp(8));
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(list, listLp);
        list.setOnItemClickListener((parent, view, position, id) -> {
            ServerStore.setSelected(this, position);
            adapter.notifyDataSetChanged();
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showServerOptions(position);
            return true;
        });
        root.addView(gap(14));

        TextView hint = label("Tap a server to select it. Long-press for options.", 12, false);
        hint.setTextColor(GRAY);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(hint);
        root.addView(gap(10));

        Button play = new Button(this);
        play.setText("PLAY");
        play.setTextSize(18);
        play.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        play.setTextColor(Color.rgb(20, 24, 31));
        play.setBackground(accentBg());
        play.setPadding(dp(12), dp(14), dp(12), dp(14));
        play.setOnClickListener(v -> onPlay());
        root.addView(play);

        setContentView(root);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ServerStore.setNickname(this, nicknameInput.getText().toString().trim());
        ServerStore.saveServers(this, servers);
    }

    private void onPlay() {
        String nickname = nicknameInput.getText().toString().trim();
        if (nickname.isEmpty()) {
            Toast.makeText(this, "Enter a nickname first", Toast.LENGTH_SHORT).show();
            return;
        }
        int selected = ServerStore.getSelected(this);
        if (selected < 0 || selected >= servers.size()) {
            Toast.makeText(this, "Select a server first", Toast.LENGTH_SHORT).show();
            return;
        }
        ServerStore.setNickname(this, nickname);
        ServerStore.saveServers(this, servers);
        ServerEntry s = servers.get(selected);
        Intent i = new Intent(this, GameActivity.class);
        i.putExtra("nickname", nickname);
        i.putExtra("ip", s.host);
        i.putExtra("port", s.port);
        startActivity(i);
    }

    private void showServerDialog(int editIndex) {
        ServerEntry existing = editIndex >= 0 ? servers.get(editIndex) : null;

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), dp(4), dp(8), dp(4));

        EditText nameIn = field(existing != null ? existing.name : "", "Server name");
        EditText hostIn = field(existing != null ? existing.host : "", "IP address (e.g. 127.0.0.1)");
        EditText portIn = field(existing != null ? String.valueOf(existing.port) : "7777", "Port");
        portIn.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(nameIn); form.addView(gap(8));
        form.addView(hostIn); form.addView(gap(8));
        form.addView(portIn);

        new AlertDialog.Builder(this)
                .setTitle(editIndex >= 0 ? "Edit server" : "Add server")
                .setView(form)
                .setPositiveButton("Save", (d, w) -> {
                    String name = nameIn.getText().toString().trim();
                    String host = hostIn.getText().toString().trim();
                    int port;
                    try { port = Integer.parseInt(portIn.getText().toString().trim()); }
                    catch (NumberFormatException e) { port = 7777; }
                    if (host.isEmpty()) {
                        Toast.makeText(this, "IP address is required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (name.isEmpty()) name = host + ":" + port;
                    ServerEntry entry = new ServerEntry(name, host, port);
                    if (editIndex >= 0) servers.set(editIndex, entry);
                    else {
                        servers.add(entry);
                        ServerStore.setSelected(this, servers.size() - 1);
                    }
                    ServerStore.saveServers(this, servers);
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showServerOptions(int position) {
        ServerEntry s = servers.get(position);
        new AlertDialog.Builder(this)
                .setTitle(s.name)
                .setItems(new String[]{"Edit", "Delete"}, (d, which) -> {
                    if (which == 0) {
                        showServerDialog(position);
                    } else {
                        servers.remove(position);
                        int sel = ServerStore.getSelected(this);
                        if (sel == position) ServerStore.setSelected(this, -1);
                        else if (sel > position) ServerStore.setSelected(this, sel - 1);
                        ServerStore.saveServers(this, servers);
                        adapter.notifyDataSetChanged();
                    }
                })
                .show();
    }

    // ---------------------------------------------------------- UI helpers --
    private class ServerAdapter extends ArrayAdapter<ServerEntry> {
        ServerAdapter() {
            super(LauncherActivity.this, 0, servers);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = new LinearLayout(LauncherActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            boolean selected = position == ServerStore.getSelected(LauncherActivity.this);
            row.setBackground(selected ? accentBg() : cardBg());
            row.setPadding(dp(14), dp(12), dp(14), dp(12));

            ServerEntry s = getItem(position);
            TextView name = label(s.name, 16, true);
            name.setTextColor(selected ? Color.rgb(20, 24, 31) : WHITE);
            TextView addr = label(s.address(), 13, false);
            addr.setTextColor(selected ? Color.rgb(60, 50, 20) : GRAY);
            row.addView(name);
            row.addView(addr);
            return row;
        }
    }

    private TextView label(String s, int size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(WHITE);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private EditText field(String value, String hint) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setHintTextColor(GRAY);
        e.setTextColor(WHITE);
        e.setSingleLine(true);
        e.setBackground(cardBg());
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        return e;
    }

    private View gap(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    private GradientDrawable cardBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(CARD);
        d.setCornerRadius(dp(12));
        return d;
    }

    private GradientDrawable accentBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(ACCENT);
        d.setCornerRadius(dp(12));
        return d;
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }
}
