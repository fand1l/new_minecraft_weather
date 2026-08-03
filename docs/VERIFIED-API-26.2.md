# Підтверджені сигнатури 26.2

Усе нижче взято **з першоджерела** — декомпільованих `genSources` або вихідників FabricMC —
а не з пам'яті. Мапінги: офіційні Mojang (дефолт Loom 1.17).

Мета документа — не переоткривати те саме. Якщо чогось тут немає, значить воно **не
перевірене**, і писати його навмання не можна.

Джерела: `tools/verify-262-names.sh` (раунд 1), `tools/dump-262-api.sh` (2),
`tools/dump-262-api-3.sh` (3), `tools/dump-262-server-api.sh` (4), плюс прямі читання
`raw.githubusercontent.com/FabricMC/fabric`.

---

## Токен-факти

| Що | Значення |
|---|---|
| Ідентифікатор ресурсу | `net.minecraft.resources.Identifier` (**не** `ResourceLocation`) |
| Фабрики | `Identifier.fromNamespaceAndPath(ns, path)`, `Identifier.withDefaultNamespace(path)` |
| Рівень компіляції міксинів | `JAVA_25`, `overwrites.requireAnnotations: true` |
| Ґеймрул погодного циклу | `GameRules.ADVANCE_WEATHER` (перейменований з `doWeatherCycle`) |
| Пакет ґеймрулів | `net.minecraft.world.level.gamerules` (**не** `net.minecraft.world.level`) |

---

## Погода: сервер

```java
// net.minecraft.world.level.Level
protected float oRainLevel, rainLevel, oThunderLevel, thunderLevel;
public float getRainLevel(float partialTick);
public void  setRainLevel(float value);
public float getThunderLevel(float partialTick);   // = lerp(...) * getRainLevel(a)  ← в'яже грозу до дощу
public void  setThunderLevel(float value);
public boolean canHaveWeather();
public boolean isRaining();                        // getRainLevel(1.0F) > 0.2
public boolean isThundering();                     // getThunderLevel(1.0F) > 0.9
public boolean isRainingAt(BlockPos pos);          // precipitationAt(pos) == RAIN
public Biome.Precipitation precipitationAt(BlockPos pos);
```

Тіло `precipitationAt` — умови, які треба зберегти при заміні:

```java
if (!isRaining())                                              return NONE;
if (!canSeeSky(pos))                                           return NONE;
if (getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY() > pos.getY()) return NONE;
return getBiome(pos).value().getPrecipitationAt(pos, getSeaLevel());
```

```java
// net.minecraft.server.level.ServerLevel
private void advanceWeatherCycle();                // ціль M1; викликається у тіку рівня, рядок 365
public  void resetWeatherCycle();
public  void tickThunder(LevelChunk chunk);        // ціль M10
public  void tickPrecipitation(BlockPos pos);      // ціль M9; @VisibleForTesting, але public
public  WeatherData getWeatherData();              // делегує в server.getWeatherData()
protected BlockPos findLightningTargetAround(BlockPos pos);   // рядок 622 — потрібен @Shadow для M10
```

`getBlockRandomPos(...)` у `ServerLevel.java` лише викликається, оголошення немає — воно
десь у надкласі. Обходимо: випадкову точку в чанку рахуємо самі з
`chunkPos.getMinBlockX()/getMinBlockZ()` плюс `random.nextInt(16)`.

```java
// net.minecraft.server.MinecraftServer
public void setWeatherParameters(int clearTime, int rainTime, boolean raining, boolean thundering);
public WeatherData getWeatherData();

// net.minecraft.world.level.saveddata.WeatherData extends SavedData  — ГЛОБАЛЬНА на сервер, не на вимір
public static final Codec<WeatherData> CODEC;
public static final SavedDataType<WeatherData> TYPE =
        new SavedDataType<>(Identifier.withDefaultNamespace("weather"), WeatherData::new,
                            CODEC, DataFixTypes.SAVED_DATA_WEATHER);
```

