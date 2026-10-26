# Общие контуры 2×2 terrain pages — эксперимент

Продолжение `737052b9`, ветка `wip/lod-surfaces-and-water`.
Цель этого этапа — снять излишние ограничения на внутренних границах плиток,
сохраняя внешний/native/cave контракт и не превращая геометрию в heightmap blur.
Streaming, освобождение старой Voxy arena и batching этим этапом не реализуются.

## Контракт

`JoinedTerrainBake` собирает 64×64 patch из четырёх terrain pages32, упрощает
поверхность вместе и **после** QEM разрезает её обратно на GPU pages32.
Материалы сохраняет прежняя карта state+biome; листья/строения/вода/пещеры остаются
в своих слоях. Радиус bevel и ошибка ограничены; материал не усредняется.

`TerrainPageClipper` использует канонический порядок концов пересекаемого ребра,
публикует точки на исходной проверяемой сетке1/16 и сохраняет единственного
владельца coplanar cliff. Квантованный результат не может перевернуть треугольник:
такой кандидат отклоняется. Внешние грани patch не подчиняются правилу выбора
владельца внутреннего стыка — ошибка первоначального прототипа была поймана
контурной проверкой до игрового просмотра и исправлена.

Перед принятием проверяются **после разрезания**:

- Полный boundary edge multiset, включая multiplicity, совпадает с исходным:
  новых открытых рёбер внутри patch нет; внешние и cave contours не перемещены.
- Bidirectional distance относительно исходной поверхности, а не только QEM cost.
  Толщина заменяемого естественного снега входит в сертифицированный error budget.
- Triangles и GPU geometry+maps всего patch меньше его исходного точного грунта
  со снежной оболочкой; в стоимость результата входят оставшиеся empty snow pages.
  Это не сравнение только с уже удалённой оболочкой предыдущего far renderer.

Если четыре прежние независимые страницы уже гладкие и выигрывают у joined
варианта по GPU bytes и triangles, выбирается прежняя геометрия с её проверенным
общим точным контуром. Группировка не должна ухудшать удачные участки автоматически.

Manifest добавляет `lod_group:[x,z,64]`. Runtime проверяет полный состав из четырёх
pages, неизменность ownership между уровнями, согласованность dedup metadata.
Группа выбирает один уровень по общему bounding box/max certified error; ближний
snow shell принудительно сохраняет точный уровень **всей** группы. Внешний контур
точный на каждом уровне, поэтому соседние группы могут выбирать уровни независимо.
Это предотвращает щель, но не реализует плавный temporal morph и не сглаживает
все внешние границы между группами64.

## Ограниченная проверка

Проверяется прежний immutable batch33 из Sky_Art_Study: X128..256, Z704..832.
Остальной полный пакет сохранён, источник/палитра проверены при замене одного
batch. Файлы мира не пересоздаются. `overlay_joined_patch.py` сверяет snapshot hash,
physical tile/layer/level coverage, remap geometry и все dictionary/macro cap IDs.

Воспроизведение после `python tools/lod/check.py` на JDK21:

```bash
java -Xmx1g -cp build/lod-proof/classes BakeLayersMain \
  docs/lod-system/surfaces-water-2026-10-26/mountain-source.fvvol.gz \
  /tmp/joined-mountain 32 joined
python tools/lod/overlay_joined_patch.py \
  /workspace/fragmentrender-sky/run-water/lod-packages/sky-layered-surface \
  /tmp/joined-mountain \
  /workspace/fragmentrender-sky/run-water/lod-packages/sky-joined-selected --batch 33
```

Для полной экспериментальной сборки появился `build_sky_art_frame.py --joined`;
всю карту этим вариантом пока не пересобирали.

Для диагностики `/voxy mesh load PACKAGE 0|1|2` фиксирует уровень; без последнего
аргумента (либо `-1`) сохраняется автоматический projected-error selection2px.
Фиксированный LOD2 для приближённого кадра — диагностический вид геометрии,
не демонстрация реальной дистанции его автоматического включения.

## Измеренный результат

Исходник — тот же frozen b33, без регенерации. Все числа ниже для внешнего
terrain batch128×128, а не всей карты/леса/renderer:

| Far L2 | Предыдущие независимые32 | Выбранный joined/independent |
|---|---:|---:|
| Smooth pages /16 | 13 | 16 |
| Triangles | 9922 | 7802 |
| GPU geometry + material maps B | 88348 | 87186 |

Сокращение triangles21.37%, GPU bytes1.32% **относительно предыдущего WIP**.
Предварительный вариант без отбора давал8162tri/89418B; он не выбран для итогового
пакета. Удачные независимые страницы дешевле совместного варианта на части групп.

Включая неизменённые детали/пещеры, batch far: 286128→284008 triangles,
1199556→1198394 bytes. L1 этого batch, напротив, 1228132→1250586 bytes:
групповой выбор и fallback пока могут требовать более точной геометрии.
Одновременно резидентный **полный** пакет всех уровней в игре:
86234724→86234480 bytes, выигрыш всего244B. Нельзя переносить21% на FPS/всю память.

