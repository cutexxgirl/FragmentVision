# Офлайн-прототип: прямое упрощение с контролем стоимости и ошибки

Это CPU-инструмент, **не включённый игровой renderer**. Текущий этап добавляет
прямой baseline текущего порта Voxy. Для каждого tile/level выбирается один тип
страницы: точные packed quads или непосредственно упрощённый индексированный mesh. Геометрические
бюджеты не заменяют игровой GPU A/B.

Результат: [comparison.ru.md](comparison.ru.md).
[Исходные числа](offline-proof/comparison.json), [проверки/hashes](offline-proof/validation.json).

## Алгоритм

1. Читать фактические блоки, state properties, biomes и сохранённый light из
   закрытой копии Anvil. Пустое и неизвестное — разные состояния.
2. Построить baseline **неизменённым** shipping ScanMesher2D с пределом 16×16
   и scan domains, привязанными к мировой 32-блочной решётке.
3. Независимо попробовать 32×32 greedy rectangles с теми же material/light keys.
   Для каждого domain выбрать кандидат или shipping результат с меньшим числом
   rectangles. Payload одинаков: 8 B на quad.
4. Из merged rectangles получить точную объёмную поверхность без предварительного
   сглаживания: сварить углы, разбить общие рёбра во всех T-junctions, триангулировать.
   На границах плиток сохранять полный канонический решётчатый контур.
5. Непосредственно упростить одну исходную поверхность edge-collapse QEM.
   Поиск пробует внутренние cost thresholds 1/2/3/4/6/8, target ratio 0.1.
   Эти величины не являются пределом географического отклонения.
   Контуры, авторская защита, material/biome/light seams закреплены; если защищён
   хотя бы один voxel крупной грани, фиксируется весь её полигон. Проверяются
   link condition, локальные normals и новые контакты вершина/ребро.
6. Кандидат должен не увеличивать ни triangles, ни payload относительно **нашего
   exact-кандидата**, улучшив хотя бы один счётчик. Среди прошедших выбирается
   меньший payload, затем triangles. Предпочтения косметической гладкости нет.
7. Независимо проверить двустороннее расстояние до **исходной блочной поверхности**.
   Caps L0/L1/L2 = 0.5/1/1.5 блока. BVH даёт точные ближайшие расстояния для точек;
   адаптивные подразделения треугольников используют 1-Lipschitz оценку
   `max(vertexDistances) + longestEdge/sqrt(3) + 1e-6`, покрывающую весь треугольник.
   Если bound недостаточен, продолжается деление; превышение ошибки, depth/query
   budget или неопределённость означают отказ. Проверяются оба направления.
8. Если ни один кандидат не прошёл оба gate, оставить exact quads. Manifest
   содержит `algorithm`, собственный exact baseline, выбранную стоимость,
   отклонённого/принятого кандидата, `error_limit_blocks`, `candidate_error` и reason.
   `candidate_error=null` означает, что проверка расстояния не запускалась:
   кандидат уже проиграл геометрический бюджет. `upper_bound_blocks=null` при
   отказе означает отсутствие доказанной границы, а не нулевую ошибку.
9. Идентичные страницы уровней записать один раз. Checksums и размеры проверить
   по реальным файлам. Не сохранять неиспользуемые вершины или все morph targets.

Поверхность объёмная, не heightfield: пещеры и навесы не заменяются верхней высотой.
Каждый уровень сравнивается с исходником, ошибки не накапливаются от ребёнка к
родителю. Проверяется уже квантованная геометрия 1/256. Граница с exact/mesh
остаётся точной; это может оставить ступени по краю плитки и пока не решает
native transition. World-space bound не заменяет topology, material/silhouette
приёмку или screen-space LOD selection.

## Запуск

Нужны **JDK 21** и Python 3.10+. Core/export/check не требуют pip, Minecraft,
Gradle или native libraries.

```sh
python tools/lod/check.py
java -Xmx256m -cp build/lod-proof/classes BakeProofMain \
  build/lod-proof/comparison/hills.fvvol.gz build/lod-proof/hills
java -Xmx256m -cp build/lod-proof/classes BakeProofMain \
  build/lod-proof/comparison/arch.fvvol.gz build/lod-proof/arch
```

Output directory должен быть новым. `check.py` компилирует CPU-код с
`--release 21 -Xlint:all` и неизменённый shipping scanner для baseline.
Проверяются полное покрытие unit faces, state/biome/light keys, шесть направлений,
стыки fine/coarse и packed/mesh, protected objects, unknown, caps, v1/v3 codecs,
широкие attribute dictionaries, normal error и побайтная повторяемость пакета.

Необязательное превью требует NumPy/Pillow:

```sh
python tools/lod/preview.py build/lod-proof/hills build/lod-proof/arch \
  build/lod-proof/preview.png
```

![Исходная поверхность, прямой кандидат и выбранный уровень](offline-proof/geometry-preview.png)

Это синтетические сцены с условной палитрой/светом, не F2 Minecraft. Правая
колонка показывает выбранное представление. Отклонённый прямой кандидат не
выдаётся за результат нового renderer.

## Настоящий участок карты

