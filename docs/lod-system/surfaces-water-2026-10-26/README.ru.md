# Поверхности, растительность и вода — WIP 26.10.2026

Работа поверх `b0586089` / пользовательского `6136416a`, отдельная ветка
`wip/lod-surfaces-and-water`. Старый `wip-new-lods` не меняется. Render остаётся
`0daec67`, ветка `wip/hybrid-terrain`: sky/water/material inputs используются из
его существующего API. Эта работа не включает перевод мира на production streaming.

## Цель и причина изменения

Убрать из LOD мелкую растительность, сохранить полные блоки листвы, устранить
серую воду и уменьшать ступеньки внешнего рельефа без дорогого vertex/index
представления и без усреднения текстур. Порог принятия относится к конкретной
геометрии, а не к FPS всей игры.

Раньше в одной поверхности оказывались горы, отдельные листовые кубы, модели,
пещеры и материальные границы. Они закрепляли много вершин. Меш с меньшим числом
треугольников часто занимал больше GPU bytes, чем 8-байтовые quads. Ослаблять
защиту всех объектов ради белого сглаженного пятна — неправильное решение.

## Текущий подход

- `LodInputPolicy` очищает копию immutable snapshot **до** visibility/meshing.
  Удаляются трава, цветы, мелкие кусты, лианы и аналогичные тонкие растения.
  Подводная растительность/водяные столбы восстанавливают water source state с
  исходным biome/light. Листва, стволы и постройки сохраняются. Исходник не меняется.
  Простое удаление plant fragments оставило бы отверстия в закрытых ими поверхностях.
- Для грунта строится непрерывный контекст под листвой и водой. Поверхность
  разделена на внешний рельеф, точные пещеры/навесы и точные детали (листва,
  строения, вода). Пещеры не выбрасываются ради цифр. Open contours внешней
  поверхности закреплены, включая границу с точными внутренними поверхностями.
- Внешний рельеф получает bounded bevel и topology-checked QEM, затем независимую
  bidirectional world-space distance проверку относительно исходной поверхности.
  L0 точный; L1/L2 имеют пределы 1/1.5 блока. Ошибка исходной тонкой снежной
  оболочки добавляется к бюджету, а не скрывается.
- Геометрические границы естественных terrain materials/biomes можно сокращать:
  вверх направленная поверхность выбирает **исходный state+biome ближайшего
  столбца** из отдельной lossless карты. Каменные стенки сохраняют свой face
  material. Нет усреднения цвета, atlas downscale или панорамной запечённой картинки.
  Текстуры берутся из действующего игрового атласа с обычными derivatives/mipmaps.
- Карта `FVCAP002`: halo, uniform 4×4 macroblocks, локальный словарь ID и
  4/8/16-bit индексы. Словарь меняет представление данных, а не их содержание.
  Она не загружается для точного грунта без снега/без принятого меша.
- Естественный тонкий снег сохраняет реальную высоту `layers/8` в ближнем уровне
  (`FVQUAD02`). Дальний уровень заменяет оболочку исходным snowy cap material;
  при выборе ближней оболочки грунт принудительно остаётся точным. Неподдержанный
  грунтом снег не проецируется. Native и старые `FVQUAD01` страницы не меняют ABI.
- Компактный terrain GPU ABI: 6-байтовые вершины на проверенной сетке 1/16 блока,
  oct7 normal, oriented triangle strips с fixed-index restart, словарь face/light
  attributes. CPU-данные и OpenGL проверяются на одинаковые triangles/winding/IDs.
- Кандидат принимается только при **строго меньших triangles и GPU geometry bytes**,
  включая material map и запас на дополнительные quad-layer commands. Encoded
  disk pages могут быть больше; это учитывается отдельно. Точные fallback остаются
  там, где ограничения не выполнены: гладкость не подменяет проверку стоимости.
