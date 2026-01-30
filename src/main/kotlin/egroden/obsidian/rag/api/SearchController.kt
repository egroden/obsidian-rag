package egroden.obsidian.rag.api

import egroden.obsidian.rag.search.SearchMode
import egroden.obsidian.rag.search.SearchResult
import egroden.obsidian.rag.search.SearchService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/search")
class SearchController(
    private val searchService: SearchService
) {

    @GetMapping
    fun search(
        @RequestParam query: String,
        @RequestParam(defaultValue = "20") limit: Int
    ): List<SearchResult> {
        return searchService.search(query, SearchMode.HYBRID, limit)
    }
}