Использовать **закрытую копию** сохранения. Экспорт не обеспечивает согласованный
снимок одновременно записываемого мира и не меняет `.mca`. Поддерживаются
Minecraft 1.21.1 DataVersion 3955, full chunks и inline gzip/zlib/uncompressed.
Иные версии, protochunks, external `.mcc` и неизвестные codecs отклоняются.
Java modified UTF-8 (например, emoji на табличке) поддерживается.

Пример четырёх плиток 32×32: core XZ [0,64), halo 7, snapshot [-7,71),
Overworld Y [-64,320).

```sh
python tools/lod/export_world.py /path/to/closed-world-copy/region \
  build/lod-proof/map.fvvol.gz \
  --origin -7 -64 -7 --size 78 384 78 \
  --natural-box -7 -64 -7 71 320 71 \
  --protect-box 16 50 16 32 110 32
java -Xmx256m -cp build/lod-proof/classes BakeProofMain \
  build/lod-proof/map.fvvol.gz build/lod-proof/map
```

Координаты — пример. `--natural-box` разрешает упрощение узкого списка полных
природных блоков; `--protect-box` приоритетно защищает постройки. Оба повторяемы,
max XYZ exclusive. Без маски геометрия точная. Неизвестные/modded формы, снеговые
слои, растения и жидкости не разрешены для упрощения. Палитра сохраняет state
и biome; protected/plant/water модели пока представлены диагностическими кубами,
а не финальными block models.

Отсутствующий chunk/lighting-only section без block payload — unknown. Отсутствующая
section в корректном full chunk — воздух. Missing skylight не заменяется на 15.
Неполный snapshot не публикуется как готовый пакет.

## Форматы

Все числа big-endian. Manifest `fragment-lod-proof-3`. Это экспериментальный ABI.

**FVQUAD01:** header 24 B: magic 8, origin XYZ 12, count 4. Затем 8 B на quad:
X 6 bits, Y 9, Z 6, U-1 6, V-1 6, face 3, light 8, palette 16, reserved 4.
XYZ — minimum owning voxel относительно origin. У positive face плоскость на
один блок дальше. Face order: -X,+X,-Y,+Y,-Z,+Z. Размер = 24 + 8×quadCount.
Vertex/index streams нет; quad означает 2 triangles.

**FVMSH003:** header 52 B: magic 8, origin XYZ 12, vertex/triangle counts 8,
scale=256 (4), attribute count 4, quantized minimum XYZ 12, XYZ bit widths 3,
reserved 1. Vertex содержит XYZ offsets от minima и два oct8 normal bytes.
Word size = ceil((sum XYZ bits + 16)/8), в тестовых холмах 7 B.

Далее lossless dictionary по 4 B на faceData:
`paletteIndex << 11 | sourceFace << 8 | savedNeighbourLight`.
Triangle содержит три vertex IDs и dictionary ID с минимальными общими для
страницы bit widths. Word size = ceil((3×indexBits + attributeBits)/8), в тестовых
холмах 4 B. Координаты сохраняют **1/256**; материал/свет не усредняются. Oct8 normal
имеет погрешность примерно до 1°, проверенную на произвольных направлениях.
Старые FVMSH001/002 читаются.

`payload_bytes` включает vertices/triangles и dictionary без header. `bytes` —
фактический файл. `prepared_unique_pages_bytes` считает страницу один раз;
три ссылки разных уровней не означают три GPU-копии. OBJ/palette/manifest отдельно.

## Ограничения

- QEM cost — эвристика поиска; независимая проверка даёт консервативный world-space
  distance bound с численным запасом, не pixel guarantee. Link condition, local
  normals и vertex/edge contact guard не исключают все triangle self-intersections
  и не сертифицируют сохранность отверстий меньше разрешённой ошибки.
- Snapshot до 8M voxels; direct extraction до 32768 rectangles, 65536 vertices,
  100000 triangles; exact до 262144 quads; QEM queue пересобирается после 200000
  записей. Direct contact guard ограничен 100M потенциальных проверок на один
  поисковый запуск; при лимите поиск останавливается на текущем mesh, приёмка
  стоимости/расстояния всё равно обязательна. Distance audit: 300000 уникальных
  точек на направление и depth 12; исчерпание означает отказ. Это proof caps,
  не production RAM/VRAM manager. Поиск прямых контактов пока квадратичный.
- Переменную упаковку ещё надо измерить в GPU decoder. Payload не равен shader
  time или resident VRAM с alignment/metadata.
- Нет pack bakery, нового входа Render, UV/material maps, hierarchy, streaming,
  native ownership, delta/tombstones, новых крон/дальних теней. Local edit test —
  повторный bake, не живой игровой overlay.
- Прежняя ошибка Gradle 9.4.1 + foojay 0.8.0 (`JvmVendorSpec.IBM_SEMERU`)
  исправлена обновлением foojay до 1.0.0; полная compilation проходит.
  Новый этап описан в [журнале реализации](implementation-progress.ru.md).
  Игровой renderer и shipping scanner этим этапом не изменены.

Следующий gate — настоящая карта, экранная ошибка и материалы
при том же бюджете, затем взаимоисключающий draw и GPU A/B с общим Render.
