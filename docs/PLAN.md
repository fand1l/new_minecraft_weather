# Vibe Weather — план реалізації

Мод багатовимірної локальної погоди для Minecraft Java Edition **26.2** / Fabric.
Крок 1 (план) — цей документ. Коду моду ще немає.

**Ревізія 3** — враховано три твої рішення (розділ «Рішення») і рев'ю плану:
переписано `sanitize()`, виправлено суху грозу, смугу бленду й M3; додано
креатив/спектатор.

---

## Рішення

| Питання | Твій вибір | Що це змінює |
|---|---|---|
| Локальність | **C — жива симуляція** | Сервер тримає реальні об'єкти-зони, тікає їх і зберігає у `SavedData`. Детермінований хеш-генератор (варіант B) не робимо. |
| Як клієнт бачить погоду вдалині | **Сітка семплів** | Клієнт не отримує геометрію зон і нічого не обчислює сам. Сервер шле готову сітку значень, клієнт лише інтерполює. |
| Невідомі імена класів 26.2 | **Спершу `genSources`** | Виконано, раунди 1–3. Лишились три ванільні імені (розділ «Наступний крок»). |

---

## Наступний крок — єдиний

Розвідку по 26.2 **закінчено**: раунди 1–3 відпрацювали, Minecraft-частина закрита
повністю, Fabric API дістав напряму з репозиторію FabricMC. Скрипти `tools/*.sh`
лишаються в репозиторії як довідка, перезапускати їх не треба.

Відкритий рівно один пункт — **три ванільні імені, одна команда**:

```bash
rg -n "FallFlying|Gliding|isSpectator" ~/.gradle/caches/fabric-loom \
   --glob 'LivingEntity.java' --glob 'Player.java' --glob 'Entity.java' | head -20
```

Потрібні для: перевірки польоту на елітрах, режиму спектатора й креативного польоту
(див. «Кого вітер НЕ зачіпає»). Клас човна обходжу без імені — через список ID реєстру
в конфігу.

Це блокує **лише** `WindPhysicsClient`. Решта — модель погоди, симуляція зон, конфіг,
мережа, команди, серверні ефекти й увесь рендер — розблокована, і Крок 2 починається
з неї.

---

## 0. Що перевірено, а що ні

### Перевірено з першоджерела

Моє знання обривається до релізу 26.2, тому версії звірено не по пам'яті, а з офіційного
шаблону FabricMC гілки `26.2` (`FabricMC/fabric-example-mod`) і з `gradle.properties`
самого Fabric API (`FabricMC/fabric`, HEAD = `0.156.0`, `minecraft_version=26.2`):

| Що | Значення | Джерело |
|---|---|---|
| Minecraft | `26.2` | template `gradle.properties` |
| Fabric Loader | `0.19.3` | template `gradle.properties` |
| Fabric Loom | `1.17-SNAPSHOT` | template `gradle.properties` |
| Fabric API | `0.156.0+26.2` | template + `fabric/gradle.properties` |
| Gradle | `9.5.1` | template `gradle-wrapper.properties` |
| Java | `25` (`options.release = 25`) | template `build.gradle` |
| Mixin compat | `JAVA_25`, `overwrites.requireAnnotations: true` | template `modid.mixins.json` |

Твої цифри з ТЗ збіглися повністю.

**Мапінги.** У шаблоні 26.2 **немає** рядка `mappings "net.fabricmc:yarn:…"` і немає
властивості `yarn_mappings` — Loom 1.17 підставляє дефолт, і шаблон написаний
**офіційними мапінгами Mojang**. Підтвердження: `MinecraftServer.loadLevel` (Yarn звав би
його `loadWorld`), а у Fabric API 0.156.0 — `ServerLevel`, `ServerPlayer`, `ClientLevel`,
`Minecraft`, `FriendlyByteBuf`, `RegistryFriendlyByteBuf`, `StreamCodec`,
`CustomPacketPayload`, `CommandSourceStack`, `Commands`, `ServerGamePacketListenerImpl`,
`ParticleEngine`.

Окремо: клас ідентифікатора у 26.2 — **`net.minecraft.resources.Identifier`**
(`Identifier.fromNamespaceAndPath(...)`), не `ResourceLocation`. Видно і в шаблоні,
і в `ServerPlayNetworking` з Fabric API 0.156.0.

`build.gradle` у репозиторії свідомо **без** блоку `mappings` — з коментарем, чому.

### Підтверджено раундом 1 (`genSources` на реальному 26.2)

| Що шукали | Реальне ім'я у 26.2 | Наслідок |
|---|---|---|
| Рендер погоди | `net.minecraft.client.renderer.WeatherEffectRenderer` | ✅ цілі M5 і M6 підтверджені |
| Стан рендеру погоди | `net.minecraft.client.renderer.state.level.WeatherRenderState` | 🆕 погода теж переїхала на extract/submit-архітектуру |
| Туман | `net.minecraft.client.renderer.fog.FogRenderer`, `.fog.FogData`, `.fog.environment.FogEnvironment` + `AtmosphericFogEnvironment`, `WaterFogEnvironment`, `LavaFogEnvironment`, `PowderedSnowFogEnvironment`, `BlindnessFogEnvironment`, `DarknessFogEnvironment`, `MobEffectFogEnvironment` | 🆕 туман тепер стратегія `FogEnvironment`. Надія зареєструвати свою **не справдилась** — список приватний; див. розділ нижче. |
| Тип туману | `net.minecraft.world.level.material.FogType` | — |
| Ванільна `/weather` | `net.minecraft.server.commands.WeatherCommand` | ✅ ціль M8 підтверджена |
| Blaze3D-пайплайн | `com.mojang.blaze3d.pipeline.RenderPipeline` з **публічним `Builder`**: `withVertexShader/withFragmentShader(Identifier)`, `withVertexBinding`, `withBindGroupLayout`, `withColorTargetState`, `withDepthStencilState`, `withPolygonMode`, `withCull`, `withPrimitiveTopology`, `withShaderDefine`, `buildSnippet()` | ✅ **головний ризик знято**: власний пайплайн офіційно підтримується через Blaze3D, сирий GL не потрібен |
| Fabric-розширення пайплайна | `RenderPipeline implements FabricRenderPipeline`; є `FabricRenderPipeline.Builder` | Fabric офіційно інжектить інтерфейс у ванільний білдер |
| GPU-ресурси | `blaze3d.buffers.{GpuBuffer, GpuBufferSlice, GpuFence, Std140Builder, Std140SizeCalculator}`, `blaze3d.framegraph.{FrameGraphBuilder, FramePass}`, `GpuFormat`, `IndexType` | Уніформи через std140 bind groups (Vulkan-стиль) |
| Заміна `WorldRenderEvents` | `net.fabricmc.fabric.api.client.rendering.v1.level.{LevelRenderEvents, LevelExtractionEvents, LevelRenderContext, LevelExtractionContext, LevelTerrainRenderContext, AbstractLevelRenderContext}` | ✅ подія світового рендеру є, просто переїхала в підпакет `level` |
| Render-state API | `FabricRenderState`, `RenderStateDataKey`, `SubmitRenderPhase`, `SubmitRenderPhases`, `FabricOrderedSubmitNodeCollector`, `InvalidateRenderStateCallback` | Офіційний шлях додати свої дані в extract-фазу |
| Партикли (Fabric) | `api.client.particle.v1.{ParticleProviderRegistry, FabricSpriteSet, ParticleGroupRegistry, ParticleRenderEvents}`, `api.particle.v1.FabricParticleTypes` | ✅ `ParticleProviderRegistry`, а не `ParticleFactoryRegistry` |
| Партикли (ваніль) | `net.minecraft.client.particle.{ParticleProvider, SpriteSet, ParticleEngine}`, `net.minecraft.core.particles.ParticleType` | — |

