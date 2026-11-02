# Автоподбор пиров для Tyr 1.9.1 (порт из Huginn)

Дата: 2026-11-02 · Источник: `Projects/Huginn/src/picker.rs` (коммиты `2f08e55`, `e5741c4`, `4a7c672`)

## Зачем

В 1.8.x автоподбор жил в Go-движке; при миграции на Rust (`16dbe4d`) он удалён вместе с UI. Issue #40:
«Automatic peer discovery is no longer working». Возвращаем — по алгоритму Huginn, проверенному на десктопе
на том же ядре Yggdrasil-ng.

## Объём (одобрено)

Подбор + запасной пир + автопереход по событию. Вне объёма: ежедневный пересмотр, пинг-проверки здоровья,
перепоиск при смене корня, прямые линки (`direct.rs`).

## Архитектура

```
Kotlin PeerCatalog ── publicnodes.json (HTTP, кэш 7 дней) ──┐
Kotlin PeersActivity / автоподбор после онбординга           │
   └─ YggmailMobile.pickPeers(catalogJson, enabled, disabled, listener) ── Rust picker.rs
          этап A: TCP-замер каталога (tls/tcp, public IP, аптайм ≥ 90 %) → топ-8
          этап B: add_peer топ-8 → up → get_tree → RTT ядра × (глубина+1), корень большинства
          → PickReport { rows, verdict Keep/Switch(+gain%), backup (другая /24|/48) }
          временные линки удаляются всегда, кроме победителя Switch
Kotlin применение: победитель — единственный включённый, остальные выключены (не удалены),
   запасной — выключен с флагом isBackup → hotReloadPeers
Kotlin PeerFailover (YggmailService, опрос getPeerConnectionsJson раз в 30 с, только локально):
   основной не up ≥ 2 мин и есть запасной и с прошлого перехода ≥ 10 мин → swap(основной↔запасной)
```

## Отличия от Huginn

| Huginn | Tyr | Почему |
|---|---|---|
| Схемы tls, quic, tcp, ws, wss | **tls, tcp** | Tyr собирает Yggdrasil-ng с `default-features = false`: ws/quic-линков нет |
| ICMP (Windows API) на этапах A (quic) и B | **нет**; задержка этапа B = RTT ядра (`latency_ms`) | на Android нет этого API; Huginn уже использует RTT ядра как запасной вариант |
| Каталог качает Rust (`ureq`) | **Kotlin** (`HttpURLConnection`), Rust получает строку | не тащить второй HTTP/TLS-стек в `.so` |
| `CancellationFlag` | `AtomicBool` в `YggmailMobile`, `cancel_pick()` | одна активная проверка на узел |
| Переход на запасной + поиск нового запасного через 5 мин | **swap** основной↔запасной, без нового поиска | объём «без фонового трафика» |
| Прогресс в egui | `PickListener.on_progress(stage, done, total)` — UniFFI callback | |

## FFI (UDL)

```
callback interface PickListener { void on_progress(string stage, u32 done, u32 total); };
dictionary PickRow { string uri; string host; string country; f64 latency_ms; i32 depth; f64 score; boolean usable; string reason; };
dictionary PickResult { sequence<PickRow> rows; string verdict; string switch_uri; i32 gain_pct; string backup; };
[Throws=YggmailError] PickResult pick_peers(string catalog_json, sequence<string> enabled, sequence<string> disabled, PickListener listener);
void cancel_pick();
```
`verdict` = `switch` | `current_best` | `small_gain` | `none_passed`; пустые строки / −1 = нет значения.
`pick_peers` блокирует вызывающий поток ~1 мин; Kotlin зовёт его с отдельного потока (не `serviceHandler`),
Rust берёт `Arc<Core>` из состояния и сразу отпускает мьютекс.

## Ошибки

Нет сети / каталог не скачан и кэша нет → понятное сообщение, пиры не трогаются. Отмена → временные линки
удалены, конфиг не менялся. `Keep` → конфиг не меняется (запасной всё равно сохраняется, если найден).

## Проверка

Rust: тесты Huginn (tree_info, network_root, decide, apply/backup, candidates, is_public) + фильтр схем tls/tcp.
Kotlin: JVM-тест `PeerFailover` (решение о переходе). Стенд: подбор на эмуляторе → применить → линк
основного не поднимается (блок в эмуляторе) → через ~2 мин переход на запасной.
