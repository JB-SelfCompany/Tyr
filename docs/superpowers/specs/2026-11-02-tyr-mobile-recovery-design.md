# Tyr Android — восстановление в боевое состояние (спасательный релиз 1.9.1)

Дата: 2026-11-02 · Объём: только Android (Desktop вне объёма)

## 1. Проблема

1. **IzzyOnDroid застрял на 1.9.0 (vc 29).** Релиз и тег 1.9.0 удалены с GitHub, «Latest» = 1.8.1 (vc 28).
   Izzy не откатывается по versionCode, Android не ставит vc 28 поверх 29. Лечится только релизом с vc ≥ 30.
2. **1.9.0 несовместим с сетью** (#40 «я невидим»): Rust-движок 1.9.0 говорил только YMP, а весь
   Go-мир (Tyr 1.8.x, Desktop, upstream yggmail) — QUIC (yggquic).
3. **Фикс уже написан, но не выпущен и не застрахован:** QUIC mail-overlay в Yggmail-ng
   (вход YMP+QUIC, выход QUIC) лежит только локально.
4. **Фундаментальные зависимости ушли вперёд:** `.so` в Android собраны 2026-09-07 со старым
   Yggdrasil-ng (до ~2026-09-05); с тех пор upstream выпустил v0.3.1/v0.3.2 с исправлениями
   маршрутизации и безопасности; yggdrasil-go и upstream yggmail перешли на 0.5.15.

## 2. Решение

Выпустить текущий Rust-стек (Yggmail-ng + QUIC-overlay) как **1.9.1 / vc 30**, предварительно
застраховав код, синхронизировав зависимости с upstream и проверив на стенде.
Отвергнуто: откат на Go под vc 30 — пользователи 1.9.0 имеют зашифрованную БД (at-rest), Go её
не прочтёт → смена адреса.

## 3. Граф зависимостей и что с каждой делать

```
Android ──(.so + uniffi .kt)──> Yggmail-ng/crates/yggmail-mobile
                                   ├─ path ─> Yggdrasil-ng (ironwood, yggdrasil)   [Revertron upstream]
                                   ├─ path ─> imap-rs                              [JB-SelfCompany]
                                   └─ [patch.crates-io] rustls ─> rustls-yggmail   [локально, без remote]
Сеть-собеседник: yggdrasil-go 0.5.15 (публичные пиры), upstream yggmail Go (neilalexander) + yggquic,
                 Tyr 1.8.x (yggmail-old-mobile, yggdrasil-go 0.5.13)
```

| Репо | Сейчас | Upstream | Действие |
|---|---|---|---|
| Yggdrasil-ng | рабочая копия = v0.3.2 (pull 2026-11-02); `.so` собраны со сборкой до 2026-09-05 | v0.3.2 | **Пересобрать `.so`.** Вошедшее после сборки: ironwood #54 (выбор родителя), #55 (tree distance), #56 (отказ от low-order ключей), выравнивание RTT с Go, «probe peers before declaring dead», direct-peer shortcut, таймауты QUIC/TLS-рукопожатий, `?sni=` |
| rustls-yggmail | форк 0.23.40 + патч SNI, **нет remote** | crates.io 0.23.46 (так залочен в Yggdrasil-ng) | Перевендорить форк на 0.23.46, повторно наложить однострочный SNI-патч, создать репо на GitHub и запушить. Просмотреть changelog 0.23.41–0.23.46 на security-фиксы |
| Yggmail-ng | 33 коммита не запушены; lock: rustls 0.23.40, quinn 0.11.11, tokio 1.52 | — | Обновить lock до графа Yggdrasil-ng (quinn 0.11.12, tokio 1.54, rustls 0.23.46). `uniffi` остаётся 0.31.x — без смены API биндингов. После: `cargo tree -p yggmail -i rustls` обязан показать путь `../rustls-yggmail`. Запушить |
| imap-rs | живой фикс эха `BODY.PEEK` уже в origin (`fetch.rs`); незакоммичена правка мёртвого `writer.rs`; тестом не покрыто | = origin | Добавить регрессионный тест, закоммитить вместе с правкой `writer.rs`, запушить |
| yggdrasil-go | pull до v0.5.15 (2026-11-02) | v0.5.15 | В Android не линкуется. Используется как эталон: **аудит паритета** — в changelog 0.5.15 есть «stale bloom filter bits» и «correctly throttle repeated lookups»; проверить, что эквиваленты есть в ironwood Yggdrasil-ng. Если нет — зафиксировать как риск/issue в Yggdrasil-ng, не чинить в рамках релиза |
| upstream yggmail (Go) | нет локальной копии | 2026-10-24 → Yggdrasil 0.5.15; 2026-07-24 волна правок IMAP/очереди («Wait for SMTP data acceptance») | yggquic с февраля менял только версию Yggdrasil — формат на проводе стабилен. Семантика «ждать 250 после DATA» уже учтена в overlay (`12e911a`, `862d917`) |
| yggmail-old-mobile (Go) | незакоммичен hot-reload пиров (4 файла); `replace ../yggquic` указывает на несуществующий каталог | — | Заморозить: WIP закоммитить в ветку `wip-hot-reload`. Не обновлять — Android его больше не использует |
| Android Gradle (AGP 8.13, Kotlin 2.2.20) | — | — | Не трогать в спасательном релизе |

## 4. Фазы

**Фаза 0 — страховка (без риска).** imap-rs commit+push; Yggmail-ng push; rustls-yggmail → GitHub;
yggmail-old-mobile WIP → ветка. Android 3 локальных коммита не пушить до релиза.

**Фаза 1 — синхронизация зависимостей** (таблица §3): перевендоринг rustls → `cargo update` в
Yggmail-ng → `cargo test --workspace` → проверка `cargo tree -i rustls` → аудит паритета ironwood
с Go 0.5.15 → `build-android.ps1` для **всех 4 ABI** → копия `.so` + `.kt` в Android →
`./gradlew assembleRelease`. Проверить, что сгенерированный `yggmail_mobile.kt` не изменился
по API (иначе — правка вызовов в `YggmailService`).

**Фаза 2 — стенд** (основной телефон + второй с 1.8.1):

| # | Проверка | Ожидание |
|---|---|---|
| 1 | Новая → 1.8.1 через DeltaChat/ArcaneChat | доставлено (QUIC) |
| 2 | 1.8.1 → новая | доставлено |
| 3 | Встроенный чат в обе стороны | SENT → READ |
| 4 | Апгрейд 1.8.1 → новая на втором телефоне | адрес/почта/чаты сохранены |
| 5 | 1.9.0 (из локального тега) на основном, данные, апгрейд → новая | адрес сохранён |
| 6 | Экспорт/импорт бэкапа (#40 «пустой файл») | файл не пустой, восстановление работает |
| 7 | Автоподбор пиров (#40) | пиры находятся, RTT отображается |
| 8 | Узлы за разными хабами (если доступно) | доставка стабильна, не 1/10 |

**Фаза 3 — фиксы** только по красным строкам стенда, через systematic-debugging.

**Фаза 4 — релиз.** `RELEASE_VERSION_CODE = 30`, `RELEASE_VERSION_NAME = "1.9.1"` (вместо `1.9.0.1`),
`DEV_*` аналогично; `fastlane/metadata/android/{en-US,ru-RU}/changelogs/30.txt`; push Android;
тег `1.9.1`; GitHub Release с APK; комментарий в #40. Izzy подхватит автоматически (30 > 29).
Порядок обязателен: сначала запушены все библиотеки, затем тег Android.

**Фаза 5 — после релиза.** Обновить устаревшие CLAUDE.md (корневой: `Library/`, gomobile, yggquic;
Android: `Library/yggmail`). Бэклог: #32 MTE, параллельная отправка по адресатам, истечение очереди
для узлов 1.9.0, #34, обновление uniffi 0.32 / AGP / Kotlin, Desktop.

## 5. Риски

- **Тихо проигнорированный patch rustls** при рассинхроне версий → соберётся rustls без SNI-фикса,
  QUIC к Go-узлам упадёт на рукопожатии. Контроль: `cargo tree -i rustls` в Фазе 1.
- **Устаревший `.so` в одном из ABI** молча запускает старую маршрутизацию. Контроль: пересборка
  всех 4 ABI одним прогоном, сверка дат/размеров.
- **Миграция 1.9.0 → 1.9.1** ломает ключ → смена адреса. Контроль: тест 5, не релизить при красном.
- **Отправка на узлы 1.9.0 невозможна** (нет QUIC-слушателя) — принятое ограничение, уходит по мере обновления.

## 6. Роли

Сборки, `cargo`, `gradlew`, git commit/push/tag выполняет автор. Claude готовит правки кода,
списки команд и статический ревью.
