package app.halcyon.runner;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;

final class Journal {
    private static final int CHUNK_BYTES = 512 * 1024;
    private static final Pattern TERMINAL = Pattern.compile("\\x1B\\[[0-?]*[ -/]*[@-~]|[\\p{Cntrl}&&[^\\t]]");
    private Journal() {}

    static long revision(Context context) {
        File file = new File(context.getFilesDir(), "runner.log");
        return 31 * file.lastModified() + file.length();
    }

    static synchronized void append(Context context, String line) throws IOException {
        File file = new File(context.getFilesDir(), "runner.log");
        if (file.length() >= CHUNK_BYTES) {
            Files.move(file.toPath(), backup(context).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        String text = TERMINAL.matcher(line).replaceAll("");
        if (text.length() > 8192) text = text.substring(0, 8192) + "…";
        Files.write(file.toPath(), (text + "\n").getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static synchronized String text(Context context, int limit) throws IOException {
        File file = new File(context.getFilesDir(), "runner.log");
        String recent = tail(file, limit);
        int remaining = limit - (int) Math.min(limit, file.length());
        return remaining > 0 ? tail(backup(context), remaining) + recent : recent;
    }

    private static File backup(Context context) {
        return new File(context.getFilesDir(), "runner.previous.log");
    }

    private static String tail(File file, int limit) throws IOException {
        if (!file.isFile()) return "";
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long start = Math.max(0, input.length() - limit);
            input.seek(start);
            if (start > 0) input.readLine();
            byte[] bytes = new byte[(int) (input.length() - input.getFilePointer())];
            input.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
