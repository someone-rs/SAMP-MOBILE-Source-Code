package com.sampmobile.fork.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Persists the favorite server list and the player nickname. */
public final class ServerStore {
    private static final String PREFS = "samp_launcher";
    private static final String KEY_SERVERS = "servers";
    private static final String KEY_NICKNAME = "nickname";
    private static final String KEY_SELECTED = "selected_server";

    private ServerStore() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<ServerEntry> loadServers(Context ctx) {
        List<ServerEntry> out = new ArrayList<>();
        try {
            String raw = prefs(ctx).getString(KEY_SERVERS, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new ServerEntry(
                        o.optString("name", ""),
                        o.optString("host", ""),
                        o.optInt("port", 7777)));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void saveServers(Context ctx, List<ServerEntry> servers) {
        try {
            JSONArray arr = new JSONArray();
            for (ServerEntry s : servers) {
                JSONObject o = new JSONObject();
                o.put("name", s.name);
                o.put("host", s.host);
                o.put("port", s.port);
                arr.put(o);
            }
            prefs(ctx).edit().putString(KEY_SERVERS, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static String getNickname(Context ctx) {
        return prefs(ctx).getString(KEY_NICKNAME, "Player");
    }

    public static void setNickname(Context ctx, String nickname) {
        prefs(ctx).edit().putString(KEY_NICKNAME, nickname).apply();
    }

    public static int getSelected(Context ctx) {
        return prefs(ctx).getInt(KEY_SELECTED, -1);
    }

    public static void setSelected(Context ctx, int index) {
        prefs(ctx).edit().putInt(KEY_SELECTED, index).apply();
    }
}
