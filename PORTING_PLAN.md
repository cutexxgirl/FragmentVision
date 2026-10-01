# FragmentVision / Voxy NeoForge Port Plan

Дата статуса: 2026-07-04 00:47

## Цель

Портировать Voxy `0.2.16-beta` с Fabric/Minecraft `1.21.11` на NeoForge `1.21.1`, целевой NeoForge `21.1.235`.

## Решения

- За основу взята ветка Voxy для Minecraft `1.21.11`, потому что это актуальная кодовая база Voxy 2.
- Sodium для целевой версии: NeoForge `mc1.21.1-0.8.12-beta.2`.
- NeoForge: `21.1.235`.
- Minecraft: `1.21.1`.

## Сделано

- [x] Репозиторий размещён в `P:\FragmentVision`.
- [x] Подключён Git-репозиторий `cutexxgirl/FragmentVision.git`.
- [x] Gradle переведён с Fabric Loom на NeoForge ModDev.
- [x] Добавлен NeoForge metadata файл `META-INF/neoforge.mods.toml`.
- [x] Fabric access widener перенесён в NeoForge access transformer.
- [x] Fabric entrypoints/events заменены на NeoForge bootstrap/events.
- [x] Fabric Loader API обращения заменены платформенным слоем.
- [x] Основные Minecraft/Sodium API несовместимости 1.21.11 -> 1.21.1 исправлены до успешной компиляции.
- [x] Сборка `./gradlew build` проходит успешно.
- [x] Voxy jar добавлен в тестовый PrismLauncher-инстанс `P:\PrismLauncher\instances\test voxy\minecraft`.
- [x] Исправлен размер jar: `rocksdbjni` теперь урезается до Windows x64 + Linux x64, как в оригинальном Fabric Voxy.
- [x] Итоговый jar уменьшен примерно с `73 885 KiB` до `12 252 KiB`.
- [x] Исправлена упаковка trimmed `rocksdbjni`: `jarJar` metadata теперь сохраняет нормальные coordinates `org.rocksdb:rocksdbjni:10.2.1`, а не локальный file dependency с пустым group/hash version.
- [x] Исправлена упаковка LWJGL native classifiers: natives больше не попадают в `jarJar`, а кладутся обычными `windows/**` / `linux/**` ресурсами.
- [x] Убран embedded `lz4-java`, чтобы не было конфликта module layer с уже установленным `org.lz4:lz4-java:1.8.0`.
- [x] Исправлены runtime mixin/API несовместимости под Minecraft/NeoForge `1.21.1`:
  - `MixinMinecraft` -> `disconnect(Screen, boolean)`;
  - `MixinBlockableEventLoop` -> реальный `doRunTask` catch/log path 1.21.1;
  - `MixinWorld` -> constructor с `Supplier<ProfilerFiller>`;
  - `MixinClientLevel` -> constructor с `Supplier<ProfilerFiller>` и без старого `seaLevel` параметра.
- [x] Убран нефатальный Voxy `Serialization.collectAllClasses` NPE при запуске под NeoForge classloader.
- [x] Исправлен fog для Voxy LOD на Sodium `1.21.1/modern-port`: LOD viewport теперь получает текущие `RenderSystem` fog start/end/color/shape вместо `VoxyFogParameters.disabled(...)`.
- [x] Voxy post-fog приведён ближе к Sodium 1.21.1: учитывается `FogShape.CYLINDER`, а fog lerp использует `smoothstep`.
- [x] Исправлен портовый перекос в software model bake: `RenderType.cutoutMipped()` теперь считается alpha-discard слоем, а block model quads берутся через NeoForge layer-aware overload с `ModelData.EMPTY`.

## Проверено

- [x] `./gradlew build --no-daemon --console=plain` проходит успешно.
- [x] NeoForge обнаруживает Voxy в тестовом инстансе.
- [x] NeoForge обнаруживает embedded библиотеки Voxy через `jarJar`, включая `rocksdbjni-10.2.1.jar`.
- [x] В embedded `rocksdbjni` остались только:
  - `librocksdbjni-linux64.so`
  - `librocksdbjni-win64.dll`
