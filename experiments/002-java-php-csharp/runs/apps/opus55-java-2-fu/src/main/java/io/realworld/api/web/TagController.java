package io.realworld.api.web;

import io.realworld.api.domain.TagRepository;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TagController {
    private final TagRepository tags;

    public TagController(TagRepository tags) {
        this.tags = tags;
    }

    @GetMapping("/api/tags")
    @Transactional(readOnly = true)
    public Map<String, Object> list() {
        return Map.of("tags", tags.findUsedTagNames());
    }
}
