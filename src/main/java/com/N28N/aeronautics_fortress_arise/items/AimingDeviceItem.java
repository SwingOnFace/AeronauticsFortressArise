package com.N28N.aeronautics_fortress_arise.items;

import com.mojang.logging.LogUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import top.theillusivec4.curios.api.CuriosApi;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * 头部瞄准仪（Head Aiming Device）。
 *
 * <p>双槽位穿戴（参考机械动力护目镜）：
 * <ul>
 *     <li>原版头盔槽（{@link Equipable}，右键自动戴上）</li>
 *     <li>Curios 饰品栏 head 槽（由 {@code CuriosCompat} 注册，可手动放入）</li>
 * </ul>
 * 每个物品实例持有一个独特的 UUID（标识瞄准仪本身），
 * 用于与后续的机械动力方块绑定：机械动力方块保存瞄准仪的 UUID，
 * 通过 {@link #getBoundPlayer(Level, UUID)} 找到当前佩戴该瞄准仪的玩家，
 * 从而计算"玩家是否在操纵"等逻辑。
 *
 * <p>绑定关系：瞄准仪 UUID → 佩戴者玩家。
 */
public class AimingDeviceItem extends Item implements Equipable {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 存储 UUID 的 NBT 键名。 */
    private static final String TAG_UUID = "PilotUuid";

    /** Curios 饰品槽位 ID（head 槽）。 */
    public static final String SLOT_ID = "head";

    // ------------------------------------------------------------------
    // Equipable 实现（原版头盔槽穿戴，右键自动换上）
    // ------------------------------------------------------------------

    @Override
    public EquipmentSlot getEquipmentSlot() {
        return EquipmentSlot.HEAD;
    }

    /** 右键物品时穿戴到头盔槽（原版机制）。 */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return swapWithEquipmentSlot(this, level, player, hand);
    }

    public AimingDeviceItem(Properties properties) {
        super(properties);
    }

    // ------------------------------------------------------------------
    // UUID 管理
    // ------------------------------------------------------------------

    /**
     * 获取该物品的绑定 UUID。若不存在则生成一个并写回物品 NBT（幂等）。
     */
    public static UUID getUuid(ItemStack stack) {
        CustomData customData =
                stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag tag = customData.copyTag();
        if (tag.contains(TAG_UUID)) {
            try {
                return UUID.fromString(tag.getString(TAG_UUID));
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Aiming device UUID 解析失败，重新生成: {}", tag.getString(TAG_UUID));
            }
        }
        UUID uuid = UUID.randomUUID();
        // 写回物品 NBT：把现有 tag 复制一份加上 UUID，再包成 CustomData 写入。
        CompoundTag newTag = tag.copy();
        newTag.putString(TAG_UUID, uuid.toString());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(newTag));
        return uuid;
    }

    /**
     * 根据瞄准仪的 UUID 查找当前佩戴该瞄准仪的在线玩家。
     * <p>
     * UUID 标识瞄准仪本身（不是玩家）。绑定关系为"瞄准仪 → 佩戴者"：
     * 遍历所有在线玩家，检查其 Curios head 槽中佩戴的瞄准仪 UUID 是否匹配。
     *
     * @param level 任意服务端维度（用于访问服务器玩家列表）
     * @param uuid  瞄准仪的 UUID
     * @return 佩戴该瞄准仪的玩家（在线），无人佩戴返回 null
     */
    @Nullable
    public static Player getBoundPlayer(Level level, UUID uuid) {
        if (level == null || level.getServer() == null || uuid == null) {
            return null;
        }
        for (Player player : level.getServer().getPlayerList().getPlayers()) {
            if (isWearing(player, uuid)) {
                return player;
            }
        }
        return null;
    }

    /**
     * 检查玩家是否佩戴了指定 UUID（或任意）的瞄准仪。
     * <p>同时检查原版头盔槽与 Curios 饰品栏 head 槽（两者都可穿戴）。
     */
    private static boolean isWearing(Player player, @Nullable UUID uuid) {
        if (player == null || !player.isAlive()) {
            return false;
        }
        // 1) 原版头盔槽。
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        if (head.getItem() instanceof AimingDeviceItem) {
            return uuid == null || uuid.equals(getUuid(head));
        }
        // 2) Curios head 槽（饰品栏）。Curios 未安装时忽略。
        try {
            return CuriosApi.getCuriosInventory(player)
                    .map(inventory -> inventory.findCurios(SLOT_ID).stream()
                            .filter(result -> result.stack().getItem() instanceof AimingDeviceItem)
                            .anyMatch(result -> uuid == null || uuid.equals(getUuid(result.stack()))))
                    .orElse(false);
        } catch (NoClassDefFoundError e) {
            // Curios 未安装：不视为佩戴。
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 工具提示
    // ------------------------------------------------------------------

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents,
            TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
        UUID uuid = getUuid(stack);
        // 显示 UUID 前 8 位，方便识别绑定关系（调试友好）。
        tooltipComponents.add(Component.literal("§7UUID: " + uuid.toString().substring(0, 8) + "..."));
        tooltipComponents.add(Component.translatable("item.aeronautics_fortress_arise.aiming_device.tooltip"));
    }
}
