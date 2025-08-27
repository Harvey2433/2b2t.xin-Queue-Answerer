package me.maple_bamboo_team.xinQueueAnswerer.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
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
import java.util.HashMap;
import java.util.Map;

public class XinQueueAnswererClient implements ClientModInitializer {

    private boolean isFunctionEnabled = false;
    private String playerQueuePosition = "";
    private String answerToProcess = null;
    private long queueStartTime = 0;
    private int answeredQuestionCount = 0;
    private long lastPositionCheckTime = 0;
    private boolean hasSentStartupMessage = false;
    private boolean isSessionEnded = false;
    private BlockPos previousPlayerPos = null;
    private boolean hasPlayerMovedIntoWorld = false;
    private boolean hasSentWelcomeMessage = false;

    // 单例的日志文件路径
    private static final Path LOG_FILE_PATH = Paths.get(MinecraftClient.getInstance().runDirectory.getAbsolutePath(), "logs", "debug-xin-chat.log");
    private static final SimpleDateFormat TIME_FORMATTER = new SimpleDateFormat("HH:mm:ss");

    // 题库，使用 Map 存储以方便查找
    private static final Map<String, String> QUESTION_BANK = new HashMap<>();

    static {
        QUESTION_BANK.put("挖掘速度最快的镐子是什么?", "金镐");
        QUESTION_BANK.put("本服务器开服年份？", "2020");
        QUESTION_BANK.put("红石火把信号有几格?", "15");
        QUESTION_BANK.put("羊驼会主动攻击人吗?", "不会");
        QUESTION_BANK.put("定位末地要塞至少需要几颗末影之眼?", "0");
        QUESTION_BANK.put("小箱子能储存多少格物品?", "27");
        QUESTION_BANK.put("大箱子能储存多少格物品?", "54");
        QUESTION_BANK.put("南瓜的生长是否需要水?", "不需要");
        QUESTION_BANK.put("无限水至少需要几格空间?", "3");
        QUESTION_BANK.put("凋灵死后会掉落什么?", "下界之星");
    }