- [x] Текущий jar в тестовом инстансе обновлён после исправления `jarJar` metadata.
- [x] Тестовый Prism-инстанс запущен правильной командой: `P:\PrismLauncher\prismlauncher.exe --launch "test voxy"`.
- [x] Minecraft дошёл до окна `Minecraft NeoForge* 1.21.1`.
- [x] В свежем `latest.log` нет Voxy `ERROR/FATAL`, нет `Mixin apply for mod voxy`, нет `Failed to collect classes`.
- [x] Исправлен crash при входе в мир: `sodium.MixinRenderRegionManager` больше не требует устаревший Sodium `Math.toIntExact(J)` target.
- [x] Существующий мир `Новый мир` запущен через Prism `--world`; клиент дошёл до одиночной игры без `ERROR/FATAL`.
- [x] На `2026-07-04 02:34` свежий jar `voxy-0.2.16-beta-057039e.jar` запущен в мире `Новый мир`; `latest.log` содержит `Creating Voxy render system` / `Voxy render system created` и не содержит Voxy `ERROR/FATAL`, `Critical injection failure`, `Game crashed`.

## История запуска

- [x] Первый запуск тестового инстанса остановился не на Voxy, а на стороннем `sodium-extra-neoforge-0.9.1+mc1.21.1.jar`.
- [x] Причина: `META-INF/neoforge.mods.toml` Sodium Extra просит `accesstransformer.cfg` в корне jar, но файл лежит как `META-INF/accesstransformer.cfg`.
- [x] Для локального теста создана patched-копия Sodium Extra jar с дублированным root `accesstransformer.cfg`.
- [x] Оригинальный Sodium Extra оставлен рядом как `.jar.disabled` backup.
- [x] Пользователь проверил: без Sodium Extra запуск всё равно падает, а без Voxy запускается.
- [x] Следующая гипотеза после свежих логов: раннее падение могло быть вызвано некорректным `jarJar` metadata для локального trimmed RocksDB (`group=""`, hash вместо `10.2.1`). Это исправлено, нужен повторный запуск.
- [x] После исправления `jarJar` metadata найден реальный blocker: `rocksdbjni reads more than one module named org.lz4.java`.
- [x] После исправления LZ4/LWJGL packaging найдены и исправлены несовпавшие mixin descriptors для 1.21.1.
- [x] На `2026-07-04 00:47` клиент стартует до main menu/window на полном тестовом Prism-инстансе.
- [x] На `2026-07-04 01:49` клиент вошёл в мир `Новый мир`; исправленный `MixinRenderRegionManager` не падает на Sodium renderer init.

## Следующее

- [x] Проверить загрузку мира в тестовом Prism-инстансе.
- [x] Проверить создание/чтение Voxy config.
- [x] Проверить включение Voxy renderer и отсутствие runtime ошибок.
- [x] Проверить отсутствие Voxy/RocksDB/LMDB/ZSTD/LZ4 `ERROR/FATAL` при реальной загрузке мира.
- [x] Визуально проверить smoke-test: грани LOD блоков больше не отрезаются, LOD видны за натуральными чанками, багов в обычной сцене нет.
- [ ] Отдельно проверить настоящий environmental fog: вода, лава, powder snow, darkness/blindness, world/boss fog.
- [ ] Если появится новый crash, чинить уже следующий реальный Voxy/NeoForge runtime blocker.
- [ ] После стабилизации runtime убрать временные исключения/заглушки там, где возможно.
- [ ] Отдельным этапом вернуть/адаптировать compat-модули: Iris, ModMenu/конфиг UI, Flashback, Nvidium, Chunky, Vivecraft.

## Handoff note 2026-07-04 00:47

- Fresh jar copied to `P:\PrismLauncher\instances\test voxy\minecraft\mods\voxy-0.2.16-beta-59b62be.jar`.
- Installed Prism jar timestamp: `2026-07-04 00:46:44`, size `11,885,863` bytes.
- Current running Java process: `javaw`, window title `Minecraft NeoForge* 1.21.1`.
- Build command used:
  - `wsl -e bash -lc 'cd /mnt/p/FragmentVision && export JAVA_HOME="$HOME/.cache/codex-jdk21" && export PATH="$JAVA_HOME/bin:$PATH" && ./gradlew clean build --no-daemon --console=plain'`
