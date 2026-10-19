package io.realworld.article;

import java.util.List;

public record ArticleList(List<ArticleView> articles, long articlesCount) {
}
