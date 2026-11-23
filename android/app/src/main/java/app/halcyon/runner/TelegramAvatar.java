package app.halcyon.runner;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.Html;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

final class TelegramAvatar {
    private static final Pattern IMAGE = Pattern.compile(
        "<meta\\s+property=\"og:image\"\\s+content=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private TelegramAvatar() {}

    static Bitmap fetch(String username) throws IOException {
        String profile = new String(read(new URL("https://t.me/" + username), 128 * 1024),
            StandardCharsets.UTF_8);
        Matcher image = IMAGE.matcher(profile);
        if (!image.find()) return null;
        URL url = new URL(Html.fromHtml(image.group(1), Html.FROM_HTML_MODE_LEGACY).toString());
        String host = url.getHost().toLowerCase(Locale.ROOT);
        if (!url.getProtocol().equals("https") || !host.endsWith(".telesco.pe")) return null;

        byte[] data = read(url, 512 * 1024);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (options.outWidth < 1 || options.outHeight < 1 ||
            options.outWidth > 2048 || options.outHeight > 2048) return null;
        options.inJustDecodeBounds = false;
        options.inSampleSize = Math.max(1, Math.max(options.outWidth, options.outHeight) / 256);
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    private static byte[] read(URL url, int limit) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Telegram profile unavailable");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) != -1;) {
                    if (output.size() + count > limit) throw new IOException("Telegram profile response is too large");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }
}
