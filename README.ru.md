# Tasirin Vaultwarden Host (Android)

[![Build APK](https://github.com/tasirin1/tasirin-vaultwarden-host/actions/workflows/build-apk.yml/badge.svg)](https://github.com/tasirin1/tasirin-vaultwarden-host/actions)
[![Build Binary](https://github.com/tasirin1/tasirin-vaultwarden-host/actions/workflows/build-binary.yml/badge.svg)](https://github.com/tasirin1/tasirin-vaultwarden-host/actions)
[![Release](https://img.shields.io/github/v/release/tasirin1/tasirin-vaultwarden-host/releases)](https://github.com/tasirin1/tasirin-vaultwarden-host/releases)

<p align="center"><b>&#127760; Language: <a href="README.md">English</a> &middot; <a href="README.id.md">Indonesia</a> &middot; <a href="README.ru.md">Русский</a> &middot; <a href="CHANGELOG.md">Changelog</a></b></p>

Сервер **Vaultwarden** (совместимый с Bitwarden) на Android — для STB/TV-приставок и старых телефонов. **Android 5.0+**, 32-битный ARM (`armeabi-v7a`). APK ~0,1 МБ; бинарник и веб-волт скачиваются автоматически и проверяются по SHA-256.

## Использование

1. Скачайте APK из [Releases](https://github.com/tasirin1/tasirin-vaultwarden-host/releases), установите.
2. Откройте приложение → **&#8942; → Settings**: пройдите мастер из 3 шагов (Folder → Port → Start). Простой режим показывает только основное.
3. Нажмите **Start** (первое скачивание автоматическое, с прогрессом).
4. Откройте `https://<IP-ТЕЛЕФОНА>:<порт>` в браузере / приложении Bitwarden (Server URL).

Все настройки — в **Settings**; главный экран — это только статус, Start/Stop и лог.

## Без интернета

Скачайте с другого телефона, положите в папку данных, нажмите **Start**:

- `vaultwarden-armeabi-v7a` → папка данных
- `libgetrandom-shim-armeabi-v7a.so` → та же папка (для старых приставок)
- `web-vault.zip` → распакуйте в `web-vault/` (должен содержать `index.html`)

## Возможности

Автообновление бинарника и веб-волта, зашифрованные бэкапы (локально + Telegram), самоподписанный HTTPS, PIN-код, автозапуск при загрузке, автоперезапуск, управление пультом ТВ.

Одна вертикальная колонка (портрет и альбом имеют одинаковый порядок): сверху карточка ссылок, в середине лог в реальном времени, ниже статус сервера (шрифт лога масштабируется под экран), каждая кнопка работает с пультом ТВ (D-pad).

**Telegram** (укажите Bot token + Chat ID в Settings): `/status` `/log` `/uptime` `/alive` `/backup` `/restore` `/ca` `/cabackup` `/careset` `/crashlog` `/versi` `/update` `/webvault` `/restart` `/start` `/stop` `/help`. При включённом PIN команды, меняющие состояние (`/start` `/stop` `/restart` `/backup` `/update` `/webvault` `/restore` `/careset`), а также `/status`, `/log`, `/crashlog`, `/ca` и `/cabackup` (они содержат папку данных, порт, версии бинарника/веб-волта, время бэкапа, LAN-URL или TLS-файлы) должны заканчиваться PIN-кодом (например, `/stop 123456`); остальные команды чтения (`/uptime` `/alive` `/versi` `/help`) требуют только авторизации чата. Сообщения с PIN автоматически удаляются из чата, если у бота есть право на удаление; возможность удаления проверяется один раз по команде `/help` (ваше первое сообщение `/help` может быть удалено в рамках проверки), владелец один раз предупреждается, если бот не может удалять. Обратите внимание: бэкапы больше 20 МБ нельзя восстановить через бота (лимит скачивания Bot API) — скачайте вручную из чата и восстановите локально в Settings.

**Фиксация версии (не обязательно latest):** Settings → Maintenance → кнопки «Versi binary» / «Versi web vault» для выбора старой версии (даунгрейд разрешён) или возврата к «Terbaru (otomatis)». Зафиксированная версия соблюдается автообновлением/стартом (тихого обновления не будет). Через бота: `/versi` (показать версии + фиксации), `/update 1.32.0` / `/webvault 1.32.0` (зафиксировать + установить), `/update terbaru` (снять фиксацию).

**Бэкап:** база данных в `<папка-данных>/db.sqlite3`. Локальный бэкап в Settings → Maintenance; бэкап в Telegram через `/backup`; восстановление через `/restore` или файл `.zip`/`.sqlite3`.

**HTTPS (обязателен, всегда включён):** нажмите Start (сервер отдаёт только HTTPS) → установите `ca.pem` на каждый телефон (кнопка Share CA / `/ca`, или Backup CA to Storage `/cabackup` без Telegram). Если раньше работало, а потом сломалось: Reset Certificate (`/careset`, новый CA), затем переустановите CA на каждом телефоне.

## Устранение неполадок

- **Порт занят?** Смените Port в Settings.
- **`failed to generate random data` / ошибка HTTPS?** Старое ядро приставки — нажмите **Check Update**, затем Start снова.
- **Веб-интерфейс недоступен?** Нужен тот же WiFi, используйте локальный IP.
- **Скачивание не удаётся?** Проверьте интернет и часы приставки, нажмите Start снова (докачка автоматическая).
- **Бэкап в Telegram завершается с ошибкой `Certificate not valid until`?** Часы приставки сброшены на 2015
  (потеря питания / нет RTC). Включите автоматические дату и время в настройках приставки
  (нужен интернет) или выставьте вручную на сегодня, затем повторите бэкап.

## Разработчикам и лицензия

См. [AGENTS.md](AGENTS.md). Сборка только через GitHub Actions (push в `main`, раздельно: binary и APK); `targetSdk 28` — осознанно; бинарник никогда не входит в APK.

Приложение: GPL-3.0 (`LICENSE`). Vaultwarden: AGPL-3.0 (`LICENSE.vaultwarden`).
