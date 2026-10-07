package dev.chatmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatModClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("chatmoderator");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private record Pending(String nick, String msg, Detector.Verdict verdict, List<String> context, long readyTick) {}

    private Config cfg;
    private Detector detector;
    private Pattern chatPattern;

    private final Deque<String> recent = new ArrayDeque<>();
    private final Queue<Pending> queue = new ArrayDeque<>();
    private final Map<String, Long> cooldown = new HashMap<>();
    private long tick = 0;
    private long nextAllowedTick = 0;

    @Override
    public void onInitializeClient() {
        reload();

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) handleLine(message.getString());
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) -> {
            if (sender != null && signed != null) {
                String line = message.getString();
                addRecent(line);
                handle(sender.getName(), signed.getContent().getString());
            } else {
                handleLine(message.getString());
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> onTick());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> registerCommands(dispatcher));
        LOG.info("Chat Moderator загружен");
    }

    private void reload() {
        cfg = Config.load();
        detector = new Detector(cfg);
        chatPattern = Pattern.compile(cfg.chatRegex);
    }

    // ---------- приём сообщений ----------

    private void addRecent(String line) {
        recent.addLast("[" + LocalTime.now().format(TIME) + "] " + line);
        while (recent.size() > 8) recent.pollFirst();
    }

    private void handleLine(String raw) {
        String line = raw.replaceAll("§.", "");
        addRecent(line);
        Matcher m = chatPattern.matcher(line);
        if (m.matches()) {
            handle(m.group("nick"), m.group("msg"));
        }
    }

    private void handle(String nick, String msg) {
        if (!cfg.enabled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        if (nick.equalsIgnoreCase(mc.getSession().getUsername())) return;
        for (String ign : cfg.ignoredPlayers) if (nick.equalsIgnoreCase(ign)) return;
        if (cfg.requireOnline && mc.getNetworkHandler() != null
                && mc.getNetworkHandler().getPlayerListEntry(nick) == null) return;

        if (cfg.debug) {
            LOG.info("[debug] nick={} msg={}", nick, msg);
            mc.player.sendMessage(Text.literal("[ChatMod] nick=" + nick + " | " + msg).formatted(Formatting.DARK_GRAY), false);
        }

        Detector.Verdict v = detector.check(nick, msg, true);
        if (v == null) return;
        if (!cfg.rules.containsKey(v.rule())) return;

        String key = nick.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        Long last = cooldown.get(key);
        if (last != null && now - last < cfg.punishCooldownSeconds * 1000L) return;
        cooldown.put(key, now);
        detector.clear(nick);

        queue.add(new Pending(nick, msg, v, new ArrayList<>(recent), tick + 4));
    }

    // ---------- выдача наказаний ----------

    private void onTick() {
        tick++;
        Pending p = queue.peek();
        if (p == null || tick < p.readyTick() || tick < nextAllowedTick) return;
        queue.poll();
        nextAllowedTick = tick + cfg.commandDelayTicks;
        try {
            execute(p);
        } catch (Exception e) {
            LOG.error("Ошибка при выдаче наказания", e);
        }
    }

    private void execute(Pending p) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        Config.RuleCfg r = cfg.rules.get(p.verdict().rule());
        String template = r.ip ? cfg.muteIpCommand : cfg.muteCommand;
        String cmd = template
                .replace("{nick}", p.nick())
                .replace("{time}", r.time)
                .replace("{reason}", r.reason);

        byte[] png = screenshot(mc);

        if (!cfg.dryRun) {
            mc.getNetworkHandler().sendChatCommand(cmd);
        }
        Webhook.send(cfg.webhookUrl, buildPayload(p, r, cmd, png != null), png);

        mc.player.sendMessage(Text.literal("[ChatMod] " + (cfg.dryRun ? "(ТЕСТ) " : "") + "/" + cmd
                + "  — " + p.verdict().detail()).formatted(Formatting.GOLD), false);
    }

    private byte[] screenshot(MinecraftClient mc) {
        try (NativeImage img = ScreenshotRecorder.takeScreenshot(mc.getFramebuffer())) {
            Path tmp = Files.createTempFile("chatmod", ".png");
            try {
                img.writeTo(tmp);
                return Files.readAllBytes(tmp);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (Exception e) {
            LOG.warn("Не удалось сделать скриншот", e);
            return null;
        }
    }

    private JsonObject buildPayload(Pending p, Config.RuleCfg r, String cmd, boolean hasImage) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "🚨 Нарушение правил чата — п. " + p.verdict().rule());
        embed.addProperty("color", 0xE74C3C);
        embed.addProperty("timestamp", Instant.now().toString());

        JsonArray fields = new JsonArray();
        fields.add(field("Игрок", "`" + p.nick() + "`", true));
        fields.add(field("Правило", p.verdict().rule() + " — " + r.reason, true));
        fields.add(field("Сработало", clip(p.verdict().detail(), 200), false));
        fields.add(field("Наказание", (cfg.dryRun ? "ТЕСТ, не выдано: " : "") + "`/" + clip(cmd, 300) + "`", false));
        fields.add(field("Сообщение", "```\n" + clean(clip(p.msg(), 500)) + "\n```", false));

        String ctx = String.join("\n", p.context());
        if (ctx.length() > 900) ctx = ctx.substring(ctx.length() - 900);
        fields.add(field("Контекст чата", "```\n" + clean(ctx) + "\n```", false));
        embed.add("fields", fields);

        if (hasImage) {
            JsonObject image = new JsonObject();
            image.addProperty("url", "attachment://screenshot.png");
            embed.add("image", image);
        }

        JsonArray embeds = new JsonArray();
        embeds.add(embed);

        JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray()); // чтобы @everyone из чата не пинговал

        JsonObject payload = new JsonObject();
        payload.addProperty("username", "ChatMod");
        payload.add("embeds", embeds);
        payload.add("allowed_mentions", allowed);
        return payload;
    }

    private static JsonObject field(String name, String value, boolean inline) {
        JsonObject f = new JsonObject();
        f.addProperty("name", name);
        f.addProperty("value", value.isEmpty() ? "—" : clip(value, 1024));
        f.addProperty("inline", inline);
        return f;
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String clean(String s) {
        return s.replace("```", "'''");
    }

    // ---------- команды /chatmod ----------

    private void registerCommands(com.mojang.brigadier.CommandDispatcher<FabricClientCommandSource> d) {
        d.register(ClientCommandManager.literal("chatmod")
                .then(ClientCommandManager.literal("on").executes(c -> {
                    cfg.enabled = true;
                    cfg.save();
                    return say(c, "Включён");
                }))
                .then(ClientCommandManager.literal("off").executes(c -> {
                    cfg.enabled = false;
                    cfg.save();
                    return say(c, "Выключен");
                }))
                .then(ClientCommandManager.literal("dry").executes(c -> {
                    cfg.dryRun = !cfg.dryRun;
                    cfg.save();
                    return say(c, "Тестовый режим (без мутов): " + (cfg.dryRun ? "ВКЛ" : "ВЫКЛ"));
                }))
                .then(ClientCommandManager.literal("debug").executes(c -> {
                    cfg.debug = !cfg.debug;
                    cfg.save();
                    return say(c, "Отладка (показывать распознанные ник+сообщение): " + (cfg.debug ? "ВКЛ" : "ВЫКЛ"));
                }))
                .then(ClientCommandManager.literal("reload").executes(c -> {
                    reload();
                    return say(c, "Конфиг перезагружен");
                }))
                .then(ClientCommandManager.literal("test")
                        .then(ClientCommandManager.argument("text", StringArgumentType.greedyString()).executes(c -> {
                            String t = StringArgumentType.getString(c, "text");
                            Detector.Verdict v = detector.check("TestUser", t, false);
                            return say(c, v == null ? "Нарушений нет"
                                    : "Нарушение п. " + v.rule() + " — " + v.detail());
                        })))
        );
    }

    private int say(CommandContext<FabricClientCommandSource> c, String s) {
        c.getSource().sendFeedback(Text.literal("[ChatMod] " + s).formatted(Formatting.AQUA));
        return 1;
    }
}