- Вода определяется отдельным material kind. Дальний цвет/отражённое небо берутся
  из `fr_lodWaterColor` Fragment Render, поверхность понижена до 8/9 блока.
  Это непрозрачная дальняя аппроксимация: native refraction/shoreline foam остаются
  у настоящей воды. Листва по-прежнему cutout, её alpha не становится серым фоном.
- Runtime хранит ошибки каждого уровня отдельно даже при dedup одинакового файла.
  Выбор LOD использует projected certified error 2px и hysteresis. Все три уровня
  каждой физической плитки обязательны. Map hash/origin/palette/pointers/length,
  геометрия и суммарные budgets проверяются до замены видимого пакета.

## Воспроизведение

Профиль: `/workspace/fragmentrender-sky/run-water`, мир `saves/Sky_Art_Study`.
Не экспортировать открытый save. Этот проход использует те же immutable 64 inputs
`/workspace/scratch/full-mesh/b0.fvvol.gz` … `b63.fvvol.gz`, источник мира не заменён.
Полное покрытие пакета: X `[0,1024]`, Z `[192,1216]`, Y `[-64,320]`.

```bash
export JAVA_HOME=/workspace/scratch/jdk/jdk-21.0.12.1+1
python tools/lod/check.py
bash gradlew build --offline --no-daemon --max-workers=2
python tools/lod/build_sky_art_frame.py \
  --region /workspace/fragmentrender-sky/run-water/saves/Sky_Art_Study/region \
  --snapshots /workspace/scratch/full-mesh \
  --work /workspace/scratch/layers-surface-world \
  --output /workspace/fragmentrender-sky/run-water/lod-packages/sky-layered-surface
python tools/lod/audit_layered_package.py \
  /workspace/fragmentrender-sky/run-water/lod-packages/sky-layered-surface \
  --work /workspace/scratch/layers-surface-world
```

Builder отказывается смешивать старые cached batches с изменившимися исходниками
baker/настройками. Компиляция CPU классов должна предшествовать сборке пакета.

В игре `/voxy hybrid off`; `/voxy mesh load sky-layered-surface` включает новый
backend **вместо** старого Voxy draw/traversal. `/voxy mesh off` возвращает
портированный Voxy. Это не отдельная установка upstream Voxy другого выпуска.

## Ограничения, которые остаются

Это WIP, а не доказанное превосходство над Voxy. Прежняя Voxy arena 512MiB
остаётся в памяти; пакет сейчас целиком resident, отсутствуют production
streaming/edits/native ownership transfer и morph между уровнями. Порог 2px
ограничивает положение, а не ошибку нормалей или материалов; смена уровня не
является непрерывной деформацией. Точные закреплённые контуры могут сохранять
локальные ступеньки, а rejected tiles остаются блочными.

Листва пока сохраняет исходные кубы/cutout и получает существующий material/light
receiver Fragment Render, а не новый crown proxy. Другие сложные retained models
ещё используют diagnostic voxel geometry. Дальние caster shadows требуют
отдельной работы. Новые sample карты добавляют fragment reads: снижение числа
triangles/геометрических bytes не доказывает уменьшения времени полного кадра.

Изображения проверяются в реальном профиле, но рендерер этой машины — llvmpipe,
CPU quota 2 cores. Аппаратный FPS/GPU-time A/B этим не заменяется.

## Связь нового draw pass с атмосферой

Во время игрового A/B найдено дополнительное расхождение: `FR_LOD_MATERIAL`
намеренно возвращает не затуманенный материал, потому что обычный Voxy применяет
атмосферу позже в composite. Новый direct backend этот composite обходит.
Поэтому в его fragment shader теперь явно применяются **один раз** те же
`fr_aerialFog` и `fr_aerialLodEdge`, включая воду. Render API уже передаёт их
параметры через material receiver; дополнительного volumetric pass нет.

