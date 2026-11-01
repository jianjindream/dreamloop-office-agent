package com.jianjin.assistant.service.office;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReviewDiffServiceTest {
    @Test
    void reportsAddedAndRemovedLines() {
        ReviewDiffService.Result result = new ReviewDiffService().compare("标题\n旧内容", "标题\n新内容\n补充");
        assertEquals(2, result.added());
        assertEquals(1, result.removed());
        assertEquals(1, result.unchanged());
    }
}
