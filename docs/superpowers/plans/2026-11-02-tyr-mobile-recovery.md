# Tyr Android 1.9.1 — план восстановления

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Выпустить Tyr Android 1.9.1 (vc 30) на Rust + QUIC-overlay, совместимый с Go-узлами, собранный из запушенных исходников со свежими upstream-зависимостями, — и тем разблокировать IzzyOnDroid, застрявший на 1.9.0.

**Architecture:** Android берёт `libyggmail_mobile.so` и UniFFI-биндинги из `Librarys/Yggmail-ng`, который через path-зависимости тянет `Librarys/Yggdrasil-ng` и `Librarys/imap-rs`, а rustls подменяет форком `Librarys/rustls-yggmail` (`[patch.crates-io]`). Порядок работ: страховка → синхронизация зависимостей снизу вверх → пересборка `.so` → стенд → релиз.

**Tech Stack:** Rust stable (msvc host) + cargo-ndk + uniffi 0.31.1; Kotlin 2.2.20 / AGP 8.13; git + gh CLI.

**Spec:** `Android/docs/superpowers/specs/2026-11-02-tyr-mobile-recovery-design.md`

## Global Constraints

- Версия релиза: `RELEASE_VERSION_CODE = 30`, `RELEASE_VERSION_NAME = "1.9.1"`; тег `1.9.1` (без `v`, как `1.8.1`).
- `uniffi` остаётся `=0.31.1`; AGP / Kotlin / Gradle-зависимости не трогать.
- rustls-форк обязан совпадать по версии с тем, что резолвит граф (0.23.46); проверка — `cargo tree -p yggmail -i rustls` показывает `../rustls-yggmail`.
- `.so` пересобираются для **всех 4 ABI** одним прогоном (`-All`).
- Все библиотеки запушены **до** тега Android.
- Сборки, `cargo`, `gradlew`, `git commit/push/tag`, `gh release` выполняет автор. Агент правит файлы, готовит команды, делает статический ревью.
- Коммиты — без трейлера `Co-Authored-By`.
- Не редактировать руками `app/src/main/java/uniffi/yggmail_mobile/yggmail_mobile.kt`.
- `.rs` не писать через PowerShell `Set-Content` (ломает UTF-8, добавляет BOM).

Рабочие каталоги (все пути ниже — относительно `D:\Docs\VSCode\JB-SelfCompany\Yggdrasil\Tyr_app`):
`Android/`, `Librarys/Yggmail-ng/`, `Librarys/Yggdrasil-ng/`, `Librarys/imap-rs/`, `Librarys/rustls-yggmail/`, `Librarys/yggmail-old-mobile/`, `Librarys/yggdrasil-go-old/`.

---

### Task 1: imap-rs — регрессионный тест на эхо `BODY.PEEK` и коммит фикса

Живой фикс уже запушен с `0a46c40`: `imap-server/src/cmd/fetch.rs:144-149` (`write_body_section_response` всегда пишет `BODY`). Незакоммиченная правка в `writer.rs` касается `FetchResponseWriter` — мёртвого кода (нигде не используется); коммитим её ради согласованности. Тестом живой путь не покрыт: тестовая сессия возвращает из `fetch` пустой список, поэтому body-секции никогда не пишутся.

**Files:**
- Modify: `Librarys/imap-rs/imap-server/tests/roundtrip.rs:68` (fetch тестовой сессии) + новый тест в конце файла
- Commit: `Librarys/imap-rs/imap-server/src/cmd/writer.rs` (уже изменён)

**Interfaces:**
- Consumes: `imap_server::backend::FetchedMessage { seq, uid, flags, internal_date, rfc822_size, body }`
- Produces: ничего для других задач; запушенный `imap-rs@main` — вход для Task 3.

- [ ] **Step 1: Дать тестовой сессии одно письмо в `fetch`**

Заменить строку 68 в `roundtrip.rs`:

```rust
    async fn fetch(&mut self, _: bool, _: &SeqSet, _: &FetchOptions) -> Result<Vec<FetchedMessage>, ImapError> {
        // One fixed message so FETCH responses actually carry body sections.
        let body = b"Subject: hi\r\nX-Tyr-Chat: 1\r\n\r\nhello\r\n".to_vec();
        Ok(vec![FetchedMessage {
            seq: 1, uid: 1, flags: vec![],
            internal_date: "01-Jan-2026 00:00:00 +0000".into(),
            rfc822_size: body.len() as u32, body,
        }])
    }
```

- [ ] **Step 2: Добавить тест в конец `roundtrip.rs`**

```rust
#[tokio::test]
async fn test_fetch_peek_response_is_not_echoed() {
    // RFC 3501 §7.4.2: the response item is BODY[...], never BODY.PEEK[...].
    // Tyr's strict ImapFetcher dropped every chat mail when the server echoed .PEEK.
    let cmd = b"a1 LOGIN test test\r\na2 SELECT INBOX\r\n\
               a3 UID FETCH 1 (UID BODY.PEEK[])\r\n\
               a4 NOOP\r\n";
    let all = run_session(cmd).await.join("");
    eprintln!("peek FETCH response: {all}");
    assert!(all.contains("a3 OK"), "fetch ok: {all}");
    assert!(all.contains("BODY[]"), "response must name BODY[]: {all}");
    assert!(!all.contains("BODY.PEEK"), "response must not echo .PEEK: {all}");
    assert!(all.contains("a4 OK"), "no desync after fetch: {all}");
}
```

- [ ] **Step 3: Проверить, что тест ловит баг (RED)**

Временно вернуть баг в живом пути: в `imap-server/src/cmd/fetch.rs:149` заменить
`conn.encoder.atom("BODY").await;` на
`conn.encoder.atom(if bs.peek { "BODY.PEEK" } else { "BODY" }).await;`

```bash
cd Librarys/imap-rs
cargo test -p imap-server --test roundtrip test_fetch_peek_response_is_not_echoed
```
Expected: FAIL на `response must not echo .PEEK`.

- [ ] **Step 4: Вернуть фикс и прогнать всё (GREEN)**

```bash
git checkout -- imap-server/src/cmd/fetch.rs
cargo test --workspace
```
Expected: все тесты PASS, включая `test_deltachat_prefetch_fetch_parses` и `test_fetch_partial_section_parses` (теперь они тоже пишут body-секции).

- [ ] **Step 5: Коммит и push**

```bash
git add imap-server/src/cmd/writer.rs imap-server/tests/roundtrip.rs
git commit -m "test(fetch): guard against echoing BODY.PEEK in FETCH responses; align dead FetchResponseWriter"
git push origin main
```

---

### Task 2: rustls-yggmail — перевендоринг на 0.23.46, перенос SNI-патча, публикация

В 0.23.46 фатальная ветка на месте: `src/server/hs.rs:750` (`Some(ServerNamePayload::Invalid) => return Err(... IllegalParameter ...)`). Патч из `e9217be` переносится один в один.

**Files:**
- Replace: всё содержимое `Librarys/rustls-yggmail/` кроме `.git/` — исходниками crates.io `rustls-0.23.46`
- Modify: `Librarys/rustls-yggmail/src/server/hs.rs:750-755`

**Interfaces:**
- Produces: пакет `rustls` версии `0.23.46` по пути `../rustls-yggmail` — его подхватит `[patch.crates-io]` в Yggmail-ng (Task 3).

- [ ] **Step 1: Ветка на текущую версию — на случай отката**

```bash
cd Librarys/rustls-yggmail
git branch rustls-0.23.40 e9217be
```

- [ ] **Step 2: Перевендорить 0.23.46 (исходник уже в кэше cargo)**

PowerShell:
```powershell
$src = "$env:USERPROFILE\.cargo\registry\src\index.crates.io-1949cf8c6b5b557f\rustls-0.23.46"
Get-ChildItem -Force | Where-Object Name -ne '.git' | Remove-Item -Recurse -Force
Copy-Item "$src\*" . -Recurse -Force
Copy-Item "$src\.cargo_vcs_info.json","$src\.clippy.toml","$src\.gitignore" . -Force -ErrorAction SilentlyContinue
git add -A
git commit -m "vendor: rustls 0.23.46 exactly as published on crates.io"
```
Expected: `Select-String Cargo.toml -Pattern '^version'` → `version = "0.23.46"`.

