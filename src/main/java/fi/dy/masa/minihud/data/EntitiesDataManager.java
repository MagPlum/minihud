package fi.dy.masa.minihud.data;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.tuple.Pair;

import com.mojang.datafixers.util.Either;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.DoubleInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import fi.dy.masa.malilib.interfaces.IClientTickHandler;
import fi.dy.masa.malilib.interfaces.IDataSyncer;
import fi.dy.masa.malilib.mixin.IMixinAbstractHorseEntity;
import fi.dy.masa.malilib.mixin.IMixinPiglinEntity;
import fi.dy.masa.malilib.network.ClientPlayHandler;
import fi.dy.masa.malilib.network.IPluginClientPlayHandler;
import fi.dy.masa.malilib.util.Constants;
import fi.dy.masa.malilib.util.InventoryUtils;
import fi.dy.masa.malilib.util.WorldUtils;
import fi.dy.masa.malilib.util.nbt.NbtKeys;
import fi.dy.masa.minihud.MiniHUD;
import fi.dy.masa.minihud.Reference;
import fi.dy.masa.minihud.config.Configs;
import fi.dy.masa.minihud.mixin.IMixinDataQueryHandler;
import fi.dy.masa.minihud.network.ServuxEntitiesHandler;
import fi.dy.masa.minihud.network.ServuxEntitiesPacket;
import fi.dy.masa.minihud.util.DataStorage;
import fi.dy.masa.minihud.util.EntityUtils;

@SuppressWarnings("deprecation")
public class EntitiesDataManager implements IClientTickHandler, IDataSyncer
{
    private static final EntitiesDataManager INSTANCE = new EntitiesDataManager();

    public static EntitiesDataManager getInstance()
    {
        return INSTANCE;
    }

    private final static ServuxEntitiesHandler<ServuxEntitiesPacket.Payload> HANDLER = ServuxEntitiesHandler.getInstance();
    private final static MinecraftClient mc = MinecraftClient.getInstance();
    private int uptimeTicks = 0;
    private boolean servuxServer = false;
    private boolean hasInValidServux = false;
    private String servuxVersion;

    // Data Cache
    private final ConcurrentHashMap<BlockPos, Pair<Long, Pair<BlockEntity, NbtCompound>>> blockEntityCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer,  Pair<Long, Pair<Entity,      NbtCompound>>> entityCache      = new ConcurrentHashMap<>();
    //private final long cacheTimeout = 4;
    private long serverTickTime = 0;
    // Requests to be executed
    private final Set<BlockPos> pendingBlockEntitiesQueue = new LinkedHashSet<>();
    private final Set<Integer> pendingEntitiesQueue = new LinkedHashSet<>();
    // To save vanilla query packet transaction
    private final Map<Integer, Either<BlockPos, Integer>> transactionToBlockPosOrEntityId = new HashMap<>();
    private ClientWorld clientWorld;

    @Nullable
    @Override
    public World getWorld()
    {
        return WorldUtils.getBestWorld(mc);
    }

    @Override
    public ClientWorld getClientWorld()
    {
        if (this.clientWorld == null)
        {
            clientWorld = mc.world;
        }

        return clientWorld;
    }

    private EntitiesDataManager() { }

