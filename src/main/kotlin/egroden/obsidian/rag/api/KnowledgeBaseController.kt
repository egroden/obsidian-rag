package egroden.obsidian.rag.api

import egroden.obsidian.rag.knowledgebase.KnowledgeBaseService
import egroden.obsidian.rag.knowledgebase.QuestionResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/kb")
class KnowledgeBaseController(
    private val knowledgeBaseService: KnowledgeBaseService
) {

    @GetMapping("/ask")
    fun ask(@RequestParam question: String): QuestionResponse {
        return knowledgeBaseService.answer(question)
    }
}
