package com.N28N.aeronautics_fortress_arise.blocks.LootItemVault;

import java.util.List;

import com.N28N.aeronautics_fortress_arise.AllBlocks;
import com.N28N.aeronautics_fortress_arise.AeronauticsFortressArise;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 战利品保险箱的方块实体。
 * <p>
 * 职责：
 * <ul>
 *     <li>首次 tick 时根据方块对应的战利品表生成“海量”物品；</li>
 *     <li>对外只提供只读物品处理器（禁止存入，仅允许取出）；</li>
 *     <li>当库存被清空后移除自身方块（保险箱消失）。</li>
 * </ul>
 * <p>
 * 战利品表由方块本身决定（见 {@link LootItemVaultBlock#getLootTableKey()}），
 * 因此 15 种保险库共享同一个方块实体类型，仅方块不同。
 */
public class LootItemVaultBlockEntity extends BlockEntity {

    /** 库存容量（9×9，足够容纳海量物品）。 */
    private static final int INVENTORY_SIZE = 81;

    /** 每次生成时对战利品表进行的 roll 次数。 */
    private static final int LOOT_ROLLS = 16;

    /** 内部物品库存（用于生成与取出）。 */
    private final ItemStackHandler inventory = new ItemStackHandler(INVENTORY_SIZE);

    /** 只读物品处理器：拒绝存入，仅允许取出，供机械动力漏斗等自动化设备使用。 */
    private final IItemHandler readOnlyHandler = new IItemHandler() {
        @Override
        public int getSlots() {
            return inventory.getSlots();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return inventory.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            // 战利品生成后不允许再存入任何物品。
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return inventory.extractItem(slot, amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) {
            return inventory.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return false;
        }
    };

    /** 是否已经生成过战利品（避免重复生成）。 */
    private boolean lootGenerated = false;

    public LootItemVaultBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** 服务端每 tick 调用一次。 */
    public void tick() {
        if (this.level == null || this.level.isClientSide) {
            return;
        }
        if (!this.lootGenerated) {
            this.generateLoot();
        }
        if (this.lootGenerated && this.isEmpty()) {
            // 物品被清空后，保险箱随之消失。
            this.level.removeBlock(this.worldPosition, false);
        }
    }

    /** 根据方块对应的战利品表生成海量物品并填充库存。 */
    private void generateLoot() {
        if (!(this.level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(this.getBlockState().getBlock() instanceof LootItemVaultBlock vaultBlock)) {
            return;
        }
        ResourceKey<LootTable> lootTableKey = vaultBlock.getLootTableKey();
        LootTable table = serverLevel.getServer().reloadableRegistries().getLootTable(lootTableKey);
        LootVaultLootConfig.Changes changes = LootVaultLootConfig.getChanges(lootTableKey.location());
        this.lootGenerated = true;
        LootParams params = new LootParams.Builder(serverLevel)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(this.worldPosition))
                .create(LootContextParamSets.CHEST);

        for (int i = 0; i < LOOT_ROLLS; i++) {
            List<ItemStack> items = changes.cleared() ? List.of() : table.getRandomItems(params);
            for (ItemStack stack : items) {
                if (stack.isEmpty()) {
                    continue;
                }
                ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (changes.removed().contains(itemId)) {
                    continue;
                }
                ItemHandlerHelper.insertItemStacked(this.inventory, stack, false);
            }
        }
        for (LootVaultLootConfig.BonusItem bonus : changes.added()) {
            BuiltInRegistries.ITEM.getOptional(bonus.item()).ifPresentOrElse(
                    item -> ItemHandlerHelper.insertItemStacked(
                            this.inventory, new ItemStack(item, bonus.count()), false),
                    () -> AeronauticsFortressArise.LOGGER.warn(
                            "KubeJS loot vault bonus item {} is not registered", bonus.item()));
        }
        this.setChanged();
    }

    /** 库存是否已全部清空。 */
    private boolean isEmpty() {
        for (int i = 0; i < this.inventory.getSlots(); i++) {
            if (!this.inventory.getStackInSlot(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** 获取对外暴露的只读物品处理器。 */
    public IItemHandler getItemHandler() {
        return this.readOnlyHandler;
    }

    /** 方块被破坏时掉落内部剩余物品。 */
    public void dropContents() {
        if (this.level == null || this.level.isClientSide) {
            return;
        }
        for (int i = 0; i < this.inventory.getSlots(); i++) {
            ItemStack stack = this.inventory.getStackInSlot(i);
            if (!stack.isEmpty()) {
                Containers.dropItemStack(this.level,
                        this.worldPosition.getX(), this.worldPosition.getY(), this.worldPosition.getZ(), stack);
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("LootGenerated", this.lootGenerated);
        tag.put("Inventory", this.inventory.serializeNBT(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.lootGenerated = tag.getBoolean("LootGenerated");
        if (tag.contains("Inventory")) {
            this.inventory.deserializeNBT(registries, tag.getCompound("Inventory"));
        }
    }

    /** 注册只读物品处理器 capability，供机械动力漏斗取出物品。 */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        // 直接使用 AllBlocks 中保存的共享方块实体类型引用，
        // 避免在事件触发时通过注册表查找（此时类型可能尚未注册完成）。
        BlockEntityType<LootItemVaultBlockEntity> type = AllBlocks.LOOT_VAULT_BLOCK_ENTITY.get();
        if (type == null) {
            return;
        }
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, type, (be, side) -> be.getItemHandler());
    }
}