**Ключовий момент по `tickThunder`** — три дощові гейти, усі треба обійти заради сухої грози:

```java
boolean raining = this.isRaining();                                  // (1)
if (raining && this.isThundering() && rnd.nextInt(100000) == 0) {    // (2)
    BlockPos pos = this.findLightningTargetAround(this.getBlockRandomPos(minX, 0, minZ, 15));
    if (this.isRainingAt(pos)) {                                     // (3)
        // EntityTypes.SKELETON_HORSE.create(this, EntitySpawnReason.EVENT)  — пастка
        // EntityTypes.LIGHTNING_BOLT.create(this, EntitySpawnReason.EVENT)
        // bolt.snapTo(Vec3.atBottomCenterOf(pos)); bolt.setVisualOnly(isTrap);
        // this.addFreshEntity(bolt);
    }
}
```

---

## Погода: клієнт

```java
// net.minecraft.client.multiplayer.ClientLevel  — ОКРЕМИЙ метод, інші умови ніж у Level
public Biome.Precipitation getPrecipitationAt(BlockPos pos) {
    if (!chunkSource.hasChunk(SectionPos.blockToSectionCoord(pos.getX()),
                              SectionPos.blockToSectionCoord(pos.getZ()))) return NONE;
    return getBiome(pos).value().getPrecipitationAt(pos, getSeaLevel());
}
public void tickWeatherEffects();     // бризки + звук; читає options.weatherRadius()
```

Звуки: `SoundEvents.WEATHER_RAIN`, `SoundEvents.WEATHER_RAIN_ABOVE`,
`SoundEvents.LIGHTNING_BOLT_THUNDER`, `SoundEvents.LIGHTNING_BOLT_IMPACT`.

```java
// net.minecraft.client.Options
private final OptionInstance<Integer> weatherRadius;      // рядок 205
public OptionInstance<Integer> weatherRadius();           // рядок 1038 — ціль M5
public OptionInstance<Integer> cloudRange();
public OptionInstance<Integer> renderDistance();
```

---

## Рендер опадів

```java
// net.minecraft.client.renderer.WeatherEffectRenderer implements AutoCloseable
public void extractRenderState(ClientLevel level, float partialTicks, Vec3 cameraPos,
                               WeatherRenderState renderState);
public void render(Vec3 cameraPos, WeatherRenderState renderState);      // ціль M6
public record ColumnInstance(int x, int z, int bottomY, int topY,
                             float uOffset, float vOffset, int lightCoords) {}

// net.minecraft.client.renderer.state.level.WeatherRenderState implements FabricRenderState
public final List<ColumnInstance> rainColumns, snowColumns;
public float intensity;    // = level.getRainLevel(partialTicks)
public int   radius;       // = Minecraft.getInstance().options.weatherRadius().get()
```

Усе, що треба для власного draw — ванільне, свого шейдера не потрібно:

```java
RenderPipelines.WEATHER_DEPTH_WRITE / WEATHER_NO_DEPTH_WRITE
OutputTarget.WEATHER_TARGET            // -> Minecraft.getInstance().levelRenderer.weatherTarget()
DefaultVertexFormat.PARTICLE
Identifier.withDefaultNamespace("textures/environment/rain.png"), ".../snow.png"
```

Геометрія колонки: верхня пара вершин має ті самі `x`/`z`, що й нижня, тобто стовп
вертикальний. **Нахил = зсув верхньої пари.**

---

## Туман

```java
// net.minecraft.client.renderer.fog.FogRenderer
public FogData setupFog(Camera camera, int renderDistanceInChunks, DeltaTracker deltaTracker,
                        float darkenWorldAmount, ClientLevel level);     // ціль M7, @Inject RETURN

// net.minecraft.client.renderer.fog.FogData implements FabricRenderState — усі поля public
public float environmentalStart, environmentalEnd, renderDistanceStart, renderDistanceEnd,
             skyEnd, cloudEnd;
public Vector4f color;
```

