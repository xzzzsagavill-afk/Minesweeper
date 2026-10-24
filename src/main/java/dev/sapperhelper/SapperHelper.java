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
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;

@Mod(value = SapperHelper.MODID, dist = Dist.CLIENT)
public class SapperHelper {
    public static final String MODID = "sapperhelper";

    private static final int CMD_ONE = 397, CMD_EIGHT = 404, CMD_FLAG = 405, CMD_CLOSED = 406;

    private static final KeyMapping TOGGLE = new KeyMapping(
            "key.sapperhelper.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, "key.categories.misc");

    private static boolean enabled = true;
    private static int mines = 0;
    private static double density = 0.2;

    private int[] lastGrid = null;
    private int lastMines = -1;
    private double[] lastProb = null;

    public SapperHelper(IEventBus modBus) {
        modBus.addListener(this::registerKeys);
        NeoForge.EVENT_BUS.addListener(this::onKey);
        NeoForge.EVENT_BUS.addListener(this::onRender);
    }

    private void registerKeys(RegisterKeyMappingsEvent e) {
        e.register(TOGGLE);
    }

    private static void msg(String s) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(Component.literal(s), true);
    }

    private void onKey(ScreenEvent.KeyPressed.Pre e) {
        if (!(e.getScreen() instanceof AbstractContainerScreen<?> scr) || !(scr.getMenu() instanceof ChestMenu)) return;
        int k = e.getKeyCode();
        if (TOGGLE.matches(k, e.getScanCode())) {
            enabled = !enabled;
            msg("Sapper Helper: " + (enabled ? "ON" : "OFF"));
            e.setCanceled(true);
        } else if (k == GLFW.GLFW_KEY_EQUAL || k == GLFW.GLFW_KEY_KP_ADD) {
            mines = Math.min(mines + 1, 99);
            e.setCanceled(true);
        } else if (k == GLFW.GLFW_KEY_MINUS || k == GLFW.GLFW_KEY_KP_SUBTRACT) {
            mines = Math.max(mines - 1, 0);
            e.setCanceled(true);
        }
    }

    private void onRender(ContainerScreenEvent.Render.Foreground e) {
        if (!(e.getContainerScreen().getMenu() instanceof ChestMenu menu)) return;
        if (!enabled) return;

        final int w = 9, rows = menu.getRowCount(), n = w * rows;
        int[] g = new int[n];
        boolean anyClosed = false;
        for (int i = 0; i < n; i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (s.isEmpty()) { g[i] = 0; continue; }
            CustomModelData cmd = s.get(DataComponents.CUSTOM_MODEL_DATA);
            if (cmd == null) return;
            int v = cmd.value();
            if (v >= CMD_ONE && v <= CMD_EIGHT) g[i] = v - CMD_ONE + 1;
            else if (v == CMD_FLAG || v == CMD_CLOSED) { g[i] = Solver.CLOSED; anyClosed = true; }
            else return;
        }
        if (!anyClosed) return;

        if (lastGrid == null || lastMines != mines || !Arrays.equals(lastGrid, g)) {
            lastGrid = g;
            lastMines = mines;
            lastProb = Solver.solve(g, w, rows, mines, density);
        }
        double[] p = lastProb;

        GuiGraphics gg = e.getGuiGraphics();
        Font font = Minecraft.getInstance().font;

        boolean anySafe = false;
        int certainMines = 0, certainSafe = 0;
        for (int i = 0; i < n; i++) {
            if (g[i] != Solver.CLOSED) continue;
            if (p[i] == 0) { anySafe = true; certainSafe++; }
            else if (p[i] == 1) certainMines++;
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
                gg.fill(RenderType.guiOverlay(), x, y, x + 16, y + 16, 0x99FF0000);
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
                + (anySafe ? "" : " | guess: yellow");
        gg.drawString(font, status, 0, -10, 0xFFFFFF, true);
    }
          }
