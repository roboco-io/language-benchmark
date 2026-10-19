package io.realworld.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.realworld.auth.CurrentUser;
import io.realworld.web.Dtos.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ArticleController {

    private final ArticleService service;
    private final CurrentUser currentUser;

    public ArticleController(ArticleService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/articles")
    public ArticlesResponse list(@RequestParam(required = false) String tag,
                                 @RequestParam(required = false) String author,
                                 @RequestParam(required = false) String favorited,
                                 @RequestParam(required = false) Integer limit,
                                 @RequestParam(required = false) Integer offset,
                                 HttpServletRequest request) {
        return service.list(tag, author, favorited, limit, offset, currentUser.optional(request));
    }

    @GetMapping("/articles/feed")
    public ArticlesResponse feed(@RequestParam(required = false) Integer limit,
                                 @RequestParam(required = false) Integer offset,
                                 HttpServletRequest request) {
        return service.feed(currentUser.require(request), limit, offset);
    }

    @GetMapping("/articles/{slug}")
    public ArticleResponse get(@PathVariable String slug, HttpServletRequest request) {
        return service.get(slug, currentUser.optional(request));
    }

    @PostMapping("/articles")
    @ResponseStatus(HttpStatus.CREATED)
    public ArticleResponse create(HttpServletRequest request, @RequestBody(required = false) JsonNode body) {
        return service.create(currentUser.require(request), body);
    }

    @PutMapping("/articles/{slug}")
    public ArticleResponse update(@PathVariable String slug, HttpServletRequest request,
                                  @RequestBody(required = false) JsonNode body) {
        return service.update(slug, currentUser.require(request), body);
    }

    @DeleteMapping("/articles/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String slug, HttpServletRequest request) {
        service.delete(slug, currentUser.require(request));
    }

    @PostMapping("/articles/{slug}/favorite")
    public ArticleResponse favorite(@PathVariable String slug, HttpServletRequest request) {
        return service.favorite(slug, currentUser.require(request), true);
    }

    @DeleteMapping("/articles/{slug}/favorite")
    public ArticleResponse unfavorite(@PathVariable String slug, HttpServletRequest request) {
        return service.favorite(slug, currentUser.require(request), false);
    }

    @GetMapping("/articles/{slug}/comments")
    public CommentsResponse comments(@PathVariable String slug, HttpServletRequest request) {
        return service.comments(slug, currentUser.optional(request));
    }

    @PostMapping("/articles/{slug}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse addComment(@PathVariable String slug, HttpServletRequest request,
                                      @RequestBody(required = false) JsonNode body) {
        return service.addComment(slug, currentUser.require(request), body);
    }

    @PutMapping("/articles/{slug}/comments/{id}")
    public CommentResponse updateComment(@PathVariable String slug, @PathVariable long id, HttpServletRequest request,
                                         @RequestBody(required = false) JsonNode body) {
        return service.updateComment(slug, id, currentUser.require(request), body);
    }

        @DeleteMapping("/articles/{slug}/comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteComment(@PathVariable String slug, @PathVariable long id, HttpServletRequest request) {
        service.deleteComment(slug, id, currentUser.require(request));
    }

    @GetMapping("/tags")
    public TagsResponse tags() {
        return service.tags();
    }
}
