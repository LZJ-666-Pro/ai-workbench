package com.ai.workbench.core.kb;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * 文本索引器：切片与关键词打分，知识库 MVP 的「索引内核」。
 *
 * 切片：滑动窗口按 chunk_size 切、chunk_overlap 重叠，边界优先落在换行/句号，
 * 保证一片基本是一个完整语义段。切片数、字数都是真实计算值。
 *
 * 检索打分：把问题拆成原词 + 中文 bigram（相邻两字），统计每个切片命中的
 * gram 数占比。纯关键词召回——可解释、零依赖；embedding 接入后此方法
 * 保留为「词面分」，与向量分加权融合即可。
 */
@Component
public class TextIndexer {

    /** 一片索引内容：seq=文档内序号，content=切片文本 */
    public record Chunk(int seq, String content, int words) {
    }

    /** 一条检索命中：score ∈ [0,1]，命中 gram 数 / 问题 gram 总数 */
    public record Hit(long docId, String docTitle, int seq, String content, double score) {
    }

    /** 按目标长度切片；短文本单片直过。normalize 先把 \r\n 与连续空行规整掉 */
    public List<Chunk> chunk(String text, int chunkSize, int chunkOverlap) {
        List<Chunk> result = new ArrayList<>();
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
        if (normalized.isEmpty()) {
            return result;
        }
        int size = Math.max(chunkSize, 100);
        int overlap = Math.min(Math.max(chunkOverlap, 0), size / 2);
        int start = 0;
        int seq = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + size, normalized.length());
            if (end < normalized.length()) {
                end = adjustBoundary(normalized, start, end);
            }
            String piece = normalized.substring(start, end).trim();
            if (!piece.isEmpty()) {
                result.add(new Chunk(seq++, piece, piece.length()));
            }
            if (end >= normalized.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1); // overlap 保证推进，避免死循环
        }
        return result;
    }

    /** 回退到最近的换行/句号，避免把一句话拦腰截断；找不到就用原边界 */
    private int adjustBoundary(String text, int start, int end) {
        int window = Math.max(start, end - 200);
        for (int i = end; i > window; i--) {
            char c = text.charAt(i - 1);
            if (c == '\n' || c == '。' || c == '；' || c == '!' || c == '?' || c == ';') {
                return i;
            }
        }
        return end;
    }

    /** 关键词召回：topN 按分数降序；score = 命中 gram 数 / 问题 gram 总数 */
    public List<Hit> search(String question, List<Candidate> candidates, int topN) {
        Set<String> grams = gramsOf(question);
        if (grams.isEmpty() || candidates.isEmpty()) {
            return List.of();
        }
        record Scored(Candidate c, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (!candidate.enabled() || candidate.content() == null || candidate.content().isBlank()) {
                continue;
            }
            long hits = gramsOf(candidate.content()).stream().filter(grams::contains).count();
            double score = (double) hits / grams.size();
            if (score > 0) {
                scored.add(new Scored(candidate, score));
            }
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scored.stream().limit(Math.max(topN, 1))
                .map(s -> new Hit(s.c().docId(), s.c().docTitle(), s.c().seq(), s.c().content(), round(s.score())))
                .toList();
    }

    /** 检索候选项：调用方从库里拼装（文档标题 + 切片内容） */
    public record Candidate(long docId, String docTitle, int seq, String content, boolean enabled) {
    }

    /**
     * 分词：英文按非字母数字切词（保留 ≥2 长度的词与小写形式），
     * 中文按相邻两字滑窗成 bigram。两 gram 集合都用于命中统计。
     */
    private Set<String> gramsOf(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> grams = new HashSet<>();
        String normalized = text.toLowerCase();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            boolean isCjk = c >= 0x4e00 && c <= 0x9fff;
            boolean isWord = Character.isLetterOrDigit(c);
            if (isWord && !isCjk) {
                // 连续英文/数字段整词收录
                int j = i;
                while (j < normalized.length() && Character.isLetterOrDigit(normalized.charAt(j))
                        && !(normalized.charAt(j) >= 0x4e00 && normalized.charAt(j) <= 0x9fff)) {
                    j++;
                }
                String word = normalized.substring(i, j);
                if (word.length() >= 2) {
                    grams.add(word);
                }
                i = j - 1;
                cjk.setLength(0);
            } else if (isCjk) {
                cjk.append(c);
            } else {
                flushCjk(cjk, grams);
            }
        }
        flushCjk(cjk, grams);
        return grams;
    }

    private void flushCjk(StringBuilder cjk, Set<String> grams) {
        for (int i = 0; i + 1 < cjk.length(); i++) {
            grams.add(cjk.substring(i, i + 2));
        }
        cjk.setLength(0);
    }

    private double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