`FOG_ENVIRONMENTS` — **приватний** статичний список, публічної реєстрації немає.
Але `AtmosphericFogEnvironment` уже стискає туман від `level.getRainLevel()`
на `-160 / -256 * rainFogMultiplier`, тож туман від опадів дістається через M3 задарма.

---

## Партикли

```java
// ваніль
net.minecraft.client.particle.ParticleProvider<T extends ParticleOptions>
    @Nullable Particle createParticle(T options, ClientLevel level,
                                      double x, double y, double z,
                                      double xAux, double yAux, double zAux, RandomSource random);
net.minecraft.client.particle.SpriteSet          // get(int,int) / get(RandomSource) / first()
net.minecraft.client.particle.ParticleEngine
    public void add(Particle p);
net.minecraft.client.particle.ParticleRenderType // record(name, shorthand); SINGLE_QUADS, ...

// SingleQuadParticle extends Particle
protected float quadSize;                        // ОДИН розмір — стрічку розтягнути не можна
protected float roll, oRoll;                     // поворот навколо осі погляду — орієнтація за вітром
protected abstract SingleQuadParticle.Layer getLayer();
public record Layer(boolean translucent, Identifier textureAtlasLocation, RenderPipeline pipeline) {
    public static final Layer TRANSLUCENT;       // LOCATION_PARTICLES + TRANSLUCENT_PARTICLE
}
```

```java
// Fabric
ParticleProviderRegistry.getInstance()
    .register(ParticleType<T> type, ParticleProvider<T> provider);
    .register(ParticleType<T> type, PendingParticleProvider<T> ctor);
interface PendingParticleProvider<T extends ParticleOptions> { ParticleProvider<T> create(FabricSpriteSet s); }
interface FabricSpriteSet extends SpriteSet { TextureAtlas getAtlas(); List<TextureAtlasSprite> getSprites(); }
FabricParticleTypes.simple() -> SimpleParticleType
```

---

## Fabric API 0.156.0 — що ми викликаємо

```java
// мережа
PayloadTypeRegistry.playS2C() / playC2S() / configurationS2C() / configurationC2S()
        // -> PayloadTypeRegistry<RegistryFriendlyByteBuf> для play, <FriendlyByteBuf> для configuration
ServerPlayNetworking.registerGlobalReceiver(CustomPacketPayload.Type<T>, PlayPayloadHandler<T>);
ServerPlayNetworking.send(ServerPlayer player, CustomPacketPayload payload);
ServerPlayNetworking.canSend(ServerPlayer, CustomPacketPayload.Type<?>);
ClientPlayNetworking.registerGlobalReceiver(CustomPacketPayload.Type<T>, PlayPayloadHandler<T>);
ClientPlayNetworking.getSender();

// життєвий цикл
ServerTickEvents.START_SERVER_TICK / END_SERVER_TICK / START_LEVEL_TICK / END_LEVEL_TICK
ClientTickEvents.START_CLIENT_TICK / END_CLIENT_TICK / START_LEVEL_TICK / END_LEVEL_TICK
ServerLifecycleEvents.SERVER_STARTING / SERVER_STARTED / SERVER_STOPPING / SERVER_STOPPED
                     / BEFORE_SAVE / AFTER_SAVE / START_DATA_PACK_RELOAD / END_DATA_PACK_RELOAD
ServerPlayConnectionEvents.INIT / JOIN / DISCONNECT      // JOIN: (listener, sender, server)

// команди
CommandRegistrationCallback  // (dispatcher, CommandBuildContext, Commands.CommandSelection)
```

Ваніль, ціль M8:

```java
// net.minecraft.server.commands.WeatherCommand
public static void register(CommandDispatcher<CommandSourceStack> dispatcher);
// всередині: Commands.literal("weather").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
//            Commands.argument("duration", TimeArgument.time(1))
```

---

## Геймплей, на який ми спираємось

