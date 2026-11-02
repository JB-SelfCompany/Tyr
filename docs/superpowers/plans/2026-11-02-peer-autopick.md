# Автоподбор пиров — план реализации

> Spec: `docs/superpowers/specs/2026-11-02-peer-autopick-design.md`. Исполняется в этой сессии (агент собирает и коммитит сам).

**Global constraints:** схемы только `tls`/`tcp`; без ICMP; каталог качает Kotlin; uniffi `=0.31.1`;
временные линки этапа B удаляются на любом выходе, кроме победителя `Switch`; native-вызов `pickPeers`
— с отдельного потока, не с `serviceHandler`.

### Task P1 — Rust `picker.rs` в `yggmail-mobile` (порт + тесты)
- Create `Librarys/Yggmail-ng/crates/yggmail-mobile/src/picker.rs`: `candidates`, `is_public`, `uptime`,
  `base`, `decide`, `tree_info`, `network_root`, `same_net`, `pick_backup`, `rank`, `stage_a` (TCP),
  `stage_b` (add_peer → up → get_tree → core RTT), `run`. Константы как в Huginn, `SCHEMES = ["tls","tcp"]`.
- Modify `Cargo.toml`: `url = "2"`.
- Тесты: перенесённые из Huginn + `candidates_keep_only_tls_and_tcp`.
- `cargo test -p yggmail-mobile`.

### Task P2 — FFI
- UDL: `PickListener`, `PickRow`, `PickResult`, `pick_peers`, `cancel_pick` (см. spec).
- `lib.rs`: `cancel: Arc<AtomicBool>` в `YggmailMobile`; `pick_peers` клонирует `Arc<Core>`, отпускает
  мьютекс, `runtime.block_on(picker::run(..))`, маппинг в `PickResult`.
- `cargo test --workspace`; commit + push Yggmail-ng; `build-android.ps1 -All`; копия `.so` + `.kt` в Android.

### Task P3 — Kotlin: каталог, применение, запасной
- Create `utils/PeerCatalog.kt` (download 15 s → `filesDir/publicnodes.json`; при ошибке кэш ≤ 7 дней).
- `data/PeerInfo.kt`: поле `isBackup` (JSON, по умолчанию false); `ConfigRepository.applyPick(winner, backup)`,
  `getBackupPeer()`, `swapToBackup()`.
- Create `service/PeerFailover.kt` — чистая логика решения (JVM-тест `app/src/test/.../PeerFailoverTest.kt`).
- `YggmailService`: опрос раз в 30 с на `serviceHandler` → `PeerFailover` → `swapToBackup()` + `hotReloadPeers()`;
  `runPeerPick(listener)` на отдельном потоке.

### Task P4 — UI
- `PeersActivity`: пункт «Подобрать лучший пир» → диалог с прогрессом, отменой, результатом (топ, RTT,
  страна, вердикт) и «Применить»; запасной помечен в списке пиров.
- Онбординг: галочка «Подобрать пир автоматически» (по умолчанию вкл.) → флаг; после первого запуска
  сервиса — подбор и автоприменение `Switch`.
- Строки en/ru. `./gradlew assembleRelease testDebugUnitTest`.

### Task P5 — стенд и релиз-заметки
- Эмулятор: подбор → применить → проверить пиры/почту; failover (блок основного) → swap через ~2 мин.
- changelog `30.txt` en/ru: строка про автоподбор; bench-results.
