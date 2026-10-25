# Игровая проверка Vision 6136416

Проверен именно `6136416a0a5f1e6506dbb948f13d85b23d867a47`, ветка
`fix/lod-cost-and-export`. Код алгоритма не изменялся. Render остаётся на
`0daec67`, `wip/hybrid-terrain`. Игровой профиль доступен **в этой среде**:
`/workspace/fragmentrender-sky/run-water`, мир `saves/Sky_Art_Study`.

JAR собран локально и установлен как `mods/fragmentvision-atmosphere-dev.jar`.
SHA256: `9098c2e388db6d79af0a13e3f34d2a7789135fb58bcf10266f09d08971ecc860`.
Предыдущий JAR сохранён отдельно: `/workspace/scratch/vision-jar-checkpoints/pre-6136416.jar`.

## Проверки

- [Gradle build](build.log): успешно.
- [Полный CPU suite](cpu.log): успешно, включая 12 Python tests, projected selection,
  cost gates, topology, export, codecs/replay, conservative directional buckets.
- [Настоящий GPU indirect test](indirect-gpu.log): 6 ракурсов, direct и culled
  multi-draw побитово совпали, включая two-sided geometry. Это llvmpipe.
- [Игровой log](client.log): профиль загрузился, High связан с Vision, старый
  HybridTerrain выключен. Пакеты загружаются, переключение mesh on/off работает.
  Мир штатно сохранён во всех измерениях и renderer закрыт. Показанные shader
  compiler warnings о глобальных receiver inputs присутствовали и прежде; входы
  присваиваются в main перед fr_lighting. Новых shader compile/link failures нет.

## Парные кадры

Один мир и pack, native 8 chunks, Vision distance 512 blocks, High/shadows2048,
1280x720, FOV setting90, SMAA. Player position (512,180,704), yaw135/pitch25,
время4500, дневной цикл остановлен. Cloud shadow strength временно 0 в обоих
режимах, затем восстановлен в 1. Облака продолжали двигаться: это не побитовое
сравнение всего финального framebuffer.

`/voxy hybrid off` -> `/voxy mesh load sky-reduced` -> status -> F2;
`/voxy mesh off` -> status normal -> дождаться прогрузки Voxy -> F2.
Позже загружен отдельно `sky-cost-6136416`, без перемещения камеры.
Voxy здесь означает портированный штатный renderer без нашего HybridTerrain,
не отдельную установку upstream Voxy другой версии.

- [Новый renderer, прежний sky-reduced](ours-old-package.png) — внизу ещё виден
  затухающий диагностический текст; правый участок сравнения он не закрывает.
- [Портированный Voxy](voxy.png).
- [Новый renderer, два участка повторно baked новым gate](ours-cost-package.png).

Внешний вид по-прежнему не принят: дальний лес слишком блочный, освещение и
материалы расходятся с Voxy/native; вода нового backend выглядит серой вместо
синей. Новых очевидных дыр от directional submission в этом ракурсе не видно,
но это не аудит всех положений камеры или гарантия отсутствия любых артефактов.

### Точки, отмеченные пользователем

На равнине разница с Voxy действительно видна. Но вывод «все серые пятна —
ошибка генерации/экспорта» был бы неверным. [Проверка источника](source-samples.json)
дала в выбранных координатах grass_block, short_grass, stone, water и oak_leaves.
После закрытия игры прочитан реальный Anvil save: все 11 state совпали со snapshot.
Например stone действительно существует в (493,67,472), (516,68,474), (536,68,472).
Это не доказывает, что каждый серый пиксель отображён правильно: Voxy укрупняет
геометрию и использует другой material/model pipeline, а наш full-cube input
не учитывает реальные формы растений, fluid/material handling и canopy proxies.

Координаты образцов получены приблизительным CPU-лучом по diagnostic cubes при
FOV90 и eye height1.62; это **не GPU primitive picking** и не доказательство
точного соответствия каждого отмеченного экранного пикселя данному state.
Сама проверка state в файле мира независима от такой проекции.

[Красные пиксели](red-pixels.json): 14 выбранных красных точек правой области
есть и в кадре Voxy, обычно с почти тем же RGB. Примеры:
(815,479): ours (204,21,19), Voxy (204,22,19);
(884,521): (205,21,19) в обоих;
(1085,571): (165,54,44) в обоих.
Значит эти точки не являются доказательством ошибки исключительно нового mesh
backend. Их семантическая причина (цветы/другой материал/общий filtering) ещё
не установлена. Объявлять их ошибкой индексов или atlas bleed пока оснований нет.

## Что дали оптимизации

На прежнем неизменённом пакете в том же ракурсе:

- submitted: **3 447 392 triangles**;
- direction-culled: **1 934 818 triangles**;
- сумма: 5 382 210 — прежняя отправка без directional culling;
- сокращение отправки около **35.95%**, GPU buffers 74 709 412 bytes.

Это реальное уменьшение submitted geometry, **не аппаратный FPS benchmark**.
Драйвер llvmpipe, лимит CPU 2 cores; прежняя 512MiB Voxy arena всё ещё резидентна.

## Проверка нового cost gate на реальных snapshots

Повторно baked неизменяемые старые b0 и b27, 32x32 pages, triangle-priority.
[Рельеф](rebake-terrain.json), [лес](rebake-forest.json). Новый exporter opt-in для
листвы здесь **не применялся**: старые natural masks не менялись. Его поведение
проверено экспортными tests, но нельзя приписывать этому bake новые crown LOD.

| Область, far | Старые tri | Новые tri | Старые GPU geometry B | Новые GPU geometry B | Старые/новые mesh pages |
|---|---:|---:|---:|---:|---:|
| b0, рельеф | 27 756 | 35 630 | 310 700 | 208 380 | 10 / 4 |
| b27, лес | 286 012 | 312 536 | 1 659 744 | 1 252 576 | 4 / 0 |

Сравнение GPU bytes использует одинаковый текущий ABI, включая EBO/command budget
для quads. Общие atlas/material tables сюда не входят. Gate честнее: он отклонил
дорогие меши, поэтому число triangles выросло. Это не новое эффективное представление
леса и не доказательство улучшения общей производительности.

Из этих двух результатов создан `lod-packages/sky-cost-6136416` через существующий
`tools/lod/overlay_sky_art_frame.py`; остальные участки исходного sky-full сохранены.
Полный мир не rebaked. В игре этот пакет загрузился: 284 unique pages,
GPU 73 776 852 bytes, submitted 3 444 690, direction-culled 1 945 394.
Общий выигрыш resident GPU buffers относительно старого пакета — 932 560 bytes,
а не 25–33% на всю карту. Проценты из таблицы относятся только к двум областям.

Итог: изменения работают, cost gates стали честнее, directional submission
сокращает отправку. Качество изображения, fluid/model/material inputs, отдельные
кроны, morph, streaming/edits, native seam contract и дальние caster shadows
по-прежнему требуют отдельной работы. Код пользователя в этой проверке не исправлялся.
