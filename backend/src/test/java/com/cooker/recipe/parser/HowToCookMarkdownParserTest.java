package com.cooker.recipe.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cooker.recipe.dto.ParsedRecipe;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class HowToCookMarkdownParserTest {
    private final HowToCookMarkdownParser parser = new HowToCookMarkdownParser();

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void shouldParseHowToCookCompatibilityFixtures(String fixture, boolean successful) throws IOException {
        ParsedRecipe result = parser.parse(readFixture(fixture), "home/" + fixture);
        assertEquals(successful, result.successful());
        assertEquals("home", result.categoryCode());
    }

    static Stream<Arguments> fixtures() {
        return Stream.of(
                Arguments.of("normal.md", true),
                Arguments.of("missing-optional.md", true),
                Arguments.of("range-amount.md", true),
                Arguments.of("optional-ingredient.md", true),
                Arguments.of("fullwidth.md", true),
                Arguments.of("comment-and-image.md", true),
                Arguments.of("custom-unit.md", true),
                Arguments.of("missing-title.md", false),
                Arguments.of("missing-steps.md", false),
                Arguments.of("continuation-step.md", true),
                Arguments.of("tool-and-unknown.md", true),
                Arguments.of("title-variant.md", true));
    }

    @org.junit.jupiter.api.Test
    void shouldKeepStructuredRangeAndOptionalFlags() throws IOException {
        ParsedRecipe range = parser.parse(readFixture("range-amount.md"), "home/range-amount.md");
        assertEquals("10", range.ingredients().getFirst().amountMin().toPlainString());
        assertEquals("15", range.ingredients().getFirst().amountMax().toPlainString());
        // 新人数策略完整解析范围后取下限，材料范围上下限保持不变。
        assertEquals(3, range.servings());
        assertTrue(range.warnings().stream().anyMatch(w -> w.contains("人数依据=RANGE_MIN_AS_BASELINE")));

        ParsedRecipe optional = parser.parse(readFixture("optional-ingredient.md"), "home/optional-ingredient.md");
        assertTrue(optional.ingredients().getFirst().optional());
    }

    @org.junit.jupiter.api.Test
    void shouldRemoveCommentsAndImagesAndKeepUnknownUnitRawLine() throws IOException {
        ParsedRecipe withImage = parser.parse(readFixture("comment-and-image.md"), "home/comment-and-image.md");
        assertEquals("图片菜", withImage.name());
        assertEquals("清爽，10 分钟完成。", withImage.summary());

        ParsedRecipe customUnit = parser.parse(readFixture("custom-unit.md"), "home/custom-unit.md");
        assertFalse(customUnit.warnings().isEmpty());
        assertEquals("盐 适量", customUnit.ingredients().getFirst().rawLine());
        assertNotNull(customUnit.ingredients().getFirst().rawName());
    }

    @org.junit.jupiter.api.Test
    void shouldRecognizeToolsAndJoinContinuationStep() throws IOException {
        ParsedRecipe tools = parser.parse(readFixture("tool-and-unknown.md"), "home/tool-and-unknown.md");
        assertTrue(tools.tools().contains("空气炸锅"));
        ParsedRecipe continuation = parser.parse(readFixture("continuation-step.md"), "home/continuation-step.md");
        assertTrue(continuation.steps().getFirst().contains("加热到表面酥脆"));
    }

    private String readFixture(String fixture) throws IOException {
        try (var input = getClass().getResourceAsStream("/fixtures/howtocook/" + fixture)) {
            assertNotNull(input, fixture);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
