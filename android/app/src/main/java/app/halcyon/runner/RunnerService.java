package app.halcyon.runner;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public final class RunnerService extends Service {
    static final String STOP = "app.halcyon.runner.STOP";
    static final String SET_BOT_TOKEN = "app.halcyon.runner.SET_BOT_TOKEN";
    static final int STATUS = 1;
    static final int ANSWER = 2;
    private static final String CHANNEL = "halcyon-runtime";
    private static final String CHILD_PID_PREFIX = "HALCYON_PID ";
    private static final String EVENT_PREFIX = "HALCYON_ANDROID ";
    private static final int NOTIFICATION = 7;
    private static final long WATCHDOG_INTERVAL = 10_000L;
    private static final long STARTUP_TIMEOUT = 120_000L;
    private static final long HEARTBEAT_TIMEOUT = 50_000L;
    enum Stage { IDLE, INSTALLING, STARTING, LOGIN, RUNNING, RECONNECTING, STOPPING, FAILED }

    static final class Status {
        final Stage stage;
        final String prompt;
        final String detail;
        final boolean inline;
        final long version;

        Status(Stage stage, String prompt, String detail, boolean inline) {
            this(stage, prompt, detail, inline, SystemClock.elapsedRealtimeNanos());
        }

        private Status(Stage stage, String prompt, String detail, boolean inline, long version) {
            this.stage = stage;
            this.prompt = prompt;
            this.detail = detail;
            this.inline = inline;
            this.version = version;
        }

        static Status idle() { return new Status(Stage.IDLE, "", "", false); }

        static Status read(Bundle data) {
            return new Status(Stage.valueOf(data.getString("stage")), data.getString("prompt"),
                data.getString("detail"), data.getBoolean("inline"), data.getLong("version"));
        }

        Bundle bundle() {
            Bundle data = new Bundle();
            data.putString("stage", stage.name());
            data.putString("prompt", prompt);
            data.putString("detail", detail);
            data.putBoolean("inline", inline);
            data.putLong("version", version);
            return data;
        }

        boolean active() { return stage != Stage.IDLE && stage != Stage.FAILED; }
    }

    private volatile Status status = Status.idle();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private final Object lock = new Object();
    private final Runnable watchdog = this::checkChild;
    private final List<String> secrets = new ArrayList<>();
    private volatile boolean stopping;
    private volatile boolean destroyed;
    private volatile boolean watchdogExpired;
    private Thread worker;
    private Process process;
    private int processId;
    private BufferedWriter input;
    private long heartbeatDeadline;
    private long readyDeadline;
    private PowerManager.WakeLock wakeLock;
    private Ubuntu ubuntu;
    private ConnectivityManager connectivity;
    private SharedPreferences preferences;
    private volatile boolean resumeRequested;
    private boolean trackingNetwork;
    private long childReadyAt;
    private String pendingError = "";
    private String invalid = "";

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) {
            synchronized (lock) { lock.notifyAll(); }
        }

        @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
            try { ubuntu.setDns(properties); }
            catch (IOException error) { log("Не удалось обновить настройки DNS."); }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        ubuntu = new Ubuntu(this);
        connectivity = getSystemService(ConnectivityManager.class);
        preferences = getSharedPreferences("runner", MODE_PRIVATE);
        resumeRequested = preferences.getBoolean("resume", false);
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL, "Работа Halcyon", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            synchronized (lock) {
                setResumeRequested(false);
                requestStop();
            }
            if (worker == null) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (worker != null) return stopping ? START_NOT_STICKY : START_STICKY;
        if (intent == null && (!resumeRequested || !Ubuntu.hasSession(this))) {
            setResumeRequested(false);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        boolean setBotToken = intent != null && SET_BOT_TOKEN.equals(intent.getAction());
        stopping = false;
        setResumeRequested(!setBotToken && Ubuntu.hasSession(this));
        status = new Status(Stage.INSTALLING, "", "", false);
        startForeground(NOTIFICATION, notification());
        PowerManager power = getSystemService(PowerManager.class);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Halcyon:runner");
        wakeLock.acquire();
        connectivity.registerDefaultNetworkCallback(networkCallback);
        trackingNetwork = true;
        boolean systemRestart = intent == null;
        worker = new Thread(() -> run(setBotToken, systemRestart), "halcyon-ubuntu");
        worker.start();
        return START_STICKY;
    }

    private void setResumeRequested(boolean requested) {
        synchronized (lock) {
            if (resumeRequested == requested || (requested && (stopping || destroyed))) return;
            resumeRequested = requested;
            preferences.edit().putBoolean("resume", requested).apply();
        }
    }

    private Notification notification() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP), flags);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, RunnerService.class).setAction(STOP), flags);
        return new Notification.Builder(this, CHANNEL)
            .setContentTitle("Halcyon")
            .setContentText(MainActivity.stageTitle(status.stage))
            .setSmallIcon(R.drawable.ic_notification)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "Остановить", stop).build())
            .build();
    }

    private void publish(Stage stage, String prompt, String detail, boolean inline) {
        synchronized (lock) {
            if (destroyed || (stopping && stage != Stage.STOPPING && stage != Stage.IDLE)) return;
            if (stage == Stage.LOGIN || stage == Stage.RUNNING) readyDeadline = 0;
            status = new Status(stage, prompt, detail, inline);
        }
        main.post(() -> {
            if (!destroyed && status.active()) {
                getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
            }
        });
    }

    private void run(boolean setBotToken, boolean systemRestart) {
        Stage result = Stage.FAILED;
        String detail = "";
        try {
            log(systemRestart ? "Android восстановил фоновый сервис." : "Запуск фонового сервиса Halcyon.");
            logPreviousProcessExit();
            ubuntu.setDns(connectivity.getLinkProperties(connectivity.getActiveNetwork()));
            ubuntu.prepare((builder, archive) -> {
                if (execute(builder, archive) != 0) throw new IOException("Не удалось распаковать Ubuntu.");
            });
            log("Ubuntu готова.");
            int retryDelay = 2;
            while (!stopping) {
                childReadyAt = 0;
                pendingError = "";
                invalid = "";
                ubuntu.setDns(connectivity.getLinkProperties(connectivity.getActiveNetwork()));
                publish(Stage.STARTING, "", "", false);
                log("Запуск Halcyon…");
                int code = execute(ubuntu.command(setBotToken), null);
                if (stopping) break;
                detail = pendingError.isEmpty() ? "Процесс завершился (код " + code + ")." : pendingError;
                log(detail);
                if (!resumeRequested) break;
                setBotToken = false;
                if (childReadyAt != 0 && SystemClock.elapsedRealtime() - childReadyAt >= 30_000) {
                    retryDelay = 2;
                }
                publish(Stage.RECONNECTING, "", "", false);
                log("Восстановление Halcyon после завершения процесса.");
                waitForRetry(retryDelay);
                retryDelay = Math.min(retryDelay * 2, 60);
            }
        } catch (IOException | InterruptedException | RuntimeException error) {
            detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            if (!stopping) log("Ошибка запуска: " + detail);
        } finally {
            if (stopping) {
                result = Stage.IDLE;
                detail = "";
                log("Halcyon остановлен.");
            }
            Stage finalStage = result;
            String finalDetail = detail;
            main.post(() -> finishRun(finalStage, finalDetail));
        }
    }

    private void logPreviousProcessExit() {
        if (Build.VERSION.SDK_INT < 30) return;
        ActivityManager activity = getSystemService(ActivityManager.class);
        for (ApplicationExitInfo exit : activity.getHistoricalProcessExitReasons(getPackageName(), 0, 8)) {
            if (!(getPackageName() + ":runtime").equals(exit.getProcessName())) continue;
            if (exit.getTimestamp() > preferences.getLong("reported_exit", 0)) {
                log("Предыдущее завершение сервиса Android: причина " + exit.getReason()
                    + ", код " + exit.getStatus() + ".");
                preferences.edit().putLong("reported_exit", exit.getTimestamp()).apply();
            }
            return;
        }
    }

    private void waitForRetry(int delaySeconds) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + delaySeconds * 1000L;
        synchronized (lock) {
            while (!stopping) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0 && connectivity.getActiveNetwork() != null) return;
                lock.wait(remaining > 0 ? remaining : WATCHDOG_INTERVAL);
            }
        }
    }

    private int execute(ProcessBuilder builder, InputStream archive) throws IOException, InterruptedException {
        boolean interactive = archive == null;
        Process child;
        synchronized (lock) {
            if (stopping) throw new InterruptedException();
            child = builder.start();
            process = child;
            if (interactive) {
                input = new BufferedWriter(new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8));
                watchdogExpired = false;
                heartbeatDeadline = SystemClock.uptimeMillis() + STARTUP_TIMEOUT;
                readyDeadline = resumeRequested ? heartbeatDeadline : 0;
                main.postDelayed(watchdog, WATCHDOG_INTERVAL);
            }
        }
        AtomicReference<IOException> archiveError = new AtomicReference<>();
        Thread feeder = interactive ? null : new Thread(() -> {
            try (OutputStream destination = child.getOutputStream()) {
                byte[] buffer = new byte[128 * 1024];
                for (int size; (size = archive.read(buffer)) != -1;) {
                    destination.write(buffer, 0, size);
                }
            } catch (IOException error) {
                archiveError.set(error);
            }
        }, "halcyon-unpack");
        if (feeder != null) feeder.start();
        try (BufferedReader output = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
            if (interactive) {
                String firstLine = output.readLine();
                if (firstLine == null) return child.waitFor();
                if (!firstLine.startsWith(CHILD_PID_PREFIX)) throw new IOException("Не удалось определить PID Ubuntu.");
                synchronized (lock) {
                    processId = Integer.parseInt(firstLine.substring(CHILD_PID_PREFIX.length()));
                }
            }
            for (String line; (line = output.readLine()) != null;) {
                if (interactive && line.startsWith(EVENT_PREFIX)) {
                    readEvent(line.substring(EVENT_PREFIX.length()));
                } else {
                    log(line);
                }
            }
            int code = child.waitFor();
            if (feeder != null) {
                feeder.join();
                if (code == 0 && archiveError.get() != null) throw archiveError.get();
            }
            return code;
        } catch (IOException error) {
            if (!interactive || (!watchdogExpired && !stopping)) throw error;
            return child.waitFor();
        } finally {
            synchronized (lock) {
                forceStop(child);
                if (input != null) {
                    main.removeCallbacks(watchdog);
                    try { input.close(); } catch (IOException ignored) { }
                }
                input = null;
                process = null;
                processId = 0;
            }
        }
    }

    private void forceStop(Process child) {
        synchronized (lock) {
            if (process != child || !child.isAlive()) return;
            // Older Android versions implement destroyForcibly() with SIGTERM.
            if (processId > 0) android.os.Process.killProcess(processId);
            child.destroyForcibly();
        }
    }

    private void checkChild() {
        synchronized (lock) {
            if (destroyed || stopping || input == null || !process.isAlive()) return;
            long now = SystemClock.uptimeMillis();
            boolean ubuntuStalled = now >= heartbeatDeadline;
            if (ubuntuStalled || (readyDeadline != 0 && now >= readyDeadline)) {
                watchdogExpired = true;
                log(ubuntuStalled ? "Ubuntu не отвечает. Перезапуск процесса Halcyon."
                    : "Telegram не подключился за 2 минуты. Повторяем запуск Halcyon.");
                forceStop(process);
                return;
            }
            main.postDelayed(watchdog, WATCHDOG_INTERVAL);
        }
    }

    private void readEvent(String json) {
        try {
            JSONObject event = new JSONObject(json);
            switch (event.getString("event")) {
                case "heartbeat":
                    synchronized (lock) {
                        heartbeatDeadline = SystemClock.uptimeMillis() + HEARTBEAT_TIMEOUT;
                    }
                    break;
                case "validation":
                    invalid = event.optString("field");
                    break;
                case "prompt":
                    setResumeRequested(false);
                    String field = event.getString("field");
                    publish(Stage.LOGIN, field, field.equals(invalid) ? "Проверьте введённое значение." : "", false);
                    invalid = "";
                    break;
                case "ready":
                    childReadyAt = SystemClock.elapsedRealtime();
                    setResumeRequested(true);
                    publish(Stage.RUNNING, "", "", event.optBoolean("inline"));
                    break;
                case "retry":
                    pendingError = "Связь с Telegram прервалась.";
                    break;
                case "session_lost":
                    setResumeRequested(false);
                    pendingError = "Сессия Telegram завершена. Войди в аккаунт заново.";
                    break;
                case "error":
                    setResumeRequested(false);
                    pendingError = "Ошибка Halcyon: " + event.optString("kind", "неизвестно");
                    break;
                default:
                    break;
            }
        } catch (JSONException ignored) { }
    }

    private boolean receive(Message message) {
        if (message.what == ANSWER) {
            Bundle data = message.getData();
            answer(data.getLong("version"), data.getString("value", ""));
        } else if (message.what != STATUS) {
            return false;
        }
        if (message.replyTo != null) {
            Message reply = Message.obtain(null, STATUS);
            reply.setData(status.bundle());
            try { message.replyTo.send(reply); } catch (RemoteException ignored) { }
        }
        return true;
    }

    private void answer(long version, String value) {
        synchronized (lock) {
            Status expected = status;
            if (expected.version != version || expected.stage != Stage.LOGIN || input == null || stopping) return;
            if (!expected.prompt.equals("api_id") && !expected.prompt.equals("bot") && !value.isEmpty()) {
                secrets.add(value);
                if (value.startsWith("+") && expected.prompt.equals("phone")) secrets.add(value.substring(1));
            }
            try {
                input.write(JSONObject.quote(value));
                input.newLine();
                input.flush();
                publish(Stage.STARTING, "", "", false);
            } catch (IOException ignored) { }
        }
    }

    private void log(String value) {
        synchronized (lock) {
            for (String secret : secrets) value = value.replace(secret, "[скрыто]");
        }
        try {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
            Journal.append(this, time + "  " + value);
        } catch (IOException ignored) { }
    }

    private void requestStop() {
        synchronized (lock) {
            if (worker == null || stopping) return;
            stopping = true;
            lock.notifyAll();
            publish(Stage.STOPPING, "", "", false);
            Process child = process;
            if (child != null) {
                if (input != null) {
                    try { input.close(); } catch (IOException ignored) { }
                    main.postDelayed(() -> forceStop(child), 2500);
                } else {
                    child.destroy();
                    main.postDelayed(() -> forceStop(child), 5000);
                }
            }
        }
    }

    private void finishRun(Stage stage, String detail) {
        if (destroyed) return;
        worker = null;
        setResumeRequested(false);
        publish(stage, "", detail, false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        releaseRunResources();
        stopSelf();
    }

    private void releaseRunResources() {
        if (trackingNetwork) {
            connectivity.unregisterNetworkCallback(networkCallback);
            trackingNetwork = false;
        }
        if (wakeLock != null) {
            if (wakeLock.isHeld()) wakeLock.release();
            wakeLock = null;
        }
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (worker != null && !stopping) log("Экран Halcyon убран из недавних. Фоновый сервис активен.");
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        destroyed = true;
        requestStop();
        main.removeCallbacks(watchdog);
        releaseRunResources();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }
}
