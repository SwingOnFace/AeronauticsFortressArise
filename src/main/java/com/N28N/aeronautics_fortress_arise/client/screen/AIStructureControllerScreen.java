package com.N28N.aeronautics_fortress_arise.client.screen;

import com.N28N.aeronautics_fortress_arise.networking.AIStructureControllerNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * AI 结构控制器配置面板。
 * <p>
 * 可单独配置每个控制器的奖励质量、悬浮高度与奖励数量范围。
 * 通过 {@link AIStructureControllerNetwork.S2COpenScreenPacket} 由服务端携带当前值打开。
 */
public class AIStructureControllerScreen extends Screen {

    private final int x;
    private final int y;
    private final int z;

    private int rewardQuality;
    private int hoverHeight;
    private int rewardMinCount;
    private int rewardMaxCount;

    private EditBox rewardQualityBox;
    private EditBox hoverHeightBox;
    private EditBox rewardMinCountBox;
    private EditBox rewardMaxCountBox;

    private AIStructureControllerScreen(
            int x, int y, int z, int rewardQuality, int hoverHeight, int rewardMinCount, int rewardMaxCount) {
        super(Component.translatable("screen.aeronautics_fortress_arise.ai_structure_controller"));
        this.x = x;
        this.y = y;
        this.z = z;
        this.rewardQuality = rewardQuality;
        this.hoverHeight = hoverHeight;
        this.rewardMinCount = rewardMinCount;
        this.rewardMaxCount = rewardMaxCount;
    }

    /** 由网络包调用，在客户端线程打开面板。 */
    public static void open(AIStructureControllerNetwork.S2COpenScreenPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.setScreen(new AIStructureControllerScreen(
                    packet.pos().getX(), packet.pos().getY(), packet.pos().getZ(),
                    packet.rewardQuality(), packet.hoverHeight(),
                    packet.rewardMinCount(), packet.rewardMaxCount()));
        }
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.rewardQualityBox = new EditBox(this.font, centerX - 140, centerY - 68, 280, 20,
                Component.translatable("screen.aeronautics_fortress_arise.reward_quality"));
        this.rewardQualityBox.setMaxLength(2);
        this.rewardQualityBox.setValue(String.valueOf(this.rewardQuality));
        this.rewardQualityBox.setFilter(s -> s.matches("\\d*"));
        // 用 addRenderableWidget 注册：正确接收鼠标/键盘事件，并由 super.render 自动渲染。
        this.addRenderableWidget(this.rewardQualityBox);

        this.hoverHeightBox = new EditBox(this.font, centerX - 140, centerY - 38, 280, 20,
                Component.translatable("screen.aeronautics_fortress_arise.hover_height"));
        this.hoverHeightBox.setMaxLength(4);
        this.hoverHeightBox.setValue(String.valueOf(this.hoverHeight));
        this.hoverHeightBox.setFilter(s -> s.matches("\\d*"));
        this.addRenderableWidget(this.hoverHeightBox);

        this.rewardMinCountBox = new EditBox(this.font, centerX - 140, centerY - 8, 280, 20,
                Component.translatable("screen.aeronautics_fortress_arise.reward_min_count"));
        this.rewardMinCountBox.setMaxLength(2);
        this.rewardMinCountBox.setValue(String.valueOf(this.rewardMinCount));
        this.rewardMinCountBox.setFilter(s -> s.matches("\\d*"));
        this.addRenderableWidget(this.rewardMinCountBox);

        this.rewardMaxCountBox = new EditBox(this.font, centerX - 140, centerY + 22, 280, 20,
                Component.translatable("screen.aeronautics_fortress_arise.reward_max_count"));
        this.rewardMaxCountBox.setMaxLength(2);
        this.rewardMaxCountBox.setValue(String.valueOf(this.rewardMaxCount));
        this.rewardMaxCountBox.setFilter(s -> s.matches("\\d*"));
        this.addRenderableWidget(this.rewardMaxCountBox);

        // 打开界面时让奖励质量输入框获得焦点，可直接输入。
        this.setInitialFocus(this.rewardQualityBox);
        this.rewardQualityBox.setFocused(true);

        this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.aeronautics_fortress_arise.save"),
                        btn -> this.save())
                .bounds(centerX - 140, centerY + 52, 135, 20)
                .build());

        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.cancel"),
                        btn -> this.onClose())
                .bounds(centerX + 5, centerY + 52, 135, 20)
                .build());
    }

    private void save() {
        int newQuality = Mth.clamp(this.parseInt(this.rewardQualityBox, this.rewardQuality), 1, 5);
        int newHover = Mth.clamp(this.parseInt(this.hoverHeightBox, this.hoverHeight), 1, 256);
        int newMinCount = Mth.clamp(this.parseInt(this.rewardMinCountBox, this.rewardMinCount), 0, 64);
        int newMaxCount = Mth.clamp(this.parseInt(this.rewardMaxCountBox, this.rewardMaxCount), 0, 64);
        if (newMinCount > newMaxCount) {
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(
                        Component.translatable("screen.aeronautics_fortress_arise.reward_count_invalid"), true);
            }
            return;
        }
        AIStructureControllerNetwork.C2SSaveConfigPacket packet =
                new AIStructureControllerNetwork.C2SSaveConfigPacket(
                        new net.minecraft.core.BlockPos(this.x, this.y, this.z),
                        newQuality, newHover, newMinCount, newMaxCount);
        // 客户端发送保存数据包（playToServer 由 SimpleChannel 处理）。
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(packet);
        this.onClose();
    }

    /** 解析输入框数值，解析失败时退回原值。 */
    private int parseInt(EditBox box, int fallback) {
        try {
            return Integer.parseInt(box.getValue().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;

        graphics.drawCenteredString(this.font, this.title, centerX, this.height / 2 - 100, 0xFFFFFF);
        graphics.drawString(this.font,
                Component.translatable("screen.aeronautics_fortress_arise.reward_quality"), centerX - 140, this.height / 2 - 80, 0xA0A0A0);
        graphics.drawString(this.font,
                Component.translatable("screen.aeronautics_fortress_arise.hover_height"), centerX - 140, this.height / 2 - 50, 0xA0A0A0);
        graphics.drawString(this.font,
                Component.translatable("screen.aeronautics_fortress_arise.reward_min_count"), centerX - 140, this.height / 2 - 20, 0xA0A0A0);
        graphics.drawString(this.font,
                Component.translatable("screen.aeronautics_fortress_arise.reward_max_count"), centerX - 140, this.height / 2 + 10, 0xA0A0A0);

        // EditBox 和按钮已通过 addRenderableWidget 注册，由 super.render 统一渲染。
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