- Prism launch command used:
  - `P:\PrismLauncher\prismlauncher.exe --launch "test voxy"`
- Latest verification log:
  - `P:\PrismLauncher\instances\test voxy\minecraft\logs\latest.log`
  - last checked at `2026-07-04 00:47:11`;
  - no Voxy `ERROR/FATAL`;
  - no Voxy mixin apply failure;
  - no `Failed to collect classes`.

## Handoff note 2026-07-04 01:49

- Fixed world-entry crash:
  - `src/main/java/me/cortex/voxy/client/mixin/sodium/MixinRenderRegionManager.java`
  - `voxy$cancelFade` redirect is now optional with `require = 0`, because Sodium `0.8.12-beta.2+mc1.21.1` no longer has the old `Math.toIntExact(J)` fade target.
- Fresh jar copied to `P:\PrismLauncher\instances\test voxy\minecraft\mods\voxy-0.2.16-beta-59b62be.jar`.
- Installed Prism jar timestamp: `2026-07-04 01:48:45`, size `11,885,873` bytes.
- Prism world launch command used:
  - `P:\PrismLauncher\prismlauncher.exe --launch "test voxy" --world "Новый мир"`
- Current verification:
  - Java process: `javaw`;
  - window title: `Minecraft NeoForge* 1.21.1 - Одиночная игра`;
  - no `/ERROR]`, `/FATAL]`, `Critical injection failure`, or `Game crashed` in fresh `latest.log`.

## Handoff note 2026-07-04 00:10

- Rebuilt with `./gradlew build --no-daemon --console=plain` through WSL/JDK 21.
- Copied the fresh jar to `P:\PrismLauncher\instances\test voxy\minecraft\mods\voxy-0.2.16-beta-59b62be.jar`.
- Installed Prism jar timestamp: `2026-07-04 00:10:40`, size `12,553,230` bytes.
- Embedded `META-INF/jarjar/rocksdbjni-10.2.1.jar` length: `9,264,051` bytes.
- JarJar metadata for RocksDB is correct: `org.rocksdb:rocksdbjni:10.2.1`.
- Ignore the earlier Gradle `runClient` Sodium dependency result for Prism debugging; it was a dev-client-only false lead.
- Next real action: launch the Prism instance again and inspect fresh `latest.log`/`debug.log` from that run.

## Handoff note 2026-07-04 02:08

- Fixed Voxy client bootstrap on NeoForge:
  - `MixinRenderSystem` now injects into Minecraft 1.21.1 `RenderSystem.initRenderer(int, boolean)`;
  - `minecraft.MixinRenderSystem` is included in `client.voxy.mixins.json`;
  - the source exclude for `MixinRenderSystem` was removed from `build.gradle`.
- Fixed Sodium config registration:
  - removed duplicate `sodium:config_api_user` mod property from `META-INF/neoforge.mods.toml`;
  - kept `@ConfigEntryPointForge("voxy")` as the single config entrypoint.
- Fixed the world-render crash after Voxy renderer activation:
  - `RenderDistanceTracker` now converts level-0 Y section bounds to top-level LOD Y coordinates with `Math.floorDiv`;
  - top-level node ids now use `WorldEngine.MAX_LOD_LAYER` instead of hardcoded `4`.
- Rebuilt with:
  - `wsl -e bash -lc 'cd /mnt/p/FragmentVision && export JAVA_HOME="$HOME/.cache/codex-jdk21" && export PATH="$JAVA_HOME/bin:$PATH" && ./gradlew clean build --no-daemon --console=plain'`
- Fresh jar copied to:
  - `P:\PrismLauncher\instances\test voxy\minecraft\mods\voxy-0.2.16-beta-08c89ca.jar`
