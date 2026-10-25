# Тест ЛОДов с воздуха

Minecraft 1.21.1, NeoForge 21.1.262, JDK 21. Нужны собранные Fragment Render
из `wip/hybrid-terrain` и Grass-for-Fragment из `final-grass`; ревизии и хеши
использованных JAR — в `fragment.lock.json`. Положить эти JAR в `run/mods/`.

`python tools/lod/test-pack/install.py` устанавливает закреплённые версии
FerriteCore, ModernFix, Entity Culling, ImmediatelyFast, Chunky, Fzzy Config
и Kotlin for Forge с проверкой SHA-512. Sodium 0.8.13 устанавливает Gradle,
Lithium подключается через runtimeClasspath. Iris исключён в режиме `lodAerial`:
он отключает адаптер мирового шейдера Fragment Render. Шейдеры Render встроены
в его JAR, отдельный Iris shaderpack не нужен.

Запуск: `JAVA_HOME=/path/to/jdk21 python tools/lod/test-pack/launch.py`.
Для продолжения указать вторым аргументом имя собственного мира `lod-aerial-*`.
Профиль создаёт только собственные тестовые миры, seed 1234; произвольное имя
существующего мира отклоняется. На программном Mesa в этой среде потребовались
`MESA_GL_VERSION_OVERRIDE=4.6 MESA_GLSL_VERSION_OVERRIDE=460` и Xvfb `:99`.
Это проверка корректности на llvmpipe, а не результат аппаратного GPU.

Chunky генерирует квадрат радиуса 768 с центром 512,700, затем сервер сохраняет
мир и Vision импортирует региональные файлы. Только после завершения импорта
создаётся рендерер. Запрещён спавн мобов, удалены сущности тестовой сцены,
новые Mob не принимаются в мир. Ясная погода и время 4500 фиксированы,
randomTickSpeed=0. Во время подготовки ограничение 10 FPS освобождает CPU;
после подготовки оно снимается.

Настройки: 960×540, FOV 90, native distance 6 чанков, simulation distance 5,
Sodium builder threads 2, CPU render ahead 0, VSync выключен, fog occlusion
выключен для проверки дальнего ландшафта, листья Fancy. Fragment Render Medium,
тени 1024. Фоновые ограничения FPS Modern UI должны быть выключены при замерах.
Набор оптимизаций и качество одинаковы во всех сравниваемых режимах.
Установщик сохраняет эти настройки Sodium и Vision, задаёт Medium и снимает
фоновые ограничения Modern UI. Это конфигурация отдельного `run/` для этой среды;
два потока сборки и llvmpipe не являются рекомендацией для игрового компьютера.

Управление после появления `stage: ready` в `run/lod-aerial/status.json`:

```sh
python tools/lod/test-pack/control.py '{"command":"tp @a 512.5 180 700.5 0 20"}'
python tools/lod/test-pack/control.py '{"mode":"voxel","measure":"voxel-a","frames":120,"warmup":60}'
python tools/lod/test-pack/control.py '{"build":true,"mode":"hybrid"}'
python tools/lod/test-pack/control.py '{"earlyCull":false,"measure":"hybrid-no-cull","frames":120,"warmup":60}'
python tools/lod/test-pack/control.py '{"earlyCull":true,"measure":"hybrid-cull","frames":120,"warmup":60}'
python tools/lod/test-pack/control.py '{"mode":"native","measure":"native-a","frames":120,"warmup":60}'
python tools/lod/test-pack/control.py '{"stop":true}'
```

Дожидаться окончания каждого замера и полной сборки гибридных плиток перед
следующим. Файл управления защищён от перезаписи ещё не принятой команды.
Сырые результаты — `run/lod-aerial/*.json`, оригинальные скриншоты —
`run/screenshots/`. `frame_ms` включает весь кадр, `lod_submit_cpu_ms` — вызов
прохода Vision на CPU, `lod_gpu_ms` — асинхронные GL timestamp queries вокруг
этого прохода. Отсутствующие GPU-замеры не заменяются нулями. Очередь запросов
ограничена; ожидания GPU и `glFinish` для замеров нет. Включение гибрида пока
не устраняет исходные draw-команды и чтение их вершин; раннее отсечение
устраняет растеризацию целиком заменённых граней.

Три уровня этого игрового WIP используют прежнюю геометрию HybridVoxelMesh.
Новая QEM-иерархия из `lod/bake` пока не подключена к игровому draw backend.
Не смешивать результаты двух реализаций и не считать эти кадры проверкой
полной целевой архитектуры из `three-level-runtime.ru.md`.

Сохранённый результат предыдущего запуска и ограничения последней версии:
[отчёт с воздуха](../../../docs/lod-system/aerial-proof/README.ru.md).
После запрета пользователя новые тесты и игровые замеры не запускались.
