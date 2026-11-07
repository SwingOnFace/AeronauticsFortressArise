package com.N28N.aeronautics_fortress_arise.blocks.AIFireController;

import javax.annotation.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;

/**
 * 火控方块的目标火炮模式，决定使用哪一类弹道与是否额外装填药筒。
 */
public enum CannonMode implements StringRepresentable {
    /**
     * 大炮模式：CBC 标准大炮（{@code MountedBigCannonContraption}）。
     * 发射 {@code ProjectileBlock} 类炮弹，通常需要另装药筒。
     */
    BIG_CANNON("big_cannon"),
    /**
     * 标准炮模式：CBC-Military-Supplement 的标准炮（双联炮，{@code MountedDualCannonContraption}）。
     * 发射该模组的 {@code normal_*} 等炮弹，<b>不需要</b>药筒。
     */
    STANDARD_CANNON("standard_cannon");

    private final String name;

    CannonMode(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }

    public Component getDisplayName() {
        return Component.translatable("screen.aeronautics_fortress_arise.cannon_mode." + this.name);
    }

    public CannonMode cycle() {
        CannonMode[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }

    /** 按序号解析（网络传输用），越界时回退到 {@link #BIG_CANNON}。 */
    public static CannonMode byOrdinal(int ordinal) {
        CannonMode[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : BIG_CANNON;
    }

    /** 按名字解析（NBT 读取用），无法识别时回退到 {@link #BIG_CANNON}。 */
    public static CannonMode byName(@Nullable String name) {
        for (CannonMode mode : values()) {
            if (mode.name.equals(name)) {
                return mode;
            }
        }
        return BIG_CANNON;
    }
}