Подій туману у Fabric API немає й далі. Ваніль теж не дає їх зареєструвати — розв'язок
виявився в іншому місці, див. «Туман: моє припущення було хибне».

### Підтверджено раундом 2 — архітектура визначена

**Рендер опадів.** `WeatherEffectRenderer` має два **публічні** методи:

```java
public void extractRenderState(ClientLevel level, float partialTicks, Vec3 cameraPos, WeatherRenderState renderState)
public void render(Vec3 cameraPos, WeatherRenderState renderState)
```

Що робить `extractRenderState` (важливо — це вирішує майже все):

- `renderState.intensity = level.getRainLevel(partialTicks)` → **наш M3 керує інтенсивністю задарма**;
- `renderState.radius = Minecraft.getInstance().options.weatherRadius().get()` →
  **у 26.2 радіус рендеру погоди вже є ванільною клієнтською опцією**. «Дощ до дальності
  промальовування» не треба винаходити — треба лише підмінити це число;
- далі подвійний цикл по `x`/`z` у межах радіуса, і для кожної колонки:
  `Precipitation p = level.getPrecipitationAt(mutablePos.set(x, camY, z))` →
  **локальність вмикається прямо у ванільний цикл**: досить зробити цей виклик
  позиційно-залежним, і стіна дощу з'явиться сама, без власного циклу.

Геометрія колонки в `renderInstances`: чотири вершини, у верхньої пари `y1 = topY - cam.y`,
у нижньої `y0 = bottomY - cam.y`, а `x`/`z` **однакові** зверху й знизу — тобто стовп строго
вертикальний. **Нахил = зсув верхньої пари вершин** на `(sin dir, cos dir) · висота · tan(кут)`.
Рівно те, що я планував, і тепер підтверджено по вихідниках.

Малюється це так (усе ванільне, свого шейдера не треба):
`RenderPipelines.WEATHER_DEPTH_WRITE` / `WEATHER_NO_DEPTH_WRITE`,
`DefaultVertexFormat.PARTICLE`, `OutputTarget.WEATHER_TARGET`,
текстури `textures/environment/rain.png` і `snow.png`.

`WeatherRenderState implements FabricRenderState`, а в Fabric API є `RenderStateDataKey` —
офіційний спосіб причепити свої дані до ванільного render-state. Запис `ColumnInstance`
поля для нахилу не має, тож свої колонки веземо через `RenderStateDataKey`.

**Серверна погода.** Підтверджено все:

- `ServerLevel.advanceWeatherCycle()` ✅ — ціль M1 саме під цим іменем;
- `Level.precipitationAt(BlockPos)` — **єдина позиційно-залежна точка входу**;
  `Level.isRainingAt(pos)` це буквально `precipitationAt(pos) == RAIN`. Один хук
  накриває гасіння вогню, врожай, казан, риболовлю, спавн;
- `ClientLevel.getPrecipitationAt(BlockPos)` — **окремий** метод клієнта (інша назва!),
  ним користуються рендер погоди й бризки. Треба хукати обидва;
- погода тепер **глобальна на сервер**, а не на вимір: `MinecraftServer.getWeatherData()` →
  `net.minecraft.world.level.saveddata.WeatherData`; `ServerLevel.getWeatherData()` лише делегує;
- ґеймрул тепер `GameRules.ADVANCE_WEATHER` (не `doWeatherCycle`);
- `ServerLevel.tickPrecipitation(BlockPos)` — **публічний** → прискорений казан і сніг
  робимо прямим викликом, **без міксина**;
- `LayeredCauldronBlock.handlePrecipitation(BlockState, Level, BlockPos, Biome.Precipitation)` — публічний;
- `Biome.getPrecipitationAt(BlockPos, int seaLevel)`, `coldEnoughToSnow`, `warmEnoughToRain`,
  `hasPrecipitation()` — усе на місці для вибору «сніг чи дощ»;
- `WeatherCommand.register(CommandDispatcher<CommandSourceStack>)` ✅.

### Туман: моє припущення було хибне

Я сподівався, що `FogEnvironment` — відкритий реєстр. **Ні.** Це приватний статичний список:

```java
private static final List<FogEnvironment> FOG_ENVIRONMENTS = Lists.newArrayList(
    new LavaFogEnvironment(), new PowderedSnowFogEnvironment(), new BlindnessFogEnvironment(),
    new DarknessFogEnvironment(), new WaterFogEnvironment(), new AtmosphericFogEnvironment());
```

Публічного API реєстрації немає, тож свій `FogEnvironment` офіційно не додати.

Але вийшло навіть краще. `AtmosphericFogEnvironment.updateRainFogState()` уже рахує
`rainFogMultiplier` з `level.getRainLevel(partialTicks)` і стискає туман на
`-160 / -256 * multiplier`. Тобто **«чим сильніші опади — тим менша видимість» працює
задарма через M3**, нічого писати не треба.

Для незалежної осі туману лишається дрібниця: `FogRenderer.setupFog(...)` **повертає
`FogData`** з публічними полями. Один `@Inject(at = @At("RETURN"))`, який читає
`cir.getReturnValue()` і масштабує `environmentalStart/End` — п'ять рядків, і приватного
списку ми не чіпаємо.

### Підтверджено раундом 3 — розвідку закрито

**Локальність: два різні методи, обидва потрібні.**

```java
// Level.precipitationAt — сюди делегує isRainingAt, звідси вогонь/врожай/риболовля
if (!isRaining())                                        return NONE;
if (!canSeeSky(pos))                                     return NONE;
if (getHeightmapPos(MOTION_BLOCKING, pos).getY() > pos.getY()) return NONE;
return getBiome(pos).value().getPrecipitationAt(pos, getSeaLevel());

// ClientLevel.getPrecipitationAt — ІНШІ умови: перевірки isRaining немає взагалі
if (!chunkSource.hasChunk(...)) return NONE;
return getBiome(pos).value().getPrecipitationAt(pos, getSeaLevel());
```

Клієнтський варіант не питає `isRaining()`, бо викликач (`extractRenderState`) уже
відсікає за `intensity`. Тобто це справді два незалежні хуки, а не один.

**Два обходи, яких я не очікував:**

1. `ServerLevel.tickPrecipitation` питає **біом напряму**, а не `precipitationAt` —
   тобто M2 його не накриває, і казан із снігом накопичувались би по всій карті, поки
   `isRaining()` глобально true. Потрібен окремий гейт (M9).
2. `getThunderLevel(a)` у ванілі — це `lerp(...) * getRainLevel(a)`. **Ваніль множить грозу
   на дощ**, тобто суха гроза в ній неможлива в принципі. Наш M3 має повертати грозу
   незалежно від опадів — інакше вимога «суха гроза дозволена й має траплятися» не працює.
   Добре, що це спливло зараз, а не в грі.

**Блискавки — виправлено після рев'ю. Виграш був не безкоштовний.**

Спершу я зрадів, що `ServerLevel.tickThunder(LevelChunk)` викликається по завантажених
чанках і гейтиться на `isRainingAt(pos)`, тобто через M2 стає локальним задарма. І тим
самим абзацом окремо зазначив, що ваніль в'яже грозу до дощу в `getThunderLevel`.
Не звів одне з одним. У `tickThunder` **три** дощові гейти:

```java
boolean raining = this.isRaining();                      // (1) глобальний
if (raining && this.isThundering() && rnd.nextInt(100000) == 0) {
    BlockPos pos = this.findLightningTargetAround(this.getBlockRandomPos(minX, 0, minZ, 15));
    if (this.isRainingAt(pos)) {                         // (3) позиційний ← М2 робить його false
        ... skeleton horse trap ... LightningBolt ...
    }
}
```

