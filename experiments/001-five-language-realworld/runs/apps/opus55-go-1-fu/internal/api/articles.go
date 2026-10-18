package api

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode"

	"github.com/gin-gonic/gin"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"

	"realworld/internal/db"
)

const (
	defaultLimit = 20
	maxLimit     = 100
)

type articleResponse struct {
	Slug           string          `json:"slug"`
	Title          string          `json:"title"`
	Description    string          `json:"description"`
	Body           *string         `json:"body,omitempty"`
	TagList        []string        `json:"tagList"`
	CreatedAt      string          `json:"createdAt"`
	UpdatedAt      string          `json:"updatedAt"`
	Favorited      bool            `json:"favorited"`
	FavoritesCount int64           `json:"favoritesCount"`
	Author         profileResponse `json:"author"`
}

// buildArticles assembles responses for the given articles in batched
// queries. When withBody is false the body field is omitted (list endpoints).
func (s *Server) buildArticles(articles []db.Article, viewer *db.User, withBody bool) ([]articleResponse, error) {
	out := make([]articleResponse, 0, len(articles))
	if len(articles) == 0 {
		return out, nil
	}
	articleIDs := make([]uint, len(articles))
	authorIDs := make([]uint, 0, len(articles))
	seenAuthor := map[uint]bool{}
	for i, a := range articles {
		articleIDs[i] = a.ID
		if !seenAuthor[a.AuthorID] {
			seenAuthor[a.AuthorID] = true
			authorIDs = append(authorIDs, a.AuthorID)
		}
	}

	var authors []db.User
	if err := s.db.Where("id IN ?", authorIDs).Find(&authors).Error; err != nil {
		return nil, err
	}
	authorByID := make(map[uint]*db.User, len(authors))
	for i := range authors {
		authorByID[authors[i].ID] = &authors[i]
	}

	var tagRows []struct {
		ArticleID uint
		Name      string
	}
	if err := s.db.Table("article_tags").Select("article_tags.article_id, tags.name").
		Joins("JOIN tags ON tags.id = article_tags.tag_id").
		Where("article_tags.article_id IN ?", articleIDs).
		Order("article_tags.article_id, article_tags.position").Scan(&tagRows).Error; err != nil {
		return nil, err
	}
	tagsByArticle := map[uint][]string{}
	for _, r := range tagRows {
		tagsByArticle[r.ArticleID] = append(tagsByArticle[r.ArticleID], r.Name)
	}

	var countRows []struct {
		ArticleID uint
		N         int64
	}
	if err := s.db.Model(&db.Favorite{}).Select("article_id, COUNT(*) AS n").
		Where("article_id IN ?", articleIDs).Group("article_id").Scan(&countRows).Error; err != nil {
		return nil, err
	}
	favCount := map[uint]int64{}
	for _, r := range countRows {
		favCount[r.ArticleID] = r.N
	}

	favorited := map[uint]bool{}
	following := map[uint]bool{}
	if viewer != nil {
		var favIDs []uint
		if err := s.db.Model(&db.Favorite{}).Where("user_id = ? AND article_id IN ?", viewer.ID, articleIDs).
			Pluck("article_id", &favIDs).Error; err != nil {
			return nil, err
		}
		for _, id := range favIDs {
			favorited[id] = true
		}
		var followIDs []uint
		if err := s.db.Model(&db.Follow{}).Where("follower_id = ? AND followee_id IN ?", viewer.ID, authorIDs).
			Pluck("followee_id", &followIDs).Error; err != nil {
			return nil, err
		}
		for _, id := range followIDs {
			following[id] = true
		}
	}

	for i := range articles {
		a := &articles[i]
		author := authorByID[a.AuthorID]
		if author == nil {
			author = &db.User{ID: a.AuthorID}
		}
		tags := tagsByArticle[a.ID]
		if tags == nil {
			tags = []string{}
		}
		r := articleResponse{
			Slug:           a.Slug,
			Title:          a.Title,
			Description:    a.Description,
			TagList:        tags,
			CreatedAt:      formatTime(a.CreatedAt),
			UpdatedAt:      formatTime(a.UpdatedAt),
			Favorited:      favorited[a.ID],
			FavoritesCount: favCount[a.ID],
			Author:         toProfile(author, following[a.AuthorID]),
		}
		if withBody {
			r.Body = &a.Body
		}
		out = append(out, r)
	}
	return out, nil
}

func (s *Server) writeArticle(c *gin.Context, status int, a *db.Article) {
	res, err := s.buildArticles([]db.Article{*a}, currentUser(c), true)
	if err != nil {
		internalError(c, err)
		return
	}
	c.JSON(status, gin.H{"article": res[0]})
}

func pageParams(c *gin.Context) (limit, offset int) {
	limit, offset = defaultLimit, 0
	if v, err := strconv.Atoi(c.Query("limit")); err == nil && v >= 0 {
		limit = min(v, maxLimit)
	}
	if v, err := strconv.Atoi(c.Query("offset")); err == nil && v >= 0 {
		offset = v
	}
	return limit, offset
}