    @Override
    public void onClientTick(MinecraftClient mc)
    {
        long now = System.currentTimeMillis();
        this.uptimeTicks++;

        if (now - this.serverTickTime > 50)
        {
            // In this block, we do something every server tick
            if (Configs.Generic.ENTITY_DATA_SYNC.getBooleanValue() == false)
            {
                this.serverTickTime = now;
                if (DataStorage.getInstance().hasIntegratedServer() == false && this.hasServuxServer())
                {
                    this.servuxServer = false;
                    HANDLER.unregisterPlayReceiver();
                }
                return;
            }
            else if (DataStorage.getInstance().hasIntegratedServer() == false &&
                    this.hasServuxServer() == false &&
                    this.hasInValidServux == false &&
                    this.getWorld() != null)
            {
                // Make sure we're Play Registered, and request Metadata
                HANDLER.registerPlayReceiver(ServuxEntitiesPacket.Payload.ID, HANDLER::receivePlayPayload);
                this.requestMetadata();
            }

            // Expire cached NBT
            this.tickCache(now);

            // 5 queries / server tick
            for (int i = 0; i < Configs.Generic.SERVER_NBT_REQUEST_RATE.getIntegerValue(); i++)
            {
                if (!this.pendingBlockEntitiesQueue.isEmpty())
                {
                    var iter = this.pendingBlockEntitiesQueue.iterator();
                    BlockPos pos = iter.next();
                    iter.remove();
                    if (this.hasServuxServer())
                    {
                        requestServuxBlockEntityData(pos);
                    }
                    else
                    {
                        requestQueryBlockEntity(pos);
                    }
                }
                if (!this.pendingEntitiesQueue.isEmpty())
                {
                    var iter = this.pendingEntitiesQueue.iterator();
                    int entityId = iter.next();
                    iter.remove();
                    if (this.hasServuxServer())
                    {
                        requestServuxEntityData(entityId);
                    }
                    else
                    {
                        requestQueryEntityData(entityId);
                    }
                }
            }

            this.serverTickTime = now;
        }
    }

    public Identifier getNetworkChannel()
    {
        return ServuxEntitiesHandler.CHANNEL_ID;
    }

    private static ClientPlayNetworkHandler getVanillaHandler()
    {
        if (mc.player != null)
        {
            return mc.player.networkHandler;
        }

        return null;
    }

    public IPluginClientPlayHandler<ServuxEntitiesPacket.Payload> getNetworkHandler()
    {
        return HANDLER;
    }

    @Override
    public void reset(boolean isLogout)
    {
        if (isLogout)
        {
            MiniHUD.printDebug("EntitiesDataStorage#reset() - log-out");
            HANDLER.reset(this.getNetworkChannel());
            HANDLER.resetFailures(this.getNetworkChannel());
            this.servuxServer = false;
            this.hasInValidServux = false;
        }
        else
        {
            MiniHUD.printDebug("EntitiesDataStorage#reset() - dimension change or log-in");
            long now = System.currentTimeMillis();
            this.serverTickTime = now - (this.getCacheTimeout() + 5000L);
            this.tickCache(now);
            this.serverTickTime = now;
            this.clientWorld = mc.world;
        }
        // Clear data
        this.blockEntityCache.clear();
        this.entityCache.clear();
        this.pendingBlockEntitiesQueue.clear();
        this.pendingEntitiesQueue.clear();
    }

    public long getCacheTimeout()
    {
        return (long) (MathHelper.clamp(Configs.Generic.ENTITY_DATA_SYNC_CACHE_TIMEOUT.getFloatValue(), 0.25f, 25.0f) * 1000L);
    }

    private void tickCache(long nowTime)
    {
        long blockTimeout = this.getCacheTimeout();
        long entityTimeout = this.getCacheTimeout();
        //int total = this.blockEntityCache.size();
        //int count = 0;

        synchronized (this.blockEntityCache)
        {
            for (BlockPos pos : this.blockEntityCache.keySet())
            {
                Pair<Long, Pair<BlockEntity, NbtCompound>> pair = this.blockEntityCache.get(pos);

                if (nowTime - pair.getLeft() > blockTimeout || pair.getLeft() > nowTime)
                {
                    MiniHUD.printDebug("entityCache: be at pos [{}] has timed out by [{}] ms", pos.toShortString(), blockTimeout);
                    this.blockEntityCache.remove(pos);
                    //count++;
                }
            }
        }
        //total = this.entityCache.size();
        //count = 0;

        synchronized (this.entityCache)
        {
            for (Integer entityId : this.entityCache.keySet())
            {
                Pair<Long, Pair<Entity, NbtCompound>> pair = this.entityCache.get(entityId);

                if (nowTime - pair.getLeft() > entityTimeout || pair.getLeft() > nowTime)
                {
                    MiniHUD.printDebug("entityCache: enity Id [{}] has timed out by [{}] ms", entityId, entityTimeout);
                    this.entityCache.remove(entityId);
                    //count++;
                }
            }
        }
    }

