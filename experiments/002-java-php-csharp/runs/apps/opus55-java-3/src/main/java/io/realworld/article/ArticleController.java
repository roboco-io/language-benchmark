package io.realworld.article;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import io.realworld.common.ApiException;
import io.realworld.common.Auth;

@RestController
@RequestMapping("/api")
public class ArticleController {

    private static final String AUTHORIZATION = "Authorization";
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 1000;

    private final ArticleService service;
    private final Auth auth;

    public ArticleController(ArticleService service, Auth auth) {
        this.service = service;
        this.auth = auth;
    }

    @GetMapping("/articles")
    public ArticleList list(@RequestHeader(value = AUTHORIZATION, required = false) String header,
                            @RequestParam(required = false) String tag,
                            @RequestParam(required = false) String author,
                            @RequestParam(required = false) String favorited,
                            @RequestParam(required = false) String limit,
                            @RequestParam(required = false) String offset) {
        return service.list(new ArticleService.Filter(blankToNull(tag), blankToNull(author), blankToNull(favorited)),
                limit(limit), offset(offset), auth.optional(header));
    }

    @GetMapping("/articles/feed")
    public ArticleList feed(@RequestHeader(value = AUTHORIZATION, required = false) String header,
                            @RequestParam(required = false) String limit,
                            @RequestParam(required = false) String offset) {
        return service.feed(auth.require(header), limit(limit), offset(offset));
    }

    @GetMapping("/articles/{slug}")
    public Map<String, ArticleView> get(@RequestHeader(value = AUTHORIZATION, required = false) String header,
                                        @PathVariable String slug) {
        return Map.of("article", service.get(slug, auth.optional(header)));
    }

    @PostMapping("/articles")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, ArticleView> create(@RequestHeader(value = AUTHORIZATION, required = false) String header,
                                           @RequestBody(required = false) JsonNode body) {
        long userId = auth.require(header);
        return Map.of("article", service.create(userId, body));
    }

    @PutMapping("/articles/{slug}")
    public Map<String, ArticleView> update(@RequestHeader(value = AUTHORIZATION, required = false) String header,
                                           @PathVariable String slug,
                                           @RequestBody(required = false) JsonNode body) {
        long userId = auth.require(header);
        return Map.of("article", service.update(slug, userId, body));
    }

    @DeleteMapping("/articles/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader(value = AUTHORIZATION, required = false) String header,
                       @PathVariable String slug) {
        service.delete(slug, auth.require(header));
    }

    @PostMapping("/articles/{slug}/favorite")
    public Map<String, ArticleView> favorite(@RequestHeader(value = AUTHORIZATION, required = false) String header,
                                             @PathVariable String slug) {
        return Map.of("article", service.favorite(slug, auth.require(header)));
    }

    @DeleteMapping("/articles/{slug}/favorite")
    public Map<String, ArticleView> unfavorite(@RequestHeader(value = AUTHORIZATION, required = false) String header,
                                               @PathVariable String slug) {
        return Map.of("article", service.unfavorite(slug, auth.require(header)));
    }

    @GetMapping("/articles/{slug}/comments")
    public Map<String, List<CommentView>> comments(
            @RequestHeader(value = AUTHORIZATION, required = false) String header, @PathVariable String slug) {
        return Map.of("comments", service.comments(slug, auth.optional(header)));
    }

    @PostMapping("/articles/{slug}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, CommentView> addComment(@RequestHeader(value = AUTHORIZATION, required = false) String header,
                                               @PathVariable String slug,
                                               @RequestBody(required = false) JsonNode body) {
        long userId = auth.require(header);
        return Map.of("comment", service.addComment(slug, userId, body));
    }

    @DeleteMapping("/articles/{slug}/comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteComment(@RequestHeader(value = AUTHORIZATION, required = false) String header,
                              @PathVariable String slug, @PathVariable String id) {
        long userId = auth.require(header);
        long commentId;
        try {
            commentId = Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw ApiException.notFound("comment");
        }
        service.deleteComment(slug, commentId, userId);
    }

    @GetMapping("/tags")
    public Map<String, List<String>> tags() {
        return Map.of("tags", service.tags());
    }

    private static int limit(String value) {
        int parsed = parse(value, DEFAULT_LIMIT);
        return parsed < 0 ? DEFAULT_LIMIT : Math.min(parsed, MAX_LIMIT);
    }

    private static int offset(String value) {
        return Math.max(parse(value, 0), 0);
    }

    private static int parse(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