Новый draw также ограничен текущим `viewport.fogParameters.renderEnd()`:
полностью дальние страницы отсекаются по XZ AABB до draw; пересекающие границу
пиксели отсекаются в shader. Последние 20% расстояния затухают тем же sky edge,
что у обычного Voxy. Без этого пакет мог рисовать больше настроенных 512 блоков,
а его дальняя картинка не имела штатной воздушной перспективы.

[GPU check композиции](surface-atmosphere-gpu.log) выполняет реальный mesh
fragment main с контролируемыми material/air stubs: ordinary/snow/water получают
воздух ровно один раз; геометрия за горизонтом не пишет framebuffer. Полные
функции Render отдельно связаны во всех [12 shader variants](surface-final-shaders.json).

## Результат полной пересборки

Пакет `sky-layered-surface`, все 64 исходника, 3375 tile/layer groups, 4813
уникальных geometry pages, 854 material maps. [Инвентарь источника](source-inventory.json),
[журнал bake](full-bake.log), [полный аудит](full-package-audit.json).
Все исходные cap IDs после глобального palette remap сверены с локальными
source state+biome: совпадают. В emitted geometry не осталось проверяемых
трава/цветы/seagrass/kelp; для каждого tile/layer присутствуют три уровня.

| Внешний рельеф всей области | L0 | L1 | L2 |
|---|---:|---:|---:|
| Принятых smooth mesh tiles из 1024 | 0 | 343 | 792 |
| Triangles | 2 209 386 | 1 607 152 | 719 237 |
| GPU geometry + material maps, bytes | 9 544 880 | 8 656 730 | 6 180 130 |

Точная packed-геометрия этого же **внешнего** рельефа без новых карт:
2 209 386 triangles / 8 993 192 bytes. В L2 сокращение составляет 67.45% triangles
и 31.28% geometry+map bytes. L0 может стоить больше из-за общей с дальними
уровнями карты материалов. Это не 67% выигрыша всей сцены: retained foliage,
пещеры и детали преобладают в её геометрии. Вся область, если выбрать L2 везде:
17 374 195 triangles / 73 157 314 geometry+map bytes.

Одновременно резидентные все unique pages/maps/materials/commands:
86 234 724 bytes; encoded assets 86 044 837 bytes. Это WIP residency без
streaming; к нему ещё добавляются старая Voxy arena и остальные игровые ресурсы.

[Горный исходник](mountain-source.fvvol.gz), [его manifest](mountain-manifest.json)
и [аудит](mountain-audit.json) позволяют повторить небольшой горный batch отдельно.
В нём принято 13/16 far terrain pages, 29 632 → 9 922 terrain triangles,
120 960 → 88 348 GPU geometry+map bytes. Остальные три страницы не прошли gate.

## Выполненные проверки

- [Gradle build](surface-final-build.log) — успешен, installed JAR SHA256
  `e4827cafbd17aff066033953331224383a82ea8551775c00bb43093204b8a37f`.
- [CPU suite](material-final-cpu.log) — успешен, включая существующие codecs,
  projected selection, topology, deterministic publication и Python exporters.
- [Дополненный plant/fluid check](surface-policy.log) — трава/цветы исчезают до
  meshing, грунт открывается, leaves остаются, kelp/bubble columns восстанавливают воду.
- [Стыки](surface-seams.log) — соседние terrain tiles, retained cave interfaces,
  точные open contours во всех принятых уровнях, совпадение material halo.
- [Shipping material-map GPU test](surface-map-gpu.log) — точные IDs/halo,
  словари 4/8/16 bit, восемь высот снега, exposed side cuts и water8/9.
- [Compact GPU test](material-compact-gpu.log) — точные положения, oct7 normals,
  8/16-bit per-primitive dictionaries, primitive-restart images совпадают с
  legacy triangle-list результатом побитово.
- [Legacy GPU ABI](material-legacy-gpu.log), [indirect culling](material-indirect-gpu.log)
  и [12 связанных с Render shader programs](surface-final-shaders.json) — успешны.

