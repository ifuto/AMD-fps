package net.amdfaster.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.amdfaster.AmdFasterClient;
import net.amdfaster.platform.AmdArchitecture;
import net.amdfaster.platform.GpuIdentity;
import net.amdfaster.platform.GpuInfo;
import net.amdfaster.platform.GpuReport;
import net.amdfaster.vk.VkContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.nio.file.Path;
import java.util.List;

/**
 * {@code /amdfaster} — surfaces the startup probe in chat so it can be read without digging
 * through {@code latest.log} or finding the JSON file.
 */
public final class AmdFasterCommand {

    private AmdFasterCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("amdfaster")
                    .executes(ctx -> summary(ctx.getSource()))
                    .then(Commands.literal("summary").executes(ctx -> summary(ctx.getSource())))
                    .then(Commands.literal("report").executes(ctx -> report(ctx.getSource())))
                    .then(Commands.literal("extensions").executes(ctx -> extensions(ctx.getSource())))
                    .then(Commands.literal("json").executes(ctx -> jsonPath(ctx.getSource())))
                    .then(Commands.literal("vk").executes(ctx -> vkProbe(ctx.getSource())));

            dispatcher.register(root);
        });
    }

    // ---------------------------------------------------------------------------------------

    private static int summary(CommandSourceStack source) {
        GpuReport report = AmdFasterClient.report();
        if (report == null || !report.succeeded()) {
            send(source, line("Vulkan probe failed: ", ChatFormatting.RED)
                    .append(text(report == null ? "not run yet" : report.failure(), ChatFormatting.YELLOW)));
            return 0;
        }

        send(source, text("AMD-Faster ", ChatFormatting.GOLD).append(text("adapter summary", ChatFormatting.WHITE)));
        for (GpuInfo d : report.devices()) {
            ChatFormatting vendorColour = d.isAmd() ? ChatFormatting.RED : ChatFormatting.GRAY;
            send(source, text("  " + d.deviceName(), vendorColour)
                    .append(text("  [" + GpuIdentity.vendorName(d.vendorId()) + " / " + d.deviceTypeName() + "]",
                            ChatFormatting.DARK_GRAY)));
            send(source, text("    arch: ", ChatFormatting.DARK_GRAY)
                    .append(text(d.architecture().displayName(), ChatFormatting.WHITE))
                    .append(text("   driver: " + d.driverIdName(), ChatFormatting.DARK_GRAY)));
        }

        GpuInfo preferred = report.preferred();
        if (preferred != null) {
            send(source, text("  driving: ", ChatFormatting.DARK_GRAY)
                    .append(text(preferred.deviceName(), ChatFormatting.GREEN)));
        }
        send(source, text("  /amdfaster report | extensions | json", ChatFormatting.DARK_GRAY));
        return 1;
    }

    private static int report(CommandSourceStack source) {
        GpuReport report = AmdFasterClient.report();
        if (report == null || !report.succeeded()) {
            send(source, text("Vulkan probe failed — see the log for details.", ChatFormatting.RED));
            return 0;
        }

        send(source, text("AMD-Faster ", ChatFormatting.GOLD)
                .append(text("Vulkan " + GpuInfo.formatVersion(report.instanceApiVersion()),
                        ChatFormatting.WHITE)));

        for (GpuInfo d : report.devices()) {
            GpuInfo.Limits l = d.limits();
            GpuInfo.Memory m = d.memory();

            send(source, text("[" + d.index() + "] " + d.deviceName(), ChatFormatting.RED));
            send(source, text("  vendor 0x" + Integer.toHexString(d.vendorId())
                    + " device 0x" + Integer.toHexString(d.deviceId())
                    + "  " + d.deviceTypeName() + "  api " + GpuInfo.formatVersion(d.apiVersion()),
                    ChatFormatting.GRAY));
            send(source, text("  driver: " + d.driverIdName()
                    + " | " + orDash(d.driverName()) + " | " + orDash(d.driverInfo()), ChatFormatting.GRAY));
            send(source, text("  arch: ", ChatFormatting.DARK_GRAY)
                    .append(text(d.architecture().displayName(), ChatFormatting.WHITE))
                    .append(text(d.architectureToken() == null ? "" : " (matched '" + d.architectureToken() + "')",
                            ChatFormatting.DARK_GRAY)));
            send(source, text("  subgroup " + l.subgroupSize()
                    + "  SMEM " + GpuInfo.formatBytes(l.maxComputeSharedMemorySize())
                    + "  max WG invocations " + l.maxComputeWorkGroupInvocations()
                    + "  max WG size [" + l.maxComputeWorkGroupSize()[0] + ","
                    + l.maxComputeWorkGroupSize()[1] + "," + l.maxComputeWorkGroupSize()[2] + "]",
                    ChatFormatting.GRAY));
            send(source, text("  DEVICE_LOCAL " + GpuInfo.formatBytes(m.largestDeviceLocalBytes())
                    + "  CPU-writable DEVICE_LOCAL " + GpuInfo.formatBytes(m.largestCpuVisibleDeviceLocalBytes()),
                    ChatFormatting.GRAY));
            send(source, text("  direct CPU write to VRAM: ", ChatFormatting.DARK_GRAY)
                    .append(text(d.hasDirectCpuWritableDeviceMemory() ? "yes" : "no",
                            d.hasDirectCpuWritableDeviceMemory() ? ChatFormatting.GREEN : ChatFormatting.YELLOW))
                    .append(text("   unified memory: ", ChatFormatting.DARK_GRAY))
                    .append(text(d.isUnifiedMemory() ? "yes" : "no", ChatFormatting.GRAY)));
            send(source, text("  extensions: " + d.extensionsPresent().size() + "/"
                    + GpuReport.EXTENSIONS_OF_INTEREST.size() + " present", ChatFormatting.GRAY));

            AmdArchitecture arch = d.architecture();
            if (arch != AmdArchitecture.UNKNOWN) {
                send(source, text("  tuning: " + arch.notes(), ChatFormatting.BLUE));
            }
        }
        return 1;
    }

    private static int extensions(CommandSourceStack source) {
        GpuReport report = AmdFasterClient.report();
        if (report == null || !report.succeeded()) {
            send(source, text("Vulkan probe failed — see the log for details.", ChatFormatting.RED));
            return 0;
        }

        for (GpuInfo d : report.devices()) {
            send(source, text("[" + d.index() + "] " + d.deviceName(), ChatFormatting.RED));
            send(source, text("  present: " + join(d.extensionsPresent()), ChatFormatting.GREEN));
            send(source, text("  missing: " + join(d.extensionsMissing()), ChatFormatting.YELLOW));
        }
        return 1;
    }

    private static int jsonPath(CommandSourceStack source) {
        GpuReport report = AmdFasterClient.report();
        if (report == null) {
            send(source, text("Probe has not run yet.", ChatFormatting.RED));
            return 0;
        }
        Path path = report.writeToFile();
        if (path == null) {
            send(source, text("Could not write the report file.", ChatFormatting.RED));
            return 0;
        }
        send(source, text("Written to ", ChatFormatting.GRAY)
                .append(text(path.toString(), ChatFormatting.AQUA)));
        return 1;
    }

    /**
     * Creates a real {@code VkDevice} against Minecraft's window, prints what the decision layer
     * chose, and tears it down again.
     *
     * <p>A probe, like the Stage 1 report: the renderer is not live yet, and two APIs presenting to
     * one window is undefined behaviour, so the context must not outlive this call. What it proves
     * is that an instance, a device and three queues can be created on this machine and that the
     * adapter, queue-family and memory-type decisions land where they should.
     */
    private static int vkProbe(CommandSourceStack source) {
        long window = Minecraft.getInstance().getWindow().getWindow();
        VkContext context = VkContext.create(window);
        try {
            ChatFormatting colour = context.isAvailable() ? ChatFormatting.GREEN : ChatFormatting.RED;
            for (String line : context.describe().split("\\n")) {
                send(source, text(line, colour));
            }
            return context.isAvailable() ? 1 : 0;
        } finally {
            context.close();
        }
    }

    // ---------------------------------------------------------------------------------------

    private static String join(List<String> values) {
        return values.isEmpty() ? "none" : String.join(", ", values);
    }

    private static String orDash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private static MutableComponent text(String text, ChatFormatting colour) {
        return Component.literal(text).withStyle(colour);
    }

    private static MutableComponent line(String text, ChatFormatting colour) {
        return text(text, colour);
    }

    private static void send(CommandSourceStack source, MutableComponent component) {
        source.sendSuccess(() -> component, false);
    }
}
