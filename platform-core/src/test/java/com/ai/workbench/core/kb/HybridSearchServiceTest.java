package com.ai.workbench.core.kb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RRF 融合的纯函数单测：融合语义是混合检索正确性的核心，
 * 而它只依赖排名、不依赖任何外部设施——正是纯函数级验证该管的事。
 */
@DisplayName("混合检索 RRF 融合")
class HybridSearchServiceTest {

    private static TextIndexer.Hit hit(long docId, int seq) {
        return new TextIndexer.Hit(docId, "文档" + docId, seq, "切片内容 " + docId + "-" + seq, 0.5);
    }

    @Test
    @DisplayName("两路并集融合：各路第一名的 RRF 分并列最高")
    void fusesUnionOfBothChannels() {
        List<TextIndexer.Hit> merged = HybridSearchService.rrfMerge(
                List.of(hit(1, 0), hit(2, 1)),
                List.of(hit(3, 0)),
                5);
        assertThat(merged).hasSize(3);
        // 每路 rank=1 的贡献都是 1/61，三个第一名分数并列、顺序稳定
        assertThat(merged.get(0).score()).isEqualTo(1.0 / 61);
    }

    @Test
    @DisplayName("两路同时命中同一片：分数叠加排在单路命中之前")
    void overlappingHitRanksFirst() {
        List<TextIndexer.Hit> merged = HybridSearchService.rrfMerge(
                List.of(hit(1, 0), hit(2, 1)),
                List.of(hit(2, 1), hit(3, 2)),
                5);
        // 片 (2,1) 在关键词路排第 2、向量路排第 1，是唯一被两路同时认可的切片
        assertThat(merged.get(0).docId()).isEqualTo(2);
        assertThat(merged.get(0).score()).isEqualTo(1.0 / 62 + 1.0 / 61);
    }

    @Test
    @DisplayName("按 topN 截断；单路为空时退化为另一路")
    void respectsTopNAndEmptyChannel() {
        List<TextIndexer.Hit> onlyKeyword = HybridSearchService.rrfMerge(
                List.of(hit(1, 0), hit(2, 1), hit(3, 2)), List.of(), 2);
        assertThat(onlyKeyword).hasSize(2);
        assertThat(onlyKeyword.get(0).docId()).isEqualTo(1);
    }
}
