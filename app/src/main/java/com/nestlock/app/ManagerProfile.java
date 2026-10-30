package com.nestlock.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** One manager/driver pair. Everything device- or manager-specific lives here. */
final class ManagerProfile {
    final String id;
    final String displayName;
    final String managerPackage;
    final String managerActivity;
    final String lkmAsset;
    final String ksudAsset;
    final String profileAsset;
    final String nativeLib;
    final String ksudPath;
    final String ksudLink;
    final String moduleName;
    final List<String> insmodArgs;
    final List<String> registerCommand;
    final String register;
    /**
     * True when the LKM's built-in APK signature already recognises this
     * manager, so no userspace dynamic-manager registration is needed.
     */
    final boolean builtinManager;
    final String unameRelease;
    final int uapi;
    final String kmi;

    private ManagerProfile(
            String id,
            String displayName,
            String managerPackage,
            String managerActivity,
            String lkmAsset,
            String ksudAsset,
            String profileAsset,
            String nativeLib,
            String ksudPath,
            String ksudLink,
            String moduleName,
            List<String> insmodArgs,
            List<String> registerCommand,
            String register,
            boolean builtinManager,
            String unameRelease,
            int uapi,
            String kmi) {
        this.id = id;
        this.displayName = displayName;
        this.managerPackage = managerPackage;
        this.managerActivity = managerActivity;
        this.lkmAsset = lkmAsset;
        this.ksudAsset = ksudAsset;
        this.profileAsset = profileAsset;
        this.nativeLib = nativeLib;
        this.ksudPath = ksudPath;
        this.ksudLink = ksudLink;
        this.moduleName = moduleName;
        this.insmodArgs = insmodArgs;
        this.registerCommand = registerCommand;
        this.register = register;
        this.builtinManager = builtinManager;
        this.unameRelease = unameRelease;
        this.uapi = uapi;
        this.kmi = kmi;
    }

    static ManagerProfile fromJson(JSONObject json) {
        String id = json.optString("id", "").trim();
        if (!id.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("invalid profile id: " + id);
        }
        String displayName = json.optString("displayName", id).trim();
        if (displayName.isEmpty()) {
            displayName = id;
        }
        String managerPackage = json.optString("managerPackage", "").trim();
        String managerActivity = json.optString("managerActivity", "").trim();
        String lkmAsset = json.optString("lkmAsset", "").trim();
        String ksudAsset = json.optString("ksudAsset", "").trim();
        String profileAsset = json.optString("profileAsset", "").trim();
        if (lkmAsset.isEmpty() || ksudAsset.isEmpty() || profileAsset.isEmpty()) {
            throw new IllegalArgumentException("profile " + id + " is missing an asset path");
        }
        if (lkmAsset.contains("..") || ksudAsset.contains("..") || profileAsset.contains("..")) {
            throw new IllegalArgumentException("profile " + id + " has an invalid asset path");
        }

        String nativeLib = json.optString("nativeLib", "libghostlock.so").trim();
        String ksudPath = json.optString("ksudPath", "/data/adb/ksud").trim();
        String ksudLink = json.optString("ksudLink", "").trim();
        String moduleName = json.optString("moduleName", "kernelsu").trim();
        if (!moduleName.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("profile " + id + " has an invalid moduleName");
        }
        if (ksudPath.isEmpty()) {
            throw new IllegalArgumentException("profile " + id + " has an empty ksudPath");
        }

        List<String> insmodArgs = stringList(json.optJSONArray("insmodArgs"));
        if (insmodArgs.isEmpty()) {
            insmodArgs = new ArrayList<>(Arrays.asList(
                    "{ksud}", "insmod", "{lkm}", "allow_shell=1"));
        }
        List<String> registerCommand = stringList(json.optJSONArray("registerCommand"));

        return new ManagerProfile(
                id,
                displayName,
                managerPackage,
                managerActivity,
                lkmAsset,
                ksudAsset,
                profileAsset,
                nativeLib,
                ksudPath,
                ksudLink,
                moduleName,
                Collections.unmodifiableList(insmodArgs),
                Collections.unmodifiableList(registerCommand),
                json.optString("register", "none").trim(),
                json.optBoolean("builtinManager", false),
                json.optString("unameRelease", "").trim(),
                json.optInt("uapi", 0),
                json.optString("kmi", "").trim());
    }

    private static List<String> stringList(JSONArray array) {
        List<String> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "");
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }

    boolean hasManager() {
        return !managerPackage.isEmpty();
    }

    List<String> effectiveRegisterCommand() {
        if (!registerCommand.isEmpty()) {
            return registerCommand;
        }
        if ("dynamic-manager".equals(register)) {
            return Arrays.asList("{ksud}", "kernel", "dynamic-manager", "set-apk", "{apk}");
        }
        return Collections.emptyList();
    }
}