- [ ] **Step 3: Перенести SNI-патч**

```bash
git cherry-pick e9217be
```
Если конфликт по контексту — привести `src/server/hs.rs` вокруг строки 750 к виду (через редактор, не PowerShell):

```rust
        Some(ServerNamePayload::IpAddress) => None,
        // yggmail fork: узлы yggmail-go шлют ServerName = hex(pubkey) — одну
        // 64-символьную DNS-метку, которая превышает лимит в 63 символа и
        // разбирается как Invalid. Upstream отвечает фатальным IllegalParameter,
        // что убивает КАЖДОЕ входящее соединение от узла 1.8. Мы SNI не
        // используем вообще: сертификат один, а аутентификация идёт по
        // ed25519-ключу из сертификата, сверяемому с адресом источника в mesh.
        // Поэтому нераспарсенное имя трактуем ровно как IP-литерал выше —
        // ведём себя так, будто расширения server_name не было.
        Some(ServerNamePayload::Invalid) => None,
        None => None,
```
затем `git add src/server/hs.rs && git cherry-pick --continue`.

Expected: `git grep -n "ServerNameMustContainOneHostName" src/server/hs.rs` — пусто.

- [ ] **Step 4: Проверить, что форк компилируется**

```bash
cargo check --no-default-features --features ring,tls12,std,logging
```
Expected: `Finished`.

- [ ] **Step 5: Прочитать changelog 0.23.41–0.23.46 на security-фиксы**

Открыть https://github.com/rustls/rustls/releases (теги `v/0.23.41` … `v/0.23.46`). Записать в сообщение следующего коммита Yggmail-ng одной строкой, были ли security-фиксы. Если там есть изменение логики SNI в `hs.rs` — остановиться и сообщить агенту.

- [ ] **Step 6: Опубликовать форк**

```bash
gh repo create JB-SelfCompany/rustls-yggmail --public --source . --remote origin --description "rustls fork: tolerate yggmail-go SNI (64-char hex label)"
git push -u origin master
git push origin rustls-0.23.40
```

---

### Task 3: Yggmail-ng — синхронизация lock-файла с Yggdrasil-ng v0.3.2, тесты, push

**Files:**
- Modify: `Librarys/Yggmail-ng/Cargo.lock` (только `cargo update`)
- Modify (только если компилятор потребует): `Librarys/Yggmail-ng/crates/yggmail/src/**`, `crates/yggmail-mobile/src/**`

**Interfaces:**
- Consumes: `Librarys/Yggdrasil-ng@v0.3.2` (path), `imap-rs@main` (Task 1), `rustls-yggmail@0.23.46` (Task 2).
- Produces: запушенный `Yggmail-ng@main`, из которого собираются `.so` (Task 6).

- [ ] **Step 1: Убедиться, что Yggdrasil-ng стоит ровно на теге**

```bash
cd Librarys/Yggdrasil-ng
git describe --tags --exact-match   # Expected: v0.3.2
git status --short                  # Expected: только "?? CLAUDE.md"
```

- [ ] **Step 2: Обновить lock**

```bash
cd ../Yggmail-ng
cargo update
cargo tree -p yggmail -i rustls --depth 0
```
Expected: строка `rustls v0.23.46 (…\Librarys\rustls-yggmail)`. Если вместо пути — `registry` или версия ≠ 0.23.46 — **стоп**, патч проигнорирован (вернуться к Task 2).

Дополнительно:
```bash
cargo tree -p yggmail-mobile -i uniffi --depth 0   # Expected: uniffi v0.31.1
```

- [ ] **Step 3: Собрать и прогнать тесты**

```bash
cargo test --workspace
```
Expected: PASS (YMP-тесты `ymp_send_recv_*` — `ignored`, это норма).
Если ошибки компиляции из-за изменений API Yggdrasil-ng v0.3.2 — скопировать полный вывод агенту; агент правит вызовы в `crates/yggmail` / `crates/yggmail-mobile`, после чего повторить шаг.