// writeArticleList counts and pages the filtered query and writes the result.
func (s *Server) writeArticleList(c *gin.Context, q *gorm.DB) {
	limit, offset := pageParams(c)
	var total int64
	if err := q.Session(&gorm.Session{}).Count(&total).Error; err != nil {
		internalError(c, err)
		return
	}
	var articles []db.Article
	if limit > 0 {
		if err := q.Omit("body").Order("articles.created_at DESC, articles.id DESC").
			Limit(limit).Offset(offset).Find(&articles).Error; err != nil {
			internalError(c, err)
			return
		}
	}
	res, err := s.buildArticles(articles, currentUser(c), false)
	if err != nil {
		internalError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"articles": res, "articlesCount": total})
}

func (s *Server) listArticles(c *gin.Context) {
	q := s.db.Model(&db.Article{})
	if tag := c.Query("tag"); tag != "" {
		q = q.Where("articles.id IN (SELECT article_tags.article_id FROM article_tags JOIN tags ON tags.id = article_tags.tag_id WHERE tags.name = ?)", tag)
	}
	if author := c.Query("author"); author != "" {
		q = q.Where("articles.author_id IN (SELECT id FROM users WHERE username = ?)", author)
	}
	if fav := c.Query("favorited"); fav != "" {
		q = q.Where("articles.id IN (SELECT favorites.article_id FROM favorites JOIN users ON users.id = favorites.user_id WHERE users.username = ?)", fav)
	}
	s.writeArticleList(c, q)
}

func (s *Server) feedArticles(c *gin.Context) {
	q := s.db.Model(&db.Article{}).
		Where("articles.author_id IN (SELECT followee_id FROM follows WHERE follower_id = ?)", currentUser(c).ID)
	s.writeArticleList(c, q)
}

// findArticle loads the article named by the :slug route parameter.
func (s *Server) findArticle(c *gin.Context) (*db.Article, bool) {
	var a db.Article
	err := s.db.Where("slug = ?", c.Param("slug")).Take(&a).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		notFound(c, "article")
		return nil, false
	}
	if err != nil {
		internalError(c, err)
		return nil, false
	}
	return &a, true
}

func (s *Server) getArticle(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	s.writeArticle(c, http.StatusOK, a)
}

func slugify(title string) string {
	var b strings.Builder
	dash := false
	for _, r := range strings.ToLower(title) {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			b.WriteRune(r)
			dash = false
		} else if !dash && b.Len() > 0 {
			b.WriteByte('-')
			dash = true
		}
	}
	s := strings.TrimSuffix(b.String(), "-")
	if s == "" {
		s = "article"
	}
	return s
}

// uniqueSlug returns a slug for title that no other article uses.
func (s *Server) uniqueSlug(tx *gorm.DB, title string, selfID uint) (string, error) {
	base := slugify(title)
	slug := base
	for {
		var n int64
		if err := tx.Model(&db.Article{}).Where("slug = ? AND id <> ?", slug, selfID).Count(&n).Error; err != nil {
			return "", err
		}
		if n == 0 {
			return slug, nil
		}
		buf := make([]byte, 4)
		_, _ = rand.Read(buf)
		slug = base + "-" + hex.EncodeToString(buf)
	}
}

// normalizeTags trims, drops blanks and removes duplicates, keeping order.
func normalizeTags(in []string) []string {
	out := make([]string, 0, len(in))
	seen := map[string]bool{}
	for _, t := range in {
		t = strings.TrimSpace(t)
		if t == "" || seen[t] {
			continue
		}
		seen[t] = true
		out = append(out, t)
	}
	return out
}

// setTags replaces the tag links of an article.
func setTags(tx *gorm.DB, articleID uint, names []string) error {
	if err := tx.Where("article_id = ?", articleID).Delete(&db.ArticleTag{}).Error; err != nil {
		return err
	}
	if len(names) == 0 {
		return nil
	}
	tags := make([]db.Tag, len(names))
	for i, n := range names {
		tags[i] = db.Tag{Name: n}
	}
	if err := tx.Clauses(clause.OnConflict{DoNothing: true}).Create(&tags).Error; err != nil {
		return err
	}
	var existing []db.Tag
	if err := tx.Where("name IN ?", names).Find(&existing).Error; err != nil {
		return err
	}
	idByName := make(map[string]uint, len(existing))
	for _, t := range existing {
		idByName[t.Name] = t.ID
	}
	links := make([]db.ArticleTag, len(names))
	for i, n := range names {
		links[i] = db.ArticleTag{ArticleID: articleID, TagID: idByName[n], Position: i}
	}
	return tx.Create(&links).Error
}

type articleInput struct {
	Title       Optional[string]   `json:"title"`
	Description Optional[string]   `json:"description"`
	Body        Optional[string]   `json:"body"`
	TagList     Optional[[]string] `json:"tagList"`
}

