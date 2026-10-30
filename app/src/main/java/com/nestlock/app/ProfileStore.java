package com.nestlock.app;

import android.content.Context;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Loads profiles from assets. Profiles are the only source of manager paths. */
final class ProfileStore {
    private static final String INDEX = "profiles/index.json";
    private static final String FALLBACK = "profiles/bakasu.json";

    private ProfileStore() {
    }

    static List<ManagerProfile> load(Context context) {
        List<ManagerProfile> profiles = new ArrayList<>();
        try (InputStream in = context.getAssets().open(INDEX)) {
            JSONObject index = new JSONObject(readAll(in));
            JSONArray paths = index.optJSONArray("profiles");
            if (paths != null) {
                for (int i = 0; i < paths.length(); i++) {
                    String path = paths.optString(i, "");
                    if (!path.isEmpty()) {
                        add(context, path, profiles);
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to the single-profile fallback.
        }
        if (profiles.isEmpty()) {
            add(context, FALLBACK, profiles);
        }
        return profiles;
    }

    private static void add(Context context, String path, List<ManagerProfile> out) {
        try (InputStream in = context.getAssets().open(path)) {
            ManagerProfile profile = ManagerProfile.fromJson(new JSONObject(readAll(in)));
            for (ManagerProfile existing : out) {
                if (existing.id.equals(profile.id)) {
                    return;
                }
            }
            out.add(profile);
        } catch (Exception ignored) {
            // A broken optional profile must not prevent the others from loading.
        }
    }

    static ManagerProfile selected(Context context) {
        List<ManagerProfile> profiles = load(context);
        if (profiles.isEmpty()) {
            return null;
        }
        String id = AppState.getProfileId(context);
        for (ManagerProfile profile : profiles) {
            if (profile.id.equals(id)) {
                return profile;
            }
        }
        return profiles.get(0);
    }

    static int selectedIndex(Context context, List<ManagerProfile> profiles) {
        String id = AppState.getProfileId(context);
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(id)) {
                return i;
            }
        }
        return 0;
    }

    static boolean isInstalled(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            out.write(buffer, 0, count);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
