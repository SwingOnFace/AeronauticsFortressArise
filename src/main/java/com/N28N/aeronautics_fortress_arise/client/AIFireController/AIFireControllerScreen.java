package com.N28N.aeronautics_fortress_arise.client.AIFireController;

import java.util.List;

import com.N28N.aeronautics_fortress_arise.blocks.AIFireController.AIFireControllerBlockEntity;
import com.N28N.aeronautics_fortress_arise.blocks.AIFireController.CannonMode;
import com.N28N.aeronautics_fortress_arise.networking.AIFireControllerNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public class AIFireControllerScreen extends Screen {

    private final BlockPos pos;
    /** 服务器按模式下发的可选弹药，客户端直接按模式取用，不再自行查询注册表。 */
    private final AIFireControllerNetwork.S2COpenScreenPacket.ProjectileLists allProjectiles;
    private List<String> projectileIds;
    private int propellantPower;
    private int accuracy;
    private String projectileId;
    private CannonMode cannonMode;
    private EditBox propellantPowerBox;
    private EditBox accuracyBox;
    private Button projectileButton;
    private Button modeButton;

    private AIFireControllerScreen(AIFireControllerNetwork.S2COpenScreenPacket packet) {
        super(Component.translatable("screen.aeronautics_fortress_arise.ai_fire_controller"));
        this.pos = packet.pos();
        this.propellantPower = packet.propellantPower();
        this.accuracy = packet.accuracy();
        this.cannonMode = CannonMode.byOrdinal(packet.cannonMode());
        this.allProjectiles = packet.projectiles();
        this.projectileId = packet.projectileId();
        this.applyModeFilter();
    }

    /** 按当前模式筛选弹药，并在原选择失效时切到第一项。 */
    private void applyModeFilter() {
        this.projectileIds = this.allProjectiles.forMode(this.cannonMode).stream().distinct().sorted().toList();
        if (this.projectileIds.isEmpty()) {
            this.projectileId = null;
        } else if (this.projectileId == null || !this.projectileIds.contains(this.projectileId)) {
            this.projectileId = this.projectileIds.get(0);
        }
    }

    public static void open(AIFireControllerNetwork.S2COpenScreenPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.setScreen(new AIFireControllerScreen(packet));
        }
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.propellantPowerBox = new EditBox(this.font, centerX - 120, centerY - 80, 240, 20,
                Component.translatable("screen.aeronautics_fortress_arise.propellant_power"));
        this.propellantPowerBox.setMaxLength(2);
        this.propellantPowerBox.setValue(String.valueOf(this.propellantPower));
        this.propellantPowerBox.setFilter(value -> value.matches("\\d*"));
        this.addRenderableWidget(this.propellantPowerBox);

        this.projectileButton = Button.builder(this.getProjectileName(), button -> this.cycleProjectile(1))
                .bounds(centerX - 90, centerY - 42, 180, 20)
                .build();
        this.addRenderableWidget(Button.builder(Component.literal("<"), button -> this.cycleProjectile(-1))
                .bounds(centerX - 120, centerY - 42, 25, 20)
                .build());
        this.addRenderableWidget(this.projectileButton);
        this.addRenderableWidget(Button.builder(Component.literal(">"), button -> this.cycleProjectile(1))
                .bounds(centerX + 95, centerY - 42, 25, 20)
                .build());

        this.modeButton = Button.builder(this.getModeName(), button -> this.cycleCannonMode())
                .bounds(centerX - 120, centerY + 3, 240, 20)
                .build();
        this.addRenderableWidget(this.modeButton);

        this.accuracyBox = new EditBox(this.font, centerX - 120, centerY + 46, 240, 20,
                Component.translatable("screen.aeronautics_fortress_arise.accuracy"));
        this.accuracyBox.setMaxLength(3);
        this.accuracyBox.setValue(String.valueOf(this.accuracy));
        this.accuracyBox.setFilter(value -> value.matches("\\d*"));
        this.addRenderableWidget(this.accuracyBox);

        // 标准炮不使用发射药，输入框直接禁用，避免误解。
        this.propellantPowerBox.setEditable(this.cannonMode == CannonMode.BIG_CANNON);

        this.setInitialFocus(this.propellantPowerBox);
        this.propellantPowerBox.setFocused(true);

        this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.aeronautics_fortress_arise.save"),
                        button -> this.save())
                .bounds(centerX - 120, centerY + 78, 115, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> this.onClose())
                .bounds(centerX + 5, centerY + 78, 115, 20)
                .build());
    }

    private Component getModeName() {
        return Component.translatable("screen.aeronautics_fortress_arise.cannon_mode",
                this.cannonMode.getDisplayName());
    }

    private void cycleCannonMode() {
        this.cannonMode = this.cannonMode.cycle();
        this.applyModeFilter();
        if (this.modeButton != null) {
            this.modeButton.setMessage(this.getModeName());
        }
        if (this.projectileButton != null) {
            this.projectileButton.setMessage(this.getProjectileName());
        }
        if (this.propellantPowerBox != null) {
            this.propellantPowerBox.setEditable(this.cannonMode == CannonMode.BIG_CANNON);
        }
    }

    private void cycleProjectile(int direction) {
        if (this.projectileIds.isEmpty()) {
            return;
        }
        int index = this.projectileIds.indexOf(this.projectileId);
        int nextIndex = Math.floorMod(index + direction, this.projectileIds.size());
        this.projectileId = this.projectileIds.get(nextIndex);
        this.projectileButton.setMessage(this.getProjectileName());
    }

    private Component getProjectileName() {
        if (this.projectileId == null) {
            return Component.translatable("screen.aeronautics_fortress_arise.no_projectiles");
        }
        if (Minecraft.getInstance().level == null) {
            return Component.translatable("screen.aeronautics_fortress_arise.no_projectiles");
        }
        ItemStack stack = AIFireControllerBlockEntity.createProjectileStack(
                this.projectileId, Minecraft.getInstance().level.registryAccess());
        return stack.isEmpty()
                ? Component.translatable("screen.aeronautics_fortress_arise.no_projectiles")
                : stack.getHoverName();
    }

    private void save() {
        int newPower = this.parseInt(this.propellantPowerBox, this.propellantPower);
        int newAccuracy = this.parseInt(this.accuracyBox, this.accuracy);
        if (this.projectileId != null) {
            AIFireControllerNetwork.C2SSaveConfigPacket packet =
                    new AIFireControllerNetwork.C2SSaveConfigPacket(
                            this.pos, newPower, this.projectileId, newAccuracy, this.cannonMode.ordinal());
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(packet);
        }
        this.onClose();
    }

    private int parseInt(EditBox box, int fallback) {
        try {
            return Integer.parseInt(box.getValue().trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        graphics.drawCenteredString(this.font, this.title, centerX, centerY - 110, 0xFFFFFF);
        graphics.drawString(this.font,
                Component.translatable("screen.aeronautics_fortress_arise.propellant_power"),
                centerX - 120, centerY - 93, 0xA0A0A0);
        graphics.drawString(this.font,
                Component.translatable("screen.aeronautics_fortress_arise.projectile"),
                centerX - 120, centerY - 60, 0xA0A0A0);
        graphics.drawString(this.font,
                Component.translatable("screen.aeronautics_fortress_arise.cannon_mode_label"),
                centerX - 120, centerY - 10, 0xA0A0A0);
        graphics.drawString(this.font,
                Component.translatable("screen.aeronautics_fortress_arise.accuracy"),
                centerX - 120, centerY + 33, 0xA0A0A0);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