```java
// net.minecraft.world.level.biome.Biome
public boolean hasPrecipitation();
public Biome.Precipitation getPrecipitationAt(BlockPos pos, int seaLevel);
public boolean coldEnoughToSnow(BlockPos pos, int seaLevel);
public boolean warmEnoughToRain(BlockPos pos, int seaLevel);
public enum Precipitation { NONE, RAIN, SNOW }

// net.minecraft.world.level.block.LayeredCauldronBlock
public void handlePrecipitation(BlockState state, Level level, BlockPos pos, Biome.Precipitation p);

// net.minecraft.world.entity.Entity
public Vec3 getDeltaMovement();
public void setDeltaMovement(Vec3 v);
public void setDeltaMovement(double x, double y, double z);
public void addDeltaMovement(Vec3 momentum);
public final boolean hasControllingPassenger();

// накопичення снігу у ServerLevel.tickPrecipitation
GameRules.MAX_SNOW_ACCUMULATION_HEIGHT, SnowLayerBlock.LAYERS, Block.pushEntitiesUp(...)
```

---

## Персистенція

```java
// net.minecraft.world.level.saveddata.SavedData
public abstract class SavedData {
    public void setDirty();  public void setDirty(boolean);  public boolean isDirty();
}

// record — рівно чотири компоненти, без перевантажень
public record SavedDataType<T extends SavedData>(Identifier id, Supplier<T> constructor,
                                                 Codec<T> codec, DataFixTypes dataFixType) {}

// net.minecraft.server.level.ServerLevel — сховище Є на рівень виміру
public SavedDataStorage getDataStorage();          // = getChunkSource().getDataStorage()
// вжиток у ванілі: getDataStorage().computeIfAbsent(Raids.TYPE)
//                  getDataStorage().computeIfAbsent(WorldBorder.TYPE)
```

## Мережа

```java
// net.minecraft.network.protocol.common.custom.CustomPacketPayload
record Type<T extends CustomPacketPayload>(Identifier id) {}
static <T> Type<T> createType(String id);      // УВАГА: підставляє withDefaultNamespace -> minecraft:
                                               // для свого namespace конструюємо Type напряму
static <B extends ByteBuf, T> StreamCodec<B, T> codec(StreamMemberEncoder<B,T> w, StreamDecoder<B,T> r);

// net.minecraft.network.codec.ByteBufCodecs
StreamCodec<ByteBuf, byte[]>  BYTE_ARRAY;
StreamCodec<ByteBuf, Integer> INT, VAR_INT;
StreamCodec<ByteBuf, Float>   FLOAT;
StreamCodec<ByteBuf, Boolean> BOOL;

// net.minecraft.network.codec.StreamCodec
static <B,V> StreamCodec<B,V> of(StreamEncoder<B,V>, StreamDecoder<B,V>);
static composite(...)   // перевантаження на 1..12 компонентів

// Fabric
PayloadTypeRegistry.clientboundPlay() / serverboundPlay()      // <RegistryFriendlyByteBuf>
    <T> CustomPacketPayload.TypeAndCodec<? super B, T> register(Type<T>, StreamCodec<? super B, T>);
    <T> ... registerLarge(Type<T>, StreamCodec<? super B, T>, int maxPacketSize);
```

`registerLarge` — саме те, що треба для повної сітки: 24 КБ інакше впирається у ванільний
ліміт розміру пакета. Дельти йдуть звичайним `register`.

## Команди — рівні прав більше НЕ цілі числа

