Directional submission для baked LOD, 2026-10-25

Исследована Sodium 1.21.1/stable, commit 272a4aa3792c5b06c5ec212a380779b103110a2a:

- [DefaultChunkRenderer](https://github.com/CaffeineMC/sodium/blob/272a4aa3792c5b06c5ec212a380779b103110a2a/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/DefaultChunkRenderer.java): раздельные направления геометрии, исключение невидимых slices до отправки, пакет команд.
- [BlockOcclusionCache](https://github.com/CaffeineMC/sodium/blob/272a4aa3792c5b06c5ec212a380779b103110a2a/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockOcclusionCache.java): соседний непустой блок не обязательно occluder; учитываются модели, прозрачность, shape и правила скрытия.
- OcclusionCuller просмотрен как следующий источник архитектурных идей. Его секционный граф нельзя считать готовым графом видимости сглаженных страниц с иными отверстиями и границами.

Новая реализация QuadFaceBuckets написана для нашего 8-byte quad ABI; исходники Sodium не копировались. Для каждого направления вычисляется экстремальная плоскость фактических граней страницы. Направление исключается только если все его грани гарантированно обращены от камеры. Используется camera-relative double и небольшой запас у плоскости. Вершины сглаженного mesh не классифицируются по старому material face: после QEM это уже не направление геометрической грани.

Применение ограничено opaque full blocks (`canOcclude`, `isSolidRender`, отсутствие fluid). Неопределённые/непрозрачные по иной модели материалы, листья, жидкости и non-cube blocks остаются в отдельной двусторонней группе. Это не исправляет существующую кубизацию non-cube моделей и не заменяет финальный material pipeline.

Quads переставляются один раз при upload. Один переиспользуемый CPU staging и один GPU indirect buffer содержат семь команд на страницу. На страницу остаётся один GL multi-draw вызов, а не шесть отдельных вызовов из Java. Vertex shader учитывает gl_BaseInstance при доступе к SSBO. Пустые/отсечённые группы имеют instanceCount=0. Новый статус показывает submitted и direction-culled. Общий indirect binding восстанавливается после прохода, буферы освобождаются при close.

Цена: дополнительные 140 GPU bytes и 140 CPU staging bytes на packed страницу, плюс небольшие Java metadata. GPU-budget расчёт packed страницы теперь включает 12 байт EBO и 140 байт commands. Обновление indirect buffer входит в будущий GPU/CPU benchmark; реальное ускорение не заявляется по счётчику граней. Это ещё не общая arena/один draw на весь мир и не Hi-Z occlusion.

Проверки:

1. Полный tools/lod/check.py прошёл. QuadFaceBucketsCheck проверяет перестановку без потерь/дубликатов, 1000 положений камеры, консервативность отсечения, indirect ABI и обязательное сохранение двусторонней геометрии.
2. tools/lod/check_quad_indirect_gpu.py исполняет Java-generated commands реальным shipping vertex shader. На шести ракурсах изображение direct full draw и direction-culled multi-draw побитово совпало, количество отправленных экземпляров меньше. Это EGL/llvmpipe LLVM19, с MESA_GL_VERSION_OVERRIDE=4.6 и MESA_GLSL_VERSION_OVERRIDE=460.
3. tools/lod/check_mesh_gpu.py: 6000 packed vertices и 2400 quad corners/attributes совпали с CPU; tools/check_hybrid_shaders.py: 10 вариантов вместе с текущими Render material files скомпилировались.
4. Сохранённый source.fvvol.gz, core32×32, камера (512,180,704): консервативный CPU пример с отсечением только материалов разрешённого грунта даёт 30656 → 24164 submitted triangles (−21,18%). Листва и остальные материалы сохранены. Это один маленький snapshot; не full-frame GPU timing и не точное воспроизведение runtime классификатора Minecraft для всей карты.
5. Gradle build -x test прошёл. Minecraft с полным профилем в этой итерации не запускался.

В этой среде отсутствует /workspace/fragmentrender-sky/run-water. Доступ к файловой системе предыдущей среды инструментами текущей сессии не предоставлен. Для парного игрового теста нужны перенесённые saves/Sky_Art_Study, mods, config, resourcepacks и lod-packages. Логи CPU/GLSL/build находятся в sodium-directional-submission/.

Остаются отдельные crown proxies, полноценный model/material input, переходы/native ownership, streaming/edits и иерархическая occlusion. Эти изменения уменьшают лишнюю отправку существующей exact геометрии; сами по себе не делают кроны гладкими и не закрывают пользовательские цветовые артефакты.
