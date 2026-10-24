package dev.sapperhelper;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

@Mod(value = SapperHelper.MODID, dist = Dist.CLIENT)
public class SapperHelper {
    public static final String MODID = "sapperhelper";

    private static final int CMD_ONE = 397, CMD_EIGHT = 404, CMD_FLAG = 405, CMD_CLOSED = 406;

    private static final KeyMapping TOGGLE = new KeyMapping(
            "key.sapperhelper.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, "key.categories.misc");
    private static final KeyMapping AUTO = new KeyMapping(
            "key.sapperhelper.auto", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.misc");

    private static boolean enabled = true;
    private static boolean autoOn = false;
    private static int mines = 0;
    private static double density = 0.2;

    // solve cache
    private int[] lastGrid = null;
    private int lastMines = -1;
    private double[] lastProb = null;

    // auto-click state
    private final Random rnd = new Random();
    private long ticks = 0;
    private int cooldown = 0;
    private int lastSlot = -1;
    private long lastTick = -1000;

    private static final class Board {
        final int rows;
        final int[] g;
        final boolean[] flag;
        Board(int rows, int[] g, boolean[] flag) { this.rows = rows; this.g = g; this.flag = flag; }
    }

    public SapperHelper(IEventBus modBus) {
        modBus.addListener(this::registerKeys);
        NeoForge.EVENT_BUS.addListener(this::onKey);
        NeoForge.EVENT_BUS.addListener(this::onRender);
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onClosing);
    }

    private void registerKeys(RegisterKeyMappingsEvent e) {
        e.register(TOGGLE);
        e.register(AUTO);
    }

    private static void msg(String s) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(Component.literal(s), true);
    }

    private static Board read(ChestMenu menu) {
        final int w = 9, rows = menu.getRowCount(), n = w * rows;
        int[] g = new int[n];
        boolean[] flag = new boolean[n];
        boolean anyClosed = false;
        for (int i = 0; i < n; i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (s.isEmpty()) { g[i] = 0; continue; }
            CustomModelData cmd = s.get(DataComponents.CUSTOM_MODEL_DATA);
            if (cmd == null) return null;
            int v = cmd.value();
            if (v >= CMD_ONE && v <= CMD_EIGHT) g[i] = v - CMD_ONE + 1;
            else if (v == CMD_FLAG || v == CMD_CLOSED) {
                g[i] = Solver.CLOSED;
                flag[i] = (v == CMD_FLAG);
                anyClosed = true;
            } else return null;
        }
        return anyClosed ? new Board(rows, g, flag) : null;
    }

    private double[] solveCached(Board b) {
        if (lastGrid == null || lastMines != mines || !Arrays.equals(lastGrid, b.g)) {
            lastGrid = b.g;
            lastMines = mines;
            lastProb = Solver.solve(b.g, 9, b.rows, mines, density);
        }
        return lastProb;
    }

    private void onKey(ScreenEvent.KeyPressed.Pre e) {
        if (!(e.getScreen() instanceof AbstractContainerScreen<?> scr) || !(scr.getMenu() instanceof ChestMenu)) return;
        int k = e.getKeyCode();
        if (TOGGLE.matches(k, e.getScanCode())) {
            enabled = !enabled;
            if (!enabled) autoOn = false;
            msg("Sapper Helper: " + (enabled ? "ON" : "OFF"));
            e.setCanceled(true);
        } else if (AUTO.matches(k, e.getScanCode())) {
            autoOn = enabled && !autoOn;
            cooldown = 10;
            msg("Sapper Auto-click: " + (autoOn ? "ON" : "OFF"));
            e.setCanceled(true);
        } else if (k == GLFW.GLFW_KEY_EQUAL || k == GLFW.GLFW_KEY_KP_ADD) {
            mines = Math.min(mines + 1, 99);
            e.setCanceled(true);
        } else if (k == GLFW.GLFW_KEY_MINUS || k == GLFW.GLFW_KEY_KP_SUBTRACT) {
            mines = Math.max(mines - 1, 0);
            e.setCanceled(true);
        }
    }

    private void onClosing(ScreenEvent.Closing e) {
        autoOn = false;
    }