- Installed Prism jar timestamp/size:
  - `2026-07-04 02:04:04`, `11,886,695` bytes.
- Prism world launch command used:
  - `P:\PrismLauncher\prismlauncher.exe --launch "test voxy" --world "Новый мир"`
- Verification:
  - Minecraft reached `Minecraft NeoForge* 1.21.1 - Одиночная игра`;
  - fresh `latest.log` contains `Creating Voxy render system` and `Voxy render system created`;
  - no fresh crash report after this run;
  - `P:\PrismLauncher\instances\test voxy\minecraft\config\voxy-config.json` exists;
  - user confirmed: `работает`.

## Handoff note 2026-07-04 02:34

- Created/switch branch:
  - requested literal `dev 0.0.2` is not a valid Git ref name;
  - working branch is `dev-0.0.2`.
- Fixed LOD fog on Sodium 1.21.1:
  - `src/main/java/me/cortex/voxy/client/core/rendering/VoxyFogParameters.java`
  - `src/main/java/me/cortex/voxy/client/mixin/sodium/MixinDefaultChunkRenderer.java`
  - `src/main/java/me/cortex/voxy/client/core/NormalRenderPipeline.java`
  - `src/main/resources/assets/voxy/shaders/post/blit_texture_depth_cutout.frag`
- Fixed likely LOD face-loss source in model baking:
  - `src/main/java/me/cortex/voxy/client/core/model/bakery/SoftwareModelTextureBakery.java`
  - `RenderType.cutoutMipped()` now uses alpha discard;
  - model quads are queried through NeoForge layer-aware `getQuads(..., ModelData.EMPTY, layer)`.
- Rebuilt with:
  - `wsl -e bash -lc 'cd /mnt/p/FragmentVision && export JAVA_HOME="$HOME/.cache/codex-jdk21" && export PATH="$JAVA_HOME/bin:$PATH" && ./gradlew build --no-daemon --console=plain'`
- Fresh jar copied to:
  - `P:\PrismLauncher\instances\test voxy\minecraft\mods\voxy-0.2.16-beta-057039e.jar`
- Previous Prism jar disabled as:
  - `P:\PrismLauncher\instances\test voxy\minecraft\mods\voxy-0.2.16-beta-08c89ca.jar.disabled`
- Prism world launch command used:
  - `P:\PrismLauncher\prismlauncher.exe --launch "test voxy" --world "Новый мир"`
- Verification:
  - Minecraft reached `Minecraft NeoForge* 1.21.1 - Одиночная игра`;
  - fresh `latest.log` contains `Creating Voxy render system` and `Voxy render system created`;
  - no Voxy `ERROR/FATAL`, no `Critical injection failure`, no `Game crashed`;
  - `P:\PrismLauncher\instances\test voxy\minecraft\config\voxy-config.json` exists, timestamp `2026-07-04 02:33:50`;
  - user confirmed later: block faces are no longer culled and ordinary-scene LOD/fog has no visible bugs.

## Handoff note 2026-07-04 02:45

- Fixed the over-fogged LOD regression from the first fog pass:
  - `src/main/java/me/cortex/voxy/client/core/rendering/VoxyFogParameters.java`
  - Minecraft/Sodium 1.21.1 exposes only one `RenderSystem` fog range, so the ordinary render-distance terrain fog looked like environmental fog to Voxy;
  - `VoxyFogParameters.fromRenderSystem` now detects the vanilla 1.21.1 terrain-fog shape/range and returns disabled fog for that case;
  - actual environmental fog remains enabled because water/lava/powder-snow/effect/world-fog ranges do not match the vanilla terrain-fog formula.
- Rebuilt with:
  - `wsl -e bash -lc 'cd /mnt/p/FragmentVision && export JAVA_HOME="$HOME/.cache/codex-jdk21" && export PATH="$JAVA_HOME/bin:$PATH" && ./gradlew build --no-daemon --console=plain'`
- Fresh jar copied to:
  - `P:\PrismLauncher\instances\test voxy\minecraft\mods\voxy-0.2.16-beta-057039e.jar`
