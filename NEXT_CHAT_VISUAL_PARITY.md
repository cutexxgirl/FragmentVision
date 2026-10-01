# FragmentVision / Voxy Visual Parity Handoff

Дата: 2026-07-04
Текущая ветка: `dev-0.0.4`

## Стартовый запрос для нового чата

Продолжи работу в `P:\FragmentVision` на ветке `dev-0.0.4`.

Большой визуальный этап:

> По большому визуальному приколу: да, это можно сглаживать, но не одним маленьким фиксом. AO, biome blending, вода между чанками и плавная замена LOD -> real chunk требуют отдельной работы в мешинге/тинтинге/прозрачном pass.

Нужно довести визуальное соответствие Voxy LOD к реальным Sodium чанкам:

- ambient occlusion / shading;
- biome color blending;
- прозрачность и cutout/translucent поведение листвы/воды;
- вода между чанками и LOD секциями без швов;
- плавная замена LOD на настоящий чанк.

## Уже сделано

Ветка `dev-0.0.2` запушена:

- commit: `c50a0454 Port Voxy fog and Chunky fixes`
- remote branch: `origin/dev-0.0.2`

В `dev-0.0.3` уже есть незакоммиченные визуальные фиксы:

- `SoftwareRasterizer`
  - полностью прозрачные texel'ы больше не пишут depth/stencil в baked model texture;
  - это убрало тонкие белые/черные полоски на grass LOD.
- `ModelStore`
  - model atlas больше не использует linear blending между mip levels;
  - max LOD ограничен, чтобы не уходить в самый грубый 2x2 face mip.
- `SoftwareModelTextureBakery`
  - fluid bake считает верхний сосед воздухом;
  - это было старым FIXME в upstream Voxy.
- `ModelFactory` + `block_model.glsl` + `quad_util.glsl`
  - fluid models получили флаг `modelIsFluid`;
  - shader больше не масштабирует water/fluid inset вместе с LOD scale;
  - цель: убрать далекую черную broken line на воде, где обычный water height gap превращался в большой LOD gap.

Установленный тестовый jar:

- `P:\PrismLauncher\instances\test voxy\minecraft\mods\voxy-0.2.16-beta-c50a045.jar`
- предыдущий jar отключен как `voxy-0.2.16-beta-057039e.jar.disabled`

Проверка:

- `./gradlew build --no-daemon --console=plain` проходит;
- Minecraft запускается;
- Voxy render system создается;
- критических Voxy/mixin/shader errors в `latest.log` не было.

## Что пользователь уже подтвердил

- Тонкие полоски на траве пропали.
- Высотное отсечение LOD пропало.
- Баг с водой пофикшен.
- В `dev-0.0.4` добавлены новые визуальные правки для перехода LOD -> real chunks, biome tint и AO/SSAO defaults.

## Важные файлы

- `src/main/java/me/cortex/voxy/client/core/model/ModelFactory.java`
- `src/main/java/me/cortex/voxy/client/core/model/ModelStore.java`
- `src/main/java/me/cortex/voxy/client/core/model/bakery/SoftwareModelTextureBakery.java`
- `src/main/java/me/cortex/voxy/client/core/model/bakery/SoftwareRasterizer.java`
- `src/main/resources/assets/voxy/shaders/lod/block_model.glsl`
- `src/main/resources/assets/voxy/shaders/lod/quad_util.glsl`
- `src/main/resources/assets/voxy/shaders/lod/gl46/quads.frag`
- `src/main/java/me/cortex/voxy/client/core/rendering/building/RenderDataFactory.java`
- `src/main/java/me/cortex/voxy/common/world/other/Mipper.java`
- `src/main/java/me/cortex/voxy/common/world/other/Mapper.java`

## Следующий план

1. Визуально проверить `dev-0.0.4` в мире:
   - границу vanilla render distance;
   - LOD grass/water tint рядом с реальными Sodium chunks;
   - насколько forced detail band скрывает скачок LOD без fragment fade.

2. AO:
   - сейчас Voxy в основном использует directional face tint и SSAO, а не vanilla per-vertex AO;
   - нужен neighbor-aware per-corner AO или cheap approximation при mesh generation;
   - начать с opaque full blocks, потом non-full/cutout.

3. Biome blending:
   - `dev-0.0.4` уже выбирает majority biome при mipping вместо случайного/representative-child biome;
   - полноценного vanilla/Sodium 3D biome color blending все еще нет;
   - следующий шаг: хранить blended tint или несколько biome samples, если majority biome окажется недостаточно.

4. Translucency / leaves / water:
   - leaves уже больше не forced solid;
   - нужен нормальный per-pixel tint mask вместо эвристики "почти серый пиксель" в `quads.frag`;
   - water требует отдельной fluid-aware логики, потому что это не просто translucent block.