Гейт (3) після M2 повертає `false`, коли опадів у точці немає. Отже **суха гроза давала б
нуль ударів** — темні хмари з гуркотом і без жодної блискавки. Інваріант 2 не виконано.

**Беру варіант «M10 обходить гейт», а не власний спавн.** Два кодові шляхи для блискавки
неминуче розійшлись би в поведінці (пастка зі скелетним конем, `LightningRod`, адвансменти),
а §6 вимагає, щоб ванільна механіка лишилась без змін.

Але чесно: **M10 через це не «дрібний міксин на частоту»**, як я писав, а
`@Inject(HEAD, cancellable)` з повною заміною тіла, ~25 рядків. Він відтворює ванільну
послідовність один в один — `findLightningTargetAround(getBlockRandomPos(...))`,
пастка зі скелетним конем через `EntityTypes.SKELETON_HORSE`, `EntityTypes.LIGHTNING_BOLT`
з `snapTo(Vec3.atBottomCenterOf(pos))` і `setVisualOnly(isTrap)` — і міняє рівно два місця:

1. замість `raining && isThundering()` — наша вісь грози в цьому чанку, **незалежна від опадів**;
2. замість `isRainingAt(pos)` — наша гроза в точці `pos` (а не опади);
3. замість фіксованого `nextInt(100000)` — `nextInt(thunder.strike_interval / thunderFactor)`,
   де `WEAK` і `NORMAL` мають окремі множники в конфігу.

Прогрузка чанків лишається задарма — метод і далі викликається ванільним тіком чанків,
тож «удари лише в межах прогрузки» виконується без нашої участі. **Це і був справжній
безкоштовний виграш**; локальність і суха гроза — ні.

Плановий `LightningDriver.java` усе одно викреслено: свій цикл пошуку чанків не потрібен.

**Рендер: `RenderStateDataKey` і `LevelRenderEvents` не потрібні.** M6 підміняє
`WeatherEffectRenderer.render` **на місці**, всередині ванільного frame-graph-проходу
`addWeatherPass`, де таргет уже прив'язаний. Вітер читаємо зі свого клієнтського стану,
а не веземо в render-state. Підтверджено: `RenderPipelines.WEATHER_DEPTH_WRITE` /
`WEATHER_NO_DEPTH_WRITE`, `OutputTarget.WEATHER_TARGET` →
`levelRenderer.weatherTarget()`, `DefaultVertexFormat.PARTICLE`,
`Options.weatherRadius()` → `OptionInstance<Integer>`.

**Партикли — з першоджерела Fabric API** (дістав сам через `raw.githubusercontent.com`,
`javap` для цього не знадобився):

```java
ParticleProviderRegistry.getInstance().register(ParticleType<T> type, PendingParticleProvider<T> ctor);
interface PendingParticleProvider<T extends ParticleOptions> { ParticleProvider<T> create(FabricSpriteSet spriteSet); }
interface FabricSpriteSet extends SpriteSet { TextureAtlas getAtlas(); List<TextureAtlasSprite> getSprites(); }
FabricParticleTypes.simple() -> SimpleParticleType
```

**Смуги вітру — чесне обмеження.** `SingleQuadParticle` має **один** `quadSize`, тобто
неоднорідно розтягнути квад у стрічку не можна. Витягнутість доведеться закласти в саму
текстуру (тонка біла смуга з розмитими кінцями в межах квадратного спрайта), а орієнтацію
за вітром дає поле `roll` — воно вже є і повертає квад навколо осі погляду. Виглядає як
інверсійний слід; повноцінного «довгого шлейфа» без власного типу партикла не буде.
Кажу це зараз, а не після того, як ти побачиш результат.

### Що лишилось: рівно два імені

`fabric-api source files extracted: 0` — sources-jar-ів Fabric API у кеші немає, а javap
під Java 25 на машині не знайшовся. Але з Fabric це вже й не потрібно: усе потрібне я
витягнув напряму з репозиторію. Відкритими лишились два **ванільні** імені:

1. **Клас човна.** `AbstractBoat` у 26.2 немає. → **Обходжу без імені:** список типів
   сутностей для вітру береться з конфігу як рядки реєстру
   (`["minecraft:oak_boat", …]`) через `BuiltInRegistries.ENTITY_TYPE`. Так навіть краще —
   модові човни додаються без правки коду.
2. **Перевірка польоту на елітрах.** У `Entity.java` її немає, бо вона на `LivingEntity`.
   Javadoc Fabric каже «elytra flight is also known as fall flying», що вказує на
   `isFallFlying()`, але я це **не перевірив** і вгадувати не буду.

Одна команда — і розвідка закінчена остаточно:

```bash
rg -n "FallFlying|Gliding|isGliding" ~/.gradle/caches/fabric-loom --glob 'LivingEntity.java' | head
rg -ln "class .*Boat" ~/.gradle/caches/fabric-loom
```

(або те саме `grep -rn` по розпакованому sources-jar). Це не блокує серверну половину моду —
її пишу вже зараз.

### Довідка: що дістав раунд 3 (`dump-262-api-3.sh`)

Секція `javap` у раунді 2 провалилась цілком: Fabric API 0.156.0 зібраний під Java 25
(class file v69), а `javap` у `PATH` — зі старішого JDK і такі класи не читає. Помилку
приховав мій `2>/dev/null`, тому вийшов 21 однаковий «not on classpath». Раунд 3 читає
**вихідники** Fabric API (`-sources.jar`), а на javap відкочується лише за наявності JDK ≥ 25.

Треба ще:

1. `RenderStateDataKey` + `FabricRenderState` — як саме чіпляти свої дані до `WeatherRenderState`.
2. `LevelExtractionEvents` / `LevelRenderEvents` — чи є фаза, у якій можна малювати погоду
   без міксина (погода йде окремим frame-graph-проходом `addWeatherPass` у власний таргет,
   тож імовірно ні — але це вирішує долю M6).
3. `ParticleProviderRegistry.register(...)` і форму `FabricSpriteSet`.
4. Повні тіла `Level.precipitationAt` і `ClientLevel.getPrecipitationAt` — щоб точно
   відтворити ванільні умови (`canSeeSky`, висота, `seaLevel`), а не зламати їх.
5. `SingleQuadParticle` + `ParticleRenderType` — база для партикла смуг вітру.
6. Тип `Options.weatherRadius()` (`OptionInstance<Integer>`?) — щоб коректно його підміняти.
7. `WeatherData` і `MinecraftServer.setWeatherParameters` — для мапінгу ванільної `/weather`.

---

## 1. Дерево файлів

