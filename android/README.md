# Halcyon для Android

APK поддерживает устройства ARM64 и x86-64 с Android 9 и новее. На первом запуске приложение автоматически распаковывает Ubuntu 24.04 и зависимости оригинального Halcyon. Интернет нужен для подключения к Telegram. Root и отдельное приложение для запуска Linux не требуются.

Нажмите «Запустить Halcyon» и заполните поля входа в Telegram. После сохранения сессии следующий запуск использует её автоматически. Фоновый сервис с уведомлением работает в отдельном Android-процессе и продолжает работу после закрытия экрана или удаления Halcyon из списка недавних приложений. Сервис перезапускает зависшую Ubuntu и при обрыве связи ждёт доступную сеть. Если Android завершит процесс и восстановит сервис, Halcyon возобновит работу автоматически. Кнопка «Остановить» в приложении или уведомлении полностью завершает этот запуск. После принудительной остановки из настроек Android откройте приложение и запустите Halcyon из обзора.

Если нужно вручную привязать бота из BotFather, остановите Halcyon и нажмите «Подключить инлайн-бота». Для привязки требуется одна сохранённая сессия пользовательского аккаунта.

На вкладке «Журнал» можно скопировать последние записи или сохранить доступный журнал в `.txt`. Android 10 и новее сохраняет файл в папку «Загрузки». На Android 9 папку можно выбрать в системном окне. Сессии и настройки хранятся в защищённом каталоге приложения. APK использует код Halcyon, который включён в архив исходников.

## Сборка

Нужны JDK 17 или новее, Android SDK Platform 35, Build Tools 35.0.0, Python 3.10 или новее, Docker Buildx, `curl`, `dpkg-deb`, `zip` и `rg` на Linux. Для сборки обеих архитектур нужен Buildx с поддержкой `linux/arm64` и `linux/amd64`, например с QEMU.

```sh
export ANDROID_SDK_ROOT=/path/to/android-sdk
bash android/build.sh
```

Готовый файл появится в `android/build/Halcyon.apk`. Первый запуск сборки создаёт локальный ключ. Для обновлений используйте тот же ключ. Чтобы подписать APK своим ключом, задайте `HALCYON_KEYSTORE` и `HALCYON_KEY_ALIAS`. Переменная `HALCYON_BUILDX_BUILDER` выбирает экземпляр Buildx. `HALCYON_RUNTIME_DIR` указывает на каталог сборки образов Ubuntu. При изменении Python-зависимостей пересоберите оба образа из `android/runtime.Dockerfile`.

Приложение использует целевой API 28 для запуска Ubuntu через PRoot в закрытом каталоге Android. Android, производитель устройства или администратор могут ограничивать установку приложений с таким целевым API. [Документация Android](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission)

## Компоненты Ubuntu

Ubuntu базируется на официальном образе 24.04. Лицензии установленных пакетов Ubuntu находятся в `usr/share/doc` внутри образа. [Исходные пакеты Ubuntu](https://launchpad.net/ubuntu/noble)

PRoot и его Android-библиотеки взяты из Termux. Пути пакетов и SHA-256 находятся в `native-packages.txt`. Сценарий сборки меняет имя динамической библиотеки `libtalloc`, чтобы Android установил её вместе с PRoot. [PRoot](https://github.com/termux/termux-packages/tree/master/packages/proot) · [talloc](https://github.com/termux/termux-packages/tree/master/packages/libtalloc) · [Android shared memory](https://github.com/termux/termux-packages/tree/master/packages/libandroid-shmem)