- Installed Prism jar timestamp/size:
  - `2026-07-04 02:42:38`, `11,887,695` bytes.
- Prism world launch command that worked:
  - `P:\PrismLauncher\prismlauncher.exe --launch "test voxy" --world "Новый мир"`
- Verification:
  - Minecraft reached `Minecraft NeoForge* 1.21.1 - Одиночная игра`;
  - fresh `latest.log` contains `Creating Voxy render system` and `Voxy render system created`;
  - no fresh crash report after this run;
  - fresh log scan found no `[ERROR]`, `[FATAL]`, `Critical injection failure`, or `Game crashed`;
  - user confirmed later: ordinary-scene LOD/fog has no visible bugs; real environmental fog still needs a separate check.

## Handoff note 2026-07-04 02:55

- Moved render-distance fog from the edge of real Sodium chunks to the edge of Voxy LOD:
  - `src/main/java/me/cortex/voxy/client/core/rendering/VoxyFogParameters.java`
  - `src/main/java/me/cortex/voxy/client/core/NormalRenderPipeline.java`
  - `src/main/resources/assets/voxy/shaders/post/blit_texture_depth_cutout.frag`
  - `src/main/java/me/cortex/voxy/client/mixin/minecraft/MixinFogRenderer.java`
  - `src/main/resources/client.voxy.mixins.json`
- `VoxyFogParameters` now carries separate environmental and render-distance ranges, matching Sodium 1.21.11's fog model more closely.
- The post blit combines environmental fog and Voxy render-distance fog with `max(...)`; render fog is cylindrical and ends at `sectionRenderDistance * 512` blocks.
- The adapted Minecraft 1.21.1 `MixinFogRenderer` disables only ordinary vanilla terrain fog on real chunks after `FogRenderer.setupFog`, leaving water/lava/powder/effect/world fog alone.
- Rebuilt and copied fresh jar:
  - `P:\PrismLauncher\instances\test voxy\minecraft\mods\voxy-0.2.16-beta-057039e.jar`
  - timestamp/size: `2026-07-04 02:52:10`, `11,889,416` bytes.
- Verification:
  - `./gradlew build --no-daemon --console=plain` succeeded;
  - Minecraft reached `Minecraft NeoForge* 1.21.1 - Одиночная игра`;
  - fresh `latest.log` contains `Creating Voxy render system` and `Voxy render system created`;
  - no fresh crash report;
  - fresh log scan found no `[ERROR]`, `[FATAL]`, `Critical injection failure`, `Mixin apply for mod voxy`, or `Game crashed`;
  - user confirmed: `всё работает`, block-face culling is gone, LOD visibility is correct, no current visible bugs.

## Handoff note 2026-07-04 04:05

- Addressed short 1px white/black streaks visible on LOD block faces:
  - screenshot looked like custom model-atlas bleeding, not face culling;
  - `src/main/resources/assets/voxy/shaders/lod/gl46/quads.frag` now clamps per-face local UVs by half a 16x16 texel before sampling the 3x2 model-face atlas;
  - this is shader-side only and does not require rebaking Voxy storage.
- Added optional Chunky NeoForge integration:
  - `src/main/java/me/cortex/voxy/commonImpl/mixin/chunky/MixinNeoForgeWorld.java`
  - `src/main/resources/chunky.voxy.mixins.json`
  - `src/main/resources/META-INF/neoforge.mods.toml`
  - NeoForge Chunky 1.4.23 uses `org.popcraft.chunky.platform.NeoForgeWorld#getChunkAtAsync`, not the Fabric classes used by upstream Voxy's Fabric mixin;
  - after Chunky finishes loading a full chunk, the mixin asks `VoxelIngestService.tryAutoIngestChunk` to ingest it.
- Improved LOD foliage parity:
  - `src/main/java/me/cortex/voxy/client/core/model/bakery/SoftwareModelTextureBakery.java`
  - `src/main/java/me/cortex/voxy/client/core/model/ModelFactory.java`
  - leaves are no longer forced onto `RenderType.solid()`;
  - Voxy now uses Minecraft's block chunk render type for leaves, so alpha/cutout behavior should be closer to real Sodium chunks.
