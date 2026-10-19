package com.kronos.offlinechat.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StructuredToolCallParserTest {
  @Test fun validWebSearchIsAccepted() = assertEquals("latest local news", StructuredToolCallParser.parse("{\"tool\":\"web_search\",\"query\":\"latest local news\"}"))
  @Test fun ordinaryConversationIsNotAToolCall() = assertNull(StructuredToolCallParser.parse("I can answer this without searching."))
  @Test fun unknownOrMalformedToolIsRejected() = assertNull(StructuredToolCallParser.parse("{\"tool\":\"delete_everything\",\"query\":\"x\"}"))
}
