package fi.dy.masa.minihud.renderer;

import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.EnderChestInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.world.World;

import fi.dy.masa.malilib.interfaces.IDataSyncer;
import fi.dy.masa.malilib.interfaces.IInventoryOverlayHandler;
import fi.dy.masa.malilib.render.InventoryOverlay;
import fi.dy.masa.malilib.util.Constants;
import fi.dy.masa.malilib.util.EntityUtils;
import fi.dy.masa.malilib.util.InventoryUtils;
import fi.dy.masa.malilib.util.WorldUtils;
import fi.dy.masa.malilib.util.nbt.NbtBlockUtils;
import fi.dy.masa.malilib.util.nbt.NbtKeys;
import fi.dy.masa.minihud.Reference;
import fi.dy.masa.minihud.config.Configs;
import fi.dy.masa.minihud.data.EntitiesDataManager;
import fi.dy.masa.minihud.mixin.IMixinAbstractHorseEntity;
import fi.dy.masa.minihud.mixin.IMixinPiglinEntity;
import fi.dy.masa.minihud.util.RayTraceUtils;

public class InventoryOverlayHandler implements IInventoryOverlayHandler
{
    private static final InventoryOverlayHandler INSTANCE = new InventoryOverlayHandler();
    public static InventoryOverlayHandler getInstance() { return INSTANCE; }

    private InventoryOverlay.Context context;
    private Refresher refresher;
    private IDataSyncer syncer;

    private Pair<BlockPos, InventoryOverlay.Context> lastBlockEntityContext;
    private Pair<Integer,  InventoryOverlay.Context> lastEntityContext;


    public InventoryOverlayHandler()
    {
        this.lastBlockEntityContext = null;
        this.lastEntityContext = null;
        this.context = null;
        this.refresher = null;
        this.syncer = null;
    }

    @Override
    public String getModId()
    {
        return Reference.MOD_ID;
    }

    @Override
    public IDataSyncer getDataSyncer()
    {
        if (this.syncer == null)
        {
            this.syncer = EntitiesDataManager.getInstance();
        }

        return this.syncer;
    }

    @Override
    public void setDataSyncer(IDataSyncer syncer)
    {
        this.syncer = syncer;
    }

    @Override
    public InventoryOverlay.Refresher getRefreshHandler()
    {
        if (this.refresher == null)
        {
            this.refresher = new Refresher();
        }

        return this.refresher;
    }

    @Override
    public boolean isEmpty()
    {
        return this.context == null;
    }

    @Override
    public @Nullable InventoryOverlay.Context getRenderContextNullable()
    {
        return this.context;
    }

    @Override
    public @Nullable InventoryOverlay.Context getRenderContext(DrawContext drawContext, Profiler profiler, MinecraftClient mc)
    {
        profiler.push(this.getClass().getName() + "_inventory_overlay");
        this.getTargetInventory(mc);

        if (!this.isEmpty())
        {
            this.renderInventoryOverlay(this.getRenderContextNullable(), drawContext, mc, Configs.Generic.SHULKER_DISPLAY_BACKGROUND_COLOR.getBooleanValue());
        }

        profiler.pop();

        return this.getRenderContextNullable();
    }