    @Override
    public @Nullable NbtCompound getFromBlockEntityCacheNbt(BlockPos pos)
    {
        if (this.blockEntityCache.containsKey(pos))
        {
            return this.blockEntityCache.get(pos).getRight().getRight();
        }

        return null;
    }

    @Override
    public @Nullable BlockEntity getFromBlockEntityCache(BlockPos pos)
    {
        if (this.blockEntityCache.containsKey(pos))
        {
            return this.blockEntityCache.get(pos).getRight().getLeft();
        }

        return null;
    }

    @Override
    public @Nullable NbtCompound getFromEntityCacheNbt(int entityId)
    {
        if (this.entityCache.containsKey(entityId))
        {
            return this.entityCache.get(entityId).getRight().getRight();
        }

        return null;
    }

    @Override
    public @Nullable Entity getFromEntityCache(int entityId)
    {
        if (this.entityCache.containsKey(entityId))
        {
            return this.entityCache.get(entityId).getRight().getLeft();
        }

        return null;
    }

    public void setIsServuxServer()
    {
        this.servuxServer = true;
        this.hasInValidServux = false;
    }

    public boolean hasServuxServer()
    {
        return this.servuxServer;
    }

    public void setServuxVersion(String ver)
    {
        if (ver != null && ver.isEmpty() == false)
        {
            this.servuxVersion = ver;
            MiniHUD.printDebug("entityDataChannel: joining Servux version {}", ver);
        }
        else
        {
            this.servuxVersion = "unknown";
        }
    }

    public String getServuxVersion()
    {
        return servuxVersion;
    }

    public int getPendingBlockEntitiesCount()
    {
        return this.pendingBlockEntitiesQueue.size();
    }

    public int getPendingEntitiesCount()
    {
        return this.pendingEntitiesQueue.size();
    }

    public int getBlockEntityCacheCount()
    {
        return this.blockEntityCache.size();
    }

    public int getEntityCacheCount()
    {
        return this.entityCache.size();
    }

    @Override
    public void onGameInit()
    {
        ClientPlayHandler.getInstance().registerClientPlayHandler(HANDLER);
        HANDLER.registerPlayPayload(ServuxEntitiesPacket.Payload.ID, ServuxEntitiesPacket.Payload.CODEC, IPluginClientPlayHandler.BOTH_CLIENT);
    }

    @Override
    public void onWorldPre()
    {
        if (DataStorage.getInstance().hasIntegratedServer() == false)
        {
            HANDLER.registerPlayReceiver(ServuxEntitiesPacket.Payload.ID, HANDLER::receivePlayPayload);
        }
    }

    @Override
    public void onWorldJoin()
    {
        // NO-OP
    }

    public void requestMetadata()
    {
        if (DataStorage.getInstance().hasIntegratedServer() == false &&
            Configs.Generic.ENTITY_DATA_SYNC.getBooleanValue())
        {
            NbtCompound nbt = new NbtCompound();
            nbt.putString("version", Reference.MOD_STRING);

            HANDLER.encodeClientData(ServuxEntitiesPacket.MetadataRequest(nbt));
        }
    }

    public boolean receiveServuxMetadata(NbtCompound data)
    {
        if (DataStorage.getInstance().hasIntegratedServer() == false)
        {
            MiniHUD.printDebug("EntitiesDataStorage#receiveServuxMetadata(): received METADATA from Servux");

            if (Configs.Generic.ENTITY_DATA_SYNC.getBooleanValue())
            {
                if (data.getInt("version") != ServuxEntitiesPacket.PROTOCOL_VERSION)
                {
                    MiniHUD.logger.warn("entityDataChannel: Mis-matched protocol version!");
                }

                this.setServuxVersion(data.getString("servux"));
                this.setIsServuxServer();

                return true;
            }
        }

        return false;
    }