func (s *Server) createArticle(c *gin.Context) {
	var req struct {
		Article articleInput `json:"article"`
	}
	if !bindJSON(c, &req) {
		return
	}
	in := req.Article
	errs := fieldErrors{}
	requireString(errs, "title", in.Title)
	requireString(errs, "description", in.Description)
	requireString(errs, "body", in.Body)
	if len(errs) > 0 {
		abortErrors(c, http.StatusUnprocessableEntity, errs)
		return
	}

	a := db.Article{
		Title:       in.Title.Value,
		Description: in.Description.Value,
		Body:        in.Body.Value,
		AuthorID:    currentUser(c).ID,
	}
	err := s.db.Transaction(func(tx *gorm.DB) error {
		slug, err := s.uniqueSlug(tx, a.Title, 0)
		if err != nil {
			return err
		}
		a.Slug = slug
		if err := tx.Create(&a).Error; err != nil {
			return err
		}
		return setTags(tx, a.ID, normalizeTags(in.TagList.Value))
	})
	if err != nil {
		internalError(c, err)
		return
	}
	s.writeArticle(c, http.StatusCreated, &a)
}

func (s *Server) updateArticle(c *gin.Context) {
	var req struct {
		Article articleInput `json:"article"`
	}
	if !bindJSON(c, &req) {
		return
	}
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	if a.AuthorID != currentUser(c).ID {
		forbidden(c, "article")
		return
	}

	in := req.Article
	errs := fieldErrors{}
	if in.Title.Set {
		requireString(errs, "title", in.Title)
	}
	if in.Description.Set {
		requireString(errs, "description", in.Description)
	}
	if in.Body.Set {
		requireString(errs, "body", in.Body)
	}
	if in.TagList.Set && in.TagList.Null {
		errs.add("tagList", "must be an array")
	}
	if len(errs) > 0 {
		abortErrors(c, http.StatusUnprocessableEntity, errs)
		return
	}

	prevUpdated := a.UpdatedAt
	err := s.db.Transaction(func(tx *gorm.DB) error {
		if in.Title.Set && in.Title.Value != a.Title {
			slug, err := s.uniqueSlug(tx, in.Title.Value, a.ID)
			if err != nil {
				return err
			}
			a.Title, a.Slug = in.Title.Value, slug
		}
		if in.Description.Set {
			a.Description = in.Description.Value
		}
		if in.Body.Set {
			a.Body = in.Body.Value
		}
		// Guarantee a strictly increasing updatedAt at millisecond precision.
		now := time.Now().UTC()
		if !now.Truncate(time.Millisecond).After(prevUpdated.Truncate(time.Millisecond)) {
			now = prevUpdated.Add(time.Millisecond)
		}
		a.UpdatedAt = now
		if err := tx.Model(a).UpdateColumns(map[string]any{
			"slug": a.Slug, "title": a.Title, "description": a.Description, "body": a.Body, "updated_at": a.UpdatedAt,
		}).Error; err != nil {
			return err
		}
		if in.TagList.Set {
			return setTags(tx, a.ID, normalizeTags(in.TagList.Value))
		}
		return nil
	})
	if err != nil {
		internalError(c, err)
		return
	}
	s.writeArticle(c, http.StatusOK, a)
}

func (s *Server) deleteArticle(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	if a.AuthorID != currentUser(c).ID {
		forbidden(c, "article")
		return
	}
	err := s.db.Transaction(func(tx *gorm.DB) error {
		for _, m := range []any{&db.Comment{}, &db.Favorite{}, &db.ArticleTag{}} {
			if err := tx.Where("article_id = ?", a.ID).Delete(m).Error; err != nil {
				return err
			}
		}
		return tx.Delete(&db.Article{}, a.ID).Error
	})
	if err != nil {
		internalError(c, err)
		return
	}
	c.Status(http.StatusNoContent)
}

func (s *Server) favoriteArticle(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	f := db.Favorite{UserID: currentUser(c).ID, ArticleID: a.ID}
	if err := s.db.Clauses(clause.OnConflict{DoNothing: true}).Create(&f).Error; err != nil {
		internalError(c, err)
		return
	}
	s.writeArticle(c, http.StatusOK, a)
}

func (s *Server) unfavoriteArticle(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	if err := s.db.Where("user_id = ? AND article_id = ?", currentUser(c).ID, a.ID).Delete(&db.Favorite{}).Error; err != nil {
		internalError(c, err)
		return
	}
	s.writeArticle(c, http.StatusOK, a)
}

func (s *Server) listTags(c *gin.Context) {
	tags := []string{}
	if err := s.db.Table("tags").Select("tags.name").
		Joins("JOIN article_tags ON article_tags.tag_id = tags.id").
		Group("tags.id").Order("COUNT(*) DESC, tags.name").Pluck("tags.name", &tags).Error; err != nil {
		internalError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"tags": tags})
}