```java
// net.minecraft.commands.Commands
public static final PermissionCheck LEVEL_ALL, LEVEL_MODERATORS, LEVEL_GAMEMASTERS,
                                    LEVEL_ADMINS, LEVEL_OWNERS;
// LEVEL_GAMEMASTERS = new PermissionCheck.Require(Permissions.COMMANDS_GAMEMASTER)
public static LiteralArgumentBuilder<CommandSourceStack> literal(String literal);
public static <T> RequiredArgumentBuilder<CommandSourceStack, T> argument(String name, ArgumentType<T> type);
public static <T extends PermissionSetSupplier> PermissionProviderCheck<T> hasPermission(PermissionCheck permission);

// net.minecraft.commands.CommandSourceStack
public Vec3 getPosition();
public ServerLevel getLevel();
public @Nullable Entity getEntity();
public ServerPlayer getPlayerOrException() throws CommandSyntaxException;
public void sendSuccess(Supplier<Component> messageSupplier, boolean broadcast);
public void sendFailure(Component message);
```

Доступні типи аргументів: `Vec3Argument`, `BlockPosArgument`, `AngleArgument`,
`IdentifierArgument`, `DimensionArgument`, `TimeArgument`, плюс брігадірівські
`FloatArgumentType`, `IntegerArgumentType`, `StringArgumentType`.

## Реєстри

```java
BuiltInRegistries.ENTITY_TYPE   : DefaultedRegistry<EntityType<?>>   // дефолт "pig"!
BuiltInRegistries.PARTICLE_TYPE : Registry<ParticleType<?>>

// net.minecraft.core.Registry
default Optional<T> getOptional(@Nullable Identifier key);
Optional<Holder.Reference<T>> get(Identifier id);
```

`ENTITY_TYPE` — **defaulted**, тобто на невідомий ID поверне свиню. Для конфігу треба
`getOptional(...)`, щоб друкарську помилку було видно, а не мовчки отримати свиню.

## Сутності й рівень

```java
// net.minecraft.world.entity.Entity
public boolean isSpectator();                       // рядок 345
public @Nullable LivingEntity getControllingPassenger();
public final boolean hasControllingPassenger();
public Vec3 position();

// net.minecraft.world.entity.LivingEntity
public boolean isFallFlying();                      // рядок 3630 — політ на елітрах
public int getFallFlyingTicks();
public void stopFallFlying();
// також Pose.FALL_FLYING, DataComponents.GLIDER, BlockTags.CAN_GLIDE_THROUGH

// net.minecraft.world.entity.player.Player
public Abilities getAbilities();                    // рядок 1291
public boolean isSpectator();                       // перевизначає Entity
public boolean isCreative();

// net.minecraft.world.entity.player.Abilities  — публічні ПОЛЯ, не геттери
public boolean invulnerable, flying, mayfly, instabuild, mayBuild;

// човни — переїхали в підпакет boat
net.minecraft.world.entity.vehicle.boat.{AbstractBoat, Boat, Raft, ChestBoat, ChestRaft, AbstractChestBoat}

// net.minecraft.world.level.Level
public boolean isClientSide();
public DimensionType dimensionType();
public boolean canHaveWeather();   // = dimensionType().hasSkyLight() && !hasCeiling() && dimension() != END

// net.minecraft.server.level.ServerLevel
public List<ServerPlayer> players();
public List<ServerPlayer> getPlayers(Predicate<? super ServerPlayer> selector);
public long getSeed();
public MinecraftServer getServer();

// net.minecraft.world.entity.EntityType
public @Nullable T create(Level level, EntitySpawnReason reason);
```

**Nether і End задарма**: `Level.canHaveWeather()` уже виключає їх — власної перевірки
вимірів писати не треба.

## Render-state API (підтверджує рішення не використовувати його для погоди)

```java
// net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey<T>
public static <T> RenderStateDataKey<T> create(Supplier<String> debugName);
public static <T> RenderStateDataKey<T> create();
// разом із FabricRenderState.getData(key) / setData(key, value)
```

`LevelRenderEvents` має фази `START_MAIN`, `AFTER_OPAQUE_TERRAIN`, `COLLECT_SUBMITS`,
`AFTER_SOLID_FEATURES`, `AFTER_TRANSLUCENT_FEATURES`, `BEFORE_BLOCK_OUTLINE`,
`BEFORE_GIZMOS`, `BEFORE_TRANSLUCENT_TERRAIN`, `AFTER_TRANSLUCENT_TERRAIN`, `END_MAIN`.
**Фази для погоди серед них немає** — вона малюється окремим frame-graph-проходом у свій
таргет. Тому M6 лишається міксином, як і планувалось.