    public void onPacketFailure()
    {
        this.servuxServer = false;
        this.hasInValidServux = true;
    }

    @Override
    public @Nullable Pair<BlockEntity, NbtCompound> requestBlockEntity(World world, BlockPos pos)
    {
        if (this.blockEntityCache.containsKey(pos))
        {
            return this.blockEntityCache.get(pos).getRight();
        }
        else if (world.getBlockState(pos).getBlock() instanceof BlockEntityProvider)
        {
            if (!DataStorage.getInstance().hasIntegratedServer() &&
                Configs.Generic.ENTITY_DATA_SYNC.getBooleanValue())
            {
                this.pendingBlockEntitiesQueue.add(pos);
            }

            BlockEntity be = world.getWorldChunk(pos).getBlockEntity(pos);

            if (be != null)
            {
                NbtCompound nbt = be.createNbtWithIdentifyingData(world.getRegistryManager());
                Pair<BlockEntity, NbtCompound> pair = Pair.of(be, nbt);

                synchronized (this.blockEntityCache)
                {
                    this.blockEntityCache.put(pos, Pair.of(System.currentTimeMillis(), pair));
                }
                return pair;
            }
        }

        return null;
    }

    @Override
    public @Nullable Pair<Entity, NbtCompound> requestEntity(World world, int entityId)
    {
        if (this.entityCache.containsKey(entityId))
        {
            return this.entityCache.get(entityId).getRight();
        }

        if (!DataStorage.getInstance().hasIntegratedServer() &&
            Configs.Generic.ENTITY_DATA_SYNC.getBooleanValue())
        {
            this.pendingEntitiesQueue.add(entityId);
        }

        if (this.getWorld() != null)
        {
            Entity entity = this.getWorld().getEntityById(entityId);
            NbtCompound nbt = new NbtCompound();

            if (entity != null && entity.saveSelfNbt(nbt))
            {
                Pair<Entity, NbtCompound> pair = Pair.of(entity, nbt);

                synchronized (this.entityCache)
                {
                    this.entityCache.put(entityId, Pair.of(System.currentTimeMillis(), pair));
                }

                return pair;
            }
        }

        return null;
    }

    @Nullable
    @Override
    public Inventory getBlockInventory(World world, BlockPos pos, boolean useNbt)
    {
        if (this.blockEntityCache.containsKey(pos))
        {
            Inventory inv = null;

            if (useNbt)
            {
                inv = InventoryUtils.getNbtInventory(this.blockEntityCache.get(pos).getRight().getRight(), -1, world.getRegistryManager());
            }
            else
            {
                BlockEntity be = this.blockEntityCache.get(pos).getRight().getLeft();

                if (be instanceof Inventory inv1)
                {
                    if (be instanceof ChestBlockEntity)
                    {
                        BlockState state = world.getBlockState(pos);
                        ChestType type = state.get(ChestBlock.CHEST_TYPE);

                        if (type != ChestType.SINGLE)
                        {
                            BlockPos posAdj = pos.offset(ChestBlock.getFacing(state));
                            if (!world.isChunkLoaded(posAdj)) return null;
                            BlockState stateAdj = world.getBlockState(posAdj);

                            var dataAdj = this.getFromBlockEntityCache(posAdj);

                            if (dataAdj == null)
                            {
                                this.requestBlockEntity(world, posAdj);
                            }

                            if (stateAdj.getBlock() == state.getBlock() &&
                                dataAdj instanceof ChestBlockEntity inv2 &&
                                stateAdj.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE &&
                                stateAdj.get(ChestBlock.FACING) == state.get(ChestBlock.FACING))
                            {
                                Inventory invRight = type == ChestType.RIGHT ? inv1 : inv2;
                                Inventory invLeft = type == ChestType.RIGHT ? inv2 : inv1;

                                inv = new DoubleInventory(invRight, invLeft);
                            }
                        }
                        else
                        {
                            inv = inv1;
                        }
                    }
                    else
                    {
                        inv = inv1;
                    }
                }
            }

            if (inv != null)
            {
                return inv;
            }
        }

        if (Configs.Generic.ENTITY_DATA_SYNC.getBooleanValue())
        {
            this.requestBlockEntity(world, pos);
        }

        return null;
    }

