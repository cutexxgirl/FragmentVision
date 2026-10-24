# Аудит источников для новой LOD-системы

Проверено 2026-10-24 через GitHub и локальные исходники. Это исследование архитектуры, без переноса нового upstream-кода в мод. Не считать комментарии TODO реализованными возможностями.

## Зафиксированные версии

| Проект | Ревизия | Что просмотрено |
|---|---|---|
| Fragment Vision | `191e90db` (`wip/hybrid-terrain`) | HybridTerrain/HybridVoxelMesh, материалы, MDIC, NormalRenderPipeline, quads GLSL, предыдущие отчёты |
| Fragment Render | `95ec97c` (`wip/hybrid-terrain`) | AtmosphereBridge, WorldLighting, LightSettings, High shader и интеграционные документы |
| [Voxy](https://github.com/MCRcortex/voxy) | [`5bf33350a1cc7bb7e2b5d947b236c56d878b8a7d`](https://github.com/MCRcortex/voxy/commit/5bf33350a1cc7bb7e2b5d947b236c56d878b8a7d), dev, 2026-10-19 | Реальные исходники перечисленных ниже механизмов |
| [Ecstatic](https://github.com/Wizardtastic/Ecstatic) | [`41dea8df4f60d2a07fcd99424316646fb232effe`](https://github.com/Wizardtastic/Ecstatic/commit/41dea8df4f60d2a07fcd99424316646fb232effe) | README, SurfaceSampler, HeightmapColumn, LodRegionMesh |
| [meshoptimizer](https://github.com/zeux/meshoptimizer) | [`f0a684f11ead064e176775c9b796f82a55910e92`](https://github.com/zeux/meshoptimizer/commit/f0a684f11ead064e176775c9b796f82a55910e92) | Документация simplification, attributes, borders, quantization, compression и лицензия; библиотека не интегрирована |

## Что полезно у оригинального Voxy

### Изменения мира поднимаются по иерархии

[WorldUpdater.java](https://github.com/MCRcortex/voxy/blob/5bf33350a1cc7bb7e2b5d947b236c56d878b8a7d/src/main/java/me/cortex/voxy/common/world/WorldUpdater.java): `insertUpdate` последовательно обновляет уровни, передаёт child-existence, dirty flags и маску соседей; прекращает распространение, когда состояние не изменилось.

**Взять принцип:** локальная инвалидизация с зависимыми предками и соседями, ранняя остановка. В новой системе изменение геометрии, материалов и затенения имеет разные зависимости; не считать старый voxel mip готовой гладкой поверхностью.

### Компактная геометрия и объединение граней

[ScanMesher2D.java](https://github.com/MCRcortex/voxy/blob/5bf33350a1cc7bb7e2b5d947b236c56d878b8a7d/src/main/java/me/cortex/voxy/client/core/util/ScanMesher2D.java): сканирование 32-wide строк, объединение одинаковых значений, максимальная сторона объединённой грани 16. Это объясняет, почему плоский участок Voxy уже очень дешёвый.

[BasicSectionGeometryManager.java](https://github.com/MCRcortex/voxy/blob/5bf33350a1cc7bb7e2b5d947b236c56d878b8a7d/src/main/java/me/cortex/voxy/client/core/rendering/section/geometry/BasicSectionGeometryManager.java): arena с 8 байтами на quad, отдельные компактные metadata (32 байта на секцию в этой реализации), учёт инвалидированных записей, освобождение CPU built-section после upload.

**Взять принцип:** компактный GPU-friendly payload, объединение повторяющихся данных, arena allocation и освобождение CPU копий. Сравнивать весь новый payload с этим, а не с наивными 12 треугольниками на каждый блок. У точных построек в собственном backend объединение граней по-прежнему полезно.

### Иерархическое отсечение и асинхронные запросы

[HierarchicalOcclusionTraverser.java](https://github.com/MCRcortex/voxy/blob/5bf33350a1cc7bb7e2b5d947b236c56d878b8a7d/src/main/java/me/cortex/voxy/client/core/rendering/hierachical/HierarchicalOcclusionTraverser.java): GPU traversal привязывает Hi-Z depth, проходит уровни через compute/indirect dispatch, формирует render/request queues. Число запросов ограничивается с учётом заполнения очереди mesh generation; результаты загружаются асинхронно.

[NodeManager.java](https://github.com/MCRcortex/voxy/blob/5bf33350a1cc7bb7e2b5d947b236c56d878b8a7d/src/main/java/me/cortex/voxy/client/core/rendering/hierachical/NodeManager.java): управление parent/children, состояниями запросов, готовностью mesh и child-existence. В коде есть сложные случаи/замечания, поэтому это источник решений, а не гарантия правильности новой адаптации.

**Взять принцип:** не обходить все tiles CPU каждый кадр, не рисовать скрытые за горами поверхности, ограничивать загрузку, публиковать согласованные уровни. Новые bounding volumes должны учитывать сглаживание/morph; старая граница voxel-секции без расширения может ошибочно отсечь новый меш. В первой небольшой проверке допустим простой CPU selector, но его цену включать в измерения, не переносить его без проверки на всю карту.

### Загрузка без постоянного выделения буферов

[UploadStream.java](https://github.com/MCRcortex/voxy/blob/5bf33350a1cc7bb7e2b5d947b236c56d878b8a7d/src/main/java/me/cortex/voxy/client/core/rendering/util/UploadStream.java): persistent-mapped staging buffer, пакетные copies, arena allocations и fence retirement. В этой версии singleton имеет 64 MiB staging. Это конкретный текущий размер, не рекомендация автоматически выделить ещё 64 MiB в новой системе.

**Взять принцип:** ограниченное staging, пакетирование, отсутствие синхронного ожидания каждого upload. При исчерпании бюджета оставлять готовый уровень, а не запускать неограниченные allocation/flush циклы на render thread.

### Реальные модели, оттенки и листья

[ModelFactory.java](https://github.com/MCRcortex/voxy/blob/5bf33350a1cc7bb7e2b5d947b236c56d878b8a7d/src/main/java/me/cortex/voxy/client/core/model/ModelFactory.java) и [SoftwareModelTextureBakery.java](https://github.com/MCRcortex/voxy/blob/5bf33350a1cc7bb7e2b5d947b236c56d878b8a7d/src/main/java/me/cortex/voxy/client/core/model/bakery/SoftwareModelTextureBakery.java): baked block-model textures по шести направлениям; metadata непрозрачности/пустых граней/размеров и tint, таблицы моделей/биомов. `MODEL_TEXTURE_SIZE=16` у просмотренного upstream. Листва использует общий механизм моделей; отдельной реконструкции красивой кроны в этих путях нет.

**Взять принцип:** материал происходит из реального block model/пака, alpha и tint — разные свойства, закрытые/пустые грани исключаются. Для наших крон сохранить вид листвы и biome-domain. У нашего порта уже есть дополнительные material/colourDomain ключи и bushy-фильтр: обновление upstream не должно их затереть.

### Импорт готового мира

[WorldImporter.java](https://github.com/MCRcortex/voxy/blob/5bf33350a1cc7bb7e2b5d947b236c56d878b8a7d/src/main/java/me/cortex/voxy/commonImpl/importers/WorldImporter.java): чтение region directories и zip, преобразование chunk/section данных через Minecraft registry/datafixing, работа через service queue.

**Взять принцип:** строить кэш из фактического сохранения, использовать совместимую обработку версий мира и registry. Не подменять готовую карту генерацией по сиду. Наш offline baker должен выпускать готовые render pages, а не только voxel-кэш, которому ещё предстоит mesh generation у игрока.

## Что нельзя переносить автоматически

[Mipper.java](https://github.com/MCRcortex/voxy/blob/5bf33350a1cc7bb7e2b5d947b236c56d878b8a7d/src/main/java/me/cortex/voxy/common/world/other/Mipper.java) выбирает представителя из 2×2×2 по opacity и позиции; для пустого набора усредняет освещение. Он не описывает площадь покрытия снега, нормаль кроны или точную границу травы. Это нельзя считать художественно корректным material reduction для гладких мешей. Это также не доказательство, что все наблюдавшиеся кислотные цвета вызваны именно Mipper.

[ReadonlyCachingLayer.java](https://github.com/MCRcortex/voxy/blob/5bf33350a1cc7bb7e2b5d947b236c56d878b8a7d/src/main/java/me/cortex/voxy/common/config/storage/other/ReadonlyCachingLayer.java) читает cache, затем onMiss; запись/удаление направлены в cache. В таком виде удаление из cache позволяет следующему чтению снова увидеть onMiss. Это **не готовая base+delta система с tombstones**, необходимая для выкопанной земли. Методы mapping/iteration/flush также требуют отдельного аудита перед адаптацией.

## Свежий upstream и наша версия Minecraft

[gradle.properties](https://github.com/MCRcortex/voxy/blob/5bf33350a1cc7bb7e2b5d947b236c56d878b8a7d/gradle.properties) просмотренного dev указывает Voxy `0.2.20-beta`, Minecraft `26.3`, Sodium `0.9.3-beta.1`, Iris `1.11.7+26.3`. Наша связка — Minecraft 1.21.1 / NeoForge, с собственными bridge и pinned Sodium. Массовая замена на свежую ветку не является безопасной оптимизацией.

Среди последних коммитов видны изменения clip-space depth (`247adf26`), world imports (`61c1dee5`), sampler binding (`8af038dc`), vxNear/vxFar (`1ef0fc61`) и ограничения версий Iris/Sodium. На этой итерации проверены их названия и текущее состояние выбранных файлов, **не выполнен полный аудит каждого diff**. Следующая работа — отдельный backport-review применимых исправлений, а не обещание, что новая версия ускорит наш порт.

## Что даёт и чего не даёт Ecstatic

[README](https://github.com/Wizardtastic/Ecstatic/blob/41dea8df4f60d2a07fcd99424316646fb232effe/README.md), [SurfaceSampler](https://github.com/Wizardtastic/Ecstatic/blob/41dea8df4f60d2a07fcd99424316646fb232effe/src/main/java/com/angryalchemist/ecstatic/sample/SurfaceSampler.java), [HeightmapColumn](https://github.com/Wizardtastic/Ecstatic/blob/41dea8df4f60d2a07fcd99424316646fb232effe/src/main/java/com/angryalchemist/ecstatic/storage/HeightmapColumn.java), [LodRegionMesh](https://github.com/Wizardtastic/Ecstatic/blob/41dea8df4f60d2a07fcd99424316646fb232effe/src/main/java/com/angryalchemist/ecstatic/render/LodRegionMesh.java).

SurfaceSampler использует density function генератора для высоты, возвращает biome/color/признак деревьев. HeightmapColumn хранит height, biomeRawId, colorRgb, hasTrees. В просмотренном LodRegionMesh текстурированный режим выбирается при `lodLevel <= 2`; есть усреднение углов высоты, цвета и skirts. README рекомендует высокую native-дальность и прямо предупреждает об ограничениях shader support.

Полезная идея — редкая поверхность способна представлять дальний ландшафт дешевле блоков. Для нашей задачи нельзя принять sample-from-seed как истину, одноколоночный heightfield для всех объектов, подмену настоящих деревьев признаком biome.hasTrees, отказ от текстур вдали или несовместимость с собственным светом. Наблюдаемое пользователем «мыло» нельзя свести к одному параметру Ecstatic без сопоставимого кадра; перечисленное — реальные различия архитектуры, а не измерение его FPS.

## Кандидат для собственного упрощения

[meshoptimizer README, зафиксированная ревизия](https://github.com/zeux/meshoptimizer/blob/f0a684f11ead064e176775c9b796f82a55910e92/README.md#simplification): attribute-aware simplification, selective vertex locks, error metrics, compact vertex fetch, quantization и codecs. Документация прямо отмечает, что многочисленные seams могут остановить упрощение, а target triangle count не гарантирован. `simplifySloppy` хуже сохраняет границы/атрибуты — не выбирать его основой для близких LOD с читаемыми материалами.

Это кандидат для офлайн-инструмента, а не уже выбранная runtime-зависимость. Независимые compact buffers уровней и shared quantization границ важнее названия библиотеки. Возвращаемая simplifier error — приближённая метрика, не строгая гарантия всех силуэтов; нужны отдельные проверки на реальной поверхности.

## Условия заимствования кода

У проверенного Voxy [LICENSE.md](https://github.com/MCRcortex/voxy/blob/5bf33350a1cc7bb7e2b5d947b236c56d878b8a7d/LICENSE.md) прямо указано `All rights reserved. Do not redistribute.` Такой же текст есть в текущем локальном порте. Это не разрешительная open-source лицензия. В этом плане «взять» означает прежде всего архитектурные принципы; перед распространением заимствованного кода требуется подтвердить имеющееся разрешение/условия. Наличие такого разрешения здесь не проверено. Само исследование и документация не требуют останавливать работу.

Ecstatic README указывает CC BY-NC-SA 4.0; механический перенос кода без проверки условий также не предполагается. У [meshoptimizer MIT](https://github.com/zeux/meshoptimizer/blob/f0a684f11ead064e176775c9b796f82a55910e92/LICENSE.md) разрешение на использование с сохранением notices. В этой итерации исходники сторонних проектов хранятся только в scratch для исследования и не добавляются в мод.
