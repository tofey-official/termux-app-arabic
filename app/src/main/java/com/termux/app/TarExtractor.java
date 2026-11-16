package com.termux.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Minimal dependency-free tar reader that extracts selected regular-file entries from a tar
 * stream (the caller wraps the input stream in GZIPInputStream for .tar.gz archives).
 *
 * Handles plain ustar, GNU ("ustar  \0") and POSIX pax headers, including GNU long-name
 * ('L') records and base-256 sizes. Everything that is not a requested regular file
 * (directories, symlinks, pax metadata) is consumed and skipped.
 */
public final class TarExtractor {

    private static final int BLOCK_SIZE = 512;

    private TarExtractor() {
    }

    /**
     * Extract every entry whose name matches a key of {@code targets} to the mapped file.
     *
     * @param in      the raw (uncompressed) tar byte stream; positioned at the first header
     * @param targets entry name (as stored in the tar) to destination file
     * @throws IOException on malformed headers or if the stream ends before all targets were found
     */
    public static void extractEntries(InputStream in, Map<String, File> targets) throws IOException {
        String pendingLongName = null;
        int remainingTargets = targets.size();

        while (remainingTargets > 0) {
            byte[] header = readFully(in, BLOCK_SIZE);
            if (header == null)
                throw new IOException("Unexpected end of tar stream; missing entries: " + targets.keySet());

            if (isZeroBlock(header))
                continue; // Skip the zero blocks at the end of the archive and any padding.

            String name = readHeaderString(header, 0, 100);
            if (name.isEmpty())
                throw new IOException("Invalid tar header: empty name");

            long size = readHeaderSize(header, 124, 12);
            long dataBlocks = (size + BLOCK_SIZE - 1) / BLOCK_SIZE;

            int typeFlag = header[156] & 0xFF;
            boolean hasUstarMagic = readHeaderString(header, 257, 6).startsWith("ustar");

            switch (typeFlag) {
                case 'L': // GNU long name: the data block holds the name of the next entry.
                    if (!hasUstarMagic) throw new IOException("Invalid tar header before GNU long-name entry");
                    byte[] nameData = readFully(in, (int) dataBlocks * BLOCK_SIZE);
                    if (nameData == null) throw new IOException("Unexpected end of tar stream in long-name entry");
                    pendingLongName = readNullTerminatedString(nameData, (int) size);
                    break;
                case '0':
                case 0: // Regular file.
                    String entryName = pendingLongName != null ? pendingLongName : name;
                    String fileName;
                    if (pendingLongName != null) {
                        fileName = entryName;
                    } else if (hasUstarMagic) {
                        String prefix = readHeaderString(header, 345, 155);
                        fileName = prefix.isEmpty() ? entryName : prefix + "/" + entryName;
                    } else {
                        fileName = entryName;
                    }
                    pendingLongName = null;

                    File target = targets.get(fileName);
                    if (target != null) {
                        copyFully(in, target, size);
                        skipFully(in, dataBlocks * BLOCK_SIZE - size);
                        targets.remove(fileName);
                        remainingTargets--;
                    } else {
                        skipFully(in, dataBlocks * BLOCK_SIZE);
                    }
                    break;
                default: // Directories, symlinks, pax metadata ('x', 'g'), etc. — skip the data.
                    pendingLongName = null;
                    skipFully(in, dataBlocks * BLOCK_SIZE);
                    break;
            }
        }
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block)
            if (b != 0) return false;
        return true;
    }

    private static String readHeaderString(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) end++;
        return new String(header, offset, end - offset, java.nio.charset.StandardCharsets.UTF_8).trim();
    }

    private static String readNullTerminatedString(byte[] data, int length) {
        int end = 0;
        while (end < length && data[end] != 0) end++;
        return new String(data, 0, end, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Parses an octal size field, falling back to GNU base-256 when the high bit is set. */
    private static long readHeaderSize(byte[] header, int offset, int length) {
        if ((header[offset] & 0x80) != 0) {
            long value = header[offset] & 0x7F;
            for (int i = offset + 1; i < offset + length; i++)
                value = (value << 8) | (header[i] & 0xFF);
            return value;
        }
        long value = 0;
        for (int i = offset; i < offset + length; i++) {
            byte b = header[i];
            if (b == ' ' || b == 0) continue;
            if (b < '0' || b > '7')
                throw new NumberFormatException("Invalid octal byte in tar size field: " + b);
            value = (value << 3) | (b - '0');
        }
        return value;
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(buffer, read, length - read);
            if (n < 0) return null; // EOF.
            read += n;
        }
        return buffer;
    }

    private static void copyFully(InputStream in, File target, long length) throws IOException {
        try (FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8096];
            long copied = 0;
            while (copied < length) {
                int toRead = (int) Math.min(buffer.length, length - copied);
                int n = in.read(buffer, 0, toRead);
                if (n < 0) throw new IOException("Unexpected end of tar stream inside file entry " + target.getName());
                out.write(buffer, 0, n);
                copied += n;
            }
        }
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        long skipped = 0;
        while (skipped < count) {
            long n = in.skip(count - skipped);
            if (n <= 0) {
                if (in.read() < 0)
                    throw new IOException("Unexpected end of tar stream while skipping padding");
                skipped++;
            } else {
                skipped += n;
            }
        }
    }
}