```
new_minecraft_weather/
├── build.gradle                        # Loom 1.17, Java 25, split source sets  [ГОТОВО]
├── gradle.properties                   # версії MC/Loader/Loom/FabricAPI        [ГОТОВО]
├── settings.gradle                     # pluginManagement, rootProject          [ГОТОВО]
├── gradlew / gradlew.bat               # Gradle wrapper 9.5.1                   [ГОТОВО]
├── gradle/wrapper/…                    # jar + properties                       [ГОТОВО]
├── tools/verify-262-names.sh           # витяг реальних імен 26.2 після genSources [ГОТОВО]
├── docs/PLAN.md                        # цей документ                           [ГОТОВО]
├── LICENSE
├── README.md
└── src/
    ├── main/                           # COMMON: симуляція погоди + спільна модель
    │   ├── java/com/fand1l/vibeweather/
    │   │   ├── VibeWeather.java                    # ModInitializer: конфіг, реєстри, мережа, команди, тік
    │   │   ├── VibeWeatherRegistries.java          # реєстрація типів партиклів (спільна сторона)
    │   │   │
    │   │   ├── api/                                # форма майбутнього публічного API (поки internal)
    │   │   │   ├── CloudCover.java                 # enum CLEAR/FEW/SCATTERED/OVERCAST
    │   │   │   ├── Precipitation.java              # enum NONE/DRIZZLE/RAIN/DOWNPOUR + базові інтенсивності
    │   │   │   ├── ThunderLevel.java               # enum OFF/WEAK/NORMAL + множник частоти блискавок
    │   │   │   ├── FogLevel.java                   # enum NONE/LIGHT/THICK + базова густина
    │   │   │   ├── WindMode.java                   # CALM/BREEZE/GALE як діапазони float-сили
    │   │   │   ├── WeatherState.java               # НЕЗМІННИЙ record усіх осей + sanitize() + lerp()
    │   │   │   ├── WeatherSample.java              # розв'язана погода в точці: state + altitudeFactor + isSnow
    │   │   │   └── WeatherQuery.java               # read-only фасад sampleAt(level,x,y,z) — майбутній API
    │   │   │
    │   │   ├── weather/                            # модель, незалежна від MC-класів
    │   │   │   ├── WeatherZone.java                # ЖИВИЙ об'єкт-зона: center, radius, band, wind, state, таймери
    │   │   │   ├── ZoneSimulation.java             # тік зон: рух, старіння, народження, смерть
    │   │   │   ├── ZoneSpawner.java                # народження зон у кільці поза зоною видимості гравців
    │   │   │   ├── ZoneBlender.java                # зважений бленд зон у WeatherSample; без алокацій
    │   │   │   ├── WeatherTransitions.java         # зважені ланцюги Маркова по осях + рулетка тривалості
    │   │   │   ├── ZoneSizeDistribution.java       # радіус 64..1024 зі зміщенням до середини (Irwin–Hall)
    │   │   │   ├── FogRules.java                   # контекстний туман: після дощу / світанок / вода / DOWNPOUR
    │   │   │   └── WindField.java                  # напрямок як кут, безперервне обертання, сила→WindMode
    │   │   │
    │   │   ├── server/
    │   │   │   ├── ServerWeatherManager.java       # менеджер на вимір: тік симуляції, трекінг гравців, freeze
    │   │   │   ├── WeatherSavedData.java           # ПЕРСИСТЕНЦІЯ зон + freeze у SavedData виміру
    │   │   │   ├── WeatherGridBuilder.java         # будує сітку семплів навколо гравця (світова ґратка)
    │   │   │   ├── WeatherSync.java                # повна сітка vs дельта; хто що вже має
    │   │   │   └── effects/
    │   │   │       ├── PrecipitationEffects.java   # прискорений казан + накопичення шарів снігу
    │   │   │       └── WindPhysicsServer.java      # вітер на мобів і стріли (не на гравців)
    │   │   │
    │   │   ├── command/
    │   │   │   ├── VibeWeatherCommand.java         # /vibeweather query|set|freeze (рівень 2)
    │   │   │   └── VanillaWeatherBridge.java       # мапінг ванільної /weather на локальну зону
    │   │   │
    │   │   ├── net/
    │   │   │   ├── VibeWeatherNetworking.java      # реєстрація payload-типів + хелпери відправки
    │   │   │   ├── WeatherGridPayload.java         # S→C: повна сітка семплів (вхід, зміна виміру, телепорт)
    │   │   │   ├── WeatherGridDeltaPayload.java    # S→C: лише змінені/нові клітинки сітки
    │   │   │   ├── WeatherParamsPayload.java       # S→C: крок і розмір сітки, висота хмар, множники з конфігу
    │   │   │   └── WeatherCodecs.java              # StreamCodec-и + квантування семпла у 8 байтів
    │   │   │
    │   │   ├── config/
    │   │   │   ├── VibeWeatherConfig.java          # кореневий JSON-конфіг (record), дефолти
    │   │   │   ├── ConfigSections.java             # секції: zones, durations, transitions, wind, fog, grid, render, gameplay
    │   │   │   └── ConfigManager.java              # читання/запис/валідація config/vibe_weather.json
    │   │   │
    │   │   ├── compat/SodiumCompat.java            # детект Sodium → лог + одне повідомлення в чат + деградація
    │   │   │
    │   │   ├── mixin/
    │   │   │   ├── ServerLevelWeatherMixin.java    # ГЛУШИТЬ ванільний погодний цикл
    │   │   │   ├── LevelIsRainingAtMixin.java      # робить ванільний геймплей локальним
    │   │   │   ├── LevelRainLevelMixin.java        # getRainLevel/getThunderLevel з нашого стану
    │   │   │   └── WeatherCommandMixin.java        # скасовує реєстрацію ванільної /weather
    │   │   │
    │   │   └── util/
    │   │       ├── MathUtil.java                   # lerp, smoothstep, кутовий lerp, без алокацій
    │   │       └── BiomeWeatherUtil.java           # дощ чи сніг у біомі; близькість великої води
    │   │
    │   └── resources/
    │       ├── fabric.mod.json                     # id vibe_weather, environment "*", entrypoints, depends
    │       ├── vibe_weather.mixins.json            # common-міксини, JAVA_25
    │       ├── assets/vibe_weather/icon.png
    │       └── assets/vibe_weather/lang/{en_us,uk_ua}.json
    │
    └── client/                                     # CLIENT: прийом сітки + рендер, нічого не рахує сам
        ├── java/com/fand1l/vibeweather/client/
        │   ├── VibeWeatherClient.java              # ClientModInitializer: приймачі пакетів, рендер-хуки
        │   ├── ClientWeatherGrid.java              # два останні кадри сітки + білінійна вибірка в точці
        │   ├── GridInterpolator.java               # інтерполяція в просторі (bilinear) і в часі (між пакетами)
        │   ├── render/
        │   │   ├── WeatherRenderer.java            # заміна ванільного рендеру опадів: нахил + дальність
        │   │   ├── PrecipitationMesh.java          # преалоковані буфери, нуль алокацій за кадр
        │   │   ├── WeatherRenderPipelines.java     # RenderPipeline/RenderType через Blaze3D (без сирого GL)
        │   │   └── FogController.java              # дальність видимості від опадів і осі туману
        │   ├── particle/
        │   │   ├── WindStreakParticle.java         # тонка біла витягнута стрічка високо в небі
        │   │   ├── WindStreakProvider.java         # фабрика партикла
        │   │   └── WindStreakSpawner.java          # щільність від сили вітру; вимкнено при OVERCAST
        │   ├── sound/WeatherSoundController.java   # ванільні звуки дощу/грому з гучністю під рівень
        │   ├── physics/WindPhysicsClient.java      # вітер на локального гравця, елітри, керований човен
        │   ├── hud/WeatherDebugOverlay.java        # опційний оверлей стану для налагодження
        │   └── mixin/
        │       ├── WeatherEffectRendererMixin.java # скасовує ванільне малювання дощу/снігу
        │       └── FogRendererMixin.java           # застосовує наш туман
        └── resources/
            ├── vibe_weather.client.mixins.json
            └── assets/vibe_weather/
                ├── particles/wind_streak.json
                └── textures/particle/wind_streak.png
```

---

## 2. Схема потоку даних

