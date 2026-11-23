package app.halcyon.runner;

import android.content.Context;
import android.net.LinkProperties;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class Ubuntu {
    interface Unpacker {
        void run(ProcessBuilder builder, InputStream archive) throws IOException, InterruptedException;
    }

    private final Context context;
    private final File container;
    private final File data;
    private final File dns;
    private final String abi;
    private File root;
    private File project;

    Ubuntu(Context context) {
        this.context = context;
        container = new File(context.getFilesDir(), "ubuntu");
        data = new File(context.getFilesDir(), "halcyon-data");
        dns = new File(context.getFilesDir(), "resolv.conf");
        abi = Arrays.stream(Build.SUPPORTED_ABIS)
            .filter(value -> value.equals("arm64-v8a") || value.equals("x86_64"))
            .findFirst().orElse("");
    }

    void prepare(Unpacker unpack) throws IOException, InterruptedException {
        if (abi.isEmpty()) throw new IOException("Требуется ARM64 или x86-64.");
        Files.createDirectories(new File(data, "home").toPath());
        Files.createDirectories(new File(data, "proot-tmp").toPath());
        Files.createDirectories(container.toPath());
        String version;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            context.getAssets().open("build-id"), StandardCharsets.US_ASCII))) {
            version = reader.readLine();
        }
        if (version == null || !version.matches("[0-9a-f]{16}")) throw new IOException("Некорректная версия среды.");
        File installed = new File(container, version);
        root = new File(installed, "root");
        project = new File(installed, "project");
        if (new File(installed, "complete").isFile()) return;

        File stage = new File(container, "unpacking");
        deleteTree(stage);
        Files.createDirectories(stage.toPath());
        File stagedRoot = new File(stage, "root");
        unpackArchive("ubuntu-" + abi + ".tar.gz", stagedRoot, unpack);
        File resolver = new File(stagedRoot, "etc/resolv.conf");
        if (!resolver.exists()) Files.createFile(resolver.toPath());
        unpackArchive("project.tar.gz", new File(stage, "project"), unpack);
        Files.createFile(new File(stage, "complete").toPath());
        Files.move(stage.toPath(), installed.toPath());
        File[] previous = container.listFiles();
        if (previous != null) {
            for (File file : previous) {
                if (file.equals(installed)) continue;
                try {
                    deleteTree(file);
                } catch (IOException error) {
                    Log.w("Halcyon", "Could not remove an earlier Ubuntu runtime", error);
                }
            }
        }
    }

    private void unpackArchive(String asset, File destination, Unpacker unpack)
        throws IOException, InterruptedException {
        Files.createDirectories(destination.toPath());
        try (InputStream in = context.getAssets().open(asset)) {
            unpack.run(new ProcessBuilder("/system/bin/tar", "-xzf", "-",
                "-C", destination.getPath()).redirectErrorStream(true), in);
        }
    }

    synchronized void setDns(LinkProperties network) throws IOException {
        if (network == null || network.getDnsServers().isEmpty()) {
            if (!dns.exists()) Files.write(dns.toPath(),
                "nameserver 1.1.1.1\nnameserver 8.8.8.8\n".getBytes(StandardCharsets.US_ASCII));
            return;
        }
        StringBuilder servers = new StringBuilder();
        network.getDnsServers().stream().limit(3).forEach(address ->
            servers.append("nameserver ").append(address.getHostAddress()).append('\n'));
        Files.write(dns.toPath(), servers.toString().getBytes(StandardCharsets.US_ASCII));
    }

    static boolean hasSession(Context context) {
        File directory = new File(context.getFilesDir(), "halcyon-data/sessions");
        String[] sessions = directory.list((dir, name) -> name.matches("halcyon-[0-9]+\\.session"));
        return sessions != null && sessions.length > 0;
    }

    ProcessBuilder command(boolean setBotToken) {
        File libraries = new File(context.getApplicationInfo().nativeLibraryDir);
        List<String> arguments = new ArrayList<>(Arrays.asList(
            "/system/bin/sh", "-c", "printf 'HALCYON_PID %d\\n' \"$$\"; exec \"$@\"", "halcyon",
            new File(libraries, "libproot.so").getPath(), "--kill-on-exit", "-0",
            "-r", root.getPath(), "-w", "/opt/halcyon", "-b", "/dev", "-b", "/proc",
            "-b", data + ":/data", "-b", project + ":/opt/halcyon", "-b", dns + ":/etc/resolv.conf",
            "/usr/bin/env", "-i", "HOME=/data/home", "USER=halcyon", "LANG=C.UTF-8",
            "PATH=/opt/halcyon-venv/bin:/usr/local/bin:/usr/bin:/bin", "TMPDIR=/tmp",
            "PYTHONUNBUFFERED=1", "PYTHONDONTWRITEBYTECODE=1", "DOCKER=1", "HALCYON_ANDROID=1",
            "/opt/halcyon-venv/bin/python", "-m", "halcyon", "--android", "--no-tty",
            "--no-web", "--no-git", "--data-root", "/data"
        ));
        if (setBotToken) arguments.add("--set-bot-token");
        ProcessBuilder builder = new ProcessBuilder(arguments).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("LD_LIBRARY_PATH", libraries.getPath());
        env.put("PROOT_LOADER", new File(libraries, "libproot_loader.so").getPath());
        env.put("PROOT_TMP_DIR", new File(data, "proot-tmp").getPath());
        env.put("PROOT_NO_SECCOMP", "1");
        return builder;
    }

    private static void deleteTree(File entry) throws IOException {
        if (Files.isDirectory(entry.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            File[] children = entry.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        Files.deleteIfExists(entry.toPath());
    }
}