    @Nullable
    @Override
    public Inventory getEntityInventory(World world, int entityId, boolean useNbt)
    {
        if (this.entityCache.containsKey(entityId) && this.getWorld() != null)
        {
            Inventory inv = null;

            if (useNbt)
            {
                inv = InventoryUtils.getNbtInventory(this.entityCache.get(entityId).getRight().getRight(), -1, this.getWorld().getRegistryManager());
            }
            else
            {
                Entity entity = this.entityCache.get(entityId).getRight().getLeft();

                if (entity instanceof Inventory)
                {
                    inv = (Inventory) entity;
                }
                else if (entity instanceof PlayerEntity player)
                {
                    inv = new SimpleInventory(player.getInventory().main.toArray(new ItemStack[36]));
                }
                else if (entity instanceof VillagerEntity)
                {
                    inv = ((VillagerEntity) entity).getInventory();
                }
                else if (entity instanceof AbstractHorseEntity)
                {
                    inv = ((IMixinAbstractHorseEntity) entity).malilib_getHorseInventory();
                }
                else if (entity instanceof PiglinEntity)
                {
                    inv = ((IMixinPiglinEntity) entity).malilib_getInventory();
                }
            }

            if (inv != null)
            {
                return inv;
            }
        }

        if (Configs.Generic.ENTITY_DATA_SYNC.getBooleanValue())
        {
            this.requestEntity(this.getWorld(), entityId);
        }

        return null;
    }

    private void requestQueryBlockEntity(BlockPos pos)
    {
        if (Configs.Generic.ENTITY_DATA_SYNC_BACKUP.getBooleanValue() == false)
        {
            return;
        }

        ClientPlayNetworkHandler handler = getVanillaHandler();

        if (handler != null)
        {
            handler.getDataQueryHandler().queryBlockNbt(pos, nbtCompound ->
            {
                handleBlockEntityData(pos, nbtCompound, null);
            });
            this.transactionToBlockPosOrEntityId.put(((IMixinDataQueryHandler) handler.getDataQueryHandler()).minihud_currentTransactionId(), Either.left(pos));
        }
    }

    private void requestQueryEntityData(int entityId)
    {
        if (Configs.Generic.ENTITY_DATA_SYNC_BACKUP.getBooleanValue() == false)
        {
            return;
        }

        ClientPlayNetworkHandler handler = getVanillaHandler();

        if (handler != null)
        {
            handler.getDataQueryHandler().queryEntityNbt(entityId, nbtCompound ->
            {
                handleEntityData(entityId, nbtCompound);
            });
            this.transactionToBlockPosOrEntityId.put(((IMixinDataQueryHandler) handler.getDataQueryHandler()).minihud_currentTransactionId(), Either.right(entityId));
        }
    }

    private void requestServuxBlockEntityData(BlockPos pos)
    {
        if (Configs.Generic.ENTITY_DATA_SYNC.getBooleanValue())
        {
            HANDLER.encodeClientData(ServuxEntitiesPacket.BlockEntityRequest(pos));
        }
    }

    private void requestServuxEntityData(int entityId)
    {
        if (Configs.Generic.ENTITY_DATA_SYNC.getBooleanValue())
        {
            HANDLER.encodeClientData(ServuxEntitiesPacket.EntityRequest(entityId));
        }
    }