```
┌───────────────────────────── СЕРВЕР (єдине джерело істини) ─────────────────────────────┐
│                                                                                         │
│  ServerTickEvents.END_LEVEL_TICK  ──►  ServerWeatherManager.tick(level)                 │
│                                             │                                           │
│                                             ├─► ZoneSpawner                             │
│                                             │     народжує зони у КІЛЬЦІ поза зоною     │
│                                             │     видимості → погода вповзає збоку,     │
│                                             │     а не «вмикається» під гравцем         │
│                                             │                                           │
│                                             ├─► ZoneSimulation.tick()                   │
│                                             │     • рух центру за вітром                │
│                                             │     • WeatherTransitions: ланцюги Маркова │
│                                             │     • WeatherState.sanitize() після зміни │
│                                             │     • смерть зони після lifetime          │
│                                             │                                           │
│                                             ├─► WeatherSavedData  (персистенція зон)    │
│                                             │                                           │
│                                             ▼                                           │
│                                    ZoneBlender.sample(x, y, z)                          │
│                                             │                                           │
│         ┌───────────────────────────────────┼───────────────────────────────┐           │
│         ▼                    ▼              ▼               ▼               ▼           │
│  M10 tickThunder   M9 tickPrecipitation  WindPhysics   M2 precipitationAt WeatherGrid-  │
│  (лише частота —   (гейт + прискорене     Server       (весь ванільний    Builder       │
│   локальність і     накопичення казана    (моби,        геймплей стає      (сітка        │
│   прогрузку дає      й шарів снігу)        стріли)       локальним)         семплів)     │
│                                                                                │        │
│                                                                    WeatherSync │        │
└────────────────────────────────────────────────────────────────────────────────┼────────┘
                                                                                 │
        ┌────────────────────────────────────────────────────────────────────────┴──────┐
        │ WeatherParamsPayload    — вхід у гру, зміна виміру, /reload                    │
        │ WeatherGridPayload      — повна сітка: вхід, зміна виміру, телепорт > extent   │
        │ WeatherGridDeltaPayload — лише змінені/нові клітинки, кожні 40 тіків           │
        └────────────────────────────────────────────────────────────────────────┬──────┘
                                                                                 │
┌────────────────────────────── КЛІЄНТ (нічого не обчислює) ─────────────────────┴────────┐
│                                                                                         │
│  ClientWeatherGrid                                                                      │
│    • тримає ДВА останні кадри сітки (prev, next) + їхні timestamp                       │
│    • сітка прив'язана до СВІТОВОЇ ґратки, кратної кроку → при русі гравця вона           │
│      не «їде», а лише добудовується крайнім рядом                                       │
│                                             │                                           │
│                                             ▼                                           │
│  GridInterpolator.sample(x, z, partialTick)                                             │
│    • у просторі — білінійно між 4 сусідніми вузлами                                     │
│    • у часі     — лінійно між prev і next                                               │
│    • по висоті  — множник altitudeFactor із WeatherParamsPayload                        │
│                                             │                                           │
│      ┌────────────┬──────────────┬──────────┴────┬────────────────────┐                 │
│      ▼            ▼              ▼               ▼                    ▼                 │
│  Weather-      Fog-         WindStreak-     WeatherSound-      WindPhysics-             │
│  Renderer      Controller   Spawner         Controller         Client                   │
│  (нахил за     (дальність   (смуги в небі,  (ванільні звуки,   (локальний гравець,      │
│   вітром,       видимості)   вимкнені при    гучність за        елітри, керований       │
│   дальність)                 OVERCAST)       рівнем)            човен)                  │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

### Сітка семплів: параметри й обґрунтування частоти

**Квантування одного вузла — 8 байтів:**

| Поле | Байтів | Кодування |
|---|---|---|
| хмарність (float 0..1) | 1 | `u8`, крок 1/255 |
| опади (float 0..1) | 1 | `u8` |
| гроза | 1 | `u8`: 0/1/2 + запас |
| сила вітру (float 0..1) | 1 | `u8` |
| напрямок вітру | 2 | `u16`, крок 360/65536° — з надлишком для плавного обертання |
| туман (float 0..1) | 1 | `u8` |
| прапорці (сніг/дощ, freeze) | 1 | бітова маска |

Дискретні осі (`CLEAR/FEW/…`) клієнт **не отримує окремо** — вони однозначно виводяться
з float-порогів, які прийшли у `WeatherParamsPayload`. Це не «клієнт вигадує»: пороги
серверні.

**Розмір сітки.** Дефолт: крок `48` блоків, півекстент `9` вузлів → сітка `19×19 = 361`
вузол, покриття `864×864` блоки (≈ 27 чанків у кожен бік — покриває типову дальність
промальовування 16–24 чанки з запасом). Повний пакет: `361 × 8 = 2 888 B`.

**Чому сітка прив'язана до світової ґратки.** Вузли лежать на координатах, кратних кроку
(`x % 48 == 0`), а не відносно гравця. Тому при русі гравця сітка не зсувається — вона
лише добудовується крайнім рядом. Це прибирає головний недолік сіткового підходу
(інакше довелось би слати всі 361 вузол щоразу, як гравець пройшов 48 блоків).

| Пакет | Частота | Чому саме так |
|---|---|---|
| `WeatherParamsPayload` | вхід, зміна виміру, `/reload` | Константи конфігу: крок сітки, пороги дискретних осей, висота хмар, множники. Змінюються ~ніколи. |
| `WeatherGridPayload` | вхід, зміна виміру, телепорт далі за екстент | ~2.9 КБ разово. |
| `WeatherGridDeltaPayload` | 40 тіків (2 с) | Стани зон міняються раз на ~ігрову добу, тому змінюється мало вузлів. Типова дельта при русі гравця — один новий ряд, `19 × 8 = 152 B`. Клієнт інтерполює в часі між двома кадрами, тож 2 с не помітні. |

**Оцінка трафіку:** усталений режим — **~76 Б/с на гравця** (152 B / 2 c), пік при
спринті через межу зони — до ~1 КБ/с. Вимога «трафік на гравця мінімальний» виконана.

**Обмеження, яке треба поважати:** ширина перехідної смуги зони має бути **не менша за
два кроки сітки** (`zones.blend_band_min >= 2 * grid.step`, тобто ≥ 96 блоків при дефолті).
Інакше межа зони тонша за роздільність сітки, і білінійна інтерполяція її розмиє в кашу.
Це не проблема для ТЗ — межі й так мають бути розмитими, — але це жорсткий інваріант
конфігу, і `ConfigManager` буде його валідувати й лаятись у лог при порушенні.
Хочеш різкішу стіну дощу — зменшуй `grid.step` (трафік росте квадратично).

---

## 3. Локальність: варіант C, як обрано

Сервер тримає живий список `WeatherZone` на кожен вимір, тікає їх раз на
`zones.tick_interval` (дефолт 20 тіків) і зберігає у `WeatherSavedData`.

**Що я роблю, щоб мінімізувати відомі мінуси C:**

| Мінус C | Мітигація в межах C |
|---|---|
| Зона «народжується під гравцем», коли той залітає в незвідану область | `ZoneSpawner` створює зони не під гравцем, а в **кільці** `[renderDist, renderDist + zones.spawn_margin]` (дефолт margin 768 блоків) навколо кожного гравця. Погода завжди вповзає збоку. |
| Персистенція росте з обжитою картою | Жорсткий ліміт `zones.max_persisted` (дефолт 512 на вимір); зони без гравця в радіусі `zones.keep_radius` довше за `zones.unloaded_ttl` (дефолт 3 ігрові доби) видаляються. |
| Пам'ять/GC ростуть із кількістю гравців | Зона — плоский об'єкт (~15 полів), тік — кілька float-операцій. 512 зон × 15 полів ≈ 30 КБ на вимір. Дедуплікація: зони спільні для всіх гравців у вимірі, а не «на гравця». |
| Незавантажені області «не живуть» | Зони тікають **завжди**, поки існують у списку, незалежно від прогрузки чанків. Прогрузку чанків це не викликає (вимога ТЗ) — тік зони працює з координатами, не з блоками. |

**Обидві вимоги ТЗ про продуктивність виконуються:** погода не зберігається по чанках
(вона у `SavedData` виміру), чанки не вантажить (симуляція чисто координатна), і важкий
перерахунок (`WeatherGridBuilder`) робиться раз на 40 тіків на гравця, а не щотіка.

---

## 4. Де потрібні міксини і чому

Принцип: спершу Fabric API event, і лише за його відсутності — міксин.

| # | Ціль (Mojang-мапінги) | Навіщо | Чому не можна без міксина |
|---|---|---|---|
Усі цілі нижче **перевірені по декомпільованих джерелах 26.2**, не по пам'яті.

| # | Ціль | Тип | Навіщо / чому без міксина не можна |
|---|---|---|---|
| **M1** | `ServerLevel#advanceWeatherCycle()` | `@Inject HEAD cancellable` | Заглушити ванільні таймери й розсилку `RAIN_LEVEL_CHANGE`/`THUNDER_LEVEL_CHANGE`. Події «до тіку погоди» у Fabric API немає; ґеймрул `ADVANCE_WEATHER` морозить стан, а не віддає його нам, і його видно гравцю. |
| **M2** | `Level#precipitationAt(BlockPos)` | `@Inject HEAD cancellable` | **Одна точка на весь ванільний геймплей.** `isRainingAt` делегує сюди, а від нього залежать гасіння вогню, ріст врожаю, казан, риболовля, спавн. Патчити їх поодинці — десяток міксинів замість одного. |
| **M3** | `Level#getRainLevel(float)`, `#getThunderLevel(float)` | `@Inject HEAD cancellable` | Обидва методи **позиційного аргументу не мають**, тож поводяться по-різному на сторонах (див. «M3: що повертає сервер»). На клієнті — значення в камері; на сервері — максимум по виміру. `getThunderLevel` повертає грозу **без множення на дощ** — інакше суха гроза неможлива. Дає безкоштовно: `intensity` у extract-фазі, стиснення туману в `AtmosphericFogEnvironment`, затемнення неба, гучність. |
| **M4** | `ClientLevel#getPrecipitationAt(BlockPos)` | `@Inject HEAD cancellable` | Клієнтський двійник M2 — інша назва, окремий метод. Ним керується і рендер колонок, і бризки, і звук дощу. Саме він робить **межу зони видимою здалеку**, бо ванільний цикл extract уже перебирає весь радіус. |
| **M5** | `WeatherEffectRenderer#extractRenderState` | `@Redirect` на виклик `options.weatherRadius().get()` | Підмінити радіус рендеру погоди на наш конфігурований. Три рядки — решту (перебір колонок, сніг/дощ, освітлення) робить ваніль. |
| **M6** | `WeatherEffectRenderer#render` | `@Inject HEAD cancellable` | Свій draw заради **нахилу опадів за вітром**: запис `ColumnInstance` поля нахилу не має, тож вершини треба будувати самим. Пайплайн, таргет, формат і текстури беремо ванільні — свого шейдера немає. |
| **M7** | `FogRenderer#setupFog` | `@Inject RETURN` | Незалежна вісь туману. П'ять рядків: мутуємо `FogData` з `cir.getReturnValue()`. Приватний список `FOG_ENVIRONMENTS` не чіпаємо. |
| **M8** | `WeatherCommand#register` | `@Inject HEAD cancellable` | Brigadier не має публічного API для видалення зареєстрованого вузла. Accessor на приватну мапу `children` у `CommandNode` гірший — лізе в чужу структуру. |
| **M9** | `ServerLevel#tickPrecipitation(BlockPos)` | `@Inject HEAD cancellable` | **Обхід M2:** цей метод питає біом напряму, а не `precipitationAt`, тож без окремого гейта казан і сніг накопичувались би по всій карті, поки `isRaining()` глобально true. |
| **M10** | `ServerLevel#tickThunder(LevelChunk)` | `@Inject HEAD cancellable`, повна заміна тіла (~25 рядків) | **Обхід дощових гейтів заради сухої грози.** Ванільний `isRainingAt(pos)` після M2 дає `false` без опадів, тому суха гроза не била б узагалі. Відтворюємо ванільну послідовність один в один (пастка зі скелетним конем, `LightningBolt`, `snapTo`), міняючи лише гейт на нашу вісь грози та частоту під `WEAK`/`NORMAL`. Прогрузка чанків лишається ванільною задарма. |

