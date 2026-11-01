package com.N28N.aeronautics_fortress_arise.client.screen;

import com.N28N.aeronautics_fortress_arise.networking.AIStructureControllerNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * AI 结构控制器配置面板。
 * <p>
 * 可单独配置每个控制器的奖励质量、悬浮高度、区块加载范围与奖励数量范围。
 * 通过 {@link AIStructureControllerNetwork.S2COpenScreenPacket} 由服务端携带当前值打开。
 */
public class AIStructureControllerScreen extends Screen {

    /** 表单项行距（像素）。 */
    private static final int ROW_HEIGHT = 24;

    private final int x;
    private final int y;
    private final int z;

    private int rewardQuality;
    private int rewardMinCount;
    private int rewardMaxCount;
    private int loadRange;

    private EditBox rewardQualityBox;
    private EditBox rewardMinCountBox;
    private EditBox rewardMaxCountBox;
    private EditBox loadRangeBox;

    private AIStructureControllerScreen(
            int x, int y, int z, int rewardQuality,int rewardMinCount, int rewardMaxCount,
            int loadRange) {
        super(Component.translatable("screen.aeronautics_fortress_arise.ai_structure_controller"));
        this.x = x;
        this.y = y;
        this.z = z;
        this.rewardQuality = rewardQuality;
        this.rewardMinCount = rewardMinCount;
        this.rewardMaxCount = rewardMaxCount;
        this.loadRange = loadRange;
    }

    /** 由网络包调用，在客户端线程打开面板。 */
    public static void open(AIStructureControllerNetwork.S2COpenScreenPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.setScreen(new AIStructureControllerScreen(
                    packet.pos().getX(), packet.pos().getY(), packet.pos().getZ(),
                    packet.rewardQuality(),
                    packet.rewardMinCount(), packet.rewardMaxCount(), packet.loadRange()));
        }
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int top = this.height / 2 - 3 * ROW_HEIGHT;
        
        this.rewardQualityBox = this.addEditBox(centerX, top,
                "screen.aeronautics_fortress_arise.reward_quality", this.rewardQuality);
        this.loadRangeBox = this.addEditBox(centerX, top + ROW_HEIGHT * 1,
                "screen.aeronautics_fortress_arise.load_range", this.loadRange);
        this.rewardMinCountBox = this.addEditBox(centerX, top + ROW_HEIGHT * 2,
                "screen.aeronautics_fortress_arise.reward_min_count", this.rewardMinCount);
        this.rewardMaxCountBox = this.addEditBox(centerX, top + ROW_HEIGHT * 3,
                "screen.aeronautics_fortress_arise.reward_max_count", this.rewardMaxCount);

        // 打开界面时让奖励质量输入框获得焦点，可直接输入。
        this.setInitialFocus(this.rewardQualityBox);
        this.rewardQualityBox.setFocused(true);

        int buttonY = top + ROW_HEIGHT * 5 + 6;
        this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.aeronautics_fortress_arise.save"),
                        btn -> this.save())
                .bounds(centerX - 140, buttonY, 135, 20)
                .build());

        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.cancel"),
                        btn -> this.onClose())
                .bounds(centerX + 5, buttonY, 135, 20)
                .build());
    }

    private EditBox addEditBox(int centerX, int y, String labelKey, int value) {
        EditBox box = new EditBox(this.font, centerX - 140, y, 280, 20, Component.translatable(labelKey));
        box.setValue(String.valueOf(value));
        // 用 addRenderableWidget 注册：正确接收鼠标/键盘事件，并由 super.render 自动渲染。
        this.addRenderableWidget(box);
        return box;
    }

    private void save() {
        int newQuality = this.parseInt(this.rewardQualityBox, this.rewardQuality);
        int newLoadRange = this.parseInt(this.loadRangeBox, this.loadRange);
        int newMinCount = this.parseInt(this.rewardMinCountBox, this.rewardMinCount);
        int newMaxCount = this.parseInt(this.rewardMaxCountBox, this.rewardMaxCount);
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
                        newQuality,  newMinCount, newMaxCount, newLoadRange);
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
        int top = this.height / 2 - 2 * ROW_HEIGHT;

        graphics.drawCenteredString(this.font, this.title, centerX, top - 32, 0xFFFFFF);
        this.drawLabel(graphics, centerX, top - 10,
                "screen.aeronautics_fortress_arise.reward_quality");
        this.drawLabel(graphics, centerX, top + ROW_HEIGHT - 10,
                "screen.aeronautics_fortress_arise.load_range");
        this.drawLabel(graphics, centerX, top + ROW_HEIGHT * 2 - 10,
                "screen.aeronautics_fortress_arise.reward_min_count");
        this.drawLabel(graphics, centerX, top + ROW_HEIGHT * 3 - 10,
                "screen.aeronautics_fortress_arise.reward_max_count");

        // EditBox 和按钮已通过 addRenderableWidget 注册，由 super.render 统一渲染。
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawLabel(GuiGraphics graphics, int centerX, int y, String labelKey) {
        graphics.drawString(this.font, Component.translatable(labelKey), centerX - 140, y, 0xA0A0A0);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
