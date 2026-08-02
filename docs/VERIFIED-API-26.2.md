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
```

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

## Ще НЕ перевірено

Не використовувати, поки не підтверджено дампом:

- `SavedData` / `SavedDataType` — конструктор і як дістати сховище для **виміру**
  (у ванілі погода глобальна на сервер, тож `ServerLevel.getDataStorage()` не бачив);
- `CustomPacketPayload.Type` — як конструюється; `StreamCodec` / `ByteBufCodecs` для `byte[]`;
- `BuiltInRegistries.ENTITY_TYPE` — точний спосіб дістати тип за `Identifier`;
- перевірка польоту на елітрах (`isFallFlying` / `isGliding` на `LivingEntity`);
- `isSpectator()` і доступ до абілок польоту гравця;
- клас човна (обходимо через список ID реєстру в конфігу — імені не потребуємо).