**Разом 10 міксинів.** Більше, ніж хотілося, і я не буду вдавати, що це «мінімально» —
але кожен від 3 до 15 рядків, і вони куплені за викреслені підсистеми: власний цикл
рендеру опадів, власний `RenderPipeline` з шейдером, власний `LightningDriver`, власний
розрахунок туману від дощу й затемнення неба. Альтернатива — не менше міксинів, а
менше й **значно більших**, які дублюють ваніль замість того, щоб її використати.

Найбільший із десяти — M6 (копія ванільного `renderInstances` плюс нахил). Решта дев'ять
сумарно — менш ніж сотня рядків.

### Де міксини свідомо НЕ потрібні

- **Фізика вітру.** Моби й стріли — `ServerTickEvents.END_LEVEL_TICK` + публічний
  `addDeltaMovement`. Локальний гравець, елітри й керований човен — `ClientTickEvents` на
  клієнті: рух гравця симулює клієнт, і правити його з сервера означає гумування.
- **Казан і сніг.** Не патчимо ванільний `tickChunk`, а **додаємо** свої додаткові спрацювання
  у власному тіку через публічні `setBlockAndUpdate`. Ванільна швидкість лишається, ми лише
  прискорюємо — рівно як просить ТЗ.
- **Партикли листя.** Зносимо ванільні партикли, змінюючи їхню швидкість у клієнтському тіку;
  нових не спавнимо.
- **Команди, мережа, конфіг, тік, вхід гравця** — усе через Fabric API.

---

## 5. Ключові механіки

### `WeatherState.sanitize()` — переписано

**Стара редакція мала петлю зворотного зв'язку і не працювала.** Крок 1 гасив опади
множником `cloudFactor`, крок 2 підіймав хмарність до `OVERCAST`, щойно дискретна вісь
опадів була `> NONE`. Оскільки дискретна вісь падала лише після того, як float дійде нуля,
будь-яка залишкова краплина тягла хмарність назад у повний `OVERCAST` → наступного тіку
`cloudFactor = 1` → гасіння зупинялось. Інваріант 4 не виконувався ніколи.

Корінь помилки: я змішав дві різні речі в одній функції — **проєкцію на множину
допустимих станів** (позачасову) і **плавне згасання** (процес у часі). Розділяю їх.

**`sanitize()` більше не займається згасанням.** Це чиста ідемпотентна функція: застосуй
її двічі — нічого не зміниться. Вона ніколи не **знижує** опади чи грозу; лише клемпить,
виводить дискретні осі й **підіймає** хмарність під інваріанти.

```
WeatherState sanitize():

  # 1. Клемп. Жоден наступний крок не має права припускати коректний діапазон.
  cloudsF, precipF, thunderF, fogF, windF  ←  clamp(·, 0, 1)
  windDir  ←  ((windDir mod 360) + 360) mod 360

  # 2. Мертва зона. Саме вона дає згасанню ЗАВЕРШИТИСЬ, а не наближатись до нуля вічно.
  if precipF  < EPS:  precipF  = 0        # EPS у конфігу, дефолт 0.01
  if thunderF < EPS:  thunderF = 0
  if fogF     < EPS:  fogF     = 0

  # 3. Дискретні осі ВИВОДЯТЬСЯ з float, а не зберігаються окремо.
  #    Одне джерело істини прибирає весь клас розсинхронів «дискретна каже X, float каже Y».
  clouds  = cloudFromFloat(cloudsF)       # пороги з конфігу
  precip  = precipFromFloat(precipF)      # precipF == 0  ⇒  NONE
  thunder = thunderFromFloat(thunderF)    # thunderF == 0 ⇒  OFF
  fog     = fogFromFloat(fogF)

  # 4. Інваріанти 1 і 2: опади АБО гроза вимагають OVERCAST.
  #    Підіймаємо ХМАРНІСТЬ їм назустріч — і ніколи не навпаки.
  #    Зниження опадів — робота симуляції, у часі (див. нижче).
  if precipF > 0 or thunderF > 0:
      cloudsF = max(cloudsF, OVERCAST_FLOOR)
      clouds  = OVERCAST

  # Інваріант 3: OVERCAST без опадів і без грози — валідно, нічого не робимо.
  # Інваріант 5: fog вище не чіпався жодним кроком.
  # Гроза НЕ залежить від опадів: суха гроза — легальний стан (див. §Гроза).
```