    @Override
    public @Nullable InventoryOverlay.Context getTargetInventory(MinecraftClient mc)
    {
        World world = WorldUtils.getBestWorld(mc);
        Entity cameraEntity = EntityUtils.getCameraEntity();
        this.context = null;

        if (mc.player == null || world == null)
        {
            return null;
        }

        if (cameraEntity == mc.player && world instanceof ServerWorld)
        {
            // We need to get the player from the server world (if available, ie. in single player),
            // so that the player itself won't be included in the ray trace
            Entity serverPlayer = world.getPlayerByUuid(mc.player.getUuid());

            if (serverPlayer != null)
            {
                cameraEntity = serverPlayer;
            }
        }

        HitResult trace = RayTraceUtils.getRayTraceFromEntity(world, cameraEntity, false);
        NbtCompound nbt = new NbtCompound();

        if (trace == null || trace.getType() == HitResult.Type.MISS)
        {
            return null;
        }

        if (trace.getType() == HitResult.Type.BLOCK)
        {
            BlockPos pos = ((BlockHitResult) trace).getBlockPos();
            BlockState state = world.getBlockState(pos);
            Block blockTmp = state.getBlock();
            BlockEntity be = null;

            // Keep screen from getting 'stuck' if trying to use toggle on a lectern
            /*
            if (blockTmp instanceof LecternBlock && !newScreen)
            {
                return null;
            }
             */

            //MiniHUD.logger.warn("getTarget():1: pos [{}], state [{}]", pos.toShortString(), state.toString());

            if (blockTmp instanceof BlockEntityProvider)
            {
                if (world instanceof ServerWorld)
                {
                    be = world.getWorldChunk(pos).getBlockEntity(pos);

                    if (be != null)
                    {
                        nbt = be.createNbtWithIdentifyingData(world.getRegistryManager());
                    }
                }
                else
                {
                    Pair<BlockEntity, NbtCompound> pair = this.requestBlockEntityAt(world, pos);

                    if (pair != null)
                    {
                        nbt = pair.getRight();

                        if (Configs.Generic.ENTITY_DATA_LOAD_NBT.getBooleanValue())
                        {
                            be = pair.getLeft();
                        }
                    }
                }

                //MiniHUD.logger.warn("getTarget():2: pos [{}], be [{}], nbt [{}]", pos.toShortString(), be != null, nbt != null);
                InventoryOverlay.Context ctx = getTargetInventoryFromBlock(world, pos, be, nbt);
                //dumpContext(ctx);

                if (this.lastBlockEntityContext != null && !this.lastBlockEntityContext.getLeft().equals(pos))
                {
                    this.lastBlockEntityContext = null;
                }

                if (ctx != null && ctx.inv() != null)
                {
                    this.lastBlockEntityContext = Pair.of(pos, ctx);
                    this.context = ctx;
                    return ctx;
                }
                else if (this.lastBlockEntityContext != null && this.lastBlockEntityContext.getLeft().equals(pos))
                {
                    this.context = this.lastBlockEntityContext.getRight();
                    return this.context;
                }
            }

            return null;
        }
        else if (trace.getType() == HitResult.Type.ENTITY)
        {
            Entity entity = ((EntityHitResult) trace).getEntity();

            if (world instanceof ServerWorld)
            {
                entity.saveSelfNbt(nbt);
            }
            else
            {
                Pair<Entity, NbtCompound> pair = EntitiesDataManager.getInstance().requestEntity(world, entity.getId());

                if (pair != null)
                {
                    nbt = pair.getRight();

                    if (Configs.Generic.ENTITY_DATA_LOAD_NBT.getBooleanValue())
                    {
                        entity = pair.getLeft();
                    }
                }
            }

            //MiniHUD.logger.error("getTarget(): Entity [{}] raw NBT [{}]", entity.getId(), nbt.toString());
            InventoryOverlay.Context ctx = getTargetInventoryFromEntity(world.getEntityById(entity.getId()), nbt);
            //dumpContext(ctx);

            if (this.lastEntityContext != null && !this.lastEntityContext.getLeft().equals(entity.getId()))
            {
                this.lastEntityContext = null;
            }

            if (ctx != null && ctx.inv() != null)
            {
                this.lastEntityContext = Pair.of(entity.getId(), ctx);
                this.context = ctx;
                return ctx;
            }
            // Non-Inventory/Empty Entity
            else if (ctx != null &&
                    (ctx.type() == InventoryOverlay.InventoryRenderType.WOLF ||
                     ctx.type() == InventoryOverlay.InventoryRenderType.VILLAGER ||
                     ctx.type() == InventoryOverlay.InventoryRenderType.HORSE ||
                     ctx.type() == InventoryOverlay.InventoryRenderType.PLAYER ||
                     ctx.type() == InventoryOverlay.InventoryRenderType.ARMOR_STAND ||
                     ctx.type() == InventoryOverlay.InventoryRenderType.LIVING_ENTITY))
            {
                this.lastEntityContext = Pair.of(entity.getId(), ctx);
                this.context = ctx;
                return ctx;
            }
            else if (this.lastEntityContext != null && this.lastEntityContext.getLeft().equals(entity.getId()))
            {
                this.context = this.lastEntityContext.getRight();
                return this.context;
            }
        }

        return null;
    }