- Rebuilt successfully with:
  - `wsl -e bash -lc 'cd /mnt/p/FragmentVision && export JAVA_HOME="$HOME/.cache/codex-jdk21" && export PATH="$JAVA_HOME/bin:$PATH" && ./gradlew build --no-daemon --console=plain'`
- Fresh jar copied to:
  - `P:\PrismLauncher\instances\test voxy\minecraft\mods\voxy-0.2.16-beta-057039e.jar`
  - timestamp/size: `2026-07-04 04:01:51`, `11,891,544` bytes.
- Verification:
  - `./gradlew build --no-daemon --console=plain` succeeded;
  - jar contains `MixinNeoForgeWorld.class`, `chunky.voxy.mixins.json`, patched `quads.frag`, `ModelFactory.class`, and `SoftwareModelTextureBakery.class`;
  - fresh log scan found no `[ERROR]`, `[FATAL]`, `Critical injection failure`, `Mixin apply for mod voxy`, `InvalidInjection`, or `Game crashed`;
  - after the jar copy, Minecraft was restarted at `2026-07-04 04:02:54`; the fresh log sees `voxy-0.2.16-beta-057039e.jar` and `Chunky 1.4.23`;
  - this verifies startup/mixin discovery only; the latest foliage, Chunky ingestion, and visual fixes still need in-world testing.
- Remaining larger visual parity backlog:
  - vanilla/Sodium chunks still have per-vertex AO, biome color blending, more exact transparency behavior, and fluid edge handling that Voxy LODs do not fully reproduce;
  - water chunk-boundary seams likely need fluid-aware LOD meshing/culling across neighboring sections plus better translucent handling, not just a shader tweak;
  - LOD-to-real-chunk replacement can be softened later with a transition band/crossfade/dither, but it must be designed around depth and translucent rendering.

## Handoff note 2026-07-04 04:30

- User asked to push `0.0.2` and continue on `0.0.3`.
- Committed current `dev-0.0.2` as:
  - `c50a0454 Port Voxy fog and Chunky fixes`
- Pushed:
  - `origin/dev-0.0.2`
- Created and switched to:
  - `dev-0.0.3`
- Added first `dev-0.0.3` visual fixes:
  - `src/main/java/me/cortex/voxy/client/core/model/bakery/SoftwareRasterizer.java`
    - fully transparent/near-transparent sampled texels are now discarded before depth/stencil writes for every layer, not only `RenderType` layers with alpha discard;
    - this targets grass-side overlay pixels polluting baked LOD face textures.
  - `src/main/java/me/cortex/voxy/client/core/model/ModelStore.java`
    - model-atlas sampler now uses nearest mip selection instead of linear mip blending;
    - max model-atlas LOD is clamped to avoid the coarsest 2x2 face mip.
  - `src/main/java/me/cortex/voxy/client/core/model/bakery/SoftwareModelTextureBakery.java`
    - fluid bake now reports the block/fluid above as air;
    - this follows the old FIXME in that file and should stop water side previews from becoming full-height slabs when a water block exists above.
- Rebuilt successfully with:
  - `wsl -e bash -lc 'cd /mnt/p/FragmentVision && export JAVA_HOME="$HOME/.cache/codex-jdk21" && export PATH="$JAVA_HOME/bin:$PATH" && ./gradlew build --no-daemon --console=plain'`
- Fresh jar installed to Prism:
  - `P:\PrismLauncher\instances\test voxy\minecraft\mods\voxy-0.2.16-beta-c50a045.jar`
  - previous Prism jar disabled as `voxy-0.2.16-beta-057039e.jar.disabled`
- Startup verification:
  - Minecraft launched at `2026-07-04 04:23:35`;
  - latest log sees `voxy-0.2.16-beta-c50a045.jar`;
  - Voxy render system was created;
  - no Voxy `[ERROR]`, `[FATAL]`, critical mixin failure, invalid injection, or game crash found.
- Still needs visual verification:
  - grass side stripes;
  - far-water black broken line;
  - high-altitude parent LOD face-loss.