## Персистенція для мода: підтверджена конвенція

`SavedDataType` вимагає `DataFixTypes`, а той enum складається лише з ванільних значень.
Розв'язок узято не з здогаду, а з бойового коду Fabric API
(`fabric-data-attachment-api-v1`, `mixin/attachment/ServerLevelMixin.java`):

```java
var type = new SavedDataType<>(
        AttachmentSavedData.ID,                  // Identifier.fromNamespaceAndPath("fabric", "attachments")
        () -> new AttachmentSavedData(level),
        AttachmentSavedData.codec(level),
        null // Object builder API 12.1.0 and later makes this a no-op
);
level.getDataStorage().computeIfAbsent(type);
```

Тобто:

- `null` як `DataFixTypes` — **санкціонована** модова конвенція, Fabric API робить так у
  продакшені;
- працює це завдяки `fabric-object-builder-api-v1>=12.1.0`, який робить цей шлях no-op.
  У нас `24.1.0` — із великим запасом;
- `ServerLevel.getDataStorage().computeIfAbsent(type)` — персистенція **на вимір**, як і
  потрібно;
- власний namespace в `Identifier` проходить нормально.

---

## Ґеймрули — 26.2 винесла їх в окремий пакет

Джерело: FabricMC/fabric гілка `26.2` (`gradle.properties`: `minecraft_version=26.2`,
`version=0.156.0`), модуль `fabric-game-rule-api-v1` — тобто код, який компілюється саме проти
нашої версії, і теж на офіційних мапінгах Mojang.

```java
// Пакет — з fabric-game-rule-api-v1.classtweaker (там повні шляхи класів):
//     accessible class net/minecraft/world/level/gamerules/GameRules$VisitorCaller
import net.minecraft.world.level.gamerules.GameRule;          // сам тип правила, generic: GameRule<T>
import net.minecraft.world.level.gamerules.GameRuleCategory;
import net.minecraft.world.level.gamerules.GameRules;         // тримач констант

// Форма доступу — з GameRuleChangeCallbackGameTest.java, дослівно:
GameRules gameRules = serverLevel.getGameRules();             // ServerLevel#getGameRules() -> GameRules
boolean fireDamage = gameRules.get(GameRules.FIRE_DAMAGE);    // get(GameRule<T>) -> T
gameRules.set(GameRules.FIRE_DAMAGE, fireDamage, server);     // set(GameRule<T>, T, MinecraftServer)
```

Стиль імен констант: `GameRules.FIRE_DAMAGE` — без старого префікса `RULE_`. Але
`doWeatherCycle` → `ADVANCE_WEATHER` показує, що частину правил ще й **перейменували**, а не
просто перевели в SCREAMING_SNAKE. Тож саму назву конкретної константи вгадати не можна.

Бонусом підтверджено з тих самих файлів: `net.minecraft.server.commands.GameRuleCommand`,
`net.minecraft.server.jsonrpc.methods.GameRulesService`, `MinecraftServer#onGameRuleChanged(GameRule, Object)`.

---

## Спростовано компілятором

| Що я написав | Результат | Обхід |
|---|---|---|
| `ResourceKey.location()` | **не існує** у 26.2 — `cannot find symbol` на `ResourceKey<Level>`. Ім'я, найімовірніше, змінилось разом із `ResourceLocation` → `Identifier`, але нове я не перевіряв | Метод не потрібен: у лог пишемо сам `level.dimension()`, для сіда беремо `Hashing.hashString(level.dimension().toString())` |
| `net.minecraft.world.level.GameRules` | **не той пакет** — `cannot find symbol: class GameRules`. Клас нікуди не дівся, переїхав у `net.minecraft.world.level.gamerules` | Виправлено за секцією вище |
| `net.minecraft.world.entity.animal.horse` | **пакета не існує** — `package ... does not exist`. Кінські класи переїхали | `net.minecraft.world.entity.animal.equine.SkeletonHorse` (`./tools/find-class.sh`) |

