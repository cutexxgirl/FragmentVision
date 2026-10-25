Voxy is an LoD rendering mod for minecraft

## Fragment Vision research

- [Новая LOD-система для подготовленной карты: цель, архитектура и план](docs/lod-system/README.ru.md)
- [Аудит upstream Voxy, Ecstatic и кандидата упрощения](docs/lod-system/upstream-review.ru.md)
- [Критерии производительности и протокол сравнения](docs/lod-system/performance.ru.md)
- [Текущая связь с Fragment Render](docs/fragment-render-atmosphere.ru.md)

Геометрический baker, зависимая история упрощений и CPU-формат её хранения реализованы частично; [актуальное состояние](docs/lod-system/implementation-progress.ru.md). [Первый игровой baked-page backend](docs/lod-system/backend-proof-2026-10-25/README.ru.md) подключён для ограниченных пакетов и выключен по умолчанию; полная LOD-система ещё не готова. Эксперименты `docs/hybrid-wip` сохранены как история; они не доказывают выигрыш FPS.