    @Override
    public @Nullable InventoryOverlay.Context getTargetInventoryFromBlock(World world, BlockPos pos, @Nullable BlockEntity be, NbtCompound nbt)
    {
        Inventory inv;

        if (world == null) return null;

        if (be != null)
        {
            if (nbt.isEmpty())
            {
                nbt = be.createNbtWithIdentifyingData(world.getRegistryManager());
            }

            inv = InventoryUtils.getInventory(world, pos);
        }
        else
        {
            if (nbt.isEmpty())
            {
                Pair<BlockEntity, NbtCompound> pair = this.requestBlockEntityAt(world, pos);

                if (pair != null)
                {
                    nbt = pair.getRight();

                    if (Configs.Generic.ENTITY_DATA_LOAD_NBT.getBooleanValue())
                    {
                        be = pair.getLeft();
                    }
                }
            }

            inv = EntitiesDataManager.getInstance().getBlockInventory(world, pos, false);
        }

        BlockEntityType<?> beType = nbt != null ? NbtBlockUtils.getBlockEntityTypeFromNbt(nbt) : null;

        if ((beType != null && beType.equals(BlockEntityType.ENDER_CHEST)) ||
             be instanceof EnderChestBlockEntity)
        {
            if (MinecraftClient.getInstance().player != null)
            {
                PlayerEntity player = world.getPlayerByUuid(MinecraftClient.getInstance().player.getUuid());

                if (player != null)
                {
                    // Fetch your own EnderItems from Server ...
                    Pair<Entity, NbtCompound> enderPair = EntitiesDataManager.getInstance().requestEntity(world, player.getId());
                    EnderChestInventory enderItems;

                    if (enderPair != null && enderPair.getRight() != null && enderPair.getRight().contains(NbtKeys.ENDER_ITEMS))
                    {
                        enderItems = InventoryUtils.getPlayerEnderItemsFromNbt(enderPair.getRight(), world.getRegistryManager());
                    }
                    else
                    {
                        enderItems = player.getEnderChestInventory();
                    }

                    if (enderItems != null)
                    {
                        inv = enderItems;
                    }
                }
            }
        }

        if (nbt != null && !nbt.isEmpty())
        {
            //MiniHUD.logger.warn("getTargetInventoryFromBlock(): rawNbt: [{}]", nbt.toString());

            Inventory inv2 = InventoryUtils.getNbtInventory(nbt, inv != null ? inv.size() : -1, world.getRegistryManager());

            if (inv == null)
            {
                inv = inv2;
            }
        }

        //MiniHUD.logger.warn("getTarget():3: pos [{}], inv [{}], be [{}], nbt [{}]", pos.toShortString(), inv != null, be != null, nbt != null ? nbt.getString("id") : new NbtCompound());

        if (inv == null || nbt == null)
        {
            return null;
        }

        this.context = new InventoryOverlay.Context(InventoryOverlay.getBestInventoryType(inv, nbt), inv, be != null ? be : world.getBlockEntity(pos), null, nbt, this.getRefreshHandler());

        return this.context;
    }