Спільне в усіх трьох: **клас або метод нікуди не дівся, змінилось лише його місце**. Тому
`find-class.sh` і `show-source.sh` закривають цей клас помилок повністю, а пам'ять про
старіші версії — ні.

Урок: чотири раунди дампів підтвердили те, що я **шукав**, але `ResourceKey` серед цілей не
було — у дампах він трапляється лише як аргумент, ніколи з викликом методу. Відсутність у
довіднику означає «не перевірено», і саме так це й спрацювало.

---

## `ServerLevel#tickThunder` — реальне тіло 26.2

Ціль M10, прочитана дослівно (`./tools/show-source.sh ServerLevel tickThunder`, рядки 541–575).
Записано повністю, бо міксин замінює цей метод, і будь-яка розбіжність — це тиха зміна
ванільної механіки:

```java
public void tickThunder(final LevelChunk chunk) {
    ChunkPos chunkPos = chunk.getPos();
    boolean raining = this.isRaining();
    int minX = chunkPos.getMinBlockX();
    int minZ = chunkPos.getMinBlockZ();
    Profiler.get().push("thunder");
    if (raining && this.isThundering() && this.random.nextInt(100000) == 0) {
        BlockPos pos = this.findLightningTargetAround(this.getBlockRandomPos(minX, 0, minZ, 15));
        if (this.isRainingAt(pos)) {
            DifficultyInstance difficulty = this.getCurrentDifficultyAt(pos);
            boolean isTrap = this.getGameRules().get(GameRules.SPAWN_MOBS)
                    && this.random.nextDouble() < difficulty.getEffectiveDifficulty() * 0.01
                    && !this.getBlockState(pos.below()).is(BlockTags.LIGHTNING_RODS);
            if (isTrap) {
                SkeletonHorse horse = EntityTypes.SKELETON_HORSE.create(this, EntitySpawnReason.EVENT);
                if (horse != null) { horse.setTrap(true); horse.setAge(0);
                                     horse.setPos(pos.getX(), pos.getY(), pos.getZ());
                                     this.addFreshEntity(horse); }
            }
            LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(this, EntitySpawnReason.EVENT);
            if (bolt != null) { bolt.snapTo(Vec3.atBottomCenterOf(pos));
                                bolt.setVisualOnly(isTrap); this.addFreshEntity(bolt); }
        }
    }
    Profiler.get().pop();
}
```

Звідси підтверджено: `GameRules.SPAWN_MOBS` (`GameRule<Boolean>`, зареєстрований як
`spawn_mobs` у категорії `SPAWNING`), `net.minecraft.world.entity.animal.equine.SkeletonHorse`
з `setTrap`/`setAge`/`setPos`, `Level#getBlockRandomPos(int,int,int,int)`, частота **1/100000
на чанк за тік**, множник складності **0.01**, і те, що `&&` тут короткозамикає в порядку
ґеймрул → кидок → громовідвід.

Що M10 свідомо робить інакше й чому: гейт на вісь грози замість трьох дощових; частота під
`WEAK`/`NORMAL`; власний сід-генератор замість `this.random`; секцію профайлера пропущено
(`Profiler` — ще один непрочитаний пакет, а поведінки вона не змінює).

---

## Інструменти

| Скрипт | Питання, на яке відповідає |
|---|---|
| `tools/find-class.sh Foo` | у якому пакеті лежить клас |
| `tools/show-source.sh Foo bar` | що насправді написано в методі/класі |
| `tools/dump-262-*.sh` | пакетні дампи цілих зрізів API (раунди 1–4) |
| `tools/run-model-harness.sh` | 134 перевірки чистої моделі без гри |