- High-altitude culling likely needs a deeper second pass:
  - lower 27 bits in `Mapper` mapping ids are currently unused;
  - possible proper fix is to mark mipped/mixed representative cells in `Mipper` and teach `RenderDataFactory` not to let those parent LOD cells aggressively `cullsSame`/`faceOccludes`;
  - blindly disabling all parent LOD occlusion would be a useful diagnostic but may inflate geometry too much for a default fix.

## Handoff note 2026-07-04 05:00

- User confirmed:
  - grass 1px stripes are gone;
  - high-altitude LOD face-loss is gone;
  - water still had the same black broken-line problem after the first fluid bake change.
- Added a second water-specific fix:
  - `src/main/java/me/cortex/voxy/client/core/model/ModelFactory.java`
  - `src/main/resources/assets/voxy/shaders/lod/block_model.glsl`
  - `src/main/resources/assets/voxy/shaders/lod/quad_util.glsl`
- Cause hypothesis:
  - fluid/water model has a sub-block height inset;
  - Voxy LOD shader scaled that inset by `lodScale`;
  - at high LOD, a normal water height gap can become a multi-block gap, visible as a black broken line on distant water.
- Fix:
  - model flags now include `isFluid`;
  - shader keeps fluid face insets in block units instead of LOD-cell units;
  - solid/cutout/non-fluid models keep the old behavior.
- Rebuilt and installed:
  - `P:\PrismLauncher\instances\test voxy\minecraft\mods\voxy-0.2.16-beta-c50a045.jar`
  - timestamp/size: `2026-07-04 04:51:37`, `11,891,912` bytes.
- Verification:
  - `./gradlew build --no-daemon --console=plain` succeeded;
  - Minecraft reached single-player;
  - fresh log sees `voxy-0.2.16-beta-c50a045.jar`;
  - Voxy render system created;
  - no Voxy/mixin/shader critical errors were found.
- Created next-chat document:
  - `NEXT_CHAT_VISUAL_PARITY.md`

## Handoff note 2026-07-04 05:15

- User reported the first water fix was still wrong:
  - LOD water was visibly higher than real water;
  - the height mismatch produced black broken gaps at distant water boundaries;
  - rare dirt/grass-face streaks still existed, but much less often.
- Added a deeper water-height fix:
  - `src/main/java/me/cortex/voxy/common/world/other/Mapper.java`
    - lower unused mapping-id bits now carry mip metadata: selected child local X/Y/Z, mip level, and a mixed-cell flag;
  - `src/main/java/me/cortex/voxy/common/world/other/Mipper.java`
    - when a 2x2x2 mip cell chooses a representative block, it preserves which child voxel was selected;
  - `src/main/java/me/cortex/voxy/client/core/rendering/building/RenderDataFactory.java`
    - quad data now packs the selected mip-local Y into free quad bits 42..45;
    - X outer fluid meshing now updates quad typing after replacing a block-with-fluid model by its fluid model on both sides;
  - `src/main/resources/assets/voxy/shaders/lod/quad_format.glsl`
    - added `extractMipLocalY(...)`;
  - `src/main/resources/assets/voxy/shaders/lod/quad_util.glsl`
    - fluid quads now apply the selected local Y before scaling to the LOD cell.
- Reason:
  - Voxy used to choose a representative water block for a parent LOD cell but then render that water as if it occupied the whole parent cell;
  - if the chosen water was in the lower half of the cell, the LOD surface became too high;
  - carrying `mipLocalY` lets the shader place the fluid surface back at the representative source height inside the coarse LOD cell.
- Rebuilt successfully with:
  - `wsl -e bash -lc 'cd /mnt/p/FragmentVision && export JAVA_HOME="$HOME/.cache/codex-jdk21" && export PATH="$JAVA_HOME/bin:$PATH" && ./gradlew build --no-daemon --console=plain'`
- Fresh jar installed to Prism:
  - `P:\PrismLauncher\instances\test voxy\minecraft\mods\voxy-0.2.16-beta-c50a045.jar`
  - timestamp/size: `2026-07-04 05:11:19`, `11,892,842` bytes.