    @Override
    public void onInitializeClient() {
        initializeLogFile();
        log("[INFO] Mod initialization started.");

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            client.execute(() -> {
                // 进入新服务器时重置所有状态
                isFunctionEnabled = false;
                isSessionEnded = false;
                playerQueuePosition = "";
                answerToProcess = null;
                queueStartTime = 0;
                answeredQuestionCount = 0;
                lastPositionCheckTime = 0;
                hasSentStartupMessage = false;
                previousPlayerPos = null;
                hasPlayerMovedIntoWorld = false;
                hasSentWelcomeMessage = false;

                if (client.getCurrentServerEntry() != null && client.getCurrentServerEntry().address.contains("2b2t.xin")) {
                    sendWelcomeMessage();
                }
                log("[STATE] Player joined. Awaiting queue position message to start function.");
            });
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            // 只有当会话未结束时才处理聊天消息
            if (isSessionEnded) return;

            // 新增：如果玩家已经移动过，则忽略任何排队消息
            if (hasPlayerMovedIntoWorld && message.getString().contains("Position")) {
                log("[INFO] Player is already in the world. Ignoring late queue message.");
                return;
            }

            String sanitizedMessage = message.getString().replaceAll("§[0-9a-fk-or]", "").trim();
            if (!sanitizedMessage.isEmpty()) {
                processChatMessage(sanitizedMessage);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (isSessionEnded) return;

            if (client.player != null) {
                BlockPos currentPos = client.player.getBlockPos();
                if (previousPlayerPos == null) {
                    previousPlayerPos = currentPos;
                } else if (!previousPlayerPos.equals(currentPos) && !hasPlayerMovedIntoWorld) {
                    // 玩家位置首次发生变化，判断为已进入世界
                    isFunctionEnabled = false;
                    MutableText MovedIntoWorldInfo = Text.literal("[Maple Client] 由于排队人数过少，已终止自动答题\n").setStyle(Style.EMPTY.withColor(Formatting.YELLOW));
                    MinecraftClient.getInstance().player.sendMessage(MovedIntoWorldInfo);
                    hasPlayerMovedIntoWorld = true;
                    log("[INFO] Player's position has changed for the first time. Marking as in-world.");
                }
                previousPlayerPos = currentPos;
            }

            if (isFunctionEnabled && System.currentTimeMillis() - lastPositionCheckTime >= 10000) {
                checkQueuePosition();
                lastPositionCheckTime = System.currentTimeMillis();
            }

            if (isFunctionEnabled && answerToProcess != null && !answerToProcess.isEmpty()) {
                if (client.getNetworkHandler() != null) {
                    client.getNetworkHandler().sendChatMessage(answerToProcess);
                    log("[ACTION] Sent delayed answer: '" + answerToProcess + "'");
                    answeredQuestionCount++;
                }
                answerToProcess = null;
            }
        });
    }

    private void processChatMessage(String messageString) {
        if (messageString.contains("Position")) {
            if (!isFunctionEnabled) {
                isFunctionEnabled = true;
                queueStartTime = System.currentTimeMillis();
                log("[STATE] Function enabled by queue message. Queue start time recorded.");
                sendStartupMessage();
            }

            int index = messageString.indexOf("queue: ");
            if (index != -1) {
                String currentPosition = messageString.substring(index + "queue: ".length()).trim();
                if (!currentPosition.equals(playerQueuePosition)) {
                    this.playerQueuePosition = currentPosition;
                    log("[INFO] Queue position updated to: " + this.playerQueuePosition);
                }
            }
            return;
        }

        if (isFunctionEnabled && messageString.contains("丨")) {
            log("[INFO] Captured potential question: " + messageString);

            for (Map.Entry<String, String> entry : QUESTION_BANK.entrySet()) {
                String question = entry.getKey();
                String answer = entry.getValue();

                if (messageString.contains(question)) {
                    log("[MATCH] Found a matching question: '" + question + "'");
                    log("[DEBUG] Searching for answer: '" + answer + "' in message.");

                    int answerIndex = messageString.indexOf(answer);
                    if (answerIndex != -1) {
                        int optionIndex = -1;
                        for (int i = answerIndex - 1; i >= 0; i--) {
                            char c = messageString.charAt(i);
                            if (Character.isUpperCase(c) && Character.isLetter(c)) {
                                optionIndex = i;
                                break;
                            }
                        }

                        if (optionIndex != -1) {
                            String finalAnswer = messageString.substring(optionIndex, optionIndex + 1);
                            log("[ACTION] Extracted answer: " + finalAnswer);
                            answerToProcess = finalAnswer;
                            log("[STATE] Stored answer for next tick.");
                            break;
                        } else {
                            log("[ERROR] Could not find option letter for answer: " + answer);
                        }
                    } else {
                        log("[ERROR] Answer '" + answer + "' not found in message.");
                    }
                }
            }
        }
    }

    private void checkQueuePosition() {
        try {
            int position = Integer.parseInt(playerQueuePosition);
            if (position <= 5) {
                endQueueingSession();
            }
        } catch (NumberFormatException e) {
            log("[ERROR] Failed to parse queue position: " + playerQueuePosition);
        }
    }

    private void endQueueingSession() {
        if (!isFunctionEnabled) return;

        isFunctionEnabled = false;
        playerQueuePosition = "";
        isSessionEnded = true;

        long queueEndTime = System.currentTimeMillis();
        long totalQueueTimeSeconds = (queueEndTime - queueStartTime) / 1000;
        long minutes = totalQueueTimeSeconds / 60;
        long seconds = totalQueueTimeSeconds % 60;
        String startTimeStr = TIME_FORMATTER.format(new Date(queueStartTime));
        String endTimeStr = TIME_FORMATTER.format(new Date(queueEndTime));
        String playerName = (MinecraftClient.getInstance().player != null) ? MinecraftClient.getInstance().player.getName().getString() : "未知玩家";

        // 仅在玩家对象存在时发送消息
        if (MinecraftClient.getInstance().player != null) {
            MutableText yellowMessage = Text.literal("[Maple Client] 排队即将结束，排队自动答题系统已关闭，祝您玩的开心\n").setStyle(Style.EMPTY.withColor(Formatting.YELLOW));
            MutableText greenMessage = Text.literal("------------[排队统计]--------------").setStyle(Style.EMPTY.withColor(Formatting.GREEN))
                    .append(Text.literal("\n玩家名称: " + playerName).setStyle(Style.EMPTY.withColor(Formatting.AQUA)))
                    .append(Text.literal("\n本次排队时长: " + minutes + "分" + seconds + "秒").setStyle(Style.EMPTY.withColor(Formatting.YELLOW)))
                    .append(Text.literal("\n开始排队时间: " + startTimeStr).setStyle(Style.EMPTY.withColor(Formatting.GREEN)))
                    .append(Text.literal("\n排队结束时间: " + endTimeStr).setStyle(Style.EMPTY.withColor(Formatting.GREEN)))
                    .append(Text.literal("\n自动答题数量: " + answeredQuestionCount).setStyle(Style.EMPTY.withColor(Formatting.GREEN)))
                    .append(Text.literal("\n-------------------------------------------").setStyle(Style.EMPTY.withColor(Formatting.GREEN)));
            MinecraftClient.getInstance().player.sendMessage(greenMessage);
            log("[REPORT] Session ended. Total time: " + totalQueueTimeSeconds + "s, Answered questions: " + answeredQuestionCount);
            MinecraftClient.getInstance().player.sendMessage(yellowMessage);
            log("[INFO] Sent end-of-queue message.");
        }

        log("[REPORT] Session ended. Total time: " + totalQueueTimeSeconds + "s, Answered questions: " + answeredQuestionCount);
        log("[INFO] Sent end-of-queue message.");

        queueStartTime = 0;
        answeredQuestionCount = 0;
        hasSentStartupMessage = false;
    }

    private void sendStartupMessage() {
        if (MinecraftClient.getInstance().player != null && !hasSentStartupMessage) {
            MutableText prefix = Text.literal("[Maple Client] ").setStyle(Style.EMPTY.withColor(TextColor.fromRgb(0x999999)));
            MutableText message = Text.literal("已检测到排队环境，排队自动答题系统已启动").setStyle(Style.EMPTY.withColor(Formatting.GREEN));
            MinecraftClient.getInstance().player.sendMessage(prefix.append(message));
            log("[INFO] Startup message sent to chat.");
            hasSentStartupMessage = true;
        }
    }

    private void sendWelcomeMessage() {
        if (MinecraftClient.getInstance().player != null && !hasSentWelcomeMessage) {
            MutableText welcomeMessage = Text.literal("欢迎使用排队自动答题系统v3 Powered by Maple Client\n请关闭聊天后缀/BetterChat,并执行#set chatControl false以关闭baritone简化控制,防止题目答案被错误拦截").setStyle(Style.EMPTY.withColor(Formatting.DARK_GREEN));
            MinecraftClient.getInstance().player.sendMessage(welcomeMessage);
            log("[INFO] Sent welcome message to player.");
            hasSentWelcomeMessage = true;
        }
    }

    private void initializeLogFile() {
        try {
            Files.createDirectories(LOG_FILE_PATH.getParent());
            Files.deleteIfExists(LOG_FILE_PATH);
            Files.createFile(LOG_FILE_PATH);
            log("[INIT] Log file initialized successfully.");
        } catch (IOException e) {
            System.err.println("[ERROR] Failed to initialize log file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void log(String message) {
        try {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            Files.writeString(LOG_FILE_PATH, String.format("[%s] %s%n", timestamp, message), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[ERROR] Failed to write to log file: " + e.getMessage());
            e.printStackTrace();
        }
    }
}