    private void onTick(ClientTickEvent.Post e) {
        ticks++;
        if (!autoOn || !enabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof AbstractContainerScreen<?> scr) || !(scr.getMenu() instanceof ChestMenu menu)) {
            autoOn = false;
            return;
        }
        if (mc.player == null || mc.gameMode == null) return;
        if (menu.containerId != mc.player.containerMenu.containerId) return;
        if (!menu.getCarried().isEmpty()) return;
        if (--cooldown > 0) return;

        Board b = read(menu);
        if (b == null) return;
        double[] p = solveCached(b);

        List<Integer> greens = new ArrayList<>();
        List<Integer> reds = new ArrayList<>();
        for (int i = 0; i < b.g.length; i++) {
            if (b.g[i] != Solver.CLOSED || b.flag[i] || Double.isNaN(p[i])) continue;
            if (p[i] == 0) greens.add(i);
            else if (p[i] == 1) reds.add(i);
        }

        int slot = -1, button = 0;
        if (!greens.isEmpty() && greens.size() >= reds.size()) {
            slot = greens.get(0);
            button = 0; // left click: open
        } else if (!reds.isEmpty()) {
            slot = reds.get(0);
            button = 1; // right click: flag
        }
        if (slot < 0) return; // nothing certain: wait for the player

        if (slot == lastSlot && ticks - lastTick < 30) return; // waiting for the server to answer

        mc.gameMode.handleInventoryMouseClick(menu.containerId, menu.getSlot(slot).index, button, ClickType.PICKUP, mc.player);
        lastSlot = slot;
        lastTick = ticks;
        cooldown = 10 + rnd.nextInt(6); // 10..15 ticks
    }

    private void onRender(ContainerScreenEvent.Render.Foreground e) {
        if (!(e.getContainerScreen().getMenu() instanceof ChestMenu menu)) return;
        if (!enabled) return;

        Board b = read(menu);
        if (b == null) return;
        final int n = b.g.length;
        int[] g = b.g;
        double[] p = solveCached(b);

        GuiGraphics gg = e.getGuiGraphics();
        Font font = Minecraft.getInstance().font;

        boolean anySafe = false;
        int certainMines = 0, certainSafe = 0;
        for (int i = 0; i < n; i++) {
            if (g[i] != Solver.CLOSED) continue;
            if (p[i] == 0) { anySafe = true; certainSafe++; }
            else if (p[i] == 1 && !b.flag[i]) certainMines++;
        }

        int best = -1;
        if (!anySafe) {
            double bp = 2;
            for (int i = 0; i < n; i++) {
                if (g[i] == Solver.CLOSED && !Double.isNaN(p[i]) && p[i] < bp) { bp = p[i]; best = i; }
            }
        }

        for (int i = 0; i < n; i++) {
            if (g[i] != Solver.CLOSED || Double.isNaN(p[i])) continue;
            Slot slot = menu.getSlot(i);
            int x = slot.x, y = slot.y;
            if (p[i] == 0) {
                gg.fill(RenderType.guiOverlay(), x, y, x + 16, y + 16, 0x9900FF00);
            } else if (p[i] == 1) {
                if (!b.flag[i]) gg.fill(RenderType.guiOverlay(), x, y, x + 16, y + 16, 0x99FF0000);
            } else {
                if (i == best) {
                    int c = 0xFFFFFF00;
                    gg.fill(RenderType.guiOverlay(), x, y, x + 16, y + 1, c);
                    gg.fill(RenderType.guiOverlay(), x, y + 15, x + 16, y + 16, c);
                    gg.fill(RenderType.guiOverlay(), x, y, x + 1, y + 16, c);
                    gg.fill(RenderType.guiOverlay(), x + 15, y, x + 16, y + 16, c);
                }
                String t = Integer.toString((int) Math.round(p[i] * 100));
                PoseStack ps = gg.pose();
                ps.pushPose();
                ps.translate(x + 2, y + 9, 300);
                ps.scale(0.7f, 0.7f, 1f);
                gg.drawString(font, t, 0, 0, i == best ? 0xFFFF55 : 0xFFFFFF, true);
                ps.popPose();
            }
        }

        String status = "Sapper: safe " + certainSafe + ", mines " + certainMines
                + " | total mines: " + (mines > 0 ? mines : "?") + " (+/-)"
                + (anySafe ? "" : " | guess: yellow")
                + (autoOn ? " | AUTO ON (J)" : " | auto: J");
        gg.drawString(font, status, 0, -10, autoOn ? 0xFFFF55 : 0xFFFFFF, true);
    }
}