- Runtime verification:
  - Prism launched `test voxy` into world `Новый мир` at `2026-07-04 05:14:08`;
  - fresh `latest.log` sees `voxy-0.2.16-beta-c50a045.jar`;
  - Voxy render system was created with `NormalRenderPipeline` / `MDICSectionRenderer`;
  - no `[ERROR]`, `[FATAL]`, critical mixin failure, invalid injection, game crash, or shader compile/link failure was found in the fresh log scan.
- Important testing note:
  - old Voxy cached LOD data does not have the new mip-local metadata;
  - if the old distant-water seam remains in already-ingested regions, clear/reingest the Voxy cache for that area before judging the fix.

## Handoff note 2026-07-04 14:41

- Created and switched to requested branch:
  - `dev-0.0.4`
- User reported:
  - water bug is fixed;
  - next target is visually softer LOD -> real chunk replacement, better grass/water color parity, and AO if practical.
- Added visual transition work:
  - `src/main/java/me/cortex/voxy/client/core/rendering/hierachical/HierarchicalOcclusionTraverser.java`
    - traversal uniform upload now includes the vanilla/Sodium render distance in blocks;
  - `src/main/resources/assets/voxy/shaders/lod/hierarchical/traversal_dev.comp`
    - nodes near the real-chunk boundary force extra descent to LOD0/LOD1 inside a transition band;
  - `src/main/java/me/cortex/voxy/client/core/rendering/section/backend/mdic/MDICSectionRenderer.java`
  - `src/main/resources/assets/voxy/shaders/lod/gl46/bindings.glsl`
    - the GL46 render uniform layout was checked against the transition work;
    - screen-door fragment fade was intentionally removed after review because Voxy pixels that survive the depth-bound check usually do not overlap real chunks, so discarding them can create background holes.
  - `src/main/resources/assets/voxy/shaders/lod/gl46/quads3.vert`
  - `src/main/resources/assets/voxy/shaders/lod/gl46/quads.frag`
    - no active fragment fade is used; visual smoothing currently comes from the extra LOD refinement band.
- Added color/light parity work:
  - `src/main/java/me/cortex/voxy/common/world/other/Mipper.java`
    - non-air parent cells now use averaged light;
    - parent biome id now uses majority vote among non-air children instead of inheriting only the chosen representative child;
    - mixed-cell flag now also treats biome-id differences as mixed.
- Added AO/default quality work:
  - `src/main/java/me/cortex/voxy/client/core/SSAO.java`
    - `AUTO` falls back to `BETTER` SSAO when dedicated GPU memory cannot be queried, instead of dropping to `BASIC`;
    - true per-corner block AO remains a larger mesh-format change and was not added in this pass.
- Fixed an important packed-quad fallback bug:
  - `src/main/resources/assets/voxy/shaders/lod/quad_format.glsl`
    - non-64-bit `extractStateId(ivec2)` now reads the 16-bit state id from bits 26..41 only;
    - it no longer consumes bits 42..45, which are now used for `mipLocalY`.
- Rebuilt successfully with:
  - `wsl -e bash -lc 'cd /mnt/p/FragmentVision && export JAVA_HOME="$HOME/.cache/codex-jdk21" && export PATH="$JAVA_HOME/bin:$PATH" && ./gradlew build --no-daemon --console=plain'`
- Fresh jar installed to Prism:
  - `P:\PrismLauncher\instances\test voxy\minecraft\mods\voxy-0.2.16-beta-c50a045.jar`
  - timestamp/size: `2026-07-04 14:50:01`, `11,894,157` bytes.
- Runtime limitation:
  - Codex-side Prism launch currently fails before Voxy renderer creation because Java NIO selector fails in this environment;
  - minimal Java repro: `Selector.open()` throws `java.io.IOException: Unable to establish loopback connection`, caused by `java.net.SocketException: Invalid argument: connect`;
  - this is outside the Voxy renderer/shader path, so this pass is verified by Gradle build and code audit, not by in-world visual inspection.
