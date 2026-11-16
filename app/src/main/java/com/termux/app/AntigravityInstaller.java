package com.termux.app;

import android.content.Context;
import android.os.Build;
import android.os.StatFs;
import android.system.ErrnoException;

import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;

/**
 * Automatically installs the antigravity CLI (the standalone binaries published by the
 * "wallentx/antigravity-cli-termux" repository) into "$PREFIX/bin" on app start, so users do
 * not have to run the official installer script manually.
 *
 * <p>The binaries are glibc-linked and require the glibc runtime that this fork bundles
 * (see {@link TermuxInstaller#setupGlibcIfNeeded}). Once installed, the "agy" launcher keeps
 * itself and the "agy.va39" payload up to date on its own, so this installer only seeds the
 * files when they are missing: no network calls are made on the common path where they
 * already exist.</p>
 */
final class AntigravityInstaller {

    private static final String LOG_TAG = "AntigravityInstaller";

    /** GitHub repository that publishes the standalone antigravity binaries. */
    private static final String AGY_REPO = "wallentx/antigravity-cli-termux";
    /** Name of the release asset that contains the "agy" and "agy.va39" binaries. */
    private static final String AGY_RELEASE_ASSET = "antigravity-termux-standalone.tar.gz";
    /** API URL used to resolve the latest release download URL. */
    private static final String AGY_RELEASES_API_URL =
        "https://api.github.com/repos/" + AGY_REPO + "/releases/latest";

    /** Prefix-relative destination paths, identical to what the official installer uses. */
    private static final String AGY_BIN_PATH = TERMUX_PREFIX_DIR_PATH + "/bin/agy";
    private static final String AGY_VA39_BIN_PATH = TERMUX_PREFIX_DIR_PATH + "/bin/agy.va39";
    private static final String AGY_TMP_DIR_PATH = TERMUX_PREFIX_DIR_PATH + "/tmp";

    /** Free space needed for the ~90 MB download plus the ~240 MB extracted payload. */
    private static final long MIN_FREE_SPACE_BYTES = 600 * 1024 * 1024L;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 60000;
    private static final int MAX_REDIRECTS = 8;

    static void setupIfNeeded(final Context context) {
        new Thread() {
            @Override
            public void run() {
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
                            return;
                        }

                        File agyBin = new File(AGY_BIN_PATH);
                        File agyVa39Bin = new File(AGY_VA39_BIN_PATH);
                        if (FileUtils.regularFileExists(agyBin.getAbsolutePath(), false)
                            && FileUtils.regularFileExists(agyVa39Bin.getAbsolutePath(), false)) {
                            Logger.logInfo(LOG_TAG, "Antigravity CLI already installed.");
                            return;
                        }

                        Logger.logInfo(LOG_TAG, "Installing antigravity CLI automatically.");

                        if (!checkFreeSpace()) {
                            Logger.logErrorExtended(LOG_TAG, "Antigravity setup skipped: not enough free space. It will be retried on next app start.");
                            return;
                        }

                        File tarballFile = new File(AGY_TMP_DIR_PATH, AGY_RELEASE_ASSET);
                        try {
                            String downloadUrl = resolveLatestReleaseAssetUrl();
                            Logger.logInfo(LOG_TAG, "Downloading " + downloadUrl);
                            downloadFile(downloadUrl, tarballFile);

                            installBinariesFromTarball(tarballFile);

                            Logger.logInfo(LOG_TAG, "Antigravity CLI installed successfully to \"" + TERMUX_PREFIX_DIR_PATH + "/bin\".");
                        } finally {
                            tarballFile.delete();
                        }
                    }
                } catch (final Exception e) {
                    // Automatic antigravity installation is a best-effort addition; failures are
                    // only logged and retried on the next app start.
                    Logger.logErrorExtended(LOG_TAG, "Antigravity setup error:\n" + Logger.getStackTracesStringArray(e)[0]);
                }
            }
        }.start();
    }

    private static boolean checkFreeSpace() {
        try {
            StatFs statFs = new StatFs(TERMUX_PREFIX_DIR_PATH);
            return statFs.getAvailableBytes() >= MIN_FREE_SPACE_BYTES;
        } catch (final Exception e) {
            return true; // Do not block installation if the free space check itself fails.
        }
    }

    /** Resolves the browser download URL of the latest "antigravity-termux-standalone.tar.gz" asset. */
    private static String resolveLatestReleaseAssetUrl() throws IOException, JSONException {
        HttpURLConnection connection = openConnection(AGY_RELEASES_API_URL);
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK)
                throw new IOException("GitHub releases API returned HTTP " + responseCode + " for " + AGY_RELEASES_API_URL);

            StringBuilder response = new StringBuilder();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[8096];
                int readBytes;
                while ((readBytes = in.read(buffer)) != -1)
                    response.append(new String(buffer, 0, readBytes, StandardCharsets.UTF_8));
            }

            JSONObject release = new JSONObject(response.toString());
            JSONArray assets = release.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (AGY_RELEASE_ASSET.equals(asset.optString("name"))) {
                    String url = asset.optString("browser_download_url", "");
                    if (!url.isEmpty()) return url;
                }
            }
            throw new IOException("Release asset \"" + AGY_RELEASE_ASSET + "\" not found in latest release of " + AGY_REPO);
        } finally {
            connection.disconnect();
        }
    }

    /** Downloads {@code downloadUrl} to {@code target}, following redirects manually. */
    private static void downloadFile(String downloadUrl, File target) throws IOException {
        target.getParentFile().mkdirs();

        String currentUrl = downloadUrl;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            HttpURLConnection connection = openConnection(currentUrl);
            try {
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM
                    || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                    || responseCode == 307 || responseCode == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isEmpty())
                        throw new IOException("HTTP " + responseCode + " redirect without Location header");
                    currentUrl = location;
                    continue;
                }
                if (responseCode != HttpURLConnection.HTTP_OK)
                    throw new IOException("Download returned HTTP " + responseCode + " for " + currentUrl);

                try (InputStream in = connection.getInputStream();
                     FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[8096];
                    int readBytes;
                    while ((readBytes = in.read(buffer)) != -1)
                        out.write(buffer, 0, readBytes);
                }
                if (target.length() == 0)
                    throw new IOException("Downloaded file is empty: " + currentUrl);
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many redirects while downloading " + downloadUrl);
    }

    /** Extracts "agy" and "agy.va39" from the downloaded tarball and installs them into "$PREFIX/bin". */
    private static void installBinariesFromTarball(File tarballFile) throws IOException, ErrnoException {
        File installDir = new File(AGY_TMP_DIR_PATH, "agy-install." + System.nanoTime());
        try {
            installDir.mkdirs();
            if (!installDir.isDirectory())
                throw new IOException("Failed to create antigravity install staging directory: " + installDir.getAbsolutePath());

            Map<String, File> targets = new HashMap<>();
            targets.put("agy", new File(installDir, "agy"));
            targets.put("agy.va39", new File(installDir, "agy.va39"));

            try (InputStream in = new GZIPInputStream(new FileInputStream(tarballFile), 65536)) {
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
            throw new IOException("Missing or empty entry \"" + source.getName() + "\" in antigravity tarball");
        if (!source.renameTo(target))
            throw new IOException("Failed to move " + source.getAbsolutePath() + " to " + target.getAbsolutePath());
    }

    private static HttpURLConnection openConnection(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "termux-antigravity-installer");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("Accept", "application/octet-stream");
        return connection;
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