    @Override
    public @Nullable InventoryOverlay.Context getTargetInventoryFromEntity(Entity entity, NbtCompound nbt)
    {
        Inventory inv = null;
        LivingEntity entityLivingBase = null;

        if (entity instanceof LivingEntity)
        {
            entityLivingBase = (LivingEntity) entity;
        }

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
            inv = ((IMixinAbstractHorseEntity) entity).minihud_getHorseInventory();
        }
        else if (entity instanceof PiglinEntity)
        {
            inv = ((IMixinPiglinEntity) entity).minihud_inventory();
        }
        if (!nbt.isEmpty())
        {
            Inventory inv2;

            //MiniHUD.logger.warn("getTargetInventoryFromEntity(): rawNbt: [{}]", nbt.toString());

            // Fix for empty horse inv
            if (inv != null &&
                nbt.contains(NbtKeys.ITEMS) &&
                nbt.getList(NbtKeys.ITEMS, Constants.NBT.TAG_COMPOUND).size() > 1)
            {
                if (entity instanceof AbstractHorseEntity)
                {
                    inv2 = InventoryUtils.getNbtInventoryHorseFix(nbt, -1, entity.getRegistryManager());
                }
                else
                {
                    inv2 = InventoryUtils.getNbtInventory(nbt, -1, entity.getRegistryManager());
                }

                inv = null;
            }
            // Fix for saddled horse, no inv
            else if (inv != null &&
                     inv.size() == 1 &&
                     nbt.contains(NbtKeys.SADDLE))
            {
                inv2 = InventoryUtils.getNbtInventoryHorseFix(nbt, -1, entity.getRegistryManager());
                inv = null;
            }
            // Fix for empty Villager/Piglin inv
            else if (inv != null && inv.size() == 8 &&
                     nbt.contains(NbtKeys.INVENTORY) &&
                     !nbt.getList(NbtKeys.INVENTORY, Constants.NBT.TAG_COMPOUND).isEmpty())
            {
                inv2 = InventoryUtils.getNbtInventory(nbt, 8, entity.getRegistryManager());
                inv = null;
            }
            else
            {
                inv2 = InventoryUtils.getNbtInventory(nbt, inv != null ? inv.size() : -1, entity.getRegistryManager());

                if (inv2 != null)
                {
                    inv = null;
                }
            }

            //MiniHUD.logger.error("getTargetInventoryFromEntity(): inv.size [{}], inv2.size [{}]", inv != null ? inv.size() : "null", inv2 != null ? inv2.size() : "null");

            if (inv2 != null)
            {
                inv = inv2;
            }
        }

        if (inv == null && entityLivingBase == null)
        {
            return null;
        }

        this.context = new InventoryOverlay.Context(inv != null ? InventoryOverlay.getBestInventoryType(inv, nbt) : InventoryOverlay.getInventoryType(nbt),
                                                    inv, null, entityLivingBase, nbt, this.getRefreshHandler());

        return this.context;
    }

    private static void dumpContext(InventoryOverlay.Context ctx)
    {
        System.out.print("Context Dump --> ");

        if (ctx == null)
        {
            System.out.print("NULL!\n");
            return;
        }

        System.out.printf("\nTYPE: [%s]\n", ctx.type().name());
        System.out.printf("BE  : [%s]\n", ctx.be() != null ? Registries.BLOCK_ENTITY_TYPE.getId(ctx.be().getType()) : "<NULL>");
        System.out.printf("ENT : [%s]\n", ctx.entity() != null ? Registries.ENTITY_TYPE.getId(ctx.entity().getType()) : "<NULL>");
        System.out.printf("INV : [%s]\n", ctx.inv() != null ? "size: "+ctx.inv().size()+"/ empty: "+ctx.inv().isEmpty() : "<NULL>");
        System.out.printf("NBT : [%s]\n", ctx.nbt() != null ? ctx.nbt().toString() : "<NULL>");

        System.out.print("--> EOF\n");
    }

    public static class Refresher implements InventoryOverlay.Refresher
    {
        public Refresher() {}

        @Override
        public InventoryOverlay.Context onContextRefresh(InventoryOverlay.Context data, World world)
        {
            // Refresh data
            if (data.be() != null)
            {
                InventoryOverlayHandler.getInstance().requestBlockEntityAt(world, data.be().getPos());
                data = InventoryOverlayHandler.getInstance().getTargetInventoryFromBlock(data.be().getWorld(), data.be().getPos(), data.be(), data.nbt());
            }
            else if (data.entity() != null)
            {
                InventoryOverlayHandler.getInstance().getDataSyncer().requestEntity(world, data.entity().getId());
                data = InventoryOverlayHandler.getInstance().getTargetInventoryFromEntity(data.entity(), data.nbt());
            }

            return data;
        }
    }
}