    @Nullable
    @Override
    public BlockEntity handleBlockEntityData(BlockPos pos, NbtCompound nbt, @Nullable Identifier type)
    {
        this.pendingBlockEntitiesQueue.remove(pos);
        if (nbt == null || this.getClientWorld() == null) return null;

        BlockEntity blockEntity = this.getClientWorld().getBlockEntity(pos);

        if (blockEntity != null && (type == null || type.equals(BlockEntityType.getId(blockEntity.getType()))))
        {
            if (nbt.contains(NbtKeys.ID, Constants.NBT.TAG_STRING) == false)
            {
                Identifier id = BlockEntityType.getId(blockEntity.getType());

                if (id != null)
                {
                    nbt.putString(NbtKeys.ID, id.toString());
                }
            }
            synchronized (this.blockEntityCache)
            {
                this.blockEntityCache.put(pos, Pair.of(System.currentTimeMillis(), Pair.of(blockEntity, nbt)));
            }

            blockEntity.read(nbt, this.getClientWorld().getRegistryManager());
            return blockEntity;
        }

        Optional<RegistryEntry.Reference<BlockEntityType<?>>> opt = Registries.BLOCK_ENTITY_TYPE.getEntry(type);

        if (opt.isPresent())
        {
            BlockEntityType<?> beType = opt.get().value();

            if (beType.supports(this.getClientWorld().getBlockState(pos)))
            {
                BlockEntity blockEntity2 = beType.instantiate(pos, this.getClientWorld().getBlockState(pos));

                if (blockEntity2 != null)
                {
                    if (nbt.contains(NbtKeys.ID, Constants.NBT.TAG_STRING) == false)
                    {
                        Identifier id = BlockEntityType.getId(beType);

                        if (id != null)
                        {
                            nbt.putString(NbtKeys.ID, id.toString());
                        }
                    }
                    synchronized (this.blockEntityCache)
                    {
                        this.blockEntityCache.put(pos, Pair.of(System.currentTimeMillis(), Pair.of(blockEntity2, nbt)));
                    }

                    if (Configs.Generic.ENTITY_DATA_LOAD_NBT.getBooleanValue())
                    {
                        blockEntity2.read(nbt, this.getClientWorld().getRegistryManager());
                        this.getClientWorld().addBlockEntity(blockEntity2);
                    }

                    return blockEntity2;
                }
            }
        }

        return null;
    }

    @Nullable
    @Override
    public Entity handleEntityData(int entityId, NbtCompound nbt)
    {
        this.pendingEntitiesQueue.remove(entityId);
        if (nbt == null || this.getClientWorld() == null) return null;
        Entity entity = this.getClientWorld().getEntityById(entityId);

        if (entity != null)
        {
            if (nbt.contains(NbtKeys.ID, Constants.NBT.TAG_STRING) == false)
            {
                Identifier id = EntityType.getId(entity.getType());

                if (id != null)
                {
                    nbt.putString(NbtKeys.ID, id.toString());
                }
            }

            synchronized (this.entityCache)
            {
                this.entityCache.put(entityId, Pair.of(System.currentTimeMillis(), Pair.of(entity, nbt)));
            }

            if (Configs.Generic.ENTITY_DATA_LOAD_NBT.getBooleanValue())
            {
                EntityUtils.loadNbtIntoEntity(entity, nbt);
            }
        }
        return entity;
    }

    @Override
    public void handleBulkEntityData(int transactionId, NbtCompound nbt)
    {
        // todo
    }

    @Override
    public void handleVanillaQueryNbt(int transactionId, NbtCompound nbt)
    {
        Either<BlockPos, Integer> either = this.transactionToBlockPosOrEntityId.remove(transactionId);
        if (either != null)
        {
            either.ifLeft(pos -> handleBlockEntityData(pos, nbt, null))
                    .ifRight(entityId -> handleEntityData(entityId, nbt));
        }
    }

    // TODO --> Only in case we need to save config settings in the future
    public JsonObject toJson()
    {
        return new JsonObject();
    }

    public void fromJson(JsonObject obj)
    {
        // NO-OP
    }
}