5. LOD -> real chunk transition:
   - `dev-0.0.4` добавляет transition detail band в traversal;
   - fragment/screen-door fade намеренно не используется, чтобы не делать дырки после depth-bound discard;
   - tune `minAlpha` / band widths после визуального теста.

## Команды

Сборка:

```powershell
wsl -e bash -lc 'cd /mnt/p/FragmentVision && export JAVA_HOME="$HOME/.cache/codex-jdk21" && export PATH="$JAVA_HOME/bin:$PATH" && ./gradlew build --no-daemon --console=plain'
```

Установка jar в Prism:

```powershell
Copy-Item -LiteralPath 'P:\FragmentVision\build\libs\voxy-0.2.16-beta-c50a045.jar' -Destination 'P:\PrismLauncher\instances\test voxy\minecraft\mods\voxy-0.2.16-beta-c50a045.jar' -Force
```

Запуск:

```powershell
Start-Process -FilePath 'P:\PrismLauncher\prismlauncher.exe' -ArgumentList '--launch "test voxy" --world "Новый мир"' -WindowStyle Hidden
```

Лог-проверка:

```powershell
Select-String -Path 'P:\PrismLauncher\instances\test voxy\minecraft\logs\latest.log' -Pattern '\[ERROR\]|\[FATAL\]|Critical injection failure|Mixin apply for mod voxy|Game crashed|InvalidInjection|Shader|compile|link|Creating Voxy render system|Voxy render system created'
```

## Update 2026-07-04 05:15

Первого water-fix было недостаточно. Пользователь подтвердил: LOD-вода все еще была выше настоящей воды, из-за этого оставался черный рваный зазор.

Добавлен более глубокий фикс:

- `Mapper` хранит выбранные mip-child local X/Y/Z, mip level и mixed-cell flag в ранее свободных нижних битах mapping id.
- `Mipper` сохраняет, какой child voxel был выбран из каждой 2x2x2 ячейки.
- `RenderDataFactory` пакует выбранный `mipLocalY` в quad bits 42..45 и исправляет асимметрию quad typing на X-edge fluid пути.
- `quad_format.glsl` добавляет `extractMipLocalY(...)`.
- `quad_util.glsl` использует `mipLocalY`, чтобы поставить fluid geometry внутри coarse LOD cell до масштабирования.

Причина: Voxy выбирал один representative water block для крупной LOD-ячейки, но renderer забывал, где внутри этой ячейки была исходная вода. Если выбранная вода была в нижней части ячейки, LOD-вода рисовалась слишком высоко. Перенос `mipLocalY` позволяет шейдеру поставить fluid surface ближе к исходной высоте.

Важно для теста: старые cached Voxy LOD data не содержат новые mip-local bits. Для чистой проверки воды нужно очистить или переингестить Voxy cache для нужной области.

Runtime check: свежий jar `voxy-0.2.16-beta-c50a045.jar` запущен в Prism `test voxy` / `Новый мир` 2026-07-04 05:14. Voxy render system создался с `NormalRenderPipeline` / `MDICSectionRenderer`; свежий `latest.log` не содержит `[ERROR]`, `[FATAL]`, critical mixin failure, invalid injection, game crash или shader compile/link failure.

## Update 2026-07-04 14:41

Создана и используется ветка `dev-0.0.4`.

Добавлено:

- `Mipper`
  - non-air parent cells теперь получают averaged light, а не свет выбранного child;
  - representative biome теперь выбирается majority vote по non-air children;
  - mixed-cell flag учитывает различие biome id, не только block id.
- `traversal_dev.comp` + `HierarchicalOcclusionTraverser`
  - traversal получает vanilla render distance;
  - около границы real chunks принудительно спускает LOD до 0/1 в transition band, чтобы переход не прыгал крупными coarse cells.
- `bindings.glsl` + `MDICSectionRenderer`
  - основной GL46 pass получает vanilla render distance.
- `traversal_dev.comp`
  - transition smoothing оставлен через forced detail band, без fragment discard fade;
  - screen-door fade был снят после ревью, потому что depth-bound уже вырезает real chunk overlap, а дополнительный discard может делать фоновые дырки.
- `SSAO`
  - AUTO без GPU memory query теперь выбирает `BETTER`, а не `BASIC`, чтобы default AO был ближе к реальным чанкам.
- `quad_format.glsl`
  - исправлен non-64-bit fallback `extractStateId(ivec2)`: он больше не читает bits 42..45, занятые `mipLocalY`.

Проверка:

- `./gradlew build --no-daemon --console=plain` проходит.
- Fresh jar установлен в `P:\PrismLauncher\instances\test voxy\minecraft\mods\voxy-0.2.16-beta-c50a045.jar`.
- Полный in-world runtime-test из Codex сейчас заблокирован не Voxy: даже минимальный Java `Selector.open()` в этом окружении падает с `Unable to establish loopback connection` / `Invalid argument: connect`, тот же корень, что и crash до создания Voxy renderer.
