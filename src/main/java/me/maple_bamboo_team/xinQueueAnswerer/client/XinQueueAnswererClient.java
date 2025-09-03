package me.maple_bamboo_team.xinQueueAnswerer.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.block.Blocks;
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

    // 主控制变量：如果为 false，则暂停所有消息和位置检测
    private boolean isSystemActive = false;
    // 子控制变量：指示答题功能是否已启动
    private boolean isFunctionEnabled = false;
    private String playerQueuePosition = "";
    private String answerToProcess = null;
    private long queueStartTime = 0;
    private int answeredQuestionCount = 0;
    private long lastPositionCheckTime = 0;
    private boolean hasSentStartupMessage = false;
    private boolean hasSentWelcomeMessage = false;

    // 用于标记排队答题系统是否因排队位置已到而被主动关闭
    // 这样可以避免在排队结束后的无限重启循环
    private boolean sessionEndedForQueueReason = false;

    // 单例的日志文件路径
    private static final Path LOG_FILE_PATH = Paths.get(MinecraftClient.getInstance().runDirectory.getAbsolutePath(), "logs", "debug-autoxinqueue.log");
    // 日志时间格式化工具，作为静态成员以提高性能
    private static final SimpleDateFormat LOG_TIME_FORMATTER = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    // 排队报告时间格式化工具
    private static final SimpleDateFormat TIME_FORMATTER = new SimpleDateFormat("HH:mm:ss");

    // 题库，使用 Map 存储以方便查找 - 使用AutoQueue中的asks
    private static final Map<String, String> QUESTION_BANK = new HashMap<>() {{
        put("红石火把", "15");
        put("猪被闪电", "僵尸猪人");
        put("小箱子能", "27");
        put("开服年份", "2020");
        put("定位末地遗迹", "0");
        put("爬行者被闪电", "高压爬行者");
        put("大箱子能", "54");
        put("羊驼会主动", "不会");
        put("无限水", "3");
        put("挖掘速度最快", "金镐");
        put("凋灵死后", "下界之星");
        put("苦力怕的官方", "爬行者");
        put("南瓜的生长", "不需要");
        put("定位末地", "0");
    }};
    private static final BlockPos QUEUE_COORDINATES = new BlockPos(8, 5, 8);

    // 静态前缀，颜色为固定天蓝色
    private static final MutableText CLIENT_PREFIX = Text.literal("[Maple Client]").setStyle(Style.EMPTY.withColor(TextColor.fromRgb(0x00BFFF)));

    @Override
    public void onInitializeClient() {
        initializeLogFile();
        log("[INFO] Mod initialization started");

        // 监听玩家加入事件，发送欢迎消息并等待排队环境
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            if (client.getCurrentServerEntry() != null && client.getCurrentServerEntry().address.contains("2b2t.xin")) {
                sendWelcomeMessage();
            }
            log("[STATE] Player joined, waiting for queue position message and correct coordinates to start function");
        }));

        // 监听玩家退出事件，进行完整重置
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            // 在玩家退出时，重置所有状态变量，包括新增加的标志
            isSystemActive = false;
            isFunctionEnabled = false;
            playerQueuePosition = "";
            answerToProcess = null;
            queueStartTime = 0;
            answeredQuestionCount = 0;
            lastPositionCheckTime = 0;
            hasSentStartupMessage = false;
            hasSentWelcomeMessage = false;
            // 在玩家退出时，重置新标志，以允许下次进入时正常工作
            sessionEndedForQueueReason = false;
            log("[STATE] Player disconnected, all states reset");
        });

        // 监听客户端接收消息事件
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            // Process the message only if the system is active or if the message contains the "Position" keyword.
            String sanitizedMessage = message.getString().replaceAll("§[0-9a-fk-or]", "").trim();
            if (!sanitizedMessage.isEmpty()) {
                // 修改：使用不区分大小写的匹配
                if (sanitizedMessage.toLowerCase().contains("position") || isSystemActive) {
                    processChatMessage(sanitizedMessage);
                }
            }
        });

        // 监听客户端游戏刻，进行位置和答题检查
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // 如果系统未激活，则不执行任何位置或答题逻辑
            if (!isSystemActive) {
                return;
            }

            if (client.player != null) {
                // 检查玩家是否偏离排队坐标，如果是，则关闭系统
                if (!client.player.getBlockPos().equals(QUEUE_COORDINATES)) {
                    log("[STATE] Player has moved away from queue coordinates, ending session");
                    sessionEndedForQueueReason = true;
                    endSessionAndDisableSystem();
                    return;
                }
                // 检查脚下方块是否为屏障，如果不是，则关闭系统
                if (client.world != null && !client.world.getBlockState(client.player.getBlockPos().down()).isOf(Blocks.BARRIER)) {
                    log("[STATE] Player is no longer standing on a barrier block, ending session as player is likely out of the queue");
                    sessionEndedForQueueReason = true;
                    endSessionAndDisableSystem();
                    return;
                }
            }

            // 如果功能已启用，检查排队位置是否更新
            if (isFunctionEnabled && System.currentTimeMillis() - lastPositionCheckTime >= 10000) {
                checkQueuePosition();
                lastPositionCheckTime = System.currentTimeMillis();
            }

            // 如果有待处理的答案，则发送
            if (isFunctionEnabled && answerToProcess != null && !answerToProcess.isEmpty()) {
                if (client.getNetworkHandler() != null) {
                    client.getNetworkHandler().sendChatMessage(answerToProcess);
                    log("[ACTION] Sent answer: '" + answerToProcess + "'");
                    answeredQuestionCount++;
                    log("[STATS] Questions answered: " + answeredQuestionCount);
                }
                answerToProcess = null;
            }
        });
    }

    private void processChatMessage(String messageString) {
        // 1. 检查是否为 'Position' 消息 (不区分大小写)
        if (messageString.toLowerCase().contains("position")) {
            log("[MESSAGE] Received position message: " + messageString);

            // 检查新增的标志，如果为 true，则不再次启动系统
            if (!isSystemActive && !sessionEndedForQueueReason && MinecraftClient.getInstance().player != null && MinecraftClient.getInstance().player.getBlockPos().equals(QUEUE_COORDINATES)) {
                MinecraftClient client = MinecraftClient.getInstance();
                // 额外检测玩家脚下的方块是否为屏障方块
                if (client.world != null && client.world.getBlockState(client.player.getBlockPos().down()).isOf(Blocks.BARRIER)) {
                    // 玩家在正确的坐标并且脚下是屏障，启动系统
                    isSystemActive = true;
                    isFunctionEnabled = true;
                    queueStartTime = System.currentTimeMillis();
                    log("[STATE] Function enabled by position message, correct coordinates and barrier block check, queue start time recorded");
                    sendStartupMessage();
                }
            }
            // 无论系统是否已激活，只要检测到 "Position"，就更新排队位置
            int index = messageString.toLowerCase().indexOf("queue: ");
            if (index != -1) {
                String currentPosition = messageString.substring(index + "queue: ".length()).trim();
                if (!currentPosition.equals(playerQueuePosition)) {
                    this.playerQueuePosition = currentPosition;
                    log("[STATE] Queue position updated to: " + this.playerQueuePosition);
                }
            }
            return; // 处理完排队消息后直接返回
        }

        // 2. 检查是否为问题消息，该检查只在系统激活时进行
        if (isSystemActive && messageString.contains("丨")) {
            log("[MESSAGE] Detected question message: " + messageString);

            for (String key : QUESTION_BANK.keySet()) {
                if (messageString.contains(key)) {
                    log("[MATCH] Found matching question keyword: '" + key + "'");
                    log("[MATCH] Expected answer: " + QUESTION_BANK.get(key));

                    // 使用AutoQueue的逻辑处理答案
                    String[] abc = new String[]{"A", "B", "C"};
                    for (String s : abc) {
                        if (messageString.contains(s + "." + QUESTION_BANK.get(key))) {
                            answerToProcess = s.toLowerCase();
                            log("[MATCH] Found matching answer: " + answerToProcess + " for question: " + key);
                            log("[STATE] Answer stored for sending: " + answerToProcess);
                            return; // 找到答案后立即返回，避免处理多个问题
                        }
                    }

                    log("[WARN] Found matching question but no valid answer option: " + key);
                    log("[DEBUG] Full message content: " + messageString);
                }
            }
        }
    }

    private void checkQueuePosition() {
        try {
            int position = Integer.parseInt(playerQueuePosition);
            log("[STATE] Checking queue position: " + position);

            if (position <= 2) {
                // 当排队位置到达条件时，关闭系统并设置标志
                log("[STATE] Queue position reached condition (" + position + " <= 2), ending session");
                endSessionAndDisableSystem();
                sessionEndedForQueueReason = true;
            }
        } catch (NumberFormatException e) {
            log("[ERROR] Failed to parse queue position: " + playerQueuePosition);
        }
    }

    // 完整关闭系统的方法
    private void endSessionAndDisableSystem() {
        if (!isSystemActive) return;

        log("[STATE] Starting session end and system disable");

        // 1. 发送统计报告
        endQueueingSessionReport();
        // 2. 停止所有功能
        isSystemActive = false;
        isFunctionEnabled = false;
        // 3. 重置所有变量
        playerQueuePosition = "";
        answerToProcess = null;
        queueStartTime = 0;
        answeredQuestionCount = 0;
        lastPositionCheckTime = 0;
        hasSentStartupMessage = false;
        log("[STATE] Session completely ended, system disabled");
    }

    // 修正统计报告输出问题
    private void endQueueingSessionReport() {
        long queueEndTime = System.currentTimeMillis();
        long totalQueueTimeSeconds = (queueEndTime - queueStartTime) / 1000;
        long minutes = totalQueueTimeSeconds / 60;
        long seconds = totalQueueTimeSeconds % 60;
        String startTimeStr = TIME_FORMATTER.format(new Date(queueStartTime));
        String endTimeStr = TIME_FORMATTER.format(new Date(queueEndTime));
        String playerName = (MinecraftClient.getInstance().player != null) ? MinecraftClient.getInstance().player.getName().getString() : "未知玩家";

        if (MinecraftClient.getInstance().player != null) {
            // 修正：确保所有统计信息都能正确输出
            MutableText header = CLIENT_PREFIX.copy().append(Text.literal(" -------------[排队统计]---------------").setStyle(Style.EMPTY.withColor(Formatting.GREEN)));
            MutableText playerNameText = CLIENT_PREFIX.copy().append(Text.literal(" 玩家名称: " + playerName).setStyle(Style.EMPTY.withColor(Formatting.AQUA)));
            MutableText durationText = CLIENT_PREFIX.copy().append(Text.literal(" 本次排队时长: " + minutes + "分" + seconds + "秒").setStyle(Style.EMPTY.withColor(Formatting.YELLOW)));
            MutableText startTimeText = CLIENT_PREFIX.copy().append(Text.literal(" 开始排队时间: " + startTimeStr).setStyle(Style.EMPTY.withColor(Formatting.GREEN)));
            MutableText endTimeText = CLIENT_PREFIX.copy().append(Text.literal(" 排队结束时间: " + endTimeStr).setStyle(Style.EMPTY.withColor(Formatting.GREEN)));
            MutableText answeredCountText = CLIENT_PREFIX.copy().append(Text.literal(" 自动答题数量: " + answeredQuestionCount).setStyle(Style.EMPTY.withColor(Formatting.GREEN)));
            MutableText footer = CLIENT_PREFIX.copy().append(Text.literal(" -------------------------------------------").setStyle(Style.EMPTY.withColor(Formatting.GREEN)));
            MutableText HHF = Text.literal("\n");
            MutableText endMessage = CLIENT_PREFIX.copy().append(Text.literal(" 排队即将结束(或已结束)，排队自动答题系统已关闭，祝您玩的开心").setStyle(Style.EMPTY.withColor(Formatting.YELLOW)));

            // 发送所有统计信息
            MinecraftClient.getInstance().player.sendMessage(header);
            MinecraftClient.getInstance().player.sendMessage(playerNameText);
            MinecraftClient.getInstance().player.sendMessage(durationText);
            MinecraftClient.getInstance().player.sendMessage(startTimeText);
            MinecraftClient.getInstance().player.sendMessage(endTimeText);
            MinecraftClient.getInstance().player.sendMessage(answeredCountText);
            MinecraftClient.getInstance().player.sendMessage(footer);
            MinecraftClient.getInstance().player.sendMessage(HHF);
            MinecraftClient.getInstance().player.sendMessage(endMessage);

            log("[REPORT] Session ended, total time: " + totalQueueTimeSeconds + "s, questions answered: " + answeredQuestionCount);
            log("[INFO] End-of-queue message sent");
        }
    }

    private void sendStartupMessage() {
        if (MinecraftClient.getInstance().player != null && !hasSentStartupMessage) {
            MutableText message = CLIENT_PREFIX.copy().append(Text.literal(" 已检测到排队环境，排队自动答题系统已启动").setStyle(Style.EMPTY.withColor(Formatting.GREEN)));
            MinecraftClient.getInstance().player.sendMessage(message);
            log("[INFO] Startup message sent to chat");
            hasSentStartupMessage = true;
        }
    }

    private void sendWelcomeMessage() {
        if (MinecraftClient.getInstance().player != null && !hasSentWelcomeMessage) {
            MutableText message = CLIENT_PREFIX.copy().append(Text.literal(" 欢迎使用排队自动答题系统v4.0").setStyle(Style.EMPTY.withColor(Formatting.AQUA)));
            MutableText message4 = CLIENT_PREFIX.copy().append(Text.literal(" 无需排队或优先队列情况下不会启动自动答题").setStyle(Style.EMPTY.withColor(Formatting.AQUA)));
            MutableText message2 = CLIENT_PREFIX.copy().append(Text.literal(" 请关闭聊天后缀或BetterChat,并执行#set chatControl false以关闭baritone简化控制,防止题目答案被错误拦截").setStyle(Style.EMPTY.withColor(Formatting.AQUA)));
            MutableText message3 = CLIENT_PREFIX.copy().append(Text.literal(" Powered by Maple Client").setStyle(Style.EMPTY.withColor(Formatting.AQUA)));
            MinecraftClient.getInstance().player.sendMessage(message);
            MinecraftClient.getInstance().player.sendMessage(message4);
            MinecraftClient.getInstance().player.sendMessage(message2);
            MinecraftClient.getInstance().player.sendMessage(message3);
            log("[INFO] Welcome message sent to player");
            hasSentWelcomeMessage = true;
        }
    }

    /**
     * 初始化日志文件，创建父目录并清空旧日志文件。
     */
    private void initializeLogFile() {
        try {
            Files.createDirectories(LOG_FILE_PATH.getParent());
            Files.deleteIfExists(LOG_FILE_PATH);
            Files.createFile(LOG_FILE_PATH);
            log("[INIT] Log file initialized successfully");
        } catch (IOException e) {
            System.err.println("[ERROR] Failed to initialize log file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 将消息写入日志文件，并附加时间戳。
     * 优化：使用静态的 SimpleDateFormat 减少对象创建开销。
     *
     * @param message 要写入日志的消息
     */
    private void log(String message) {
        try {
            String timestamp = LOG_TIME_FORMATTER.format(new Date());
            Files.writeString(LOG_FILE_PATH, String.format("[%s] %s%n", timestamp, message), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[ERROR] Failed to write to log file: " + e.getMessage());
        }
    }
}