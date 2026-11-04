package com.github.libretube.voiceover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeAiSummaryParserTest {
    @Test
    fun findsSummaryInInitialWatchHtml() {
        val html = """
            <html><head></head><script>
            var ytInitialData = {
              "contents": {
                "expandableMetadataRenderer": {
                  "header": {
                    "collapsedTitle": { "simpleText": "Hannah Bell introduces..." },
                    "expandedTitle": { "simpleText": "Краткий пересказ от ИИ" },
                    "expandedSubtitle": { "simpleText": "ИИ может ошибаться. Перепроверяйте ответы." }
                  },
                  "expandedContent": {
                    "videoSummaryContentViewModel": {
                      "paragraphs": [
                        {
                          "videoSummaryParagraphViewModel": {
                            "text": {
                              "content": "Hannah Bell introduces fundamental concepts of computational thinking, distinguishing between declarative and imperative knowledge."
                            }
                          }
                        },
                        {
                          "videoSummaryParagraphViewModel": {
                            "text": {
                              "content": "The lecture covers basic Python primitives, syntax rules, and the role of computers in executing algorithmic recipes."
                            }
                          }
                        }
                      ]
                    }
                  },
                  "trackingParams": "unrelated id"
                }
              }
            };
            </script></html>
        """.trimIndent()
        assertEquals(
            "Hannah Bell introduces fundamental concepts of computational thinking, distinguishing between declarative and imperative knowledge.\n\n" +
                "The lecture covers basic Python primitives, syntax rules, and the role of computers in executing algorithmic recipes.",
            YouTubeAiSummaryParser.extractFromWatchHtml(html)
        )
    }

    @Test
    fun ignoresDescriptionsAndAiWarningWithoutActualSummary() {
        val html = """
            "videoDescription": "Hannah Bell introduces fundamental concepts of computational thinking",
            "expandableMetadataRenderer": {
              "header": {
                "expandedTitle": { "simpleText": "Краткий пересказ от ИИ" },
                "expandedSubtitle": { "simpleText": "ИИ может ошибаться. Перепроверяйте ответы." }
              }
            }
        """.trimIndent()
        assertNull(YouTubeAiSummaryParser.extractFromWatchHtml(html))
    }

    @Test
    fun handlesEscapedQuotesAndNestedBraces() {
        val html = """
            "expandableMetadataRenderer": {
              "header": { "collapsedTitle": { "simpleText": "some { braces }" } },
              "expandedContent": {
                "videoSummaryContentViewModel": {
                  "paragraphs": [
                    { "videoSummaryParagraphViewModel": {
                      "text": { "content": "The speaker explains a \"program\" with nested {curly} braces and the process of finding a solution." }
                    }}
                  ]
                }
              }
            }
        """.trimIndent()
        assertEquals(
            """The speaker explains a "program" with nested {curly} braces and the process of finding a solution.""",
            YouTubeAiSummaryParser.extractFromWatchHtml(html)
        )
    }

    @Test
    fun rejectsMalformedAndVeryShortSummaries() {
        assertNull(YouTubeAiSummaryParser.extractFromWatchHtml(
            """"expandableMetadataRenderer": { "expandedContent": {"""
        ))
        assertNull(YouTubeAiSummaryParser.extractFromWatchHtml(
            """"expandableMetadataRenderer": {
              "expandedContent": {
                "videoSummaryContentViewModel": {
                  "paragraphs": [{"videoSummaryParagraphViewModel":{"text":{"content":"Too short"}}}]
                }
              }
            }"""
        ))
    }

    @Test
    fun skipsFirstNonAiBlockAndFindsSecondSummary() {
        val html = """
            "expandableMetadataRenderer": { "header": { "expandedTitle": { "simpleText": "Other metadata" } } }
            "expandableMetadataRenderer": { "expandedContent": {
              "videoSummaryContentViewModel": { "paragraphs": [
                {"videoSummaryParagraphViewModel": {"text": {
                  "content": "This is a substantial AI recap provided by YouTube for a test lecture about basic computer science."
                }}}
              ]}
            }}
        """.trimIndent()
        assertEquals(
            "This is a substantial AI recap provided by YouTube for a test lecture about basic computer science.",
            YouTubeAiSummaryParser.extractFromWatchHtml(html)
        )
    }
}
