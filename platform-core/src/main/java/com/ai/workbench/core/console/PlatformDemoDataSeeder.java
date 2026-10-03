package com.ai.workbench.core.console;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 工作台演示数据播种：知识库文档、检索日志、面试场次、平台运行事件、LLM 用量历史。
 *
 * 为什么是 ApplicationRunner 而不是 Flyway 迁移：这些数据的价值全在「时间分布」上——
 * 首页要算"今日会话""近 7 天 P95"，如果用迁移写死绝对时间，跑起来第二天就全变成历史数据，
 * 页面立刻空掉。这里用相对当前时间生成，每次全新部署都能看到一个"正在运行"的平台。
 *
 * 为什么只在表为空时播种：真实使用产生的事件与这里造的数据混在一起是对的（它们本来就
 * 属于同一张运行日志表），但不能每次启动都重复插一遍，否则数字会随重启虚增。
 *
 * 说明：这里造的是**平台运行数据**（谁在什么时候用了哪个应用），不碰 bank_account /
 * bank_transaction / bank_audit_log 这些业务与合规表——那三张表里的数字必须来自真实操作。
 */
@Component
public class PlatformDemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformDemoDataSeeder.class);

    private static final int DAYS = 7;

    private final JdbcTemplate jdbc;

    /** 固定种子：每次全新部署生成同一套数据，便于对照与截图 */
    private final Random random = new Random(20261002L);

    public PlatformDemoDataSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seedKnowledgeDocuments();
            seedKnowledgeQueries();
            seedInterviewSessions();
            seedPlatformEvents();
            seedLlmUsage();
            seedDataSources();
        } catch (Exception e) {
            // 演示数据失败不该拦截应用启动：页面显示空态即可，不影响任何真实功能
            log.warn("工作台演示数据播种失败（不影响启动）: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 知识库文档：银行场景下真实会有的几类语料
    // ------------------------------------------------------------------

    private static final String[][] DOC_TITLES = {
            {"个人账户管理办法（2026 修订）", "制度库"},
            {"对公人民币结算账户管理细则", "制度库"},
            {"客户身份识别与尽职调查规程", "制度库"},
            {"反洗钱可疑交易报告操作规程", "制度库"},
            {"个人结售汇业务管理办法", "制度库"},
            {"电子银行业务安全管理办法", "制度库"},
            {"客户投诉处理与回访规范", "制度库"},
            {"印章与重要空白凭证管理办法", "制度库"},
            {"对公转账产品说明书", "产品手册"},
            {"企业网银操作手册（管理员版）", "产品手册"},
            {"个人手机银行功能清单", "产品手册"},
            {"结构性存款产品要素表", "产品手册"},
            {"小微企业信贷产品指引", "产品手册"},
            {"供应链金融应收账款融资说明", "产品手册"},
            {"商业银行资本管理办法（要点摘编）", "监管文件"},
            {"银行业金融机构数据治理指引", "监管文件"},
            {"金融机构客户尽职调查和客户身份资料保存管理办法", "监管文件"},
            {"商业银行互联网贷款管理暂行办法", "监管文件"},
            {"支付结算违法违规行为举报处理要点", "监管文件"},
            {"个人信息保护合规审计要点", "监管文件"},
            {"二季度风险管理委员会会议纪要", "会议纪要"},
            {"科技条线数据中台建设专题会纪要", "会议纪要"},
            {"零售业务季度经营分析会纪要", "会议纪要"},
            {"反诈专班周例会纪要", "会议纪要"},
    };

    private void seedKnowledgeDocuments() {
        if (count("knowledge_document") > 0) {
            return;
        }
        String[] owners = {"admin", "zhangsan", "staff001"};
        String[] types = {"pdf", "docx", "md"};
        List<Object[]> batch = new ArrayList<>();
        // 每份语料生成两个版本（现行版 + 上一版），48 篇的量级与真实制度库相符
        for (String[] doc : DOC_TITLES) {
            for (int version = 0; version < 2; version++) {
                String title = version == 0 ? doc[0] : doc[0] + "（历史版本）";
                int chunks = 12 + random.nextInt(90);
                batch.add(new Object[]{
                        title,
                        doc[1],
                        types[random.nextInt(types.length)],
                        chunks,
                        80 + random.nextInt(2600),
                        // 少量文档仍待向量化，页面才有"待入库"的真实状态
                        random.nextInt(10) == 0 ? 0 : 1,
                        owners[random.nextInt(owners.length)],
                        Timestamp.valueOf(LocalDateTime.now()
                                .minusDays(random.nextInt(120))
                                .minusMinutes(random.nextInt(1440))),
                });
            }
        }
        jdbc.batchUpdate("""
                INSERT INTO knowledge_document
                    (title, source, doc_type, chunks, size_kb, embedded, owner, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, batch);
        log.info("工作台演示数据：写入知识库文档 {} 篇", batch.size());
    }

    private static final String[] QUESTIONS = {
            "对公账户开立需要哪些材料？",
            "个人跨境汇款的年度限额是多少？",
            "可疑交易报告的报送时限是几个工作日？",
            "企业网银管理员如何重置操作员密码？",
            "结构性存款能否提前支取？",
            "小微企业的授信额度上限怎么核定？",
            "客户风险等级多久复评一次？",
            "手机银行转账的日限额是多少？",
            "应收账款融资需要哪些确权材料？",
            "客户投诉的处理时限规定是多久？",
            "反洗钱培训的频次要求是什么？",
            "数据出境需要做哪些合规评估？",
    };

    private void seedKnowledgeQueries() {
        if (count("knowledge_query_log") > 0) {
            return;
        }
        List<Object[]> batch = new ArrayList<>();
        for (int day = 0; day < DAYS; day++) {
            int perDay = day == 0 ? 14 : 12 + random.nextInt(10);
            for (int i = 0; i < perDay; i++) {
                int roll = random.nextInt(100);
                // 约 8% 无命中：真实检索一定有查不到的，全成功反而不可信
                boolean miss = roll < 8;
                batch.add(new Object[]{
                        "knowledge:demo-%d".formatted(random.nextInt(6)),
                        QUESTIONS[random.nextInt(QUESTIONS.length)],
                        miss ? 0 : 1 + random.nextInt(6),
                        // 命中时 120~420ms，未命中时更久（全库扫一遍）
                        miss ? 380 + random.nextInt(260) : 120 + random.nextInt(300),
                        miss ? PlatformEventLogger.FAIL : PlatformEventLogger.SUCCESS,
                        Timestamp.valueOf(at(day, 9, 20)),
                });
            }
        }
        jdbc.batchUpdate("""
                INSERT INTO knowledge_query_log
                    (memory_id, question, hits, latency_ms, result, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, batch);
        log.info("工作台演示数据：写入知识库检索日志 {} 条", batch.size());
    }

    private static final String[][] POSITIONS = {
            {"赵敏", "对公客户经理"}, {"孙浩", "风险控制岗"}, {"周雨", "数据分析师"},
            {"吴迪", "柜面综合柜员"}, {"郑凯", "产品经理"}, {"冯雪", "合规专员"},
            {"陈鹏", "Java 后端工程师"}, {"林菲", "前端工程师"}, {"何军", "运维工程师"},
            {"许静", "客户经理助理"}, {"邓超", "信贷审批岗"}, {"曹阳", "反欺诈建模"},
            {"袁媛", "渠道运营"}, {"范磊", "测试工程师"},
    };

    private void seedInterviewSessions() {
        if (count("interview_session") > 0) {
            return;
        }
        List<Object[]> batch = new ArrayList<>();
        for (String[] person : POSITIONS) {
            int rounds = 3 + random.nextInt(4);
            boolean ongoing = random.nextInt(10) == 0;
            LocalDateTime started = at(random.nextInt(DAYS), 9, 30);
            batch.add(new Object[]{
                    person[0],
                    person[1],
                    ongoing ? 1 + random.nextInt(2) : rounds,
                    // 未结束的不给分：页面上"进行中"的场次没有成绩才合理
                    ongoing ? null : 62 + random.nextInt(33),
                    ongoing ? "ONGOING" : "FINISHED",
                    Timestamp.valueOf(started),
                    ongoing ? null : Timestamp.valueOf(started.plusMinutes(38 + random.nextInt(30))),
            });
        }
        jdbc.batchUpdate("""
                INSERT INTO interview_session
                    (candidate, position, rounds, score, status, started_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, batch);
        log.info("工作台演示数据：写入面试场次 {} 场", batch.size());
    }

    // ------------------------------------------------------------------
    // 平台运行事件：三个应用 + 登录 + 护栏
    // ------------------------------------------------------------------

    private static final String[] TOOL_NAMES = {
            "queryAccount", "listAccounts", "transfer", "queryTransferOrder", "bankOverview",
    };
    private static final String[] BANK_ACTIONS = {
            "查询账户余额与最近交易", "列出可见账户", "创建转账确认单并推送确认卡片",
            "查询确认单真实状态", "查看全行概况",
    };
    private static final String[] KNOWLEDGE_ACTIONS = {
            "制度库检索", "产品手册检索", "监管文件检索",
    };
    private static final String[] USERS = {"zhangsan", "staff001", "corp001"};

    private void seedPlatformEvents() {
        if (count("platform_event_log") > 0) {
            return;
        }
        List<Object[]> batch = new ArrayList<>();
        for (int day = 0; day < DAYS; day++) {
            // 今天的事件只到"当前时刻"为止：否则会出现 23:xx 这种还没发生的日志
            LocalDateTime dayStart = LocalDateTime.now().minusDays(day);
            int workday = dayStart.getDayOfWeek().getValue() <= 5 ? 1 : 0;

            int bankChats = (workday == 1 ? 9 : 3) + random.nextInt(5);
            int knowledgeChats = (workday == 1 ? 6 : 2) + random.nextInt(4);
            int interviewChats = (workday == 1 ? 2 : 1) + random.nextInt(2);

            for (int i = 0; i < bankChats; i++) {
                addChat(batch, PlatformEventLogger.APP_BANK, day, 8 + random.nextInt(10),
                        20 + random.nextInt(40), 900 + random.nextInt(2600));
                // 一次银行对话平均触发 2~4 次工具调用
                int tools = 2 + random.nextInt(3);
                for (int t = 0; t < tools; t++) {
                    int idx = random.nextInt(TOOL_NAMES.length);
                    // 约 7% 被风控/权限拒绝，与真实业务比例相符
                    boolean denied = random.nextInt(100) < 7;
                    batch.add(event(PlatformEventLogger.APP_BANK, PlatformEventLogger.CATEGORY_TOOL,
                            TOOL_NAMES[idx],
                            denied ? "调用被拒：" + BANK_ACTIONS[idx] : BANK_ACTIONS[idx],
                            denied ? PlatformEventLogger.DENY : PlatformEventLogger.SUCCESS,
                            USERS[random.nextInt(USERS.length)],
                            PlatformEventLogger.APP_BANK + ":%s:demo".formatted(USERS[random.nextInt(USERS.length)]),
                            null, null, at(day, 8 + random.nextInt(10), random.nextInt(60))));
                }
            }
            for (int i = 0; i < knowledgeChats; i++) {
                int idx = random.nextInt(KNOWLEDGE_ACTIONS.length);
                boolean miss = random.nextInt(100) < 8;
                batch.add(event(PlatformEventLogger.APP_KNOWLEDGE, PlatformEventLogger.CATEGORY_SEARCH,
                        "knowledge.search", KNOWLEDGE_ACTIONS[idx] + "：" + QUESTIONS[random.nextInt(QUESTIONS.length)],
                        miss ? PlatformEventLogger.FAIL : PlatformEventLogger.SUCCESS,
                        null, "knowledge:demo-%d".formatted(random.nextInt(6)),
                        120 + random.nextInt(420), null, at(day, 9 + random.nextInt(9), random.nextInt(60))));
            }
            for (int i = 0; i < interviewChats; i++) {
                batch.add(event(PlatformEventLogger.APP_INTERVIEW, PlatformEventLogger.CATEGORY_INTERVIEW,
                        "interview.turn", "面试追问与结构化评分",
                        PlatformEventLogger.SUCCESS, null,
                        "interview:demo-%d".formatted(random.nextInt(5)),
                        420 + random.nextInt(900), 600 + random.nextInt(1800),
                        at(day, 10 + random.nextInt(8), random.nextInt(60))));
            }
            // 登录事件：每个工作日都有人登录
            for (int i = 0; i < (workday == 1 ? 3 : 1); i++) {
                String user = USERS[random.nextInt(USERS.length)];
                boolean failed = random.nextInt(100) < 12;
                batch.add(event(PlatformEventLogger.APP_PLATFORM, PlatformEventLogger.CATEGORY_AUTH,
                        "auth.login", failed ? "登录失败：用户名或密码错误" : "登录成功（用户）",
                        failed ? PlatformEventLogger.FAIL : PlatformEventLogger.SUCCESS, user, null,
                        null, null, at(day, 8, random.nextInt(60))));
            }
            // 护栏拒绝：偶发，让首页的"护栏拦截"通知有真实来源
            if (random.nextInt(100) < 45) {
                int rejects = 1 + random.nextInt(3);
                for (int i = 0; i < rejects; i++) {
                    batch.add(event(PlatformEventLogger.APP_BANK, PlatformEventLogger.CATEGORY_GUARD,
                            "guard.reject", "对话被拒绝：提问过于频繁，请稍后再试。",
                            PlatformEventLogger.DENY, null,
                            "bank:%s:demo".formatted(USERS[random.nextInt(USERS.length)]),
                            null, null, at(day, 9 + random.nextInt(9), random.nextInt(60))));
                }
            }
        }
        jdbc.batchUpdate("""
                INSERT INTO platform_event_log
                    (app, category, action, actor, memory_id, detail, result,
                     duration_ms, tokens, trace_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, batch);
        log.info("工作台演示数据：写入平台运行事件 {} 条", batch.size());
    }

    private void addChat(List<Object[]> batch, String app, int day, int hour, int minute, int tokens) {
        boolean failed = random.nextInt(100) < 6;
        batch.add(event(app, PlatformEventLogger.CATEGORY_CHAT, "agent.chat",
                failed ? "对话失败：模型服务暂时繁忙，请稍后再试"
                        : "对话完成，回复约 %d token".formatted(tokens),
                failed ? PlatformEventLogger.FAIL : PlatformEventLogger.SUCCESS,
                null, "%s:demo-%d".formatted(app, random.nextInt(8)),
                failed ? null : 300 + random.nextInt(2400),
                failed ? null : tokens,
                at(day, hour, minute)));
    }

    private Object[] event(String app, String category, String action, String detail, String result,
                           String actor, String memoryId, Integer durationMs, Integer tokens,
                           LocalDateTime time) {
        return new Object[]{
                app, category, action, actor, memoryId, truncate(detail), result,
                durationMs, tokens, null, Timestamp.valueOf(time),
        };
    }

    // ------------------------------------------------------------------
    // LLM 用量历史（只造过去，不占今天的配额）
    // ------------------------------------------------------------------

    private void seedLlmUsage() {
        if (count("llm_usage") > 0) {
            return;
        }
        // 平台支持改环境变量换模型，历史上用过多个模型是合理的；
        // 只写过去的日子——写今天会把当天的免费额度提前占掉，真跑来对话就被自己的演示数据限流了。
        String[][] models = {
                {"qwen3.8-flash", "70"}, {"deepseek-chat", "20"}, {"glm-4.7-flash", "10"},
        };
        List<Object[]> batch = new ArrayList<>();
        for (int day = 1; day <= DAYS; day++) {
            for (String[] model : models) {
                int calls = 3 + random.nextInt(10);
                String scope = switch (model[1]) {
                    case "70" -> "bank:zhangsan";
                    case "20" -> "bank:corp001";
                    default -> "bank:staff001";
                };
                batch.add(new Object[]{
                        scope, scope + ":demo", PlatformEventLogger.APP_BANK, model[0],
                        calls * (600 + random.nextInt(900)),
                        calls * (120 + random.nextInt(260)),
                        Timestamp.valueOf(at(day, 9 + random.nextInt(9), random.nextInt(60))),
                });
            }
        }
        jdbc.batchUpdate("""
                INSERT INTO llm_usage
                    (usage_scope, memory_id, agent, model, input_tokens, output_tokens, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, batch);
        log.info("工作台演示数据：写入 LLM 用量 {} 条", batch.size());
    }

    // ------------------------------------------------------------------
    // 数据源：平台连接登记的演示样例（与 V7__data_source.sql 的表结构对应）
    // ------------------------------------------------------------------

    private void seedDataSources() {
        if (count("data_source") > 0) {
            return;
        }
        // 前两条指向本机真实运行的 MySQL / pgvector 容器，「测试连接」能真实通过；
        // 后两条是演示域名，开发环境探活失败才是常态，页面因此才有 ERROR 状态可看。
        Object[][] sources = {
                {"客户信息主库", "DATABASE", "mysql",
                        "{\"host\":\"localhost\",\"port\":13306,\"database\":\"ai_workbench\",\"username\":\"root\",\"password\":\"204512\"}",
                        "OK", "连接成功（MySQL Community Server）", 12, "银行助手的核心业务库，账户与交易数据的唯一权威来源"},
                {"知识库向量索引", "VECTOR", "pgvector",
                        "{\"host\":\"localhost\",\"port\":15432,\"database\":\"ai_workbench_vec\",\"username\":\"postgres\",\"password\":\"ai123456\",\"collection\":\"kb_docs\"}",
                        "OK", "连接成功（PostgreSQL 16 + pgvector）", 35, "知识库文档向量化后的存储与检索底座"},
                {"产品制度文档库", "DOCUMENT", "oss",
                        "{\"location\":\"https://aiwb-demo.oss-cn-beijing.aliyuncs.com/docs\",\"format\":\"pdf/docx\"}",
                        "OK", "存储可达，HTTP 200", 88, "制度库与产品手册的对象存储桶，知识库同步语料的来源"},
                {"风险规则服务", "API", "http",
                        "{\"url\":\"https://risk-gateway.demo.internal/api/v1/rules\",\"method\":\"GET\"}",
                        "ERROR", "UnknownHostException：risk-gateway.demo.internal", null,
                        "转账风控规则的下发接口，部署在银行内网"},
        };
        // 绑定关系：数据源被哪些 Agent 使用（scope = 数据权限范围）
        String[][] bindings = {
                {"客户信息主库", "bank", "WRITE"},
                {"知识库向量索引", "knowledge", "READ"},
                {"产品制度文档库", "knowledge", "READ"},
                {"风险规则服务", "bank", "READ"},
        };
        for (Object[] src : sources) {
            jdbc.update("""
                    INSERT INTO data_source
                        (name, type, engine, description, config, status, status_msg,
                         last_sync_at, last_latency_ms, owner)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'admin')
                    """, src[0], src[1], src[2], src[7], src[3], src[4], src[5],
                    Timestamp.valueOf(LocalDateTime.now().minusMinutes(20 + random.nextInt(600))),
                    src[6]);
        }
        for (String[] binding : bindings) {
            jdbc.update("""
                    INSERT IGNORE INTO data_source_binding (source_id, agent_key, scope)
                    SELECT id, ?, ? FROM data_source WHERE name = ?
                    """, binding[1], binding[2], binding[0]);
        }
        log.info("工作台演示数据：写入数据源 {} 个（含绑定关系 {} 条）", sources.length, bindings.length);
    }

    // ------------------------------------------------------------------

    private long count(String table) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return n == null ? 0L : n;
    }

    /** 第 day 天前的某个时刻；day=0 表示今天 */
    private LocalDateTime at(int dayAgo, int hour, int minute) {
        return LocalDateTime.now().minusDays(dayAgo).withHour(hour).withMinute(minute)
                .withSecond(random.nextInt(60)).withNano(0);
    }

    private static String truncate(String text) {
        return text != null && text.length() > 500 ? text.substring(0, 500) : text;
    }
}
