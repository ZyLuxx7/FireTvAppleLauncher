package com.appletv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class LauncherFolders {
    private static final String TAG = "AppleTv.Folders";
    private static final String PREF_KEY = "app_folders";

    private LauncherFolders() {}

    static List<Folder> load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        String saved = preferences.getString(PREF_KEY, "[]");
        List<Folder> result = new ArrayList<>();
        try {
            JSONArray folders = new JSONArray(saved);
            for (int i = 0; i < folders.length(); i++) {
                JSONObject item = folders.optJSONObject(i);
                if (item == null) continue;
                String name = item.optString("name", "").trim();
                JSONArray packages = item.optJSONArray("packages");
                if (name.isEmpty() || packages == null) continue;
                List<String> members = new ArrayList<>();
                for (int j = 0; j < packages.length(); j++) {
                    String pkg = packages.optString(j, "").trim();
                    if (!pkg.isEmpty() && !members.contains(pkg)) members.add(pkg);
                }
                if (members.size() >= 2) result.add(new Folder(name, members));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Could not read saved app folders", e);
        }
        return result;
    }

    static boolean save(Context context, List<Folder> folders) {
        JSONArray serialized = new JSONArray();
        try {
            for (Folder folder : folders) {
                JSONObject item = new JSONObject();
                item.put("name", folder.name);
                JSONArray packages = new JSONArray();
                for (String pkg : folder.packages) packages.put(pkg);
                item.put("packages", packages);
                serialized.put(item);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Could not serialize app folders", e);
            return false;
        }
        return context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit()
                .putString(PREF_KEY, serialized.toString()).commit();
    }

    static final class Folder {
        final String name;
        final List<String> packages;

        Folder(String name, List<String> packages) {
            this.name = name;
            this.packages = new ArrayList<>(packages);
        }
    }
}
