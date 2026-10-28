package com.N28N.aeronautics_fortress_arise.client.screen;

import java.util.List;

import com.N28N.aeronautics_fortress_arise.blocks.AIFireController.AIFireControllerBlockEntity;
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
    private final List<String> projectileIds;
    private int propellantPower;
    private int accuracy;
    private String projectileId;
    private EditBox propellantPowerBox;
    private EditBox accuracyBox;
    private Button projectileButton;

    private AIFireControllerScreen(AIFireControllerNetwork.S2COpenScreenPacket packet) {
        super(Component.translatable("screen.aeronautics_fortress_arise.ai_fire_controller"));
        this.pos = packet.pos();
        this.propellantPower = packet.propellantPower();
        this.accuracy = packet.accuracy();
        this.projectileIds = packet.projectileIds().stream().distinct().sorted().toList();
        this.projectileId = packet.projectileId();
        if (this.projectileIds.isEmpty()) {
            this.projectileId = null;
        } else if (!this.projectileIds.contains(this.projectileId)) {
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

        this.propellantPowerBox = new EditBox(this.font, centerX - 120, centerY - 70, 240, 20,
                Component.translatable("screen.aeronautics_fortress_arise.propellant_power"));
        this.propellantPowerBox.setMaxLength(2);
        this.propellantPowerBox.setValue(String.valueOf(this.propellantPower));
        this.propellantPowerBox.setFilter(value -> value.matches("\\d*"));
        this.addRenderableWidget(this.propellantPowerBox);

        this.projectileButton = Button.builder(this.getProjectileName(), button -> this.cycleProjectile(1))
                .bounds(centerX - 90, centerY - 20, 180, 20)
                .build();
        this.addRenderableWidget(Button.builder(Component.literal("<"), button -> this.cycleProjectile(-1))
                .bounds(centerX - 120, centerY - 20, 25, 20)
                .build());
        this.addRenderableWidget(this.projectileButton);
        this.addRenderableWidget(Button.builder(Component.literal(">"), button -> this.cycleProjectile(1))
                .bounds(centerX + 95, centerY - 20, 25, 20)
                .build());

        this.accuracyBox = new EditBox(this.font, centerX - 120, centerY + 30, 240, 20,
                Component.translatable("screen.aeronautics_fortress_arise.accuracy"));
        this.accuracyBox.setMaxLength(3);
        this.accuracyBox.setValue(String.valueOf(this.accuracy));
        this.accuracyBox.setFilter(value -> value.matches("\\d*"));
        this.addRenderableWidget(this.accuracyBox);

        this.setInitialFocus(this.propellantPowerBox);
        this.propellantPowerBox.setFocused(true);

        this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.aeronautics_fortress_arise.save"),
                        button -> this.save())
                .bounds(centerX - 120, centerY + 65, 115, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> this.onClose())
                .bounds(centerX + 5, centerY + 65, 115, 20)
                .build());
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
                            this.pos.getX(), this.pos.getY(), this.pos.getZ(),
                            newPower, this.projectileId, newAccuracy);
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
        graphics.drawCenteredString(this.font, this.title, centerX, centerY - 100, 0xFFFFFF);
        graphics.drawString(this.font,
                Component.translatable("screen.aeronautics_fortress_arise.propellant_power"),
                centerX - 120, centerY - 83, 0xA0A0A0);
        graphics.drawString(this.font,
                Component.translatable("screen.aeronautics_fortress_arise.projectile"),
                centerX - 120, centerY - 38, 0xA0A0A0);
        graphics.drawString(this.font,
                Component.translatable("screen.aeronautics_fortress_arise.accuracy"),
                centerX - 120, centerY + 12, 0xA0A0A0);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