Ідемпотентність: крок 4 лише підіймає хмарність, тож другий прогін застає її вже
піднятою й нічого не змінює. Петлі немає, бо `sanitize()` не знижує опади в принципі.

### Інваріант 4 — де він тепер живе

«Падіння хмарності гасить опади плавно» — це **процес**, тож він у `ZoneSimulation`,
двофазним переходом із явним порядком:

```
requestClouds(target):
  if target < OVERCAST and (precipF > 0 or thunderF > 0):
      # ФАЗА 1: хмарність НЕ рухається, поки опади й гроза не згаснуть
      state = FADING
      кожен тік:  precipF  −= 1 / transitions.precip_fade_ticks     # дефолт 600 тіків
                  thunderF −= 1 / transitions.thunder_fade_ticks
      # мертва зона у sanitize() доводить їх рівно до 0 і зупиняє фазу
  # ФАЗА 2: щойно precipF == 0 і thunderF == 0 — cloudsF повзе до target
  #         зі швидкістю 1 / transitions.cloud_fade_ticks
```

Плавно (600 тіків ≈ 30 с), детерміновано і **завершується** — бо мертва зона відсікає
хвіст, а не лишає його асимптотою.

### Розподіл розмірів зон і смуга бленду — виправлено після рев'ю

`radius = min + (max − min) · (u₁+u₂+u₃)/3` — Irwin–Hall n=3: дзвін із піком у центрі,
краї (64 і 1024) рідкі. Кількість доданків — `zones.size_bias_samples` у конфігу
(1 = рівномірний random, 5 = майже гаусів).

**Стара прив'язка смуги бленду до кроку сітки була несумісна з мінімальним радіусом.**
Я вимагав `band ≥ 2 × grid.step` = 96 при `grid.step = 48`, тоді як мінімальний радіус
зони — 64. Зона діаметром 128 зі смугою 96 не набирає повної інтенсивності **ніколи**:
ядра в неї просто немає.

Смуга тепер в'яжеться **до радіуса зони**, а крок сітки лише обмежує її знизу:

```
band = clamp(radius · zones.blend_fraction,  2 · grid.step,  radius · 0.5)
```

`blend_fraction` дефолт `0.35`. Множник `0.5` гарантує, що ядро повної інтенсивності
завжди є — щонайменше половина радіуса.

Щоб `clamp` мав розв'язок, потрібно `2 · grid.step ≤ radius_min · 0.5`, тобто
**`radius_min ≥ 4 × grid.step`**. Це єдиний інваріант, який валідує `ConfigManager`
(старий `blend_band_min ≥ 2 × grid.step` викидаю — він саме й породив конфлікт).

Дефолти доводжу до сумісності, зменшивши крок сітки замість підняття `radius_min`
(64 — вимога ТЗ, її не чіпаю):

| Параметр | Було | Стало | Чому |
|---|---|---|---|
| `grid.step` | 48 | **16** | `4 × 16 = 64 = radius_min` — рівно сходиться |
| `grid.half_extent` | 9 вузлів | **27 вузлів** | те саме покриття 864 блоки |
| `zones.min_radius` | 64 | 64 | вимога ТЗ, без змін |

Перевірка на краях: `radius = 64` → `band = clamp(22.4, 32, 32) = 32` ✓;
`radius = 512` → `clamp(179, 32, 256) = 179` ✓; `radius = 1024` → `clamp(358, 32, 512) = 358` ✓.

**Перерахунок трафіку** (крок 16 замість 48): повна сітка `55×55×8 B = 24.2 КБ` — разово
при вході. Дельта одного нового ряду `55 × 8 = 440 B` раз на 2 с → **~220 Б/с на гравця**
замість ~76. Утричі більше, і все одно мізер; повна сітка на вході — одноразові 24 КБ,
менші за один чанк.

### M3: що повертає сервер, коли троє гравців у різній погоді

`getRainLevel(float)` позиції не має, тож «значення в точці гравця» на сервері справді
не визначене. Відповідь: **сервер повертає максимум інтенсивності по всіх активних зонах
виміру**, і це значення означає рівно «опади є десь у цьому вимірі», а не «у тебе».

Чому максимум, а не середнє чи найближчий гравець: після наших міксинів `isRaining()`
лишається тільки **дозвільним гейтом** вище за позиційну перевірку. Середнє випадково
закривало б цей гейт («дощу немає ніде, бо більша частина карти ясна») і глушило б
позиційну логіку, яка мала б вирішувати. Максимум тримає гейт відкритим, а рішення
приймає `precipitationAt` (M2), `tickPrecipitation` (M9) і `tickThunder` (M10).

Що при цьому лишається справді глобальним — і це єдина ціна:
`ServerLevel.updateSkyBrightness()` → `getSkyDarken()`, тобто рівень освітлення для
перевірок спавну мобів. Поки десь у вимірі йде злива, поріг світла для спавну трохи
зсунутий по всьому виміру. Гравці цього **не бачать**: клієнтський M3 повертає локальне
значення, тож колір неба, туман і дощ у кадрі коректні.

Компроміс керований конфігом `gameplay.server_rain_level_mode`:

| Режим | Поведінка | Коли брати |
|---|---|---|
| `MAX` (дефолт) | максимум по зонах виміру | гейти відкриті, локальність вирішують позиційні хуки |
| `NEAREST_PLAYER` | значення в зоні найближчого гравця | одиночна гра / малий сервер, точніше затемнення |
| `OFF` | завжди `0` | ванільні системи вимкнені повністю, працюють лише наші хуки |

### Розмита межа при enum-опадах: дизеринг колонок

`getPrecipitationAt` повертає enum, а `intensity` — один скаляр на кадр. Поколонної
інтенсивності у 26.2 не існує, тож м'який край не можна зробити прозорістю. Роблю його
**щільністю колонок**.

Ванільний цикл extract і так відвідує кожну колонку `(x, z)` у радіусі. Наш M4 знає
локальну ймовірність опадів `p ∈ [0,1]` у цій колонці (значення зі смуги бленду) і
вмикає колонку, якщо

```
hash(x, z) / 2³² < p        # hash — стабільний позиційний, splitmix64(x, z)
```

Ключове: джерело випадковості — **позиційний хеш, а не RNG кадру**. Інакше колонки
мерехтіли б щокадру. Так набір увімкнених колонок для даної точки простору сталий, а
через смугу бленду їхня **частка** плавно росте 0 → 1. Читається як дизернутий край
стіни дощу — саме так справжня злива й виглядає зблизька.

Два незалежні регулятори, які не змішуються:

| Що | Звідки | Що дає |
|---|---|---|
| частка ввімкнених колонок | смуга бленду зони, просторово | м'яка **межа** |
| `intensity` (альфа + щільність) | вісь опадів, у часі | сила **дощу** |

Обмеження, яке визнаю одразу: крок дизерингу — 1 блок, тож на великій відстані візерунок
стає субпіксельним і читається як рівна напівпрозорість. Для стіни дощу на горизонті це
радше плюс, але «зернистого» краю здалеку не буде.