- [ ] **Step 4: Коммит и push (33 старых коммита + этот)**

```bash
git add Cargo.lock
git commit -m "build: sync with Yggdrasil-ng v0.3.2 and rustls fork 0.23.46"
git push origin main
```
Если правился код на Step 3 — добавить эти файлы в тот же коммит и дописать в сообщение, что поменялось.

---

### Task 4: Аудит паритета ironwood (Rust) с Go-ironwood (yggdrasil-go 0.5.15)

Только чтение. Go 0.5.15 включает ironwood PR #54/#55/#56; Yggdrasil-ng портировал их коммитами `59e8973` (#54), `c062371` (#55), `736b24d` (#56). Проверить, что в порт вошли **все** подпункты.

**Files:**
- Create: `Android/docs/superpowers/notes/2026-11-02-ironwood-parity.md` (результат аудита)

- [ ] **Step 1: Сопоставить коммиты**

| Go ironwood (Arceliar/ironwood) | Что проверить в Yggdrasil-ng |
|---|---|
| `27216a0` Fix bloom filter stale-bit cleanup (#54) | `crates/ironwood/src/bloom.rs` / `router.rs:381 blooms_maintenance` — сбрасываются ли биты пиров, которые ушли |
| `b601d1b` Reject incomplete or looping ancestry (#54) | есть в `59e8973` |
| `25cbb5e` Fix cost on direct-root peers with dist 0 (#54) | `router.rs` — расчёт стоимости для прямого пира корня |
| `7238b4c` Fix throttling on repeated lookups — timestamp not updated (#55) | `pathfinder.rs:123 should_throttle_lookup` / `:132 mark_lookup_sent` — обновляется ли метка при каждой отправке |
| `901a25c` Only permit port 0 on root self-announce (#55) | есть в `c062371` |
| `b8f9437` Ignore incomplete/looping/different-root ancestry in `_getDist` (#55) | есть в `c062371` |
| `834da78` Low-order / identity point keys (#56) | есть в `736b24d`, `ff374e3` |

Команды:
```bash
gh api repos/Arceliar/ironwood/commits/27216a0 --jq '.files[].patch'
gh api repos/Arceliar/ironwood/commits/25cbb5e --jq '.files[].patch'
gh api repos/Arceliar/ironwood/commits/7238b4c --jq '.files[].patch'
git -C Librarys/Yggdrasil-ng show 59e8973 c062371 --stat
git -C Librarys/Yggdrasil-ng log --oneline -i --grep=bloom --grep=throttl --grep=cost
```

- [ ] **Step 2: Записать итог в notes-файл**

Формат — таблица из Step 1 с колонкой «Статус: есть / нет / частично + файл:строка». Для «нет/частично» — черновик issue в `Revertron/Yggdrasil-ng` (заголовок + 3–5 строк, ссылка на Go-коммит).

- [ ] **Step 3: Решение**

Отсутствие этих пунктов **не блокирует** релиз (это оптимизации маршрутизации, не совместимость протокола). Issue отправляет автор (`gh issue create -R Revertron/Yggdrasil-ng ...`) по своему решению.

---

### Task 5: yggmail-old-mobile — заморозка WIP

**Files:** `Librarys/yggmail-old-mobile/{internal/smtpsender/sender.go, internal/storage/sqlite3/table_config.go, internal/transport/yggdrasil.go, mobile/yggmail.go}`

- [ ] **Step 1: Сохранить в ветку, main оставить чистым**

```bash
cd Librarys/yggmail-old-mobile
git switch -c wip-hot-reload
git add -A
git commit -m "wip: hot-reload peers on network change (frozen, Go engine retired on Android)"
git push -u origin wip-hot-reload
git switch main
```
Expected: `git status --short` на main — пусто.

---

### Task 6: Пересборка `.so` (4 ABI) + биндинги, интеграция в Android

**Files:**
- Replace: `Android/app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86,x86_64}/libyggmail_mobile.so`
- Replace: `Android/app/src/main/java/uniffi/yggmail_mobile/yggmail_mobile.kt`

**Interfaces:**
- Consumes: `Yggmail-ng@main` после Task 3 (записать хэш: `git -C Librarys/Yggmail-ng rev-parse --short HEAD`).
- Produces: APK для стенда (Task 7).

- [ ] **Step 1: Сборка**

PowerShell:
```powershell
cd Librarys\Yggmail-ng
git status --short     # Expected: пусто — собираем только запушенное
.\build-android.ps1 -All
```
Expected: `=== Done! ===`, 4 файла `target\<triple>\release\libyggmail_mobile.so` с текущей датой.

- [ ] **Step 2: Копирование**

```powershell
$a = '..\..\Android\app\src\main'
Copy-Item target\aarch64-linux-android\release\libyggmail_mobile.so   "$a\jniLibs\arm64-v8a\" -Force
Copy-Item target\armv7-linux-androideabi\release\libyggmail_mobile.so "$a\jniLibs\armeabi-v7a\" -Force
Copy-Item target\i686-linux-android\release\libyggmail_mobile.so      "$a\jniLibs\x86\" -Force
Copy-Item target\x86_64-linux-android\release\libyggmail_mobile.so    "$a\jniLibs\x86_64\" -Force
Copy-Item kotlin-bindings\uniffi\yggmail_mobile\yggmail_mobile.kt     "$a\java\uniffi\yggmail_mobile\" -Force
Get-ChildItem "$a\jniLibs" -Recurse -File | Select-Object FullName,Length,LastWriteTime
```
Expected: все 4 `.so` с сегодняшним `LastWriteTime`.

- [ ] **Step 3: Проверка API биндингов**

```bash
cd Android
git diff --stat app/src/main/java/uniffi
git diff app/src/main/java/uniffi | grep -E '^[-+]\s*(fun|class|data class|sealed class|enum class) '
```
Expected: пусто или только внутренние изменения. Если поменялись публичные сигнатуры — показать вывод агенту; правки в `service/YggmailService.kt`.

- [ ] **Step 4: Сборка APK**

```bash
./gradlew clean assembleRelease assembleDebug
```
Expected: `BUILD SUCCESSFUL`; `app/build/outputs/apk/release/Tyr-1.9.0.1-*.apk` (имя версии меняется в Task 9).

- [ ] **Step 5: Коммит (локально, без push)**

```bash
git add app/src/main/jniLibs app/src/main/java/uniffi
git commit -m "build: rebuild yggmail-mobile on Yggdrasil-ng v0.3.2 + rustls 0.23.46 (Yggmail-ng <hash>)"
```

---

### Task 7: Стенд — ручная проверка

Устройства: **A** — основной телефон (новая сборка), **B** — второй Android с 1.8.1. Ставить debug-сборку (`Tyr-dev`, отдельный applicationId) там, где нельзя трогать боевые данные; release — для тестов миграции 4 и 5.

**Files:**
- Create: `Android/docs/superpowers/notes/2026-11-02-bench-results.md`

- [ ] **Step 1: Шаблон результатов**

```markdown
| # | Проверка | Результат | Заметки / лог |
|---|---|---|---|
| 1 | A → B письмо (DeltaChat/ArcaneChat) |  |  |
| 2 | B → A письмо |  |  |
| 3 | Встроенный чат A ↔ B, квитанции SENT→READ | | |
| 4 | B: апгрейд 1.8.1 → 1.9.1 (release APK поверх), адрес/почта/чаты на месте | | |
| 5 | A: 1.9.0 (собрать из локального тега `1.9.0`) → данные → апгрейд 1.9.1, адрес на месте | | |
| 6 | Экспорт бэкапа (с БД) → файл > 0 байт → импорт на чистой установке | | |
| 7 | Автоподбор пиров: пиры найдены, RTT отображается | | |
| 8 | Разные хабы (если есть третий узел): 10 сообщений подряд, дошло 10/10 | | |
```

- [ ] **Step 2: Прогнать 1–3** (до апгрейда B, пока на нём ещё 1.8.1). Перед каждым тестом включить сбор логов: Settings → Logs. При красном — экспорт логов за 30 минут из `LogsActivity` с обоих устройств.

- [ ] **Step 3: Тест 5 — подготовка 1.9.0**

```bash
git worktree add ../tyr-1.9.0 1.9.0
cd ../tyr-1.9.0 && ./gradlew assembleRelease
```
Поставить на A, создать адрес, отправить/получить 2 письма, записать адрес; затем поставить 1.9.1 поверх (тот же ключ подписи) и сравнить адрес. Убрать worktree: `git worktree remove ../tyr-1.9.0`.

- [ ] **Step 4: Прогнать 4, 6, 7, 8**, заполнить таблицу.

- [ ] **Step 5: Гейт** — релиз только если 1, 2, 4, 5 зелёные. Красные 3, 6, 7, 8 → Task 8.

---

### Task 8: Фиксы по красным строкам стенда

Для каждой красной строки — отдельный цикл через `superpowers:systematic-debugging`:

- [ ] **Step 1:** Агент получает строку таблицы + логи обоих устройств; воспроизводит путь по коду (для #6 — `utils/BackupManager.kt:67` `createBackup(includeDatabase)` → `readDatabaseAsBase64` `:275` → `File(filesDir, "yggmail.db")`; для #7 — автоконфиг пиров из 1.6.0; для #1–#3 — `Yggmail-ng/crates/yggmail/src/peer/` + QUIC overlay).
- [ ] **Step 2:** Падающий тест (Rust — в соответствующем крейте; Kotlin — `app/src/test/`), RED.
- [ ] **Step 3:** Минимальный фикс в общей точке, GREEN; `cargo test --workspace` / `./gradlew test`.
- [ ] **Step 4:** Если фикс в Rust — повтор Task 3 Step 4 (push) и Task 6 целиком.
- [ ] **Step 5:** Перепрогон красной строки + строк 1 и 2 на стенде.

---

### Task 9: Версия 1.9.1 и changelog

**Files:**
- Modify: `Android/app/build.gradle:8-13`
- Create: `Android/fastlane/metadata/android/en-US/changelogs/30.txt`
- Create: `Android/fastlane/metadata/android/ru-RU/changelogs/30.txt`
- Modify: `Android/CLAUDE.md` раздел «Release checklist» (тег без `v`)

- [ ] **Step 1: Версия**

```groovy
def RELEASE_VERSION_CODE = 30
def RELEASE_VERSION_NAME = "1.9.1"

// Dev (signed debug): bump independently of release
def DEV_VERSION_CODE = 30
def DEV_VERSION_NAME = "1.9.1-dev"
```

- [ ] **Step 2: `en-US/changelogs/30.txt`** (UTF-8 без BOM, ≤ 500 символов)

```text
• Works with the whole yggmail network again: mail is exchanged with Tyr 1.8.x, Tyr Desktop and yggmail over QUIC
• Updates from 1.8.x and 1.9.0 keep your @yggmail address, mail and chats
• Built-in chat is back
• Updated Yggdrasil core: more reliable routing, safer key and handshake checks
• Fixed: chat messages not appearing, duplicate deliveries
```

- [ ] **Step 3: `ru-RU/changelogs/30.txt`**

```text
• Снова работает со всей сетью yggmail: обмен почтой с Tyr 1.8.x, Tyr Desktop и yggmail по QUIC
• Обновление с 1.8.x и 1.9.0 сохраняет адрес @yggmail, почту и чаты
• Встроенный чат вернулся
• Обновлено ядро Yggdrasil: надёжнее маршрутизация, строже проверка ключей и рукопожатий
• Исправлено: не появлялись сообщения чата, дублировалась доставка
```
Если в Task 8 были фиксы — добавить по строке в оба файла.

- [ ] **Step 4: CLAUDE.md** — в «Release checklist» заменить `git tag v1.x.x` на `git tag 1.x.x` и пункт 3 поставить первым (версия коммитится до тега).

- [ ] **Step 5: Коммит**

```bash
git add app/build.gradle fastlane/metadata/android/*/changelogs/30.txt CLAUDE.md
git commit -m "release: 1.9.1 (vc 30)"
```

---

### Task 10: Релиз

**Interfaces:**
- Consumes: запушенные imap-rs (T1), rustls-yggmail (T2), Yggmail-ng (T3); локальные коммиты Android (T6, T9).

- [ ] **Step 1: Предполётная проверка — все библиотеки запушены**

```bash
for d in imap-rs rustls-yggmail Yggmail-ng; do git -C Librarys/$d status -sb | head -1; done
```
Expected: ни одной строки с `ahead`.

- [ ] **Step 2: Чистая сборка с финального коммита**

```bash
cd Android
git status --short            # Expected: пусто
./gradlew clean assembleRelease
```

- [ ] **Step 3: Подпись тем же ключом, что 1.8.1**

Подписать APK тем же способом, что прошлые релизы. Проверка совпадения сертификата:
```bash
gh release download 1.8.1 -R JB-SelfCompany/Tyr -p "*universal*" -D /tmp/tyr181
apksigner verify --print-certs /tmp/tyr181/*.apk | grep SHA-256
apksigner verify --print-certs app/build/outputs/apk/release/Tyr-1.9.1-universal*.apk | grep SHA-256
```
Expected: одинаковые SHA-256.

- [ ] **Step 4: Push, тег, релиз**

```bash
git push origin main
git tag 1.9.1
git push origin 1.9.1
gh release create 1.9.1 app/build/outputs/apk/release/Tyr-1.9.1-*.apk \
  --title "1.9.1" --notes-file fastlane/metadata/android/en-US/changelogs/30.txt --latest
```

- [ ] **Step 5: Сообщить пользователям**

```bash
gh issue comment 40 -R JB-SelfCompany/Tyr --body "1.9.1 is out: it talks QUIC again, so it exchanges mail with 1.8.x / Desktop / yggmail, and updating from 1.9.0 keeps your address. IzzyOnDroid will pick it up on its next sync."
```

- [ ] **Step 6: Проверка Izzy (через 24–48 ч)**

https://apt.izzysoft.de/packages/com.jbselfcompany.tyr — показана 1.9.1. Если нет через 72 ч — открыть issue в https://gitlab.com/IzzyOnDroid/repo с ссылкой на релиз.

---

### Task 11: Обновить устаревшую документацию

**Files:**
- Modify: `Tyr_app/CLAUDE.md` — таблица стека и раздел «Library/yggmail»: `Librarys/` (с опечаткой), Android = Rust/UniFFI из `Librarys/Yggmail-ng` (`build-android.ps1 -All`), убрать gomobile/yggquic для Android; добавить зависимость `rustls-yggmail` и правило «push библиотек до тега Android».
- Modify: `Android/CLAUDE.md` раздел «Native Library»: заменить «typically done in the `Library/yggmail/` sibling repo» и 3 шага на ссылку `../Librarys/Yggmail-ng/build-android.ps1 -All` + 5 строк копирования из Task 6 Step 2.

- [ ] **Step 1:** Агент вносит правки; автор просматривает diff.
- [ ] **Step 2:** Коммит в Android: `git commit -am "docs: point native build at Yggmail-ng"` (корневой `Tyr_app/CLAUDE.md` вне git — просто сохранить).

---

## Следующий план (после 1.9.1)

Переезд в организацию на новом git-сервере (вариант C): монорепо `tyr` (`android/`, `desktop/`, `yggmail-ng/`, `imap-rs/`, история через `git filter-repo --to-subdirectory-filter` + `merge --allow-unrelated-histories`, теги с префиксом `android/`, `desktop/`); отдельные репо `rustls-yggmail` и `Yggdrasil-ng` (форк), подключаемые Cargo git-зависимостями с `rev`; `yggmail-android` — в архив. Не начинать до выхода 1.9.1 в IzzyOnDroid. Перед переездом: согласовать с IzzyOnDroid новый URL репо и схему тегов.

## Бэклог (вне плана)

#32 MTE на GrapheneOS · параллельная отправка по адресатам · срок жизни очереди / bounce для узлов 1.9.0 · #34 · uniffi 0.32 / AGP / Kotlin · Desktop · issue в Yggdrasil-ng по итогам Task 4.