## Игровые парные кадры

Тот же `Sky_Art_Study`, тот же NatureX/built-in foliage pack и Fragment Grass,
High/shadows2048, native8 chunks, LOD512 blocks, 1280×720, FOV option90, SMAA.
Время4500, doDaylightCycle=false. В финальном клиенте cloud shadow strength1
(значение по умолчанию после перезапуска, затем явно установлено1 перед закрытием).
Значение0 использовалось только в предыдущем диагностическом запуске: настройка
в памяти не переживает перезапуск. Движение облаков/ветра не изолировано, поэтому
это визуальные парные ракурсы, а не побитовое сравнение framebuffer. Все кадры — F2.

| Ракурс | Новый backend | Портированный Voxy |
|---|---|---|
| (512,180,704), yaw135/pitch25 | [Общий вид](overview-new.png) | [Общий вид Voxy](overview-voxy.png) |
| (704,180,800), yaw90/pitch12 | [Горы/лес](mountains-new.png) | [Горы/лес Voxy](mountains-voxy.png) |

После перелёта native chunk meshes требуют времени на загрузку. Ранний кадр
10:16:20, где даже передний лес выглядел cubical, **не выбран** как итоговое A/B:
переснят 10:18:34 после готовности ближней растительности. На переднем снежном
склоне второй пары видны обычные native блоки; он не является доказательством
сглаживания дальнего terrain. Не весь видимый экран должен стать гладким LOD.

Новый backend действительно активен вместо Voxy draw/traversal; real-frame
счётчики после исправления дистанции/атмосферы:

| Ракурс | Submitted triangles | Direction-culled | Visible mesh / quad pages |
|---|---:|---:|---:|
| Общий | 3 186 340 | 2 159 122 | 42 / 1086 |
| Горы | 4 670 357 | 2 606 162 | 53 / 1023 |

Это не статистика upstream Voxy и не FPS A/B. До ограничения дистанции горный
ракурс отправлял 6 886 661 triangles: часть пакета лежала за выбранной дальностью.
Сравнение с ним показывает устранённую лишнюю отправку, а не преимущество
алгоритма над другим renderer при равном качестве.

В проверенных кадрах вода синяя, серые растительные voxel cubes убраны, leaf
blocks сохранены; близкий bushy вид после загрузки native meshes присутствует.
Дальний terrain получает воздух и мягкий edge. Новых явных открытых щелей
в этих двух ракурсах не обнаружено; это не исчерпывающий аудит всей карты.
Гладкость неравномерна: exact native/L0, закреплённые контуры и 232 rejected far
terrain tiles по-прежнему могут показывать ступеньки. Называть все горы полностью
гладкими или всю систему более быстрой, чем Voxy, пока неверно.

Следующий геометрический шаг — согласованные упрощённые контуры соседних
плиток/уровней с контролируемым переходом к native. Сейчас фиксация исходного
контура предотвращает щели, но удерживает часть блоковой ступеньки и лишние
вершины. Для производительности всего renderer также нужны batching/residency,
освобождение старой arena и аппаратные GPU timings на одинаковых маршрутах.

[Итоговый общий кадр](final-overview.png) снят после возврата в исходную точку и
загрузки native meshes. [Игровой журнал](client.log): пакет загружен, оба backend
переключаются; мир сохранён во всех измерениях, Instance shutdown завершён.
Присутствуют прежние ошибки отдельных NatureX models/OpenAL и предупреждения
compiler о глобальных receiver inputs; новых shader compile/link failures нет.
Render repository остался чистым. Установленный JAR оставлен в профиле.

Малый горный bake после CPU compilation:

```bash
java -Xmx512m -cp build/lod-proof/classes BakeLayersMain \
  docs/lod-system/surfaces-water-2026-10-26/mountain-source.fvvol.gz \
  /tmp/vision-mountain-replay 32
```
