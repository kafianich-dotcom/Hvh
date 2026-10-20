package com.hvh;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * HvH client. Управление командами в чате:
 *   /hv list            - список модулей
 *   /hv killaura        - вкл/выкл (так же sprint, autototem, esp, fullbright)
 *   /hv range 4.2       - дальность KillAura
 */
public class HvhClient implements ClientModInitializer {

    static final List<Module> MODULES = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        MODULES.add(new KillAura());
        MODULES.add(new Sprint());
        MODULES.add(new AutoTotem());
        MODULES.add(new Esp());
        MODULES.add(new Fullbright());

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null || mc.level == null) return;
            for (Module m : MODULES) {
                if (m.enabled) m.onTick(mc);
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommandManager.literal("hv");

            root.then(ClientCommandManager.literal("list").executes(ctx -> {
                StringBuilder sb = new StringBuilder("Модули: ");
                for (Module m : MODULES) {
                    sb.append(m.name).append(m.enabled ? " [ON]  " : " [off]  ");
                }
                ctx.getSource().sendFeedback(Component.literal(sb.toString()));
                return 1;
            }));

            for (Module m : MODULES) {
                root.then(ClientCommandManager.literal(m.name.toLowerCase()).executes(ctx -> {
                    m.toggle();
                    ctx.getSource().sendFeedback(
                            Component.literal(m.name + (m.enabled ? " включен" : " выключен")));
                    return 1;
                }));
            }

            root.then(ClientCommandManager.literal("range").then(
                    ClientCommandManager.argument("value", DoubleArgumentType.doubleArg(1.0, 6.0))
                            .executes(ctx -> {
                                KillAura.range = DoubleArgumentType.getDouble(ctx, "value");
                                ctx.getSource().sendFeedback(
                                        Component.literal("KillAura range = " + KillAura.range));
                                return 1;
                            })));

            dispatcher.register(root);
        });
    }
}

abstract class Module {
    final String name;
    boolean enabled;

    Module(String name) {
        this.name = name;
    }

    void toggle() {
        enabled = !enabled;
        Minecraft mc = Minecraft.getInstance();
        if (enabled) onEnable(mc); else onDisable(mc);
    }

    void onEnable(Minecraft mc) {}
    void onDisable(Minecraft mc) {}
    abstract void onTick(Minecraft mc);
}

class Sprint extends Module {
    Sprint() { super("Sprint"); }

    @Override
    void onTick(Minecraft mc) {
        Player p = mc.player;
        if (mc.options.keyUp.isDown()
                && !p.isShiftKeyDown()
                && !p.horizontalCollision
                && p.getFoodData().getFoodLevel() > 6) {
            p.setSprinting(true);
        }
    }
}

class KillAura extends Module {
    static double range = 4.2;

    KillAura() { super("KillAura"); }

    @Override
    void onTick(Minecraft mc) {
        Player me = mc.player;
        Player target = null;
        double best = Double.MAX_VALUE;

        for (AbstractClientPlayer other : mc.level.players()) {
            if (other == me || !other.isAlive() || other.isSpectator()) continue;
            double d = me.distanceTo(other);
            if (d <= range && d < best) {
                best = d;
                target = other;
            }
        }
        if (target == null) return;

        // Поворот на цель
        double dx = target.getX() - me.getX();
        double dz = target.getZ() - me.getZ();
        double dy = (target.getY() + target.getBbHeight() * 0.5) - me.getEyeY();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        me.setYRot(yaw);
        me.setXRot(pitch);

        // Бьём, когда кулдаун оружия заряжен
        if (me.getAttackStrengthScale(0.5f) >= 1.0f) {
            mc.gameMode.attack(me, target);
            me.swing(InteractionHand.MAIN_HAND);
        }
    }
}

class AutoTotem extends Module {
    private int cooldown;

    AutoTotem() { super("AutoTotem"); }

    @Override
    void onTick(Minecraft mc) {
        if (cooldown > 0) { cooldown--; return; }
        Player p = mc.player;
        if (p.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;

        for (int i = 0; i < 36; i++) {
            if (p.getInventory().getItem(i).is(Items.TOTEM_OF_UNDYING)) {
                int menuSlot = i < 9 ? i + 36 : i;
                // кнопка 40 + SWAP = обмен слота с offhand
                mc.gameMode.handleInventoryMouseClick(
                        p.inventoryMenu.containerId, menuSlot, 40, ClickType.SWAP, p);
                cooldown = 2;
                return;
            }
        }
    }
}

class Esp extends Module {
    Esp() { super("ESP"); }

    @Override
    void onTick(Minecraft mc) {
        for (AbstractClientPlayer other : mc.level.players()) {
            if (other != mc.player) other.setGlowingTag(true);
        }
    }

    @Override
    void onDisable(Minecraft mc) {
        if (mc.level == null) return;
        for (AbstractClientPlayer other : mc.level.players()) {
            other.setGlowingTag(false);
        }
    }
}

class Fullbright extends Module {
    Fullbright() { super("Fullbright"); }

    @Override
    void onTick(Minecraft mc) {
        if (!mc.player.hasEffect(MobEffects.NIGHT_VISION)) {
            mc.player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 1_000_000, 0, false, false));
        }
    }

    @Override
    void onDisable(Minecraft mc) {
        if (mc.player != null) mc.player.removeEffect(MobEffects.NIGHT_VISION);
    }
}
