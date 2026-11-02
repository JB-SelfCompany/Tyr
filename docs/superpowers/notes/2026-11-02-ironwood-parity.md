# Паритет ironwood: Yggdrasil-ng v0.3.2 ↔ Go ironwood (yggdrasil-go 0.5.15)

Дата: 2026-11-02. Go: `Arceliar/ironwood@0712ecc`. Rust: `Revertron/Yggdrasil-ng@c3a3aa6` (v0.3.2).

| Go-коммит | Суть | Статус в Rust | Где |
|---|---|---|---|
| `27216a0` (#54) bloom stale-bit cleanup | Go хранит `pbi` по значению и не записывал его обратно, если `send` не изменился → терялся `zDirty` | **есть (по построению)** — Rust меняет `pbi` через `get_mut`, `z_dirty` сохраняется всегда; бага нет | `crates/ironwood/src/bloom.rs:246-265` |
| `b601d1b` (#54) reject incomplete/looping ancestry | — | **есть** | `59e8973` |
| `25cbb5e` (#54) cost для прямых пиров корня (`dist + 1`) | — | **есть** | `crates/ironwood/src/router.rs:789` |
| `7238b4c` (#55) throttle повторных lookup (метка не обновлялась) | Go копировал `info` по значению и не писал `reqTime` обратно | **есть (по построению)** — `mark_lookup_sent` правит запись на месте, вызывается после проверки throttle | `pathfinder.rs:132-136`, `router.rs:1264-1268` |
| `901a25c` (#55) port 0 только у корня | — | **есть** | `c062371` |
| `b8f9437` (#55) `_getDist` игнорирует неполную/чужую родословную | — | **есть** | `c062371` |
| `834da78` (#56) low-order / identity ключи | — | **есть** | `736b24d`, `ff374e3` |

**Итог:** расхождений нет, issue в Yggdrasil-ng не нужен. Два «фикса» Go — это баги семантики значений Go, которых в Rust-порте не существовало.
