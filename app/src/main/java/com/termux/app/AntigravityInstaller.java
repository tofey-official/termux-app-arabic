package com.termux.app;

import android.content.Context;
import android.os.Build;
import android.os.StatFs;
import android.system.ErrnoException;

import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;

/**
 * Installs the antigravity CLI (the standalone binaries published by the
 * "wallentx/antigravity-cli-termux" repository) into "$PREFIX/bin" on app start, so users do
 * not have to run the official installer script manually.
 *
 * <p>The "agy" launcher and its "agy.va39" payload are bundled inside the APK as the
 * "antigravity-standalone.tgz" asset (already gzip compressed), so installation is fully
 * offline. The binaries are glibc-linked and require the glibc runtime that this fork also
 * bundles (see {@link TermuxInstaller#setupGlibcIfNeeded}). Once installed, the "agy"
 * launcher keeps itself and the payload up to date on its own, so this installer only
 * extracts the files when they are missing: no work is done on the common path where they
 * already exist.</p>
 */
final class AntigravityInstaller {

    private static final String LOG_TAG = "AntigravityInstaller";

    /**
     * APK asset containing the standalone antigravity binaries ("agy" and "agy.va39"),
     * taken from the v1.5.9 release of "wallentx/antigravity-cli-termux"
     * (antigravity-termux-standalone.tar.gz, sha256 20e25be0ee3e303ed67ad8a87833099653095fa5ee6583624f82ec49b02b0169).
     */
    private static final String AGY_STANDALONE_ASSET = "antigravity-standalone.tgz";

    /** Prefix-relative destination paths, identical to what the official installer uses. */
    private static final String AGY_BIN_PATH = TERMUX_PREFIX_DIR_PATH + "/bin/agy";
    private static final String AGY_VA39_BIN_PATH = TERMUX_PREFIX_DIR_PATH + "/bin/agy.va39";
    private static final String AGY_TMP_DIR_PATH = TERMUX_PREFIX_DIR_PATH + "/tmp";

    /** Free space needed for the ~240 MB extracted payload staged in "$PREFIX/tmp". */
    private static final long MIN_FREE_SPACE_BYTES = 400 * 1024 * 1024L;

    static void setupIfNeeded(final Context context) {
        new Thread() {
            @Override
            public void run() {
                installBlocking(context);
            }
        }.start();
    }

    /**
     * Blocking variant of {@link #setupIfNeeded} that installs the bundled binaries when they
     * are missing and returns whether "agy" exists afterwards. Safe to call while an async
     * {@link #setupIfNeeded} run is in progress: both synchronize on the class monitor, so
     * this simply waits for the other run and then re-checks the files.
     */
    static boolean installBlocking(final Context context) {
        try {
            synchronized (AntigravityInstaller.class) {
                // The standalone binaries are only built for 64-bit arm devices.
                boolean isArm64Device = false;
                for (String abi : Build.SUPPORTED_ABIS) {
                    if ("arm64-v8a".equals(abi)) {
                        isArm64Device = true;
                        break;
                    }
                }
                if (!isArm64Device) {
                    Logger.logInfo(LOG_TAG, "Antigravity setup skipped: no arm64-v8a support on device.");
                    return false;
                }

                if (FileUtils.regularFileExists(AGY_BIN_PATH, false)
                    && FileUtils.regularFileExists(AGY_VA39_BIN_PATH, false)) {
                    Logger.logInfo(LOG_TAG, "Antigravity CLI already installed.");
                    return true;
                }

                Logger.logInfo(LOG_TAG, "Installing bundled antigravity CLI.");

                if (!checkFreeSpace()) {
                    Logger.logErrorExtended(LOG_TAG, "Antigravity setup skipped: not enough free space. It will be retried on next app start.");
                    return false;
                }

                installBinariesFromBundledTarball(context);

                Logger.logInfo(LOG_TAG, "Antigravity CLI installed successfully to \"" + TERMUX_PREFIX_DIR_PATH + "/bin\".");
                return FileUtils.regularFileExists(AGY_BIN_PATH, false);
            }
        } catch (final Exception e) {
            // Automatic antigravity installation is a best-effort addition; failures are
            // only logged and retried on the next app start or chat message.
            Logger.logErrorExtended(LOG_TAG, "Antigravity setup error:\n" + Logger.getStackTracesStringArray(e)[0]);
            return false;
        }
    }

    private static boolean checkFreeSpace() {
        try {
            StatFs statFs = new StatFs(TERMUX_PREFIX_DIR_PATH);
            return statFs.getAvailableBytes() >= MIN_FREE_SPACE_BYTES;
        } catch (final Exception e) {
            return true; // Do not block installation if the free space check itself fails.
        }
    }

    /** Extracts "agy" and "agy.va39" from the tarball bundled in the APK assets into "$PREFIX/bin". */
    private static void installBinariesFromBundledTarball(Context context) throws IOException, ErrnoException {
        File installDir = new File(AGY_TMP_DIR_PATH, "agy-install." + System.nanoTime());
        try {
            installDir.mkdirs();
            if (!installDir.isDirectory())
                throw new IOException("Failed to create antigravity install staging directory: " + installDir.getAbsolutePath());

            Map<String, File> targets = new HashMap<>();
            targets.put("agy", new File(installDir, "agy"));
            targets.put("agy.va39", new File(installDir, "agy.va39"));

            try (InputStream in = new GZIPInputStream(context.getAssets().open(AGY_STANDALONE_ASSET), 65536)) {
                TarExtractor.extractEntries(in, targets);
            }

            File agyBin = new File(AGY_BIN_PATH);
            File agyVa39Bin = new File(AGY_VA39_BIN_PATH);
            moveIntoBin(new File(installDir, "agy"), agyBin);
            moveIntoBin(new File(installDir, "agy.va39"), agyVa39Bin);
            //noinspection OctalInteger
            android.system.Os.chmod(agyBin.getAbsolutePath(), 0700);
            //noinspection OctalInteger
            android.system.Os.chmod(agyVa39Bin.getAbsolutePath(), 0700);
        } finally {
            deleteRecursively(installDir);
        }
    }

    private static void moveIntoBin(File source, File target) throws IOException {
        if (!source.isFile() || source.length() == 0)
            throw new IOException("Missing or empty entry \"" + source.getName() + "\" in bundled antigravity tarball");
        if (!source.renameTo(target))
            throw new IOException("Failed to move " + source.getAbsolutePath() + " to " + target.getAbsolutePath());
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children)
                deleteRecursively(child);
        }
        file.delete();
    }
}
