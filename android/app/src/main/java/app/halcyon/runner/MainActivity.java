package app.halcyon.runner;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int FILL = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int EXPORT_DOCUMENT = 50;
    private static final int COPY_LIMIT = 192 * 1024;
    private static final int EXPORT_LIMIT = 2 * (512 * 1024 + 32768);
    private enum Page { OVERVIEW, JOURNAL, ABOUT }
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean dark;
    private Page page;
    private int background, surface, ink, secondary, accent, tinted, line;
    private final View[] tabs = new View[Page.values().length];
    private LinearLayout body;
    private ScrollView scroll, journalScroll;
    private TextView journalText;
    private RunnerService.Status runnerStatus = RunnerService.Status.idle();
    private Messenger runner;
    private boolean runnerBound;
    private long shownRevision = -1;

    private final Messenger statusReceiver = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        if (message.what != RunnerService.STATUS) return false;
        if (!runnerBound) return true;
        RunnerService.Status next = RunnerService.Status.read(message.getData());
        if (next.version != runnerStatus.version) {
            runnerStatus = next;
            if (page == Page.OVERVIEW) showOverview(runnerStatus);
        }
        return true;
    }));

    private final ServiceConnection runnerConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            runner = new Messenger(binder);
            send(RunnerService.STATUS, null);
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            runner = null;
            if (runnerStatus.active()) {
                runnerStatus = new RunnerService.Status(RunnerService.Stage.RECONNECTING, "", "", false);
                if (page == Page.OVERVIEW) showOverview(runnerStatus);
            }
        }
    };

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (page == Page.OVERVIEW) send(RunnerService.STATUS, null);
            if (page == Page.JOURNAL && shownRevision != Journal.revision(MainActivity.this)) showLog();
            main.postDelayed(this, 350);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
        background = dark ? 0xFF16131F : 0xFFF5F3F9;
        surface = dark ? 0xFF222032 : Color.WHITE;
        ink = dark ? 0xFFF5F0FF : 0xFF241A39;
        secondary = dark ? 0xFFB8AEC9 : 0xFF645A77;
        accent = dark ? 0xFFCCB9FF : 0xFF6443C1;
        tinted = dark ? 0xFF352953 : 0xFFECE5FA;
        line = dark ? 0xFF3D354E : 0xFFE5DFEE;
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 :
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        LinearLayout screen = column();
        screen.setBackgroundColor(background);
        screen.setPadding(dp(22), dp(12), dp(22), dp(10));
        setContentView(screen);
        screen.addView(header());

        LinearLayout navigation = row();
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        navigation.setPadding(dp(3), dp(3), dp(3), dp(3));
        navigation.setBackground(shape(dark ? 0xFF282239 : 0xFFEAE5F2, 16));
        tabs[Page.OVERVIEW.ordinal()] = tab("Обзор", Page.OVERVIEW);
        tabs[Page.JOURNAL.ordinal()] = tab("Журнал", Page.JOURNAL);
        tabs[Page.ABOUT.ordinal()] = tab("О приложении", Page.ABOUT);
        for (View tab : tabs) navigation.addView(tab, new LinearLayout.LayoutParams(0, dp(44), 1));
        add(screen, navigation, FILL, WRAP, 14);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        body = column();
        body.setPadding(0, dp(16), 0, dp(12));
        scroll.addView(body);
        screen.addView(scroll, new LinearLayout.LayoutParams(FILL, 0, 1));
        selectPage(state == null ? Page.OVERVIEW : Page.values()[state.getInt("page")]);
    }

    @Override protected void onStart() {
        super.onStart();
        runnerBound = bindService(new Intent(this, RunnerService.class), runnerConnection, BIND_AUTO_CREATE);
        main.post(refresh);
    }

    @Override protected void onStop() {
        main.removeCallbacks(refresh);
        if (runnerBound) unbindService(runnerConnection);
        runnerBound = false;
        runner = null;
        super.onStop();
    }

    private boolean send(int action, Bundle data) {
        if (runner == null) return false;
        Message request = Message.obtain(null, action);
        request.replyTo = statusReceiver;
        if (data != null) request.setData(data);
        try {
            runner.send(request);
            return true;
        } catch (RemoteException ignored) {
            return false;
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("page", page.ordinal());
        super.onSaveInstanceState(state);
    }

    private LinearLayout header() {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_halcyon);
        row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout text = column();
        text.setPadding(dp(13), 0, 0, 0);
        text.addView(label("Halcyon", 29, ink, "sans-serif-medium"));
        text.addView(label("Юзербот Telegram", 13, secondary, "sans-serif"));
        row.addView(text);
        return row;
    }

    private TextView tab(String name, Page destination) {
        TextView text = label(name, 13, ink, "sans-serif-medium");
        text.setGravity(Gravity.CENTER);
        text.setOnClickListener(view -> selectPage(destination));
        return text;
    }

    private void selectPage(Page destination) {
        page = destination;
        for (int i = 0; i < tabs.length; i++) {
            boolean selected = i == page.ordinal();
            tabs[i].setSelected(selected);
            tabs[i].setBackground(selected ? shape(surface, 13) : null);
        }
        scroll.scrollTo(0, 0);
        switch (page) {
            case JOURNAL: buildJournal(); break;
            case ABOUT: showAbout(); break;
            default: showOverview(runnerStatus);
        }
    }

    private void showAbout() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        body.removeAllViews();
        body.addView(label("Разработчики", 24, ink, "sans-serif-medium"));
        add(body, developerCard("Господин", "lyzos", "Главный разработчик и владелец", R.drawable.avatar_lyzos),
            FILL, WRAP, 14);
        add(body, developerCard("Опти", "optimist_opti", "Кошкодевочка", R.drawable.avatar_opti),
            FILL, WRAP, 12);
    }

    private LinearLayout developerCard(String name, String username, String role, int photo) {
        LinearLayout container = card();
        LinearLayout profile = row();
        profile.setGravity(Gravity.CENTER_VERTICAL);
        ImageView avatar = new ImageView(this);
        avatar.setImageResource(photo);
        avatar.setBackground(shape(tinted, 31));
        avatar.setClipToOutline(true);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        profile.addView(avatar, new LinearLayout.LayoutParams(dp(62), dp(62)));

        LinearLayout details = column();
        details.setPadding(dp(13), 0, 0, 0);
        details.addView(label(name, 17, ink, "sans-serif-medium"));
        add(details, label(role, 13, secondary, "sans-serif"), FILL, WRAP, 3);
        add(details, label("@" + username, 13, accent, "sans-serif-medium"), FILL, WRAP, 5);
        profile.addView(details, new LinearLayout.LayoutParams(0, WRAP, 1));
        container.addView(profile);
        profile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        container.setContentDescription(name + ", " + role + ", @" + username + ". Открыть профиль в Telegram");
        container.setFocusable(true);
        container.setOnClickListener(view -> open("https://t.me/" + username));

        new Thread(() -> {
            try {
                Bitmap bitmap = TelegramAvatar.fetch(username);
                if (bitmap != null) main.post(() -> {
                    if (avatar.isAttachedToWindow()) avatar.setImageBitmap(bitmap);
                });
            } catch (IOException ignored) {
                // The bundled Telegram avatar is visible while the profile is unreachable.
            }
        }, "halcyon-avatar").start();
        return container;
    }

    static String stageTitle(RunnerService.Stage stage) {
        switch (stage) {
            case INSTALLING: return "Подготовка Ubuntu";
            case STARTING: return "Запускается";
            case LOGIN: return "Вход в Telegram";
            case RUNNING: return "Halcyon работает";
            case RECONNECTING: return "Переподключение";
            case STOPPING: return "Останавливается";
            case FAILED: return "Ошибка запуска";
            default: return "Остановлен";
        }
    }

    private void showOverview(RunnerService.Status status) {
        if (status.stage != RunnerService.Stage.LOGIN) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        body.removeAllViews();
        body.addView(hero(status), new LinearLayout.LayoutParams(FILL, dp(158)));
        if (status.stage == RunnerService.Stage.LOGIN) {
            add(body, login(status), FILL, WRAP, 14);
        } else if (status.stage == RunnerService.Stage.INSTALLING || status.stage == RunnerService.Stage.STARTING ||
            status.stage == RunnerService.Stage.RECONNECTING) {
            LinearLayout waiting = card();
            ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setIndeterminate(true);
            progress.setIndeterminateTintList(ColorStateList.valueOf(accent));
            String message = status.stage == RunnerService.Stage.INSTALLING
                ? "Устанавливаем локальную среду" : "Подключаемся к Telegram";
            waiting.addView(label(message, 16, ink, "sans-serif-medium"));
            add(waiting, progress, FILL, dp(8), 10);
            add(body, waiting, FILL, WRAP, 14);
        } else if (status.stage == RunnerService.Stage.FAILED) {
            LinearLayout failure = card();
            failure.addView(label(status.detail, 15, ink, "sans-serif"));
            add(failure, button("Открыть журнал", false, () -> selectPage(Page.JOURNAL)), FILL, dp(46), 12);
            add(body, failure, FILL, WRAP, 14);
        }

        if (status.stage != RunnerService.Stage.STOPPING) {
            boolean active = status.active();
            add(body, button(active ? "Остановить Halcyon" : "Запустить Halcyon", !active, () -> {
                Intent command = new Intent(this, RunnerService.class);
                if (runnerStatus.active()) startService(command.setAction(RunnerService.STOP));
                else startForegroundService(command);
            }), FILL, dp(52), 14);
        }

        LinearLayout environment = card();
        environment.addView(label("Среда", 15, ink, "sans-serif-medium"));
        add(environment, label("Ubuntu 24.04 · На этом устройстве", 14, secondary, "sans-serif"), FILL, WRAP, 5);
        if (status.stage == RunnerService.Stage.RUNNING) {
            add(environment, label(status.inline ? "Инлайн-бот подключён" : "Инлайн-бот недоступен. Подробности в журнале.",
                13, secondary, "sans-serif"), FILL, WRAP, 5);
        }
        add(body, environment, FILL, WRAP, 14);
        if (!status.active() && Ubuntu.hasSession(this)) {
            add(body, button("Подключить инлайн-бота", false, () -> startForegroundService(
                new Intent(this, RunnerService.class).setAction(RunnerService.SET_BOT_TOKEN))), FILL, dp(45), 10);
        }
        add(body, button("Эмодзи-пак Halcyon  ↗", false,
            () -> open("https://t.me/addemoji/halcyon_pack")), FILL, dp(45), 10);
    }

    private FrameLayout hero(RunnerService.Status status) {
        FrameLayout hero = new FrameLayout(this);
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xFF24183E, 0xFF37265B, 0xFF523981});
        gradient.setCornerRadius(dp(27));
        hero.setBackground(gradient);
        hero.setClipToOutline(true);
        hero.addView(new OrbitView(this), new FrameLayout.LayoutParams(dp(151), FILL, Gravity.END));

        LinearLayout content = column();
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(18), dp(10), dp(8), dp(10));
        TextView badge = label("Состояние", 12, 0xFFF0E6FF, "sans-serif-medium");
        badge.setPadding(dp(10), dp(5), dp(10), dp(5));
        badge.setBackground(shape(0x505A4778, 12));
        content.addView(badge, new LinearLayout.LayoutParams(WRAP, WRAP));
        add(content, label(stageTitle(status.stage), 22, Color.WHITE, "sans-serif-medium"), FILL, WRAP, 8);
        String subtitle;
        switch (status.stage) {
            case RUNNING: subtitle = "Активен в фоне"; break;
            case LOGIN: subtitle = "Нужны данные аккаунта"; break;
            case INSTALLING: subtitle = "Распаковка на устройстве"; break;
            case RECONNECTING: subtitle = "Восстанавливаем фоновую работу"; break;
            case FAILED: subtitle = "Запись в журнале"; break;
            case STOPPING:
            case STARTING: subtitle = "Подождите немного"; break;
            default: subtitle = "Готов к запуску";
        }
        add(content, label(subtitle, 13, 0xFFDED0F4, "sans-serif"), FILL, WRAP, 4);
        hero.addView(content, new FrameLayout.LayoutParams(FILL, FILL));
        return hero;
    }

    private LinearLayout login(RunnerService.Status status) {
        LinearLayout card = card();
        String title, hint, description;
        int inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        boolean secret = false;
        switch (status.prompt) {
            case "api_id":
                title = "API ID"; hint = "Введите API ID"; description = "Создайте приложение в кабинете Telegram API.";
                inputType = InputType.TYPE_CLASS_NUMBER; break;
            case "api_hash":
                title = "API hash"; hint = "32 символа"; description = "Скопируйте API hash из того же кабинета Telegram.";
                secret = true; break;
            case "phone":
                title = "Номер телефона"; hint = "+7 999 123 45 67"; description = "Введите номер аккаунта в международном формате.";
                inputType = InputType.TYPE_CLASS_PHONE; break;
            case "code":
                title = "Код подтверждения"; hint = "Введите код"; description = "Проверьте сообщение от Telegram.";
                inputType = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD; secret = true; break;
            case "password":
                title = "Пароль 2FA"; hint = "Введите пароль"; description = "Введите пароль двухэтапной аутентификации Telegram.";
                secret = true; break;
            case "bot_token":
                title = "Токен инлайн-бота"; hint = "Токен от BotFather"; description = "Вставьте токен своего бота из BotFather.";
                secret = true; break;
            default:
                title = "Инлайн-бот"; hint = "@your_bot"; description = "Укажите имя бота из BotFather или пропустите для автосоздания."; break;
        }
        if (secret && inputType != (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD)) {
            inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        card.addView(label(title, 20, ink, "sans-serif-medium"));
        add(card, label(description, 14, secondary, "sans-serif"), FILL, WRAP, 5);
        if (!status.detail.isEmpty()) {
            add(card, label(status.detail, 14, dark ? 0xFFFFACB7 : 0xFFAC324B, "sans-serif"), FILL, WRAP, 7);
        }
        if (status.prompt.equals("api_id")) {
            add(card, button("Открыть Telegram API  ↗", false,
                () -> open("https://my.telegram.org/apps")), FILL, dp(44), 9);
        }
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setInputType(inputType);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        field.setTextColor(ink);
        field.setHintTextColor(secondary);
        field.setTextSize(16);
        field.setHint(hint);
        field.setPadding(dp(13), 0, dp(13), 0);
        field.setBackground(shape(dark ? 0xFF2B263C : 0xFFF5F2F9, 13));
        add(card, field, FILL, dp(51), 11);
        Runnable submit = () -> submit(status, field, false);
        field.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            submit.run();
            return true;
        });
        add(card, button("Продолжить", true, submit), FILL, dp(48), 10);
        if (status.prompt.equals("bot")) {
            add(card, button("Пропустить", false, () -> submit(status, field, true)), FILL, dp(44), 8);
        }
        return card;
    }

    private void submit(RunnerService.Status status, EditText field, boolean skip) {
        String value = skip ? "" : field.getText().toString();
        if (!status.prompt.equals("password")) value = value.trim();
        if (status.prompt.equals("phone")) value = value.replaceAll("[\\s()-]+", "");
        if (!skip && value.isEmpty()) {
            field.setError("Введите значение");
            return;
        }
        Bundle answer = new Bundle();
        answer.putLong("version", status.version);
        answer.putString("value", value);
        if (!send(RunnerService.ANSWER, answer)) return;
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(field.getWindowToken(), 0);
        field.setText("");
    }

    private void buildJournal() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        body.removeAllViews();
        LinearLayout actions = row();
        TextView copy = button("Копировать", false, this::copy);
        TextView download = button("Сохранить .txt", true, this::export);
        actions.addView(copy, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams exportLayout = new LinearLayout.LayoutParams(0, dp(48), 1);
        exportLayout.setMargins(dp(8), 0, 0, 0);
        actions.addView(download, exportLayout);
        body.addView(actions);

        journalScroll = new ScrollView(this);
        journalScroll.setNestedScrollingEnabled(true);
        journalScroll.setFillViewport(true);
        journalScroll.setBackground(shape(dark ? 0xFF1D192A : 0xFFFDFBFF, 20));
        journalText = label("", 12, ink, "monospace");
        journalText.setTextIsSelectable(true);
        journalText.setPadding(dp(14), dp(14), dp(14), dp(14));
        journalText.setLineSpacing(dp(3), 1);
        journalScroll.addView(journalText, new ScrollView.LayoutParams(FILL, WRAP));
        add(body, journalScroll, FILL, dp(390), 14);
        showLog();
    }

    private void showLog() {
        shownRevision = Journal.revision(this);
        boolean atBottom = !journalScroll.canScrollVertically(1);
        try {
            String text = Journal.text(this, 60 * 1024);
            journalText.setText(text.isEmpty() ? "Здесь появятся сообщения после запуска." : text);
            if (atBottom) journalScroll.post(() -> journalScroll.fullScroll(View.FOCUS_DOWN));
        } catch (IOException error) {
            journalText.setText("Журнал пока недоступен.");
        }
    }

    private void copy() {
        try {
            String text = Journal.text(this, COPY_LIMIT);
            if (text.isEmpty()) { toast("Журнал пуст"); return; }
            ClipData clip = ClipData.newPlainText("Журнал Halcyon", text);
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
            getSystemService(ClipboardManager.class).setPrimaryClip(clip);
            if (Build.VERSION.SDK_INT < 33) toast("Журнал скопирован");
        } catch (IOException error) {
            toast("Не удалось скопировать журнал");
        }
    }

    private void export() {
        if (Build.VERSION.SDK_INT < 29) {
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, logFilename()), EXPORT_DOCUMENT);
            return;
        }
        new Thread(() -> {
            Uri file = null;
            try {
                String text = Journal.text(this, EXPORT_LIMIT);
                if (text.isEmpty()) { runOnUiThread(() -> toast("Журнал пуст")); return; }
                ContentValues meta = new ContentValues();
                meta.put(MediaStore.MediaColumns.DISPLAY_NAME, logFilename());
                meta.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                meta.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                meta.put(MediaStore.MediaColumns.IS_PENDING, 1);
                file = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, meta);
                if (file == null) throw new IOException();
                writeText(file, text);
                meta.clear();
                meta.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(file, meta, null, null);
                runOnUiThread(() -> toast("Журнал сохранён в «Загрузки»"));
            } catch (IOException | RuntimeException error) {
                if (file != null) getContentResolver().delete(file, null, null);
                runOnUiThread(() -> toast("Не удалось сохранить журнал"));
            }
        }, "halcyon-export").start();
    }

    private String logFilename() {
        return "Halcyon-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".txt";
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT_DOCUMENT || result != RESULT_OK || data == null || data.getData() == null) return;
        try {
            writeText(data.getData(), Journal.text(this, EXPORT_LIMIT));
            toast("Журнал сохранён");
        } catch (IOException | RuntimeException error) {
            toast("Не удалось сохранить журнал");
        }
    }

    private void writeText(Uri file, String text) throws IOException {
        try (OutputStream output = getContentResolver().openOutputStream(file)) {
            if (output == null) throw new IOException();
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void open(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (android.content.ActivityNotFoundException error) { toast("Ссылку открыть не удалось"); }
    }

    private LinearLayout card() {
        LinearLayout card = column();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable background = shape(surface, 21);
        background.setStroke(dp(1), line);
        card.setBackground(background);
        return card;
    }

    private TextView button(String name, boolean primary, Runnable action) {
        TextView button = label(name, 15, primary ? Color.WHITE : accent, "sans-serif-medium");
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x286443C1),
            shape(primary ? 0xFF6544C1 : tinted, 15), null));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private TextView label(String text, int size, int color, String font) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(font, Typeface.NORMAL));
        return view;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() { return new LinearLayout(this); }

    private void add(LinearLayout parent, View child, int width, int height, int top) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(width, height);
        size.topMargin = dp(top);
        parent.addView(child, size);
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
}