### Тривалість стану

`duration = base · exp(σ · gauss())`, клемп у `[min, max]`. Дефолт `base = 24000`, `σ = 0.6`
→ медіана ≈ ігрова доба, ~10% станів довші за 2 доби, окремі — до `max`. Усі чотири числа в конфігу.

### Переходи опадів

Матриця ймовірностей на сусідні рівні + окрема низька `skip_level_chance` на стрибок через
рівень. Після виходу з опадів — `stay_overcast_chance` (дефолт високий): світ спершу лишається
похмурим; `instant_clear_chance` — рідкісний прямий шлях у ясно.

### Туман

`FogRules` складає шанси чотирьох незалежних контекстів (кожен зі своїм шансом і вікном
у конфігу): після завершення опадів, світанок, близькість великої води, одночасність із
`DOWNPOUR`. Вісь лишається незалежною і може вмикатись сама по собі.

### Вертикальна межа

`altitudeFactor = 1 − smoothstep(cloudBottom, cloudTop, y)`, дефолти `192 / 224` з конфігу
(хмари ще перероблятимуться — не хардкодимо). Множить інтенсивності всіх осей; вище
`cloudTop` — чисте небо. Клієнт застосовує цей самий множник до вибірки з сітки, тому
сітка лишається двовимірною — це і є економія, заради якої обрано сітку.

### Кого вітер НЕ зачіпає — пропущена вимога, додано

Цієї вимоги ТЗ (§4) у плані не було ніде: ні в `WindPhysicsClient`, ні в припущеннях,
ні в конфігу. Виправляю.

`WindPhysicsClient` виходить **до** будь-яких обчислень, якщо виконано хоч одне:

| Умова | Джерело |
|---|---|
| гравець у режимі спектатора | `player.isSpectator()` |
| гравець у креативному польоті | абілки гравця, прапорець `flying` |
| сила вітру нижча за `wind.min_effect_strength` | конфіг, дефолт `0.05` |
| `wind.physics_enabled = false` | конфіг, аварійний вимикач |

Перевірка робиться один раз на тік у голові `WindPhysicsClient.tick()`, а не всередині
кожного ефекту — інакше її неминуче забудуть у котромусь із них (елітри, човен, знос
при ходьбі — три різні гілки).

Серверний `WindPhysicsServer` цих перевірок не потребує: він рухає мобів, стріли й
човни, а гравців не чіпає взагалі — їхній рух симулює клієнт.

Два імені для цих перевірок (`isSpectator`, доступ до абілок польоту) я **не підтвердив**
по джерелах 26.2 — вони йдуть тим самим одним ґрепом, що й перевірка елітр (див.
«Що лишилось: рівно два імені»; тепер там три виклики, одна команда).

### Нахил опадів

Кут = `atan(windStrength · render.max_tilt_tan)`, напрямок — з осі напрямку вітру.
Реалізується як зсув верхньої пари вершин квада відносно нижньої на
`(sin(dir), cos(dir)) · height · tan(кут)`. Тобто це **геометрія меша**, а не шейдер —
працює без кастомного шейдера, і при деградації під Sodium вимикається, не ламаючи решту.

### Sodium

`FabricLoader.getInstance().isModLoaded("sodium")` → `WARN` у лог при старті + одноразове
повідомлення в чат при вході (клієнтська перевірка, раз на сесію) + прапорець `degraded`,
що вимикає власний пайплайн і нахил, лишаючи модель погоди, звук і фізику вітру працювати.

---

## 6. Припущення

1. **Мапінги — офіційні Mojang** (дефолт Loom 1.17, підтверджено шаблоном 26.2).
2. **Modid `vibe_weather`**, base package `com.fand1l.vibeweather`, `environment: "*"`.
3. **Nether і End** — через перевірку `ResourceKey` виміру / `dimensionType()`;
   `ServerWeatherManager` там навіть не реєструється, погоди немає взагалі.
4. **Сніг замість дощу** — за температурою біома в точці, з тими самими 4 рівнями інтенсивності.
5. **Ванільна `/weather`** мапиться так: `clear` → `CLEAR/NONE/OFF`, `rain` → `OVERCAST/RAIN/OFF`,
   `thunder` → `OVERCAST/RAIN/NORMAL`, у радіус `command.vanilla_weather_radius` (дефолт 512)
   навколо виконавця, на вказану тривалість. Реалізується як зона з високим пріоритетом.
6. **`freeze`** морозить лише переходи станів; зони продовжують дрейфувати — інакше «заморозити
   погоду» зупинило б і вітер, що суперечить осі вітру.
7. **Конфіг** — `config/vibe_weather.json`, створюється з дефолтами при першому запуску,
   перечитується на `/reload`. Невідомі поля ігноруються, відсутні беруться з дефолтів.
8. **`/vibeweather` рівень 2**; `query` доступний усім (це лише інформація) — скажи, якщо
   теж треба рівень 2.
9. **Партикл смуг** — одна текстура, витягнутий квад по напрямку вітру, звичайний партикл-тип,
   без власного шейдера.

## 7. Ризики

| Ризик | Наслідок | Мітигація |
|---|---|---|
| **Імена класів 26.2** | Не компілюється / міксин не знаходить ціль | ✅ Знято раундом 1: усі ключові класи знайдено в реальних декомпільованих джерелах. Лишились сигнатури — раунд 2. |
| **Blaze3D API для власного пайплайна** | Був найбільшим ризиком | ✅ **Знято повністю.** Власний пайплайн не потрібен узагалі: беремо ванільні `RenderPipelines.WEATHER_DEPTH_WRITE` / `WEATHER_NO_DEPTH_WRITE`, `DefaultVertexFormat.PARTICLE`, `OutputTarget.WEATHER_TARGET` і ванільні текстури дощу/снігу. Ні свого шейдера, ні своїх уніформ, ні сирого GL. |
| **Рендер опадів на всю дальність** | Свій цикл із LOD писати не треба, але вартість лишається реальною: кількість колонок росте як квадрат радіуса, і при 32 чанках це просадка FPS | Механіку дає ваніль: радіус — клієнтська опція `options.weatherRadius()`, а extract уже перебирає весь радіус, тож ми лише підміняємо число (M5) і робимо `getPrecipitationAt` позиційним (M4). Проти вартості: спад щільності з відстанню через ту саму `p` у дизерингу колонок, жорсткий ліміт `render.max_columns` у конфігу, преаловані буфери. |
| **Роздільність сітки vs різкість межі** | Розмита стіна дощу | Інваріант `blend_band_min >= 2 × grid.step` з валідацією в конфігу; `grid.step` налаштовний. |
| **`isRaining()`/`isThundering()` глобальні за природою** | Затемнення неба й освітлення для спавну лишаються приблизними на сервері | M3 повертає значення з семпла в точці гравця: для клієнта точно, для серверних перевірок спавну — наближено. Прийнято усвідомлено, буде в README. |
| **Вітер на гравця vs античит руху** | `moved too quickly` / гумування | Прискорення застосовує **клієнт**, клемп нижче ванільного порогу, значення в конфігу з консервативними дефолтами. |
| **Sodium** | Краш або чорний екран | Не тестується (немає можливості), тому деградація агресивна: вимикаємо власний пайплайн і нахил, лишаємо модель, звук і фізику. |
| **Vulkan експериментальний** | Артефакти саме на Vulkan | Уся геометрія через Blaze3D, нуль сирих GL-викликів → переносимо між бекендами за визначенням. Перевірити на Vulkan зможеш тільки ти. |
| **`SavedData` зон росте** | Роздутий сейв | `zones.max_persisted` (512/вимір) + TTL для зон без гравців поблизу. |

---

