package me.maple_bamboo_team.xinQueueAnswerer.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;

public class XinQueueAnswererClient implements ClientModInitializer {

    private static final BlockPos TRIGGER_POSITION = new BlockPos(8, 5, 8);
    private boolean isFunctionEnabled = false;

    // 单例的日志文件路径
    private static final Path LOG_FILE_PATH = Paths.get(MinecraftClient.getInstance().runDirectory.getAbsolutePath(), "logs", "debug-xin-chat.log");
    private static final Path CHAT_FILE_PATH = Paths.get(MinecraftClient.getInstance().runDirectory.getAbsolutePath(), "chat.txt");

    @Override
    public void onInitializeClient() {
        log("Mod initialization started.");

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            client.execute(() -> {
                log("Player joined. Checking conditions...");
                checkAndEnableFunction(client.player);
            });
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (isFunctionEnabled) {
                String messageString = message.getString().trim();
                if (!messageString.isEmpty()) {
                    saveChatMessage(messageString);
                }
            }
        });
    }

    private void checkAndEnableFunction(net.minecraft.client.network.ClientPlayerEntity player) {
        if (player != null && player.getEntityWorld().getRegistryKey().equals(net.minecraft.world.World.OVERWORLD)) {
            BlockPos playerPos = player.getBlockPos();
            log("Current player position: " + playerPos.toShortString());
            if (playerPos.getX() == TRIGGER_POSITION.getX() && playerPos.getY() == TRIGGER_POSITION.getY() && playerPos.getZ() == TRIGGER_POSITION.getZ()) {
                isFunctionEnabled = true;
                log("Trigger conditions met. Function enabled.");
                sendStartupMessage();
            } else {
                isFunctionEnabled = false;
                log("Trigger conditions not met. Function disabled.");
            }
        } else {
            isFunctionEnabled = false;
            log("Not in overworld or player is null. Function disabled.");
        }
    }

    private void sendStartupMessage() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            MutableText prefix = Text.literal("[Maple Client] ").setStyle(Style.EMPTY.withColor(TextColor.fromRgb(0x999999)));
            MutableText message = Text.literal("环境检测成功，排队自动答题系统已启动").setStyle(Style.EMPTY.withColor(Formatting.GREEN));
            client.player.sendMessage(prefix.append(message));
            log("Startup message sent to chat.");
        }
    }

    /**
     * 将消息写入专门的 chat.txt 文件，并在写入前进行去重检查
     * @param message 要写入的消息
     */
    private void saveChatMessage(String message) {
        try {
            // 确保 chat.txt 文件的父目录存在
            Files.createDirectories(CHAT_FILE_PATH.getParent());
            String keywordToCheck;

            // 根据消息中是否存在 | 符号来决定去重关键字
            int separatorIndex = message.indexOf('|');
            if (separatorIndex != -1) {
                // 如果存在 |，使用前面的部分作为关键字
                keywordToCheck = message.substring(0, separatorIndex).trim();
            } else {
                // 如果不存在 |，使用整条消息作为关键字
                keywordToCheck = message;
            }

            // 检查文件是否存在，如果不存在，则直接写入第一条消息
            if (!Files.exists(CHAT_FILE_PATH)) {
                Files.writeString(CHAT_FILE_PATH, message + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                log("New chat.txt file created. Wrote first message: " + message);
                return;
            }

            // 读取文件内容进行去重检查
            String fileContent = Files.readString(CHAT_FILE_PATH);
            // 核心去重逻辑：检查整个文件内容是否包含关键字
            if (fileContent.contains(keywordToCheck)) {
                log("Message containing keyword '" + keywordToCheck + "' already exists. Skipping write.");
            } else {
                // 如果消息不存在，则追加写入整条新消息
                Files.writeString(CHAT_FILE_PATH, message + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                log("Successfully wrote new message to chat.txt: " + message);
            }

        } catch (IOException e) {
            log("Failed to write to chat.txt: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 将消息写入日志文件
     * @param message 要写入的消息
     */
    private void log(String message) {
        try {
            // 确保日志目录存在
            Files.createDirectories(LOG_FILE_PATH.getParent());
            // 写入日志，使用追加模式
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            Files.writeString(LOG_FILE_PATH, String.format("[%s] %s%n", timestamp, message), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[Maple Client] Failed to write to log file: " + e.getMessage());
            e.printStackTrace();
        }
    }
}