Для ранее отклонявшегося снежного подучастка отдельно:
5010→1512 terrain triangles; ground+source snow shell reference за вычетом оставшихся
empty far-shell pages — 28736B, candidate ground+maps19358B.
Контуры после clipping замкнуты, внешний/cave boundary multiset точный.

## Игровой просмотр

Профиль прежний `/workspace/fragmentrender-sky/run-water`, Sky_Art_Study,
Render `0daec67` без изменений, High/shadows2048, native8, LOD512, SMAA.
Тестовый полный пакет `sky-joined-selected` заменяет **только batch33**,
остальные63 batch берутся из прежнего `sky-layered-surface`.

Чтобы видеть форму, временно FOV option50, camera(448,180,800), yaw90/pitch10,
время4500, cloud shadow strength0 в одном запуске:

- [Предыдущая геометрия, fixed L2](previous-fixed2.png).
- [Новые группы, fixed L2](joined-fixed2.png).
- [Новый пакет, автоматический выбор](joined-auto-zoom.png).

Первый F2 содержит затухающий diagnostic chat внизу; кадры не подрезались.
Fixed2 является приближённой диагностикой. В auto при таком FOV поверхность
остаётся заметно более блочной: pixel budget2px не ослаблялся ради картинки.
Упрощённая крона/материал листвы в этом режиме ещё требует отдельной работы:
на дальнем склоне видны оголённые тёмные вертикальные элементы; это присутствует
у **обоих** вариантов и не считается исправленным этим terrain experiment.
Просмотр не принят как финальное художественное качество неба/леса/гор.

Actual runtime загрузил grouped package и fixed2, и auto. В auto решений
550+282+180=1012: 1008 отдельных terrain pages и4 общие группы вместо1024
независимых pages. Одна группа имеет одно выбранное значение для всех членов.

## Оставшиеся шаги

- Общие/адаптивные внешние контуры между группами64 и безопасный переход к native.
  Пока точный внешний border сохраняет ступеньки, хотя внутренние32 освобождены.
- Temporal morph, включая смену направления перехода; его здесь ещё нет.
- Более точные error bounds/адаптивное разбиение решения: групповой bounding box
  консервативен, особенно при приближении/zoom, и auto выбирает точный уровень.
- Streaming/residency/batching и освобождение старой512MiB arena.
- Проверка foliage material/coverage отдельно от terrain и полноценные GPU timings.

Нельзя включать этот эксперимент как «все горы готовы» или обещать выигрыш FPS.
На CPU/llvmpipe проверены геометрия/декодирование/загрузка; аппаратного замера нет.

## Проверки и артефакты

- [CPU suite](joined-selected-cpu.log), включая настоящий frozen snow patch:
  topology/точный boundary multiset, 1/16 quantization, уникальный coplanar cliff
  owner, material IDs/halo, сохранение пещер, bidirectional error — passed.
- [Gradle build](build.log) — passed. Шейдерные формулы/ABI не менялись; runtime
  загрузил обе разновидности пакетов и material bridge Render в игре.
- [Пакет целиком](joined-selected-audit.json), [горный batch](mountain-audit.json),
  [manifest](mountain-manifest.json), [bake log](joined-b33-selected.log).
- [Palette proof](palette-proof.json): все20736 source-column state+biome pairs
  в16 картах, включая halo, совпали после глобального remap.
- Прежняя geometry page64 не считается joined group: проверка четверки требуется
  только для physical pages32 с `lod_group`. Это сохраняет legacy packet ABI.

Диагностический FOV50 восстановлен в90, cloud shadows —1. Профиль содержит
новый мод и `sky-joined-selected`; Render/Grass исходники не менялись.

Финальный JAR SHA256:
`a3aeeeb93ba15555896a47b59440587af75478025e727e9dfb50fb1a7c51f6c8`.
С этим артефактом отдельно перепроверена совместимость в игре:

1. `sky-full` (legacy pages64): загрузка успешна, 256 pages.
2. `sky-joined-selected`: загрузка успешна, 4813 unique pages.
3. `bad-joined-group`: у одного члена убран `lod_group` во всех уровнях;
   загрузчик отверг manifest с `Incomplete joined terrain group` **до** смены
   видимого пакета. Geometry/assets не повреждались, это отдельная fixture.
4. Status после отказа: прежний grouped package остаётся ON, 86234480 GPU bytes,
   submitted3177273, direction-culled2151446; terrain decisions581/198/233
   (всего1012), drawn mesh/quad pages57/1071.

Это проверка корректности загрузки/состояния, а не performance benchmark.
[Финальный игровой журнал](client.log). FOV90, cloud shadow strength1,
камера возвращена в(512,180,704), yaw135/pitch25. Мир штатно сохранён,
Java client закрыт, мод и тестовый пакет оставлены в профиле. Отдельной
пользовательской сборки на скачивание не требуется.

[Итоговый автоматический общий вид, FOV90](final-auto-overview.png). В нём
изменён только тестовый горный batch; это не демонстрация массовой замены всех
LOD мира. Все исходники/сравнения сохранены для следующего этапа morph/selection.
