package com.nestlock.app;

import android.content.Context;
import android.system.Os;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Copies the profile's LKM/ksud and writes the generated loader. */
final class AssetInstaller {
    static final class Installed {
        final File base;
        final File lkm;
        final File ksud;
        final File loadScript;
        final File status;
        final File ksuLog;
        final File nativeLog;
        final File binDir;

        Installed(File base, File lkm, File ksud, File loadScript, File status,
                  File ksuLog, File nativeLog, File binDir) {
            this.base = base;
            this.lkm = lkm;
            this.ksud = ksud;
            this.loadScript = loadScript;
            this.status = status;
            this.ksuLog = ksuLog;
            this.nativeLog = nativeLog;
            this.binDir = binDir;
        }
    }

    private AssetInstaller() {
    }

    static Installed install(Context context, ManagerProfile profile) throws IOException {
        File base = new File(context.getFilesDir(), "profiles/" + profile.id);
        ensureDir(base);

        File lkm = new File(base, "lkm.ko");
        copyAsset(context, profile.lkmAsset, lkm);
        chmod(lkm, 0700);

        File ksud = new File(base, "ksud");
        copyAsset(context, profile.ksudAsset, ksud);
        chmod(ksud, 0700);

        File binDir = null;
        if (!profile.unameRelease.isEmpty()) {
            binDir = new File(base, "bin");
            ensureDir(binDir);
            File uname = new File(binDir, "uname");
            writeText(uname, "#!/system/bin/sh\n"
                    + "if [ \"$1\" = \"-r\" ]; then\n"
                    + "  echo " + Shell.quote(profile.unameRelease) + "\n"
                    + "else\n"
                    + "  exec /system/bin/uname \"$@\"\n"
                    + "fi\n");
            chmod(uname, 0700);
        }

        File status = new File(base, "status.txt");
        File ksuLog = new File(base, "ghostlock-ksu.log");
        File nativeLog = new File(base, "native.log");
        File loadScript = new File(base, "load.sh");
        /* Resolve the manager APK in the app domain: the root loader runs in
         * u:r:kernel:s0, where `pm path` is denied. */
        String managerApk = "";
        if (!profile.managerPackage.isEmpty()) {
            try {
                managerApk = context.getPackageManager()
                        .getPackageInfo(profile.managerPackage, 0)
                        .applicationInfo.sourceDir;
            } catch (Exception ignored) {
                managerApk = "";
            }
        }
        writeText(loadScript, LoaderScript.generate(profile, lkm, ksud, status, managerApk));
        chmod(loadScript, 0700);

        return new Installed(base, lkm, ksud, loadScript, status, ksuLog, nativeLog, binDir);
    }

    static byte[] readAsset(Context context, String path) throws IOException {
        try (InputStream in = context.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static void copyAsset(Context context, String path, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null) {
            ensureDir(parent);
        }
        File tmp = new File(dest.getPath() + ".tmp");
        try (InputStream in = context.getAssets().open(path);
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
        }
        if (!tmp.renameTo(dest)) {
            if (!dest.delete() || !tmp.renameTo(dest)) {
                throw new IOException("cannot install " + dest);
            }
        }
    }

    static void ensureDir(File dir) throws IOException {
        if (dir.isDirectory()) {
            return;
        }
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("cannot create " + dir);
        }
    }

    static void writeText(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    static String readText(File file) {
        if (!file.isFile()) {
            return "";
        }
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            return "";
        }
    }

    static void chmod(File file, int mode) {
        try {
            Os.chmod(file.getAbsolutePath(), mode);
        } catch (Exception ignored) {
            // File.setExecutable is a weaker fallback; the owner is this app.
            file.setReadable(true, true);
            file.setWritable(true, true);
            file.setExecutable(true, true);
        }
    }
}
